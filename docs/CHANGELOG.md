# 变更记录

只记录对车机行为有影响的改动。版本号对应 `apk/AndroidManifest.xml` 的 `versionName`，
`NativeHook.VERSION` 同步维护（形如 `1.25-native25`）。

## 1.28 — 2026-11-06

**主题：学「启源助手」用无障碍服务兜底保活**

### 新增

- `KeepAliveAccessibilityService`：空壳无障碍服务，`capabilities=0`、不读窗口内容、
  不接管手势，唯一作用是让系统长期 bind 本进程。`onServiceConnected` 里拉起
  `KeepAliveService`，接通常驻链路。
- 部署/服务启动时用 root 写入：
  `settings put secure enabled_accessibility_services ...:com.changan.nativehook/.KeepAliveAccessibilityService`
  以及 `accessibility_enabled 1`（追加，不覆盖车机原有的一堆服务）。
- `apk/res/`：新增资源目录，`build.sh` 增加 `aapt2 compile` 步骤。

### 修复

- `AndroidManifest.xml` 的 `BootReceiver` 只注册了 `BOOT_COMPLETED`，
  没注册 `MY_PACKAGE_REPLACED` —— 代码里虽然处理了覆盖安装，但广播根本送不到，
  所以之前 `pm install -r` 后 App 不会自己起。现已补上。

### 实测

```
安装 1.28 后             app 自动启动（未手动点）
dumpsys accessibility   Service[label=NativeHook, capabilities=0]
kill -9 应用进程          3 秒后系统重新拉起（18888 -> 19105）
KeepAliveService        随无障碍服务重连恢复前台
```

> 仍然失效的场景：包被 `force-stop`（stopped=true）时，无障碍也绑不上。

## 1.27 — 2026-11-05

**主题：守护进程在真实开机/杀进程下跑通**

### 修复

- **心跳文件读不到**：`fopen` 受 root 的 umask 影响建成 `0600`，App 读不了，
  导致「守护进程活着时 App 让位」的判断永远为 false。改为 `open(..., 0644)` + `fchmod`。
- **App 侧存活判断改走 4455**：不再 `File.lastModified()`，改为
  `cat /data/local/tmp/hook-native/nhkeepd.hb` 后解析时间戳（和读 status 同一条通道），
  不受文件权限与 SELinux 影响。
- **`flock` 残留锁导致起不来**：`kill -9` 后新进程 `flock` 失败直接退出。
  判重改为扫 `/proc/*/comm` 是否已有 `nhkeepd`，不再依赖锁文件。

### 实测（20:31 重启）

```
20:31:37  boot_completed
20:31:47  nhkeepd started (pid 4748)      # 开机 10 秒，由 ensure_services.sh 拉起
20:31:47  system_server / decoreapp / systemui / 语音 / phonelink 依次 inject ok
20:32:03  App 巡检 check ok（5/5）
```

- App 日志出现让位记录：`bad ... -> nhkeepd handles`，两边没有重复注入；
- 从开机完成到 5 个进程全部注入 **11 秒**；
- `kill -9` SystemUI / phonelink（三次）均在下一轮（≤15s）自动补注入；
- `kill -9 rt_httpd` 后 15 秒内被 `ensure_services.sh` 拉回；
- 守护进程占用：CPU 0%、RSS 4.2 MB、单线程。

## 1.26 — 2026-11-05

**主题：把巡检从 App 里搬出来**

### 新增

- `daemon/nhkeepd`：root 侧常驻巡检进程（13 KB / arm64），由 `ensure_services.sh`
  用 `setsid` 拉起，和 `rt_httpd` 一样不随 App 退出。
  - 每 15 秒扫 `/proc/*/cmdline` 找 `inject=` 里的进程；
  - 用 `/proc/<pid>/maps` 里有没有 `libnhpayload.so` 判定，**不依赖 status 文件**
    （status 可能是上一代进程留下的，pid 对不上照样是 `ok=1`）；
  - 缺 payload 就 `fork` + `exec nhinject`，8 秒超时强杀，避免被卡死；
  - 4455/4466 掉线时自动重跑 `ensure_services.sh`（30 秒限流）；
  - 心跳 `nhkeepd.hb`、日志 `nhkeepd.log`（512 KB 滚动）、`flock` 保证单实例。

### 变更

- `KeepAliveService`：`nhkeepd.hb` 60 秒内有更新就把注入让给守护进程，
  自己只保留状态展示和开机浮层，避免两边同时往同一进程 dlopen。
- 部署链路（`NativeHook.deployAll` 与打包脚本）带上 `nhkeepd`，含大小校验
  `size_keepd=ok`。

### 背景与实测

1.25 的巡检线程跑在 App 进程里。2026-11-05 17:02 App 被 force-stop
（`stopped=true`）后线程消失，19:14 `com.wt.phonelink` 重启没人补注入，
ICCOA 回落到原厂 1080×1230，表现为“分辨率不对”。

实测：`kill -9` SystemUI（pid 1658）→ 系统自动拉起 28887 →
守护进程日志 `missing payload -> inject` / `injected ok`，
`maps` 4 条映射、status 自动更新为 `pid=28887`。

## 1.25 — 2026-11-05

**主题：开机常驻注入的可靠性**

### 修复

