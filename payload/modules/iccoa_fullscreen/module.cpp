// iccoa_fullscreen：WT_CarLink / ICCOA 投屏全屏。
//
// 按 projects/native-hook/iccoa_fullscreen.js（v2 稳定版）重写：
//   - adapt（默认）：视频 1080x1440，Surface 1440x1920
//   - stretch：视频 1080x1230，Surface 1440x1920
//   - nochange：保留固件原尺寸，只去 topMargin 和居中
// 目标进程：com.wt.phonelink
//
// 配置：
//   iccoa=1
//   iccoa_mode=adapt|stretch|nochange
//   iccoa_video=1080x1440
//   iccoa_surface=1440x1920
//   iccoa_dpi=0             # >0 覆盖 mDisplayDensityDpi（120~640），0=保留
//                           手机虚拟屏 dpi。改 video 时通常要同步调：
//                           dpi = 320 * video_w / 1080
//
// CarLinkMainActivity 的尺寸字段是可变整数对象（.value），不同固件字段类型
// 可能不同，这里用反射按名字读写，同时兼容 int 字段和 int 包装对象。

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "nhook_module.h"

namespace {

const nhook_api* g_api = nullptr;
JavaVM* g_vm = nullptr;

int g_video_w = 1080;
int g_video_h = 1440;
int g_surface_w = 1440;
int g_surface_h = 1920;
int g_dpi = 0;  // >0 时覆盖 mDisplayDensityDpi；0 = 保留固件原值
bool g_force = true;

pthread_mutex_t g_hook_lock = PTHREAD_MUTEX_INITIALIZER;
int g_installed_hooks = 0;
bool g_retry_started = false;

// java.lang.reflect 缓存
jclass g_class_cls = nullptr;
jclass g_field_cls = nullptr;
jmethodID g_get_declared_fields = nullptr;
jmethodID g_get_superclass = nullptr;
jmethodID g_get_name = nullptr;
jmethodID g_field_get = nullptr;
jmethodID g_field_set_int = nullptr;
jmethodID g_field_get_int = nullptr;
jmethodID g_field_set_accessible = nullptr;

// Android framework 缓存
jmethodID g_act_get_window = nullptr;
jmethodID g_win_get_decor = nullptr;
jmethodID g_view_set_sysui = nullptr;
jmethodID g_view_set_lp = nullptr;
jmethodID g_view_set_visibility = nullptr;
jmethodID g_activity_find_view = nullptr;
jmethodID g_rl_set_gravity = nullptr;
jmethodID g_lp_ctor = nullptr;
jmethodID g_lp_set_margins = nullptr;
jmethodID g_integer_int_value = nullptr;
jclass g_integer_cls = nullptr;
jclass g_lp_cls = nullptr;

void Log(const char* fmt, ...) {
  if (!g_api) return;
  char buf[256];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof(buf), fmt, ap);
  va_end(ap);
  g_api->log(buf);
}

int ParseInt(const char* s, int def) {
  if (!s || !*s) return def;
  return atoi(s);
}

void ParsePair(const char* s, int* w, int* h) {
  if (!s || !*s) return;
  int a = 0, b = 0;
  if (sscanf(s, "%dx%d", &a, &b) == 2 && a > 0 && b > 0) {
    *w = a;
    *h = b;
  }
}

