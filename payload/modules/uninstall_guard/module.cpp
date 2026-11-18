// uninstall_guard：安装门 + 卸载门 + 为辰安全强卸拦截。
//
// scope：system_server、com.vecentek.decoreapp
// 配置（/data/system/uninstall-hook-native.cfg）：
//   uninstall=1
//   install=1
//   deny=com.android.settings,...
//   sec_target=*

#include <jni.h>
#include <stdio.h>
#include <string.h>

#include "nhook_module.h"

namespace {

const nhook_api* g_api = nullptr;
bool g_uninstall = true;
bool g_install = true;
const char* g_deny = "";
const char* g_sec_target = "*";

jobject BoolValue(JNIEnv* env, bool value) {
  jclass cls = env->FindClass("java/lang/Boolean");
  if (!cls) return nullptr;
  jfieldID id = env->GetStaticFieldID(cls, value ? "TRUE" : "FALSE",
                                      "Ljava/lang/Boolean;");
  return env->GetStaticObjectField(cls, id);
}

void StringArgAt(JNIEnv* env, jobjectArray args, jsize index, char* out,
                 size_t size) {
  out[0] = '\0';
  if (!args || index >= env->GetArrayLength(args)) return;
  jobject value = env->GetObjectArrayElement(args, index);
  if (!value) return;
  jclass string_cls = env->FindClass("java/lang/String");
  if (env->IsInstanceOf(value, string_cls)) {
    const char* chars = env->GetStringUTFChars(static_cast<jstring>(value), nullptr);
    if (chars) {
      snprintf(out, size, "%s", chars);
      env->ReleaseStringUTFChars(static_cast<jstring>(value), chars);
    }
  }
  env->DeleteLocalRef(value);
}

// 取最后一个 String 参数作为包名：
// checkWhiteList(Context, String) 与 (Context, String, String, String) 都适用。
void LastStringArg(JNIEnv* env, jobjectArray args, char* out, size_t size) {
  out[0] = '\0';
  if (!args) return;
  jclass string_cls = env->FindClass("java/lang/String");
  const jsize count = env->GetArrayLength(args);
  for (jsize i = 0; i < count; ++i) {
    jobject value = env->GetObjectArrayElement(args, i);
    if (value && env->IsInstanceOf(value, string_cls)) {
      const char* chars =
          env->GetStringUTFChars(static_cast<jstring>(value), nullptr);
      if (chars) {
        snprintf(out, size, "%s", chars);
        env->ReleaseStringUTFChars(static_cast<jstring>(value), chars);
      }
    }
    if (value) env->DeleteLocalRef(value);
  }
}

int IntArgAt(JNIEnv* env, jobjectArray args, jsize index) {
  if (!args || index >= env->GetArrayLength(args)) return -1;
  jobject value = env->GetObjectArrayElement(args, index);
  if (!value) return -1;
  int result = -1;
  jclass integer_cls = env->FindClass("java/lang/Integer");
  if (env->IsInstanceOf(value, integer_cls)) {
    jmethodID int_value = env->GetMethodID(integer_cls, "intValue", "()I");
    result = env->CallIntMethod(value, int_value);
    if (env->ExceptionCheck()) env->ExceptionClear();
  }
  env->DeleteLocalRef(value);
  return result;
}

bool IsDenied(const char* pkg) {
  const char* cursor = g_deny;
  while (cursor && *cursor) {
    const char* comma = strchr(cursor, ',');
    size_t len = comma ? static_cast<size_t>(comma - cursor) : strlen(cursor);
    if (len > 0) {
      if (cursor[len - 1] == '*') {
        if (strncmp(pkg, cursor, len - 1) == 0) return true;
      } else if (strlen(pkg) == len && strncmp(pkg, cursor, len) == 0) {
        return true;
      }
    }
    if (!comma) break;
    cursor = comma + 1;
  }
  return false;
}

jobject OnCheckWhitelist(JNIEnv* env, jobjectArray args, jobject /*backup*/,
                         void* /*user*/) {
  char pkg[256];
  LastStringArg(env, args, pkg, sizeof(pkg));
  const bool allowed = g_uninstall && !IsDenied(pkg);
  char msg[320];
  snprintf(msg, sizeof(msg), "checkWhiteList(%s) -> %s", pkg,
           allowed ? "true" : "false");
  g_api->log(msg);
  return BoolValue(env, allowed);
}

jobject OnCheckAuth(JNIEnv* env, jobjectArray /*args*/, jobject /*backup*/,
                    void* /*user*/) {
  g_api->log("isCheckAuth -> true");
  return BoolValue(env, true);
}

jobject OnVeceentek(JNIEnv* env, jobjectArray args, jobject backup,
                    void* /*user*/) {
  char pkg[256];
  StringArgAt(env, args, 1, pkg, sizeof(pkg));
  const int type = IntArgAt(env, args, 2);
  const bool allow =
      (type == 1 || type == 4) &&
      (strcmp(g_sec_target, "*") == 0 || strcmp(pkg, g_sec_target) == 0);
  if (allow) {
    char msg[320];
    snprintf(msg, sizeof(msg), "vecentek allow %s type=%d", pkg, type);
    g_api->log(msg);
    return BoolValue(env, true);
  }
  return g_api->call_backup(env, backup, args);
}

int TryHookAll(JNIEnv* env, const char* cls, const char* method,
               nhook_callback cb) {
  const int n = g_api->hook_all(env, cls, method, cb, nullptr);
  return n > 0 ? n : 0;
}

}  // namespace

extern "C" NHOOK_MODULE_EXPORT int nhook_module_scope(const char* process) {
  return (strcmp(process, "system_server") == 0 ||
          strcmp(process, "com.vecentek.decoreapp") == 0)
             ? 1
             : 0;
}

extern "C" NHOOK_MODULE_EXPORT const char* nhook_module_name(void) {
  return "uninstall_guard";
}

extern "C" NHOOK_MODULE_EXPORT int nhook_module_init(JNIEnv* env,
                                                     const nhook_api* api) {
  if (!api || api->version != NHOOK_API_VERSION) return -1;
  g_api = api;
  const char* uninstall = api->config("uninstall");
  const char* install = api->config("install");
  const char* deny = api->config("deny");
  const char* target = api->config("sec_target");
  g_uninstall = !(uninstall[0] == '0' && uninstall[1] == '\0');
  g_install = !(install[0] == '0' && install[1] == '\0');
  g_deny = deny ? deny : "";
  g_sec_target = (target && target[0]) ? target : "*";

  const char* process = api->process_name();
  int hooks = 0;
  if (strcmp(process, "system_server") == 0) {
    if (g_uninstall) {
      hooks += TryHookAll(env, "com/vecentek/security2/WhiteListHelper",
                          "checkWhiteList", OnCheckWhitelist);
      if (hooks == 0) {
        hooks += TryHookAll(env, "com/vecentek/security/WhiteListHelper",
                            "checkWhiteList", OnCheckWhitelist);
      }
    }
    if (g_install) {
      int n = TryHookAll(env, "com/vecentek/security2/CertificateManager",
                         "isCheckAuth", OnCheckAuth);
      if (n == 0) {
        n = TryHookAll(env, "com/vecentek/security/CertificateManager",
                       "isCheckAuth", OnCheckAuth);
      }
      hooks += n;
    }
  } else if (strcmp(process, "com.vecentek.decoreapp") == 0) {
    if (api->hook_method(env, "com/vecentek/decoreapp/c/a", "b",
                         "(Ljava/lang/String;I)Z", OnVeceentek,
                         nullptr) == 0) {
      ++hooks;
    }
  }
  return hooks;
}