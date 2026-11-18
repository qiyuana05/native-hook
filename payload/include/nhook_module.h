#pragma once

#include <jni.h>
#include <stdint.h>

// NativeHook 模块 ABI。模块只通过 nhook_api 使用运行时能力。
#define NHOOK_API_VERSION 1
#define NHOOK_MODULE_EXPORT __attribute__((visibility("default")))

#ifdef __cplusplus
extern "C" {
#endif

typedef jobject (*nhook_callback)(JNIEnv* env, jobjectArray args,
                                  jobject backup, void* user);

typedef struct nhook_api {
  uint32_t version;
  const char* (*process_name)(void);
  const char* (*config)(const char* key);
  void (*log)(const char* message);
  int (*hook_method)(JNIEnv* env, const char* class_name, const char* method,
                     const char* signature, nhook_callback cb, void* user);
  int (*hook_all)(JNIEnv* env, const char* class_name, const char* method,
                  nhook_callback cb, void* user);
  jobject (*call_backup)(JNIEnv* env, jobject backup, jobjectArray args);
} nhook_api;

typedef int (*nhook_module_scope_fn)(const char* process_name);
typedef int (*nhook_module_init_fn)(JNIEnv* env, const nhook_api* api);
typedef const char* (*nhook_module_name_fn)(void);

#ifdef __cplusplus
}
#endif