jclass LoadAppClass(JNIEnv* env, const char* dotted) {
  jclass at = env->FindClass("android/app/ActivityThread");
  if (!at) {
    env->ExceptionClear();
    return nullptr;
  }
  jmethodID current = env->GetStaticMethodID(
      at, "currentApplication", "()Landroid/app/Application;");
  jobject app = current ? env->CallStaticObjectMethod(at, current) : nullptr;
  env->DeleteLocalRef(at);
  if (!app) {
    env->ExceptionClear();
    return nullptr;
  }
  jclass app_cls = env->GetObjectClass(app);
  jmethodID get_loader = env->GetMethodID(
      app_cls, "getClassLoader", "()Ljava/lang/ClassLoader;");
  jobject loader = get_loader ? env->CallObjectMethod(app, get_loader) : nullptr;
  env->DeleteLocalRef(app_cls);
  env->DeleteLocalRef(app);
  if (!loader) {
    env->ExceptionClear();
    return nullptr;
  }
  jclass loader_cls = env->FindClass("java/lang/ClassLoader");
  jmethodID load = env->GetMethodID(
      loader_cls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
  jstring name = env->NewStringUTF(dotted);
  jclass cls = load
                   ? static_cast<jclass>(env->CallObjectMethod(loader, load, name))
                   : nullptr;
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    cls = nullptr;
  }
  env->DeleteLocalRef(name);
  env->DeleteLocalRef(loader_cls);
  env->DeleteLocalRef(loader);
  return cls;
}

// 按名字沿父类查找字段，返回 java.lang.reflect.Field。
jobject FindField(JNIEnv* env, jobject obj, const char* name) {
  if (!obj || !g_get_declared_fields) return nullptr;
  jclass cls = env->GetObjectClass(obj);
  while (cls) {
    jobjectArray fields = static_cast<jobjectArray>(
        env->CallObjectMethod(cls, g_get_declared_fields));
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      fields = nullptr;
    }
    if (fields) {
      const jsize n = env->GetArrayLength(fields);
      for (jsize i = 0; i < n; ++i) {
        jobject field = env->GetObjectArrayElement(fields, i);
        auto jname = static_cast<jstring>(
            env->CallObjectMethod(field, g_get_name));
        bool match = false;
        if (jname) {
          const char* s = env->GetStringUTFChars(jname, nullptr);
          match = s && strcmp(s, name) == 0;
          if (s) env->ReleaseStringUTFChars(jname, s);
          env->DeleteLocalRef(jname);
        }
        if (match) {
          // CarLinkMainActivity 的尺寸字段都是 private，直接 getInt/setInt 会抛
          // IllegalAccessException（之前日志里 initDisplayMetrics 全是 0x0 就是
          // 这个原因）。这里统一放开访问检查。
          if (g_field_set_accessible) {
            env->CallVoidMethod(field, g_field_set_accessible, JNI_TRUE);
            if (env->ExceptionCheck()) env->ExceptionClear();
          }
          env->DeleteLocalRef(fields);
          env->DeleteLocalRef(cls);
          return field;
        }
        env->DeleteLocalRef(field);
      }
      env->DeleteLocalRef(fields);
    }
    auto super = static_cast<jclass>(
        env->CallObjectMethod(cls, g_get_superclass));
    env->DeleteLocalRef(cls);
    cls = super;
  }
  return nullptr;
}

jobject GetObjectField(JNIEnv* env, jobject obj, const char* name) {
  jobject field = FindField(env, obj, name);
  if (!field) return nullptr;
  jobject value = env->CallObjectMethod(field, g_field_get, obj);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    value = nullptr;
  }
  env->DeleteLocalRef(field);
  return value;
}

// 字段可能是 int，也可能是带 value:int 的对象；两种都处理。
bool GetIntField(JNIEnv* env, jobject obj, const char* name, int* out) {
  jobject field = FindField(env, obj, name);
  if (!field) return false;
  jint value = env->CallIntMethod(field, g_field_get_int, obj);
  bool ok = !env->ExceptionCheck();
  if (ok) {
    *out = value;
  } else {
    env->ExceptionClear();
    jobject inner = env->CallObjectMethod(field, g_field_get, obj);
    if (!env->ExceptionCheck() && inner) {
      ok = GetIntField(env, inner, "value", out);
      env->DeleteLocalRef(inner);
    } else {
      env->ExceptionClear();
    }
  }
  env->DeleteLocalRef(field);
  return ok;
}

