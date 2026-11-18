#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include "dobby.h"
#include "elf_symbols.h"
#include "hooker_dex.h"
#include "lsplant.hpp"
#include "nhook_module.h"

// 用独立 tag，避免和系统里其他带 "NativeHook" 字样的日志混在一起。
#define LOG_TAG "NHookNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr const char* kConfigPath = "/data/system/uninstall-hook-native.cfg";
constexpr const char* kStatusPath = "/data/system/uninstall-hook-native.status";
constexpr const char* kModulesDir = "/data/local/tmp/hook-native/modules";
constexpr const char* kArtPath = "/system/lib64/libart.so";

std::string StatusPath(const std::string& process) {
  std::string name = process.empty() ? "unknown" : process;
  for (char& c : name) {
    if (c == '/' || c == '\\' || c == ' ' || c == ':') c = '_';
  }
  return "/data/system/nativehook-" + name + ".status";
}

struct Config {
  std::map<std::string, std::string> values;

  void Load(const char* path) {
    FILE* fp = fopen(path, "r");
    if (!fp) {
      LOGI("config %s missing", path);
      return;
    }
    char line[4096];
    while (fgets(line, sizeof(line), fp)) {
      char* nl = strchr(line, '\n');
      if (nl) *nl = '\0';
      char* eq = strchr(line, '=');
      if (!eq || line[0] == '#') continue;
      *eq = '\0';
      values[line] = eq + 1;
    }
    fclose(fp);
  }

  const char* Get(const char* key) const {
    auto it = values.find(key);
    return it == values.end() ? "" : it->second.c_str();
  }
};

Config g_config;
std::string g_process;
std::string g_status_path = kStatusPath;
std::string g_current_module;
std::string g_loaded_modules;
int g_total_hooks = 0;
JavaVM* g_vm = nullptr;
jclass g_hooker_cls = nullptr;
jmethodID g_callback_id = nullptr;
jobject g_callback_method = nullptr;
jfieldID g_cb_field = nullptr;
jfieldID g_user_field = nullptr;
jfieldID g_backup_field = nullptr;
std::vector<jobject> g_hookers;
std::vector<jobject> g_backups;
std::vector<void*> g_module_handles;

// 状态文件必须是「本进程可重写」的。
// 早期用 root 手工写过一次 status，文件变成 root:root 0600，
// 之后 system uid 的进程 open(O_WRONLY) 直接 EACCES，状态永远停在旧 pid，
// 常驻检测就会一直判 bad(stale) 并误报「注入失败」。
// 这里遇到打不开就先 unlink（/data/system 属主是 system，进程有目录写权限），
// 再新建一个属于自己的文件。
bool OpenStatusFile(FILE** out) {
  *out = fopen(g_status_path.c_str(), "w");
  if (*out) return true;
  const int err = errno;
  if (err == EACCES || err == EPERM) {
    if (unlink(g_status_path.c_str()) == 0) {
      *out = fopen(g_status_path.c_str(), "w");
      if (*out) return true;
    }
  }
  return false;
}

void WriteStatus(const char* fmt, ...) {
  FILE* fp = nullptr;
  if (!OpenStatusFile(&fp)) return;
  va_list ap;
  va_start(ap, fmt);
  vfprintf(fp, fmt, ap);
  va_end(ap);
  fclose(fp);
}

std::string ProcessName() {
  FILE* fp = fopen("/proc/self/cmdline", "rb");
  if (!fp) return {};
  char buf[256] = {};
  size_t got = fread(buf, 1, sizeof(buf) - 1, fp);
  fclose(fp);
  if (got == 0) return {};
  return std::string(buf);
}

std::string ToUtf8(JNIEnv* env, jstring str) {
  if (!str) return {};
  const char* chars = env->GetStringUTFChars(str, nullptr);
  std::string result(chars ? chars : "");
  if (chars) env->ReleaseStringUTFChars(str, chars);
  return result;
}

using GetCreatedJavaVMsFn = jint (*)(JavaVM**, jsize, jsize*);

