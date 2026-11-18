# NativeHook 运行时 + 模块

`libnhpayload.so` 是固定运行时：负责 LSPlant 初始化、Java hook 回调分发、
按进程名加载模块。所有功能都在 `modules/*.so` 里，新增功能不再改运行时。

标准与 ABI 见 [`docs/MODULE-STANDARD.md`](../docs/MODULE-STANDARD.md)。

## 组成

| 文件 | 作用 | 大小 |
|---|---|---:|
| `libnhpayload.so` | 运行时：LSPlant + Hooker 分发 + 模块加载 | ~406 KB |
| `liblsplant.so` | LSPlant 6.4 预编译库 | ~103 KB |
| `modules/uninstall_guard.so` | 安装门 + 卸载门 + 为辰强卸拦截 | ~11 KB |

运行时依赖 `liblsplant.so` 的 soname，必须先加载 `liblsplant.so`。
两者都不依赖 `libc++_shared.so`；模块只依赖 libc/libdl/libm。

## 目录

```
/data/local/tmp/hook-native/
├── nhinject
├── liblsplant.so
├── libnhpayload.so
└── modules/
    └── uninstall_guard.so
```

## 构建

```bash
./fetch-deps.sh
bash payload/build.sh          # 缺 hooker_dex.h 时会自动调 hooker/build-dex.sh
```

产物：

- `build/payload/libnhpayload.so`
- `build/payload/liblsplant.so`
- `build/payload/modules/*.so`（4 个模块）

## 模块生命周期

1. `nhinject <pid>` 加载 `liblsplant.so` 和 `libnhpayload.so`。
2. 运行时读 `/proc/self/cmdline` 得到进程名。
3. 扫描 `modules/*.so`，`dlopen` 后找：
   - `nhook_module_scope(process)`：返回 1 才在当前进程启用
   - `nhook_module_init(env, api)`：注册 hook，返回 hook 数
   - `nhook_module_name()`：模块名
4. 回调统一进运行时 `HookerCallback`，再分发到模块的 `nhook_callback`。
5. 状态写入：
   ```
   ok=1
   pid=1028
   process=system_server
   modules=uninstall_guard
   hooks=5
   ```

## 模块示例：uninstall_guard

> 另外三个模块（`statusbar_touch` / `hide_presentation` / `iccoa_fullscreen`）
> 结构完全一样，只有 hook 目标不同。原理见 [../docs/原理.md](../docs/原理.md)，
> 新模块规范见 [../docs/MODULE-STANDARD.md](../docs/MODULE-STANDARD.md)。

作用进程：`system_server`、`com.vecentek.decoreapp`。

| 目标 | 行为 |
|---|---|
| `WhiteListHelper.checkWhiteList(...)` 全部重载 | 非 deny 返回 true |
| `CertificateManager.isCheckAuth(...)` 全部重载 | 返回 true |
| `com.vecentek.decoreapp.c.a.b(String,int)` type 1/4 | 返回 true，其他调用 backup |

配置：`/data/system/uninstall-hook-native.cfg`

```
uninstall=1
install=1
deny=com.android.settings,...
sec_target=*
```

## 部署与测试

```sh
# 上传
/data/local/tmp/hook-native/nhinject
/data/local/tmp/hook-native/liblsplant.so
/data/local/tmp/hook-native/libnhpayload.so
/data/local/tmp/hook-native/modules/uninstall_guard.so

chmod 755 /data/local/tmp/hook-native/nhinject
chmod 644 /data/local/tmp/hook-native/liblsplant.so \
          /data/local/tmp/hook-native/libnhpayload.so \
          /data/local/tmp/hook-native/modules/uninstall_guard.so

# 注入
/data/local/tmp/hook-native/nhinject $(pidof system_server)
/data/local/tmp/hook-native/nhinject $(pidof com.vecentek.decoreapp)

logcat -d -s NHookNative | tail -60
cat /data/system/nativehook-system_server.status
cat /data/system/nativehook-com.vecentek.decoreapp.status
```

预期日志：

```
NHookNative: runtime in process system_server
NHookNative: [module] uninstall_guard loaded (5 hooks)
NHookNative: runtime ready pid=1028 hooks=5
```

## 新增功能

1. 新建 `payload/modules/<name>/module.cpp`。
2. 实现 `nhook_module_scope` / `nhook_module_init` / `nhook_module_name`。
3. 在模块里调用 `api->hook_method` / `api->hook_all`。
4. `bash payload/build.sh` 生成 `.so`。
5. 放到 `/data/local/tmp/hook-native/modules/`，重启目标进程后重新注入。
6. 稳定后加入 APK 的 `assets/native/modules/`。

不要改 `libnhpayload.so`；模块通过 `nhook_api` 使用运行时能力。

## 已知限制

- 同一个进程重复注入不会更新 payload/模块：dlopen 同路径不重跑 constructor，
  改代码后必须重启目标进程。
- 模块没有热卸载；重启目标进程即全部恢复。
- 逸达的 system_server 没有 `com.vecentek.security2.*`，模块会尝试
  `com.vecentek.security.*`；都没有时 hooks=0。
- `find_instances` / 字段读写 / 定时任务还未进 ABI（v2 计划），
  statusbar-probe 迁移需要这些能力。
- 当前仍用 Frida 做开发期 hook 验证；部署形态用 native 运行时。