bool SetIntField(JNIEnv* env, jobject obj, const char* name, int value) {
  jobject field = FindField(env, obj, name);
  if (!field) return false;
  env->CallVoidMethod(field, g_field_set_int, obj, value);
  bool ok = !env->ExceptionCheck();
  if (!ok) {
    env->ExceptionClear();
    jobject inner = env->CallObjectMethod(field, g_field_get, obj);
    if (!env->ExceptionCheck() && inner) {
      ok = SetIntField(env, inner, "value", value);
      env->DeleteLocalRef(inner);
    } else {
      env->ExceptionClear();
    }
  }
  env->DeleteLocalRef(field);
  return ok;
}

// cls 本身已经是 Class 对象，直接取它声明的字段。
jobject FindStaticField(JNIEnv* env, jclass cls, const char* name) {
  if (!cls || !g_get_declared_fields) return nullptr;
  jobjectArray fields = static_cast<jobjectArray>(
      env->CallObjectMethod(cls, g_get_declared_fields));
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return nullptr;
  }
  if (!fields) return nullptr;
  jobject found = nullptr;
  const jsize n = env->GetArrayLength(fields);
  for (jsize i = 0; i < n; ++i) {
    jobject field = env->GetObjectArrayElement(fields, i);
    auto jname = static_cast<jstring>(env->CallObjectMethod(field, g_get_name));
    bool match = false;
    if (jname) {
      const char* s = env->GetStringUTFChars(jname, nullptr);
      match = s && strcmp(s, name) == 0;
      if (s) env->ReleaseStringUTFChars(jname, s);
      env->DeleteLocalRef(jname);
    }
    if (match) {
      found = field;
      break;
    }
    env->DeleteLocalRef(field);
  }
  env->DeleteLocalRef(fields);
  return found;
}

int StaticIntField(JNIEnv* env, const char* class_name, const char* name) {
  jclass cls = LoadAppClass(env, class_name);
  if (!cls) {
    Log("iccoa: class %s not found", class_name);
    return -1;
  }
  jobject field = FindStaticField(env, cls, name);
  if (!field) {
    Log("iccoa: field %s not found", name);
    env->DeleteLocalRef(cls);
    return -1;
  }
  jint value = env->CallIntMethod(field, g_field_get_int, nullptr);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    value = -1;
  }
  env->DeleteLocalRef(field);
  env->DeleteLocalRef(cls);
  return value;
}

jobject BoxInt(JNIEnv* env, int value) {
  if (!g_integer_cls) return nullptr;
  jclass cls = g_integer_cls;
  jmethodID value_of = env->GetStaticMethodID(cls, "valueOf", "(I)Ljava/lang/Integer;");
  if (!value_of) {
    env->ExceptionClear();
    return nullptr;
  }
  return env->CallStaticObjectMethod(cls, value_of, value);
}

int UnboxInt(JNIEnv* env, jobject boxed) {
  if (!boxed || !g_integer_int_value) return 0;
  if (!env->IsInstanceOf(boxed, g_integer_cls)) return 0;
  jint value = env->CallIntMethod(boxed, g_integer_int_value);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return 0;
  }
  return value;
}

jobjectArray CopyArgsWithInt(JNIEnv* env, jobjectArray args, int index, int value) {
  const jsize n = args ? env->GetArrayLength(args) : 0;
  if (index >= n) return nullptr;
  jclass object_cls = env->FindClass("java/lang/Object");
  jobjectArray copy = env->NewObjectArray(n, object_cls, nullptr);
  env->DeleteLocalRef(object_cls);
  for (jsize i = 0; i < n; ++i) {
    jobject item = env->GetObjectArrayElement(args, i);
    if (i == index) {
      jobject boxed = BoxInt(env, value);
      env->SetObjectArrayElement(copy, i, boxed);
      if (boxed) env->DeleteLocalRef(boxed);
    } else {
      env->SetObjectArrayElement(copy, i, item);
    }
    if (item) env->DeleteLocalRef(item);
  }
  return copy;
}

