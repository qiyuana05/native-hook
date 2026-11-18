# NativeHook 模块标准（v1）

目标：把 `libnhpayload.so` 变成固定运行时，所有功能以独立 `.so` 模块加载。
新功能不再改运行时，只新增模块；模块自己声明作用进程（类似 LSPosed 的 scope）。

## 1. 与 LSPosed 的差异

| 项 | LSPosed | NativeHook |
|---|---|---|
| 模块语言 | Java（Xposed API） | C/C++ |
| 注入方式 | Zygote 常驻，全局注入 | 按需 ptrace 注入指定 PID |
| 作用域 | 包名 scope | 模块导出的 `nhook_module_scope(process)` |
| 生效时机 | 应用启动前 | 注入后立即 hook |
| 恢复 | 重启应用 | 重启目标进程/车机 |
| 依赖 | Magisk + LSPosed | root（DM01）+ Permissive |

## 2. 目录结构

```
/data/local/tmp/hook-native/
├── nhinject                    # ptrace 注入器
├── liblsplant.so               # ART hook 引擎
├── libnhpayload.so             # 固定运行时
├── modules/                    # 功能模块
│   ├── uninstall_guard.so
│   ├── statusbar_passthrough.so
│   └── ...
├── modules.d/                  # 可选模块配置
│   ├── uninstall_guard.conf
│   └── statusbar_passthrough.conf
└── .version
```

运行时启动后扫描 `modules/*.so`，按文件名排序加载。模块加载后常驻目标进程，
重启目标进程即全部恢复。

## 3. 模块 ABI

头文件：`payload/include/nhook_module.h`

```c
#define NHOOK_API_VERSION 1

typedef jobject (*nhook_callback)(JNIEnv* env, jobjectArray args,
                                  jobject backup, void* user);

typedef struct nhook_api {
    uint32_t version;
    const char* (*process_name)(void);
    const char* (*config)(const char* key);
    void (*log)(const char* message);
    int (*hook_method)(JNIEnv*, const char* cls, const char* method,
                       const char* signature, nhook_callback, void* user);
    int (*hook_all)(JNIEnv*, const char* cls, const char* method,
                    nhook_callback, void* user);
    jobject (*call_backup)(JNIEnv*, jobject backup, jobjectArray args);
} nhook_api;

int  nhook_module_scope(const char* process_name);   // 1=当前进程启用
int  nhook_module_init(JNIEnv* env, const nhook_api* api); // 返回 hook 数
const char* nhook_module_name(void);
```

约束：

- 模块只通过 `nhook_api` 使用运行时能力，不直接链接运行时符号。
- 模块导出使用 `extern "C"`，符号可见性 default。
- 模块不创建线程、不写 `/system`、不卸载自己。
- 模块回调里不要做长时间阻塞操作；hook 在目标线程同步执行。

## 4. 生命周期

1. `nhinject <pid>` 加载 `liblsplant.so` 和 `libnhpayload.so`。
2. 运行时读取 `/proc/self/cmdline` 得到进程名。
3. 扫描 `modules/*.so`：
   - `dlopen(path, RTLD_NOW | RTLD_LOCAL)`
   - 找 `nhook_module_scope` / `nhook_module_init` / `nhook_module_name`
   - `scope(process) != 1` → 跳过
   - `init(env, &api)` → 返回值作为 hook 数
4. 写状态文件，例如：
   ```
   ok=1
   pid=1028
   process=system_server
   modules=uninstall_guard
   hooks=5
   ```
5. 回调统一进运行时 `HookerCallback`，再分发到模块函数。

## 5. hook API

### hook_method

```c
int hook_method(JNIEnv* env, const char* cls, const char* method,
                const char* signature, nhook_callback cb, void* user);
```

- `cls` 用 JNI 斜杠形式：`com/vecentek/security2/WhiteListHelper`
- `signature` 用 JNI 描述符：`(Landroid/content/Context;Ljava/lang/String;)Z`
- 成功返回 0，失败返回 -1。

### hook_all

按方法名匹配所有重载，适合 `checkWhiteList` / `isCheckAuth` 这类多重重载。

```c
int hook_all(JNIEnv* env, const char* cls, const char* method,
             nhook_callback cb, void* user);
```

返回实际 hook 的数量。

### call_backup

在回调里调用原方法：

```c
jobject call_backup(JNIEnv* env, jobject backup, jobjectArray args);
```

`args` 是运行时传进来的原始参数数组（实例方法 `args[0]` 是 this）。

### config / log