GetCreatedJavaVMsFn ResolveGetCreatedVMs() {
  auto fn = reinterpret_cast<GetCreatedJavaVMsFn>(
      dlsym(RTLD_DEFAULT, "JNI_GetCreatedJavaVMs"));
  if (fn) return fn;
  for (const char* lib : {"libart.so", "libnativehelper.so"}) {
    void* handle = dlopen(lib, RTLD_NOW | RTLD_NOLOAD);
    if (!handle) continue;
    fn = reinterpret_cast<GetCreatedJavaVMsFn>(dlsym(handle, "JNI_GetCreatedJavaVMs"));
    if (fn) return fn;
  }
  return nullptr;
}

// 调用 LSPlant 返回的 backup（原方法）。
//
// backup 虽然是在 LSPlant 生成的类里 new 出来的方法，但 DoHook 会把原方法的
// ArtMethod 内容（含 access flags）整体拷进 backup。所以：
//   - 原方法是静态的 -> backup 也是静态，invoke(null, args)
//   - 原方法是实例的 -> backup 也是实例，invoke(args[0], args[1..])
// 只看 backup.getModifiers() 不可靠（生成时的 dex flags 和 BackupTo 之后的
// 运行时 flags 可能不一致），所以在 HookOne 里按 **目标方法** 的修饰符记一份
// 静态标记，回调时按 jmethodID 查回来。
//
// 早期版本一律按 Modifier.isStatic(backup.getModifiers()) 判断，把实例方法
// 的 this 剥掉，导致原方法根本没执行：initDisplayMetrics 不跑 ->
// mDisplayDensityDpi=0 -> UCarConfig.setDpi 抛 "dpi must be between 120 and
// 640" -> com.wt.phonelink 每次进 ICCOA 闪退；SystemUI 的 addView 同样被跳过，
// 窗口加不进去，随后 removeView 抛 IllegalArgumentException 把 SystemUI 打崩。
std::map<jmethodID, bool> g_backup_static;
std::mutex g_backup_static_lock;