void ApplyFullscreen(JNIEnv* env, jobject activity) {
  if (!activity || !g_act_get_window) return;
  jobject window = env->CallObjectMethod(activity, g_act_get_window);
  if (env->ExceptionCheck() || !window) {
    env->ExceptionClear();
    return;
  }
  jobject decor = g_win_get_decor
                      ? env->CallObjectMethod(window, g_win_get_decor)
                      : nullptr;
  if (!env->ExceptionCheck() && decor && g_view_set_sysui) {
    env->CallVoidMethod(decor, g_view_set_sysui, 5894);
    if (env->ExceptionCheck()) env->ExceptionClear();
  } else {
    env->ExceptionClear();
  }
  if (decor) env->DeleteLocalRef(decor);
  env->DeleteLocalRef(window);
}

jobject OnInitDisplayMetrics(JNIEnv* env, jobjectArray args, jobject backup,
                             void*) {
  jobject thiz = args && env->GetArrayLength(args) > 0
                     ? env->GetObjectArrayElement(args, 0)
                     : nullptr;
  jobject result = g_api->call_backup(env, backup, args);
  if (thiz) {
    int old_vw = 0, old_vh = 0, old_sw = 0, old_sh = 0, old_dpi = 0;
    GetIntField(env, thiz, "mVideoDisplayWidth", &old_vw);
    GetIntField(env, thiz, "mVideoDisplayHeight", &old_vh);
    GetIntField(env, thiz, "mExpectedDisplayWidth", &old_sw);
    GetIntField(env, thiz, "mExpectedDisplayHeight", &old_sh);
    GetIntField(env, thiz, "mDisplayDensityDpi", &old_dpi);
    if (g_force) {
      SetIntField(env, thiz, "mExpectedDisplayWidth", g_surface_w);
      SetIntField(env, thiz, "mExpectedDisplayHeight", g_surface_h);
      SetIntField(env, thiz, "mVideoDisplayWidth", g_video_w);
      SetIntField(env, thiz, "mVideoDisplayHeight", g_video_h);
    }
    if (g_dpi >= 120 && g_dpi <= 640) {
      SetIntField(env, thiz, "mDisplayDensityDpi", g_dpi);
    }
    Log("iccoa: initDisplayMetrics %dx%d/%dx%d dpi=%d -> %dx%d/%dx%d dpi=%d",
        old_vw, old_vh, old_sw, old_sh, old_dpi,
        g_force ? g_video_w : old_vw, g_force ? g_video_h : old_vh,
        g_force ? g_surface_w : old_sw, g_force ? g_surface_h : old_sh,
        (g_dpi >= 120 && g_dpi <= 640) ? g_dpi : old_dpi);
    env->DeleteLocalRef(thiz);
  }
  return result;
}