- **开机注入被拖慢 67 秒**：`KeepAliveService.ensureServices()` 用
  `CountDownLatch.await(150s)` 等 DM01 回调。开机早期 DMService 还没就绪，回调要拖到
  `DmClient` 的 60 秒超时才返回，但脚本其实 4 秒就跑完了（`rt_shell` 已起来）。
  改为「发完命令轮询 4455/4466，最多 20 秒」，DM 回调只记日志。
  实测注入完成时间从 **74 秒降到 13 秒**。
- **状态文件写不进去导致误判**：`/data/system/nativehook-<pkg>.status` 若被别的 uid
  以 `0600` 创建过（例如用 root 手工写过），以 `system` 运行的进程 `open(O_WRONLY)`
  会 `EACCES`，状态永远停在旧 pid，常驻巡检判 `bad(stale)` 并反复重注入。
  `main.cpp` 的 `WriteStatus()` 和 iccoa 模块的 `WriteStatusFile()` 现在遇到
  `EACCES/EPERM` 会先 `unlink` 再重建（`/data/system` 属主是 system，进程有目录写权限）。
- **误报「注入失败」浮层**：注入完成后立刻读状态，status 文件可能还没落盘。
  现在先等 1.2 秒，仍异常再等 1.5 秒复读一次；上一轮弹过的失败卡片在后续成功时自动撤掉。

### 变更

- `bootOverlayFailed` 标记用于识别误报卡片。

## 1.24 — 2026-11-05

- 开机提示浮层点击**只关闭，不打开 App**（原来是点击进入 NativeHook 主界面）。

## 1.23 — 2026-11-05

- 开机提示从 Toast 改为 **TYPE_APPLICATION_OVERLAY 浮层**：这车机是自定义 UI，
  `cmd statusbar expand-notifications` 都拉不出系统通知栏，纯 Notification 用户看不到。
- 浮层常驻不自动消失，点击才移除；失败文案单独一套。
- `AndroidManifest.xml` 增加 `SYSTEM_ALERT_WINDOW`，`NativeHook.start` /
  `ensureAndSyncCommand` 用 root 执行 `appops set ... allow` 自动授权。
- 修复开机第一轮 4455 未就绪就弹「注入失败」的误报：保留 `boot_toast_pending`
  到真正出结果的那一轮。

## 1.22 — 2026-11-05

- 开机提示 Toast → 常驻通知（Toast 一闪而过，没看到就错过）。
  （1.23 起被浮层取代，Notification 仅作为没有浮层权限时的兜底。）

## 1.21 — 2026-11-05

- **修复连点「开启 Hook」把 `system_server` 打软重启**：
  部署会用新 inode 替换 `libnhpayload.so`，旧 `nhinject` 仍对同一路径 `dlopen`，
  linker 认为是新库，于是又加载一份 payload，LSPlant 二次 `Init` 失败。
  `injector/nhinject.c` 新增 `is_module_mapped()`：`/proc/<pid>/maps` 里已有
  `liblsplant.so` / `libnhpayload.so` 就直接 `[skip]`（文件被替换后显示 `(deleted)`
  也能按 basename 匹配）。
- `start.sh` 与注入器形成双保险：`ok=1` 且 pid 匹配的进程直接 SKIP。

## 1.20 — 2026-11-05

**主题：修复实例方法 backup 被跳过（影响所有非静态 hook）**

- **`CallBackup()` 重写**：LSPlant 的 backup 不是原方法本身，调用约定也随目标方法变化。
  旧实现用 `Modifier.isStatic(backup.getModifiers())` 判断，并从参数里剥掉 `this`，
  导致**所有非静态方法的原方法都没执行**：
  - `CarLinkMainActivity.initDisplayMetrics()` 不执行 → `mDisplayDensityDpi=0` →
    `UCarConfig.setDpi` 抛 `dpi must be between 120 and 640` → 进 ICCOA 必闪退；
  - `WindowManagerGlobal.addView()` 不执行 → 随后 `removeView` 抛
    `View not attached to window manager` → SystemUI 闪退。
  现在按**目标方法**记录静态标记：静态 `invoke(null, args)`，
  实例 `invoke(args[0], args[1..])`；异常打印后 clear，避免 pending JNI exception
  触发 ART abort。
- `iccoa_fullscreen` 模块：反射字段补 `Field.setAccessible(true)`
  （`mVideoDisplayWidth` 等都是 private，原来读写全失败，日志一直是 `0x0/0x0`）。
- 新增 `iccoa_dpi` 配置，默认 `iccoa_video=1440x1920`、`iccoa_surface=1440x1920`、
  `iccoa_dpi=427`，手机送流与车机面板 1:1，不再放大插值。
- `apk/build.sh` / `payload/build.sh` 支持 `ANDROID_SDK`、`JDK_ROOT`、`KEYSTORE`、
  `KS_PASS`、`KS_ALIAS`、`ANDROID_NDK` 环境变量，不再写死 `F:\env\tools\...`。
- `apk/scripts/start.sh`：已注入且 pid 匹配的进程直接 SKIP，避免状态异常时每 15 秒
  重复 dlopen。

## 1.19 及以前

- Frida 引擎替换为 LSPlant + Dobby + 自写 ptrace 注入器的基线版本。
- 5 进程注入、`uninstall_guard` / `statusbar_touch` / `hide_presentation` 模块、
  4455/4466 通道、DM01 部署链路均已可用。