bool IsStaticMethod(JNIEnv* env, jobject method) {
  jclass method_cls = env->FindClass("java/lang/reflect/Method");
  jclass modifier_cls = env->FindClass("java/lang/reflect/Modifier");
  if (!method_cls || !modifier_cls) {
    env->ExceptionClear();
    return false;
  }
  jmethodID get_modifiers = env->GetMethodID(method_cls, "getModifiers", "()I");
  jmethodID is_static =
      env->GetStaticMethodID(modifier_cls, "isStatic", "(I)Z");
  bool result = false;
  if (get_modifiers && is_static) {
    jint mods = env->CallIntMethod(method, get_modifiers);
    if (!env->ExceptionCheck()) {
      result = env->CallStaticBooleanMethod(modifier_cls, is_static, mods) ==
               JNI_TRUE;
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
  }
  env->DeleteLocalRef(modifier_cls);
  env->DeleteLocalRef(method_cls);
  return result;
}

jobject CallBackup(JNIEnv* env, jobject backup, jobjectArray args) {
  if (!backup) return nullptr;
  jclass method_cls = env->FindClass("java/lang/reflect/Method");
  if (!method_cls) {
    env->ExceptionClear();
    return nullptr;
  }
  jmethodID invoke = env->GetMethodID(
      method_cls, "invoke",
      "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
  if (!invoke) {
    env->ExceptionClear();
    env->DeleteLocalRef(method_cls);
    return nullptr;
  }

  const jsize count = args ? env->GetArrayLength(args) : 0;
  const jmethodID backup_id = env->FromReflectedMethod(backup);
  bool is_static = true;
  {
    std::lock_guard<std::mutex> lock(g_backup_static_lock);
    auto it = g_backup_static.find(backup_id);
    if (it != g_backup_static.end()) is_static = it->second;
  }

  jobject receiver = nullptr;
  jobjectArray call_args = args;
  if (!is_static) {
    if (count == 0) {
      LOGE("backup: instance method without this");
      env->DeleteLocalRef(method_cls);
      return nullptr;
    }
    receiver = env->GetObjectArrayElement(args, 0);
    jclass object_cls = env->FindClass("java/lang/Object");
    call_args = env->NewObjectArray(count - 1, object_cls, nullptr);
    env->DeleteLocalRef(object_cls);
    for (jsize i = 1; i < count; ++i) {
      jobject value = env->GetObjectArrayElement(args, i);
      env->SetObjectArrayElement(call_args, i - 1, value);
      if (value) env->DeleteLocalRef(value);
    }
  }

  jobject result = env->CallObjectMethod(backup, invoke, receiver, call_args);
  if (env->ExceptionCheck()) {
    // 原方法如果真的抛异常，按原逻辑应该继续往上抛；但模块回调在 call_backup
    // 之后还会继续用 JNI，带 pending exception 调用 JNI 会让 ART 直接 abort，
    // 所以这里打印后清掉，至少保证进程不崩。
    env->ExceptionDescribe();
    env->ExceptionClear();
    LOGE("backup threw");
    result = nullptr;
  }

  if (receiver) env->DeleteLocalRef(receiver);
  if (call_args != args) env->DeleteLocalRef(call_args);
  env->DeleteLocalRef(method_cls);
  return result;
}

jobject __attribute__((visibility("default")))
HookerCallback(JNIEnv* env, jobject thiz, jobjectArray args) {
  const jlong cb = env->GetLongField(thiz, g_cb_field);
  const jlong user = env->GetLongField(thiz, g_user_field);
  jobject backup = env->GetObjectField(thiz, g_backup_field);
  auto fn = reinterpret_cast<nhook_callback>(static_cast<intptr_t>(cb));
  if (!fn) {
    if (backup) env->DeleteLocalRef(backup);
    return nullptr;
  }
  jobject result = fn(env, args, backup,
                      reinterpret_cast<void*>(static_cast<intptr_t>(user)));
  if (backup) env->DeleteLocalRef(backup);
  return result;
}

jclass LoadHookerClass(JNIEnv* env) {
  jclass byte_buffer = env->FindClass("java/nio/ByteBuffer");
  if (!byte_buffer) return nullptr;
  jmethodID wrap = env->GetStaticMethodID(byte_buffer, "wrap",
                                          "([B)Ljava/nio/ByteBuffer;");
  if (!wrap) return nullptr;

  auto dex_array = env->NewByteArray(static_cast<jsize>(kHookerDexSize));
  env->SetByteArrayRegion(dex_array, 0, static_cast<jsize>(kHookerDexSize),
                          reinterpret_cast<const jbyte*>(kHookerDex));
  jobject buffer = env->CallStaticObjectMethod(byte_buffer, wrap, dex_array);
  env->DeleteLocalRef(dex_array);

  jclass class_loader = env->FindClass("java/lang/ClassLoader");
  jmethodID get_system = env->GetStaticMethodID(
      class_loader, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
  jobject parent = env->CallStaticObjectMethod(class_loader, get_system);

  jclass in_memory = env->FindClass("dalvik/system/InMemoryDexClassLoader");
  if (!in_memory) return nullptr;
  jmethodID ctor = env->GetMethodID(
      in_memory, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
  jobject loader = env->NewObject(in_memory, ctor, buffer, parent);

  jmethodID load_class = env->GetMethodID(class_loader, "loadClass",
                                          "(Ljava/lang/String;)Ljava/lang/Class;");
  jstring name = env->NewStringUTF("com.changan.nh.Hooker");
  auto hooker_cls =
      static_cast<jclass>(env->CallObjectMethod(loader, load_class, name));
  env->DeleteLocalRef(name);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return nullptr;
  }

  JNINativeMethod native = {
      const_cast<char*>("callback"),
      const_cast<char*>("([Ljava/lang/Object;)Ljava/lang/Object;"),
      reinterpret_cast<void*>(HookerCallback),
  };
  if (env->RegisterNatives(hooker_cls, &native, 1) != JNI_OK) {
    LOGE("RegisterNatives failed");
    return nullptr;
  }
  return static_cast<jclass>(env->NewGlobalRef(hooker_cls));
}

// system_server 的类走 bootclasspath；应用进程的类需要用应用 ClassLoader。
jclass FindClassWithAppLoader(JNIEnv* env, const char* name) {
  jclass cls = env->FindClass(name);
  if (cls) return cls;
  env->ExceptionClear();

  jclass activity_thread = env->FindClass("android/app/ActivityThread");
  if (!activity_thread) {
    env->ExceptionClear();
    return nullptr;
  }
  jmethodID current_app = env->GetStaticMethodID(
      activity_thread, "currentApplication", "()Landroid/app/Application;");
  if (!current_app) {
    env->ExceptionClear();
    return nullptr;
  }
  jobject app = env->CallStaticObjectMethod(activity_thread, current_app);
  if (!app) {
    env->ExceptionClear();
    return nullptr;
  }
  jclass app_cls = env->GetObjectClass(app);
  jmethodID get_loader = env->GetMethodID(app_cls, "getClassLoader",
                                          "()Ljava/lang/ClassLoader;");
  jobject loader = env->CallObjectMethod(app, get_loader);
  if (!loader) {
    env->ExceptionClear();
    return nullptr;
  }
  jclass loader_cls = env->FindClass("java/lang/ClassLoader");
  jmethodID load_class = env->GetMethodID(loader_cls, "loadClass",
                                          "(Ljava/lang/String;)Ljava/lang/Class;");
  std::string dotted(name);
  std::replace(dotted.begin(), dotted.end(), '/', '.');
  jstring jname = env->NewStringUTF(dotted.c_str());
  cls = static_cast<jclass>(env->CallObjectMethod(loader, load_class, jname));
  env->DeleteLocalRef(jname);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return nullptr;
  }
  return cls;
}

int HookOne(JNIEnv* env, jobject method, nhook_callback cb, void* user) {
  if (!env || !method || !cb) return -1;
  jmethodID ctor = env->GetMethodID(g_hooker_cls, "<init>", "(JJ)V");
  jobject hooker = env->NewObject(
      g_hooker_cls, ctor, static_cast<jlong>(reinterpret_cast<intptr_t>(cb)),
      static_cast<jlong>(reinterpret_cast<intptr_t>(user)));
  if (!hooker) {
    env->ExceptionClear();
    return -1;
  }
  jobject hooker_global = env->NewGlobalRef(hooker);
  env->DeleteLocalRef(hooker);
  const bool target_static = IsStaticMethod(env, method);
  jobject backup = lsplant::Hook(env, method, hooker_global, g_callback_method);
  if (!backup) {
    env->DeleteGlobalRef(hooker_global);
    return -1;
  }
  const jmethodID backup_id = env->FromReflectedMethod(backup);
  {
    std::lock_guard<std::mutex> lock(g_backup_static_lock);
    g_backup_static[backup_id] = target_static;
  }
  LOGI("hook backup id=%p static=%d", reinterpret_cast<void*>(backup_id),
       target_static ? 1 : 0);
  env->SetObjectField(hooker_global, g_backup_field, backup);
  g_backups.push_back(env->NewGlobalRef(backup));
  g_hookers.push_back(hooker_global);
  env->DeleteLocalRef(backup);
  return 0;
}

// ---- nhook_api 实现 ----

const char* ApiProcessName() { return g_process.c_str(); }

const char* ApiConfig(const char* key) {
  return key ? g_config.Get(key) : "";
}

void ApiLog(const char* message) {
  LOGI("[module] %s", message ? message : "");
}

int ApiHookMethod(JNIEnv* env, const char* class_name, const char* method,
                  const char* signature, nhook_callback cb, void* user) {
  jclass cls = FindClassWithAppLoader(env, class_name);
  if (!cls) {
    env->ExceptionClear();
    LOGE("class not found: %s", class_name);
    return -1;
  }
  jmethodID mid = env->GetMethodID(cls, method, signature);
  if (!mid) {
    env->ExceptionClear();
    LOGE("method not found: %s.%s%s", class_name, method, signature);
    return -1;
  }
  jobject target = env->ToReflectedMethod(cls, mid, false);
  if (!target) {
    env->ExceptionClear();
    return -1;
  }
  int rc = HookOne(env, target, cb, user);
  env->DeleteLocalRef(target);
  return rc;
}

int ApiHookAll(JNIEnv* env, const char* class_name, const char* method,
               nhook_callback cb, void* user) {
  jclass cls = FindClassWithAppLoader(env, class_name);
  if (!cls) {
    env->ExceptionClear();
    LOGE("class not found: %s", class_name);
    return -1;
  }
  jclass class_cls = env->FindClass("java/lang/Class");
  jmethodID get_methods = env->GetMethodID(
      class_cls, "getDeclaredMethods", "()[Ljava/lang/reflect/Method;");
  auto methods = static_cast<jobjectArray>(
      env->CallObjectMethod(cls, get_methods));
  if (!methods) {
    env->ExceptionClear();
    return -1;
  }
  jclass method_cls = env->FindClass("java/lang/reflect/Method");
  jmethodID get_name = env->GetMethodID(method_cls, "getName",
                                        "()Ljava/lang/String;");
  int hooked = 0;
  const jsize count = env->GetArrayLength(methods);
  for (jsize i = 0; i < count; ++i) {
    jobject m = env->GetObjectArrayElement(methods, i);
    auto name = static_cast<jstring>(env->CallObjectMethod(m, get_name));
    const bool match = name && ToUtf8(env, name) == method;
    if (name) env->DeleteLocalRef(name);
    if (match && HookOne(env, m, cb, user) == 0) ++hooked;
    env->DeleteLocalRef(m);
  }
  env->DeleteLocalRef(methods);
  return hooked;
}

jobject ApiCallBackup(JNIEnv* env, jobject backup, jobjectArray args) {
  return CallBackup(env, backup, args);
}

nhook_api g_api = {
    NHOOK_API_VERSION,
    ApiProcessName,
    ApiConfig,
    ApiLog,
    ApiHookMethod,
    ApiHookAll,
    ApiCallBackup,
};

void LoadModules(JNIEnv* env) {
  DIR* dir = opendir(kModulesDir);
  if (!dir) {
    LOGI("modules dir %s not found", kModulesDir);
    return;
  }
  std::vector<std::string> paths;
  struct dirent* entry;
  while ((entry = readdir(dir)) != nullptr) {
    if (entry->d_name[0] == '.') continue;
    const size_t len = strlen(entry->d_name);
    if (len < 3 || strcmp(entry->d_name + len - 3, ".so") != 0) continue;
    paths.push_back(std::string(kModulesDir) + "/" + entry->d_name);
  }
  closedir(dir);
  std::sort(paths.begin(), paths.end());

  for (const auto& path : paths) {
    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
      LOGE("dlopen %s failed: %s", path.c_str(), dlerror());
      continue;
    }
    auto scope = reinterpret_cast<nhook_module_scope_fn>(
        dlsym(handle, "nhook_module_scope"));
    auto init = reinterpret_cast<nhook_module_init_fn>(
        dlsym(handle, "nhook_module_init"));
    auto name = reinterpret_cast<nhook_module_name_fn>(
        dlsym(handle, "nhook_module_name"));
    if (!scope || !init || !name) {
      LOGE("%s: missing module symbols", path.c_str());
      dlclose(handle);
      continue;
    }
    const char* module_name = name();
    g_current_module = module_name ? module_name : "unknown";
    if (scope(g_process.c_str()) != 1) {
      LOGI("[module] %s skipped (scope)", g_current_module.c_str());
      dlclose(handle);
      continue;
    }
    const int hooks = init(env, &g_api);
    if (hooks < 0) {
      LOGE("[module] %s init failed", g_current_module.c_str());
      dlclose(handle);
      continue;
    }
    LOGI("[module] %s loaded (%d hooks)", g_current_module.c_str(), hooks);
    if (!g_loaded_modules.empty()) g_loaded_modules += ",";
    g_loaded_modules += g_current_module;
    g_total_hooks += hooks;
    g_module_handles.push_back(handle);
  }
}

void InitPayload() {
  JNIEnv* env = nullptr;
  jsize vm_count = 0;
  GetCreatedJavaVMsFn get_vms = ResolveGetCreatedVMs();
  if (!get_vms) {
    LOGE("JNI_GetCreatedJavaVMs not resolvable");
    WriteStatus("ok=0\nerror=no_jni_getter\n");
    return;
  }
  if (get_vms(&g_vm, 1, &vm_count) != JNI_OK || vm_count == 0) {
    LOGE("JavaVM not found");
    WriteStatus("ok=0\nerror=no_vm\n");
    return;
  }
  if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
    LOGE("AttachCurrentThread failed");
    WriteStatus("ok=0\nerror=attach_failed\n");
    return;
  }

  g_process = ProcessName();
  g_status_path = StatusPath(g_process);
  LOGI("runtime in process %s", g_process.c_str());
  g_config.Load(kConfigPath);
  LOGI("config keys=%zu", g_config.values.size());

  ElfSymbolResolver art(kArtPath);
  if (!art.Open()) {
    LOGE("failed to open %s", kArtPath);
    WriteStatus("ok=0\nerror=libart_open\n");
    g_vm->DetachCurrentThread();
    return;
  }

  lsplant::InitInfo info{
      .inline_hooker =
          [](void* target, void* hooker) -> void* {
            void* backup = nullptr;
            if (DobbyHook(target, hooker, &backup) != RT_SUCCESS) {
              LOGE("DobbyHook(%p) failed", target);
              return nullptr;
            }
            return backup;
          },
      .inline_unhooker =
          [](void* func) -> bool {
            return DobbyDestroy(func) == RT_SUCCESS;
          },
      .art_symbol_resolver =
          [&art](std::string_view symbol) -> void* {
            return art.Resolve(symbol);
          },
      .art_symbol_prefix_resolver =
          [&art](std::string_view prefix) -> void* {
            return art.ResolvePrefix(prefix);
          },
      .generated_class_name = "LSPHooker_NH_",
  };

  if (!lsplant::Init(env, info)) {
    LOGE("lsplant::Init failed");
    WriteStatus("ok=0\nerror=lsplant_init\n");
    g_vm->DetachCurrentThread();
    return;
  }
  LOGI("lsplant initialized");

  g_hooker_cls = LoadHookerClass(env);
  if (!g_hooker_cls) {
    LOGE("failed to load Hooker");
    WriteStatus("ok=0\nerror=hooker_load\n");
    g_vm->DetachCurrentThread();
    return;
  }
  g_callback_id = env->GetMethodID(g_hooker_cls, "callback",
                                   "([Ljava/lang/Object;)Ljava/lang/Object;");
  jobject callback_method =
      env->ToReflectedMethod(g_hooker_cls, g_callback_id, false);
  g_callback_method = env->NewGlobalRef(callback_method);
  env->DeleteLocalRef(callback_method);
  g_cb_field = env->GetFieldID(g_hooker_cls, "cb", "J");
  g_user_field = env->GetFieldID(g_hooker_cls, "user", "J");
  g_backup_field = env->GetFieldID(g_hooker_cls, "backup", "Ljava/lang/Object;");

  LoadModules(env);

  WriteStatus("ok=%d\npid=%d\nprocess=%s\nmodules=%s\nhooks=%d\n",
              g_total_hooks > 0 ? 1 : 0, getpid(), g_process.c_str(),
              g_loaded_modules.c_str(), g_total_hooks);
  LOGI("runtime ready pid=%d hooks=%d", getpid(), g_total_hooks);
  g_vm->DetachCurrentThread();
}

void* InitThread(void*) {
  LOGI("runtime init thread started");
  InitPayload();
  return nullptr;
}

}  // namespace

__attribute__((constructor)) static void OnPayloadLoad() {
  LOGI("runtime constructor pid=%d", getpid());
  pthread_t thread;
  if (pthread_create(&thread, nullptr, InitThread, nullptr) == 0) {
    pthread_detach(thread);
  } else {
    LOGE("pthread_create failed");
  }
}