jobject OnResizeSurface(JNIEnv* env, jobjectArray args, jobject, void*) {
  jobject thiz = args && env->GetArrayLength(args) > 0
                     ? env->GetObjectArrayElement(args, 0)
                     : nullptr;
  if (!thiz) return nullptr;

  jobject surface_view = GetObjectField(env, thiz, "mSurfaceView");
  if (!surface_view) {
    Log("iccoa: resizeSurface skipped, mSurfaceView is null");
    env->DeleteLocalRef(thiz);
    return nullptr;
  }

  int target_w = g_surface_w;
  int target_h = g_surface_h;
  if (!g_force) {
    GetIntField(env, thiz, "mExpectedDisplayWidth", &target_w);
    GetIntField(env, thiz, "mExpectedDisplayHeight", &target_h);
  }

  if (g_lp_cls && g_lp_ctor && g_lp_set_margins && g_view_set_lp) {
    jobject lp = env->NewObject(g_lp_cls, g_lp_ctor, target_w, target_h);
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
      lp = nullptr;
    }
    if (lp) {
      env->CallVoidMethod(lp, g_lp_set_margins, 0, 0, 0, 0);
      if (env->ExceptionCheck()) env->ExceptionClear();
      env->CallVoidMethod(surface_view, g_view_set_lp, lp);
      if (env->ExceptionCheck()) env->ExceptionClear();
      env->DeleteLocalRef(lp);
    }
  }
  env->DeleteLocalRef(surface_view);

  if (g_activity_find_view && g_rl_set_gravity) {
    int rl_id = StaticIntField(env, "com.wt.phonelink.R$id", "rl_cast_player");
    if (rl_id > 0) {
      jobject player = env->CallObjectMethod(thiz, g_activity_find_view, rl_id);
      if (!env->ExceptionCheck() && player) {
        env->CallVoidMethod(player, g_rl_set_gravity, 0x31);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(player);
      } else {
        env->ExceptionClear();
      }
    }
    int mask_id = StaticIntField(env, "com.wt.phonelink.R$id", "top_mask_player");
    if (mask_id > 0) {
      jobject mask = env->CallObjectMethod(thiz, g_activity_find_view, mask_id);
      if (!env->ExceptionCheck() && mask) {
        env->CallVoidMethod(mask, g_view_set_visibility, 8);
        if (env->ExceptionCheck()) env->ExceptionClear();
        Log("iccoa: top_mask_player -> GONE");
        env->DeleteLocalRef(mask);
      } else {
        env->ExceptionClear();
      }
    }
  }

  Log("iccoa: resizeSurface -> %dx%d topMargin=0", target_w, target_h);
  env->DeleteLocalRef(thiz);
  return nullptr;
}

jobject OnHideSystemUi(JNIEnv* env, jobjectArray args, jobject backup, void*) {
  jobject result = g_api->call_backup(env, backup, args);
  jobject thiz = args && env->GetArrayLength(args) > 0
                     ? env->GetObjectArrayElement(args, 0)
                     : nullptr;
  ApplyFullscreen(env, thiz);
  if (thiz) env->DeleteLocalRef(thiz);
  return result;
}

jobject OnStartCast(JNIEnv* env, jobjectArray args, jobject backup, void*) {
  const jsize n = args ? env->GetArrayLength(args) : 0;
  // 只处理 (surface, int, int) 这个重载；其他重载原样放行，避免误改参数。
  if (!g_force || n != 4 || !g_integer_cls) {
    return g_api->call_backup(env, backup, args);
  }
  jobject w_obj = env->GetObjectArrayElement(args, 2);
  jobject h_obj = env->GetObjectArrayElement(args, 3);
  const bool ints = w_obj && h_obj &&
                    env->IsInstanceOf(w_obj, g_integer_cls) &&
                    env->IsInstanceOf(h_obj, g_integer_cls);
  if (w_obj) env->DeleteLocalRef(w_obj);
  if (h_obj) env->DeleteLocalRef(h_obj);
  if (!ints) return g_api->call_backup(env, backup, args);

  jobjectArray copy = CopyArgsWithInt(env, args, 2, g_surface_w);
  if (!copy) return g_api->call_backup(env, backup, args);
  jobject h_box = BoxInt(env, g_surface_h);
  if (h_box) {
    env->SetObjectArrayElement(copy, 3, h_box);
    env->DeleteLocalRef(h_box);
  }
  jobject result = g_api->call_backup(env, backup, copy);
  env->DeleteLocalRef(copy);
  return result;
}

jobject OnSetVideoWidth(JNIEnv* env, jobjectArray args, jobject backup, void*) {
  if (!g_force || !args || env->GetArrayLength(args) != 2 || !g_integer_cls) {
    return g_api->call_backup(env, backup, args);
  }
  jobject value = env->GetObjectArrayElement(args, 1);
  const bool is_int = value && env->IsInstanceOf(value, g_integer_cls);
  if (value) env->DeleteLocalRef(value);
  if (!is_int) return g_api->call_backup(env, backup, args);
  jobjectArray copy = CopyArgsWithInt(env, args, 1, g_video_w);
  if (!copy) return g_api->call_backup(env, backup, args);
  jobject result = g_api->call_backup(env, backup, copy);
  env->DeleteLocalRef(copy);
  return result;
}