- `config("uninstall")` 读取全局配置
  `/data/system/uninstall-hook-native.cfg`。
- `log("...")` 输出到 logcat tag `NativeHook`，前缀是模块名。

## 6. 模块示例

```cpp
#include <jni.h>
#include <string.h>
#include "nhook_module.h"

static const nhook_api* g_api;

static jobject bool_value(JNIEnv* env, bool value) {
    jclass cls = env->FindClass("java/lang/Boolean");
    jfieldID id = env->GetStaticFieldID(cls, value ? "TRUE" : "FALSE",
                                        "Ljava/lang/Boolean;");
    return env->GetStaticObjectField(cls, id);
}

static jobject on_check(JNIEnv* env, jobjectArray args, jobject backup, void*) {
    g_api->log("check called");
    return bool_value(env, true);
}

extern "C" int nhook_module_scope(const char* process) {
    return strcmp(process, "system_server") == 0 ? 1 : 0;
}

extern "C" int nhook_module_init(JNIEnv* env, const nhook_api* api) {
    g_api = api;
    if (api->hook_all(env, "com/vecentek/security2/WhiteListHelper",
                      "checkWhiteList", on_check, nullptr) < 0) {
        return -1;
    }
    return 1;
}

extern "C" const char* nhook_module_name(void) {
    return "example";
}
```

## 7. 构建模块

模块的编译参数由 `payload/build.sh` 统一给出（`-nostdlib++`、只依赖 libc）。
新增模块时在 `payload/build.sh` 的模块循环里加一个名字即可：

```bash
for m in uninstall_guard statusbar_touch hide_presentation iccoa_fullscreen example; do
  ...
done
```

手工单独编译一个模块：

```bash
export ANDROID_NDK=/path/to/android-ndk-r26d
NDK="$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$NDK/aarch64-linux-android28-clang++" -std=c++17 -fPIC -shared -O2 \
  -fvisibility=hidden -nostdlib++ -I ../../include \
  -o ../../build/payload/modules/example.so example.cpp
```

模块产物放到 `build/modules/`，部署到
`/data/local/tmp/hook-native/modules/`。

## 8. 作用域约定

`nhook_module_scope` 只按进程名判断，不按包名扫描。常用进程：

| 进程 | 说明 |
|---|---|
| `system_server` | PMS、窗口、权限、安装/卸载门 |
| `com.android.systemui` | 状态栏、导航栏、通知 |
| `com.vecentek.decoreapp` | 为辰安全强卸 |
| 其他应用包名 | 应用自身逻辑 |

如果一个模块要作用于多个进程，在 `scope` 里返回 1，并在 `init` 里按
`api->process_name()` 分别注册不同的 hook。

## 9. 开发与测试流程

1. 用 Frida 脚本验证 hook 点（只用于开发，不打包进 APK）。
2. 按模板写模块，编译成 `.so`。
3. 部署到 `modules/`，重启目标进程。
4. `nhinject <pid>`，看 logcat：
   ```
   NativeHook: [module] uninstall_guard loaded (5 hooks)
   NativeHook: [module] statusbar_passthrough skipped (scope)
   ```
5. 功能验证。
6. 模块稳定后随 APK 的 assets 一起发布。

## 10. 迁移 statusbar-probe

`statusbar_probe` 的 Frida 脚本用到：

- `Java.use('android.view.WindowManager$LayoutParams')`
- 字段读写：`lp.flags.value = lp.flags.value | FLAG_NOT_TOUCHABLE`
- `Java.choose('android.view.ViewRootImpl')` 遍历实例
- hook `WindowManagerGlobal.addView`
- `setInterval` 定时 sweep

迁移到模块时：

1. `hook_all` 先 hook `addView`，在回调里改 `LayoutParams.flags`。
2. 遍历实例需要运行时后续增加 `find_instances` API（v2）；第一版可以先只做
   `addView` hook，覆盖新窗口。
3. 定时 sweep 用模块自己的 pthread + 定时调用 JNI，注意 attach/detach。

这部分预计 150–250 行 C++，是模块标准的第一个真实示例。

## 11. 版本策略

- `NHOOK_API_VERSION` 不兼容变更时递增。
- 模块 `init` 里检查 `api->version`，不匹配直接返回 -1。
- 运行时和模块分开升级：换模块不需要重编运行时，换运行时需要模块重新编译。
- 旧版 `libnhpayload.so`（硬编码三合一）保留为 fallback，不删。

<｜DSML｜ calls>
<｜DSML｜ invoke name="Write">
<｜DSML｜ parameter name="content" string="true">#pragma once

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