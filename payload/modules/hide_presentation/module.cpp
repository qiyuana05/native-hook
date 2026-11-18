// hide_presentation：把语音服务里 1440x110 的 PRESENTATION 窗口 alpha 置 0，
// 并保持 NOT_TOUCHABLE，消掉车机顶部 110px 黑带。
//
// scope: com.iflytek.autofly.voicecoreservice
// 配置：presentation=1（默认开）

#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "nhook_module.h"

namespace {

constexpr int kTypePresentation = 2037;
constexpr int kPresentW = 1440;
constexpr int kPresentH = 110;
constexpr int kFlagNotTouchable = 0x10;

const nhook_api* g_api = nullptr;
JavaVM* g_vm = nullptr;
jclass g_lp_class = nullptr;
jfieldID g_lp_type = nullptr;
jfieldID g_lp_width = nullptr;
jfieldID g_lp_height = nullptr;
jfieldID g_lp_flags = nullptr;
jfieldID g_lp_alpha = nullptr;
volatile bool g_running = true;

bool IsTarget(JNIEnv* env, jobject lp, int* new_flags) {
  if (!lp) return false;
  const int type = env->GetIntField(lp, g_lp_type);
  const int width = env->GetIntField(lp, g_lp_width);
  const int height = env->GetIntField(lp, g_lp_height);
  const float alpha = env->GetFloatField(lp, g_lp_alpha);
  const int flags = env->GetIntField(lp, g_lp_flags);
  if (type != kTypePresentation || width != kPresentW || height != kPresentH) {
    return false;
  }
  if (alpha == 0.0f && (flags & kFlagNotTouchable) != 0) return false;
  *new_flags = flags | kFlagNotTouchable;
  return true;
}

void Apply(JNIEnv* env, jobject vri, jobject lp) {
  int new_flags = 0;
  if (!IsTarget(env, lp, &new_flags)) return;
  env->SetFloatField(lp, g_lp_alpha, 0.0f);
  env->SetIntField(lp, g_lp_flags, new_flags);
  jclass vri_cls = env->GetObjectClass(vri);
  jmethodID set_lp = env->GetMethodID(
      vri_cls, "setLayoutParams",
      "(Landroid/view/WindowManager$LayoutParams;Z)V");
  if (set_lp) {
    env->CallVoidMethod(vri, set_lp, lp, JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
    g_api->log("hide: presentation alpha=0");
  } else {
    env->ExceptionClear();
    g_api->log("hide: setLayoutParams missing");
  }
  env->DeleteLocalRef(vri_cls);
}

void Sweep(JNIEnv* env) {
  jclass wmg = env->FindClass("android/view/WindowManagerGlobal");
  if (!wmg) {
    env->ExceptionClear();
    return;
  }
  jfieldID views_id =
      env->GetStaticFieldID(wmg, "mViews", "Ljava/util/ArrayList;");
  if (!views_id) {
    env->ExceptionClear();
    g_api->log("hide: mViews missing");
    env->DeleteLocalRef(wmg);
    return;
  }
  jobject views = env->GetStaticObjectField(wmg, views_id);
  if (!views) {
    env->DeleteLocalRef(wmg);
    return;
  }
  jclass list_cls = env->FindClass("java/util/ArrayList");
  jmethodID size = env->GetMethodID(list_cls, "size", "()I");
  jmethodID get = env->GetMethodID(list_cls, "get", "(I)Ljava/lang/Object;");
  const int count = env->CallIntMethod(views, size);
  for (int i = 0; i < count; ++i) {
    jobject vri = env->CallObjectMethod(views, get, i);
    if (!vri) continue;
    jclass vri_cls = env->GetObjectClass(vri);
    jfieldID attrs_id = env->GetFieldID(
        vri_cls, "mWindowAttributes",
        "Landroid/view/WindowManager$LayoutParams;");
    if (attrs_id) {
      jobject lp = env->GetObjectField(vri, attrs_id);
      if (lp) {
        const int type = env->GetIntField(lp, g_lp_type);
        if (type == kTypePresentation) {
          char msg[128];
          snprintf(msg, sizeof(msg), "hide: win type=%d w=%d h=%d alpha=%.2f",
                   type, env->GetIntField(lp, g_lp_width),
                   env->GetIntField(lp, g_lp_height),
                   env->GetFloatField(lp, g_lp_alpha));
          g_api->log(msg);
        }
        Apply(env, vri, lp);
        env->DeleteLocalRef(lp);
      }
    } else {
      env->ExceptionClear();
    }
    env->DeleteLocalRef(vri_cls);
    env->DeleteLocalRef(vri);
  }
  env->DeleteLocalRef(views);
  env->DeleteLocalRef(wmg);
}

jobject OnAddView(JNIEnv* env, jobjectArray args, jobject backup, void*) {
  if (args && env->GetArrayLength(args) >= 3) {
    jobject params = env->GetObjectArrayElement(args, 2);
    if (params && g_lp_class && env->IsInstanceOf(params, g_lp_class)) {
      int new_flags = 0;
      if (IsTarget(env, params, &new_flags)) {
        env->SetFloatField(params, g_lp_alpha, 0.0f);
        env->SetIntField(params, g_lp_flags, new_flags);
        g_api->log("hide: addView presentation alpha=0");
      }
    }
    if (params) env->DeleteLocalRef(params);
  }
  return g_api->call_backup(env, backup, args);
}

void* ThreadMain(void*) {
  JNIEnv* env = nullptr;
  while (g_running) {
    sleep(3);
    if (!g_running) break;
    if (g_vm && g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
      Sweep(env);
      g_vm->DetachCurrentThread();
    }
  }
  return nullptr;
}

bool ConfigOn(const nhook_api* api, const char* key) {
  const char* v = api->config(key);
  return !(v && v[0] == '0' && v[1] == '\0');
}

}  // namespace

extern "C" NHOOK_MODULE_EXPORT int nhook_module_scope(const char* process) {
  return process &&
                 strcmp(process, "com.iflytek.autofly.voicecoreservice") == 0
             ? 1
             : 0;
}

extern "C" NHOOK_MODULE_EXPORT const char* nhook_module_name(void) {
  return "hide_presentation";
}

extern "C" NHOOK_MODULE_EXPORT int nhook_module_init(JNIEnv* env,
                                                     const nhook_api* api) {
  if (!api || api->version != NHOOK_API_VERSION) return -1;
  if (!ConfigOn(api, "presentation")) return 0;
  g_api = api;

  jclass lp_local = env->FindClass("android/view/WindowManager$LayoutParams");
  if (!lp_local) {
    env->ExceptionClear();
    return -1;
  }
  g_lp_class = static_cast<jclass>(env->NewGlobalRef(lp_local));
  env->DeleteLocalRef(lp_local);
  g_lp_type = env->GetFieldID(g_lp_class, "type", "I");
  g_lp_width = env->GetFieldID(g_lp_class, "width", "I");
  g_lp_height = env->GetFieldID(g_lp_class, "height", "I");
  g_lp_flags = env->GetFieldID(g_lp_class, "flags", "I");
  g_lp_alpha = env->GetFieldID(g_lp_class, "alpha", "F");
  if (!g_lp_type || !g_lp_width || !g_lp_height || !g_lp_flags ||
      !g_lp_alpha) {
    env->ExceptionClear();
    return -1;
  }
  env->GetJavaVM(&g_vm);

  int hooks = 0;
  if (api->hook_method(
          env, "android/view/WindowManagerGlobal", "addView",
          "(Landroid/view/View;Landroid/view/ViewGroup$LayoutParams;"
          "Landroid/view/Display;Landroid/view/Window;)V",
          OnAddView, nullptr) == 0) {
    ++hooks;
  }
  pthread_t thread;
  if (pthread_create(&thread, nullptr, ThreadMain, nullptr) == 0) {
    pthread_detach(thread);
    ++hooks;
  }
  return hooks;
}