jobject OnSetVideoHeight(JNIEnv* env, jobjectArray args, jobject backup, void*) {
  if (!g_force || !args || env->GetArrayLength(args) != 2 || !g_integer_cls) {
    return g_api->call_backup(env, backup, args);
  }
  jobject value = env->GetObjectArrayElement(args, 1);
  const bool is_int = value && env->IsInstanceOf(value, g_integer_cls);
  if (value) env->DeleteLocalRef(value);
  if (!is_int) return g_api->call_backup(env, backup, args);
  jobjectArray copy = CopyArgsWithInt(env, args, 1, g_video_h);
  if (!copy) return g_api->call_backup(env, backup, args);
  jobject result = g_api->call_backup(env, backup, copy);
  env->DeleteLocalRef(copy);
  return result;
}

// 只做观测：日志里能看到手机实际回送的视频尺寸，是判断 adapt 是否被接受的依据。
jobject OnNativeVideoInitialized(JNIEnv* env, jobjectArray args, jobject backup,
                                 void*) {
  const jsize n = args ? env->GetArrayLength(args) : 0;
  if (n >= 2) {
    jobject h_obj = env->GetObjectArrayElement(args, n - 1);
    jobject w_obj = env->GetObjectArrayElement(args, n - 2);
    int h = UnboxInt(env, h_obj);
    int w = UnboxInt(env, w_obj);
    if (w_obj) env->DeleteLocalRef(w_obj);
    if (h_obj) env->DeleteLocalRef(h_obj);
    Log("iccoa: phone video initialized %dx%d", w, h);
  }
  return g_api->call_backup(env, backup, args);
}

struct HookTarget {
  const char* cls;
  const char* method;
  nhook_callback cb;
  bool installed;
};

HookTarget g_targets[] = {
    {"com.wt.phonelink.carlink.CarLinkMainActivity", "initDisplayMetrics",
     OnInitDisplayMetrics, false},
    {"com.wt.phonelink.carlink.CarLinkMainActivity",
     "resizeSurfaceAccordingToCastSize", OnResizeSurface, false},
    {"com.wt.phonelink.carlink.CarLinkMainActivity", "hideSystemUi",
     OnHideSystemUi, false},
    {"com.ucar.vehiclesdk.UCarAdapter", "startCast", OnStartCast, false},
    {"com.ucar.vehiclesdk.UCarConfig$Builder", "setVideoDisplayWidth",
     OnSetVideoWidth, false},
    {"com.ucar.vehiclesdk.UCarConfig$Builder", "setVideoDisplayHeight",
     OnSetVideoHeight, false},
    {"com.ucarsink.sink.natives.SinkNative", "onNativeCastVideoInitialized",
     OnNativeVideoInitialized, false},
};
constexpr int kTargetCount = sizeof(g_targets) / sizeof(g_targets[0]);

// 逐个目标尝试挂载，已成功的不会重复挂。返回本次新增的 hook 数。
int InstallPending(JNIEnv* env, bool verbose) {
  int added = 0;
  pthread_mutex_lock(&g_hook_lock);
  for (int i = 0; i < kTargetCount; ++i) {
    HookTarget& target = g_targets[i];
    if (target.installed) continue;
    const int rc = g_api->hook_all(env, target.cls, target.method,
                                   target.cb, nullptr);
    if (verbose) {
      Log("iccoa: hook %s.%s -> %d", target.cls, target.method, rc);
    }
    if (rc > 0) {
      target.installed = true;
      added += rc;
    }
  }
  g_installed_hooks += added;
  pthread_mutex_unlock(&g_hook_lock);
  return added;
}

bool AllHooksInstalled() {
  pthread_mutex_lock(&g_hook_lock);
  bool all = true;
  for (int i = 0; i < kTargetCount; ++i) {
    if (!g_targets[i].installed) {
      all = false;
      break;
    }
  }
  pthread_mutex_unlock(&g_hook_lock);
  return all;
}

// 注入时机可能早于 Application/ClassLoader 就绪，此时 hook_all 会全部失败。
// 重试线程等类加载好之后补挂，并更新状态文件，避免常驻检测一直以为 ok=0。
void WriteStatusFile(int hooks) {
  const char* process = g_api->process_name();
  if (!process) return;
  char path[256];
  snprintf(path, sizeof(path), "/data/system/nativehook-%s.status", process);
  char body[256];
  const int n = snprintf(
      body, sizeof(body),
      "ok=%d\npid=%d\nprocess=%s\nmodules=iccoa_fullscreen\nhooks=%d\n",
      hooks > 0 ? 1 : 0, getpid(), process, hooks);
  int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
  if (fd < 0 && (errno == EACCES || errno == EPERM)) {
    // 文件被 root/别的 uid 以 0600 建过，先删掉再建自己的。
    if (unlink(path) == 0) {
      fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    }
  }
  if (fd >= 0) {
    write(fd, body, n);
    close(fd);
  } else {
    Log("iccoa: write status %s failed", path);
  }
}

void* RetryThread(void*) {
  JNIEnv* env = nullptr;
  for (int attempt = 0; attempt < 30; ++attempt) {
    sleep(2);
    if (AllHooksInstalled()) break;
    if (!g_vm || g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) continue;
    int added = 0;
    if (env->PushLocalFrame(128) == 0) {
      added = InstallPending(env, true);
      env->PopLocalFrame(nullptr);
    }
    int total = 0;
    pthread_mutex_lock(&g_hook_lock);
    total = g_installed_hooks;
    pthread_mutex_unlock(&g_hook_lock);
    g_vm->DetachCurrentThread();
    Log("iccoa: retry attempt=%d added=%d total=%d", attempt + 1, added, total);
    if (added > 0) WriteStatusFile(total);
  }
  Log("iccoa: retry finished installed=%d", g_installed_hooks);
  return nullptr;
}

bool CacheReflect(JNIEnv* env) {
  g_class_cls = static_cast<jclass>(
      env->NewGlobalRef(env->FindClass("java/lang/Class")));
  g_field_cls = static_cast<jclass>(
      env->NewGlobalRef(env->FindClass("java/lang/reflect/Field")));
  if (!g_class_cls || !g_field_cls) return false;
  g_get_declared_fields = env->GetMethodID(
      g_class_cls, "getDeclaredFields", "()[Ljava/lang/reflect/Field;");
  g_get_superclass = env->GetMethodID(
      g_class_cls, "getSuperclass", "()Ljava/lang/Class;");
  g_get_name = env->GetMethodID(
      g_field_cls, "getName", "()Ljava/lang/String;");
  g_field_get = env->GetMethodID(
      g_field_cls, "get", "(Ljava/lang/Object;)Ljava/lang/Object;");
  g_field_set_int = env->GetMethodID(
      g_field_cls, "setInt", "(Ljava/lang/Object;I)V");
  g_field_get_int = env->GetMethodID(
      g_field_cls, "getInt", "(Ljava/lang/Object;)I");
  g_field_set_accessible = env->GetMethodID(
      g_field_cls, "setAccessible", "(Z)V");
  if (env->ExceptionCheck()) env->ExceptionClear();
  return g_get_declared_fields && g_get_superclass && g_get_name &&
         g_field_get && g_field_set_int && g_field_get_int &&
         g_field_set_accessible;
}

bool CacheFramework(JNIEnv* env) {
  jclass activity = env->FindClass("android/app/Activity");
  jclass window = env->FindClass("android/view/Window");
  jclass view = env->FindClass("android/view/View");
  jclass relative = env->FindClass("android/widget/RelativeLayout");
  jclass lp = env->FindClass("android/widget/RelativeLayout$LayoutParams");
  jclass integer = env->FindClass("java/lang/Integer");
  if (!activity || !window || !view || !relative || !lp || !integer) {
    env->ExceptionClear();
    return false;
  }
  g_integer_cls = static_cast<jclass>(env->NewGlobalRef(integer));
  g_lp_cls = static_cast<jclass>(env->NewGlobalRef(lp));
  g_integer_int_value = env->GetMethodID(integer, "intValue", "()I");
  g_act_get_window = env->GetMethodID(
      activity, "getWindow", "()Landroid/view/Window;");
  g_activity_find_view = env->GetMethodID(
      activity, "findViewById", "(I)Landroid/view/View;");
  g_win_get_decor = env->GetMethodID(
      window, "getDecorView", "()Landroid/view/View;");
  g_view_set_sysui = env->GetMethodID(view, "setSystemUiVisibility", "(I)V");
  g_view_set_lp = env->GetMethodID(
      view, "setLayoutParams", "(Landroid/view/ViewGroup$LayoutParams;)V");
  g_view_set_visibility = env->GetMethodID(view, "setVisibility", "(I)V");
  g_rl_set_gravity = env->GetMethodID(relative, "setGravity", "(I)V");
  g_lp_ctor = env->GetMethodID(lp, "<init>", "(II)V");
  g_lp_set_margins = env->GetMethodID(lp, "setMargins", "(IIII)V");
  env->DeleteLocalRef(activity);
  env->DeleteLocalRef(window);
  env->DeleteLocalRef(view);
  env->DeleteLocalRef(relative);
  env->DeleteLocalRef(lp);
  env->DeleteLocalRef(integer);
  return g_act_get_window && g_win_get_decor && g_view_set_sysui &&
         g_view_set_lp && g_activity_find_view && g_rl_set_gravity &&
         g_lp_ctor && g_lp_set_margins && g_integer_int_value;
}

}  // namespace

extern "C" NHOOK_MODULE_EXPORT int nhook_module_scope(const char* process) {
  return process && strcmp(process, "com.wt.phonelink") == 0 ? 1 : 0;
}

extern "C" NHOOK_MODULE_EXPORT const char* nhook_module_name(void) {
  return "iccoa_fullscreen";
}

extern "C" NHOOK_MODULE_EXPORT int nhook_module_init(JNIEnv* env,
                                                     const nhook_api* api) {
  if (!api || api->version != NHOOK_API_VERSION) return -1;
  const char* enabled = api->config("iccoa");
  if (enabled && enabled[0] == '0' && enabled[1] == '\0') return 0;
  g_api = api;

  const char* mode = api->config("iccoa_mode");
  g_force = !(mode && strcmp(mode, "nochange") == 0);
  ParsePair(api->config("iccoa_video"), &g_video_w, &g_video_h);
  ParsePair(api->config("iccoa_surface"), &g_surface_w, &g_surface_h);
  g_dpi = ParseInt(api->config("iccoa_dpi"), 0);
  if (g_dpi < 0) g_dpi = 0;
  if (mode && strcmp(mode, "stretch") == 0) {
    g_video_w = 1080;
    g_video_h = 1230;
  }

  if (!CacheReflect(env) || !CacheFramework(env)) {
    env->ExceptionClear();
    Log("iccoa: framework reflection init failed");
    return -1;
  }

  env->GetJavaVM(&g_vm);
  const int hooks = InstallPending(env, true);

  Log("iccoa: hooks=%d mode=%s video=%dx%d surface=%dx%d dpi=%d", hooks,
      mode ? mode : "adapt", g_video_w, g_video_h, g_surface_w, g_surface_h,
      g_dpi);

  if (!AllHooksInstalled() && !g_retry_started) {
    pthread_t thread;
    if (pthread_create(&thread, nullptr, RetryThread, nullptr) == 0) {
      pthread_detach(thread);
      g_retry_started = true;
      Log("iccoa: retry thread started");
    }
  }
  return hooks;
}