# NativeHook

用 **LSPlant + Dobby** 在车机关键进程里做 Java 方法 hook：用一个约 **407 KB** 的 native payload
替掉原来的 `frida-inject`（51 MB），首次部署从十几秒降到 1 秒内。

当前版本 **1.35**（native payload **1.29**，两者独立演进，见 [BUILD.md](docs/BUILD.md#6-改版本号)），
在长安启源 A05（CD569，Android 9 / arm64 / MT8666）实车验证。

> **这是一个针对特定车型、特定固件的专用工具，不是一个通用 hook 框架。**
> 它成立依赖三个同时满足的前提，换车或一次 OTA 就可能失效，详见[适配范围](#适配范围)。

---

## ⚠️ 授权研究声明

这个仓库是**个人对自己拥有的车辆做的安全研究**，公开目的是：给同样做车机研究的开发者一份可读的
工程实现参考，并推动厂商修复下文提到的厂商通道缺陷。

**它不适合、也不授权用于任何你不拥有的设备。** 具体来说：

- 本项目的部署链路依赖一条**厂商私有提权通道**（见 [`docs/原理.md`](docs/原理.md) 第 5 节）。
  这是该车机厂商实现的缺陷，不是本项目的发明。我们保留它是为了让工程链路完整可读；
  对他人车辆使用它属于未授权访问。
- 在他人车辆上注入 `system_server` 可能造成系统不稳定、车辆功能异常、以及**对车辆控制器的
  间接影响**。本项目**没有**在底盘、动力、制动相关的任何进程上做过注入，也**不应**这么做。
- 刷写或改装车机通常会导致**质保失效**，并可能违反车辆的用户协议与当地法规。
- 本项目**不包含**签名证书。用它重新签名 APK 覆盖安装原厂应用是另一条独立的技术路线，
  本仓库不提供，也不建议。

如果你在这台车机上发现了安全相关问题，建议同步告知厂商，而不是只发在仓库里。

---

## 现在能做什么

| 目标进程 | 模块 | 作用 |
|---|---|---|
| `system_server` | `uninstall_guard` | 白名单 / 卸载拦截 / 安装校验 |
| `com.vecentek.decoreapp` | `uninstall_guard` | 同上（安全服务） |
| `com.android.systemui` | `statusbar_touch` | 状态栏触摸与显示控制 |
| `com.iflytek.autofly.voicecoreservice` | `hide_presentation` | 隐藏副屏 Presentation 窗口 |
| `com.wt.phonelink` | `iccoa_fullscreen` | ICCOA 投屏全屏（1080×1440 送流 + dpi 320） |
| `evsserver`（独立进程） | 非模块，走 GOT 覆盖 | 后摄循环录像（见下文「两条技术路线」） |

工程侧做到的事：

- **三层保活**：空壳无障碍服务（系统长期持有，被杀/覆盖安装自动重连）+ root 侧 `nhkeepd`
  + App 内 `KeepAliveService`；
- **双层巡检**：root 侧常驻 `nhkeepd`（15 秒一轮，看 `/proc/<pid>/maps` 决断）
  + App 内 `KeepAliveService`（检测到 `nhkeepd` 心跳就自动让位，避免重复注入）。
  `nhkeepd` 用 `setsid` 拉起，**App 被 force-stop 也不影响注入**；
- **开机常驻**：`BootReceiver` → `KeepAliveService`；开机后约 **13 秒**完成 5 个进程注入；
- **进程重启自愈**：`com.wt.phonelink` 闪退重开后，下一轮巡检自动补注入；
- **重复注入保护**：`nhinject` 发现目标进程已映射 `libnhpayload.so` 就直接跳过
  —— 重复 `dlopen` 会让 LSPlant 二次 `Init` 失败，把 `system_server` 打软重启。

## 两条技术路线

不是所有进程都有 ART：

| 进程 | 有无 JavaVM | 用的 hook |
|---|---|---|
| `system_server` / SystemUI / 语音服务 / 手机互联 | 有 | **ArtMethod 入口替换**（LSPlant → Dobby inline hook） |
| `evsserver`（后摄录像） | 无，纯 native | **GOT 覆盖**：改 `R_AARCH64_JUMP_SLOT` 里的函数地址 |

`evsserver` 那条路不在本仓库：它跑在纯 native 进程里，用不上 LSPlant/Dobby 这套依赖，
代码在独立的 `evs-dvr` 项目。本仓库只在配置里留了 `evs_dvr` 开关和拉起逻辑。

## 为什么是 407 KB

Frida 是通用工具：自带 JS 引擎、脚本运行时、跨平台适配层。它能做很多事，
你为这些「能做很多事」付了体积和启动时间。

这台车上最初要解决的只是五个方法。不需要通用能力，只需要一个足够小、足够快、
只做这几件事的专用工具。**通用换专用，51 MB 变 407 KB。**

但真正难的从来不是那 407 KB。难的是：怎么在正确的时机进到正确的进程；
怎么找到那个入口并且不把原逻辑弄坏；怎么在它掉线之后一次又一次把它接回来。

原理细节见 **[docs/原理.md](docs/原理.md)**（约 5,000 字，从「什么是 Hook」一路讲到「代价与边界」，
引用的代码位置都指向本仓库真实文件）。

## 组成

```
apk/       控制台 App（com.changan.nativehook），负责部署、启动、状态展示
payload/   libnhpayload.so（LSPlant 运行时 + hook 调度）+ modules/*.so
injector/  nhinject，ptrace 注入器，把 payload dlopen 进目标进程
daemon/    nhkeepd，root 侧常驻巡检
```

```
native-hook/
├── apk/                  # 控制台 App（Java，手写构建脚本，不用 Gradle）
│   ├── scripts/          # start.sh / status.sh / ensure_services.sh
│   ├── res/              # 图标、无障碍服务配置
│   └── src/com/changan/nativehook/
├── payload/
│   ├── src/main.cpp      # 运行时入口、LSPlant 初始化、hook 调度、状态文件
│   ├── include/          # 模块 ABI（nhook_module.h）
│   ├── modules/          # 各业务模块
│   └── hooker/           # 注入用的 Hooker DEX（编译期内嵌进 payload）
├── injector/nhinject.c   # ptrace 注入器
├── daemon/nhkeepd.cpp    # root 侧巡检守护进程
├── poc/                  # 最小验证：只加载 .so，不做 hook
├── reference/            # 早期 Frida 版脚本，留作对照
├── docs/
│   ├── 原理.md            # **先看这个**：完整原理链路
│   ├── MODULE-STANDARD.md # 模块开发规范（写新模块必读）
│   ├── BUILD.md           # 构建环境与外部依赖
│   ├── CHANGELOG.md       # 版本变更
│   └── DESIGN-NOTES.md    # 立项时的技术选型记录
└── fetch-deps.sh         # 拉取 LSPlant / Dobby 预编译依赖
```

通道（root shell 与文件管理页由外部 `root-term` 项目提供，不在本仓库）：

| 端口 | 进程 | 用途 |
|---|---|---|
| 4455 | `rt_shell` | root shell，JSON 行协议，部署/启动/取状态 |
| 4466 | `rt_httpd` | 文件管理页 + `/api/exec`、`/dl`、`/ul` |

## 构建

完整环境要求和外部依赖见 **[docs/BUILD.md](docs/BUILD.md)**。最短路径：

```bash
# 0) 依赖（LSPlant 6.4 + Dobby 1.2 预编译库，不入库）
./fetch-deps.sh

# 1) payload + 四个模块
export ANDROID_NDK=/path/to/android-ndk-r26d
bash payload/build.sh

# 2) 注入器 + 守护进程
bash injector/build.sh
bash daemon/build.sh

# 3) 打 APK（会自动取 ../build 下的产物）
bash apk/build.sh
```

产物：`build/native-hook-1.35-car-signed.apk`；没有证书时输出 `*-unsigned.apk`。

> 本仓库**不包含**任何预编译二进制：`.so` / 可执行文件 / APK 都在 `.gitignore` 里，
> 需要自己构建。

## 部署

部署需要目标机上已有 root。本仓库的 App 用厂商 DM01 通道提权；如果你走 Magisk/其他 root 方案，
只要保证能执行下文命令即可，`nhinject` 本身不挑 root 来源。

```bash
# 传 APK（adbd 有密码校验时 adb install 会失败，走 root shell）
pm install -r /data/local/tmp/native-hook-1.35-car-signed.apk
appops set com.changan.nativehook SYSTEM_ALERT_WINDOW allow   # 浮层提示权限

# 打开 App 点「开启 Hook」，或直接
sh /data/local/tmp/hook-native/start.sh
```

验证：

```bash
cat /data/local/tmp/hook-native/.version          # 1.29-native29（native 版本，与 APK 版本无关）
cat /data/system/nativehook-system_server.status  # ok=1 / pid=... / hooks=5
logcat -s NHookNative                             # runtime ready / [module] ...
```

## 配置

配置文件：`/data/system/uninstall-hook-native.cfg`（`system:system 600`），
App 每次启动/开机同步一次，手工改过的值会被覆盖。

> 前 6 个开关由界面「功能开关」控制，存 SharedPreferences，
> 每次同步时按用户选择重新生成，不再硬编码。

| 键 | 默认 | 界面开关 | 说明 |
|---|---|---|---|
| `inject` | 5 个进程 | — | 注入目标列表，逗号分隔 |
| `install` | 1 | 允许安装应用 | 绕过白名单校验 |
| `uninstall` | 1 | 允许卸载应用 | 绕过白名单校验 |
| `deny` | 一批系统包 | — | 拦截名单 |
| `sec_target` | `*` | — | 安全模块作用域 |
| `statusbar` | 0 | 状态栏触摸 | 状态栏下拉区可触摸 |
| `presentation` | 1 | 状态栏透明 | 消掉顶部 110px 黑带 |
| `iccoa` | 1 | ICCOA 全屏 | 手机互联全屏 |
| `evs_dvr` | 0 | 后摄记录仪 | 1 时由 `ensure_services.sh` 拉起录制 |
| `touch_panel` | 0 | — | 触摸面板开关 |
| `iccoa_mode` | `adapt` | — | `adapt` / `stretch` / `nochange` |
| `iccoa_video` | `1080x1440` | — | 请求手机送流的尺寸 |
| `iccoa_surface` | `1440x1920` | — | 车机 Surface 尺寸 |
| `iccoa_dpi` | `320` | — | 覆盖 `mDisplayDensityDpi`，0 = 保留原值 |

### 开关生效时机

- **后摄记录仪**：改完点「应用开关」立即生效（`ensure_services.sh` 直接按配置启停进程）。
- **其余 Java 模块开关**（install / uninstall / statusbar / presentation / iccoa）：
  **必须重启车机**。模块在 `init()` 时读配置，而 `start.sh` 会跳过已注入的进程
  （重复 `dlopen` 会让 LSPlant 二次 Init 失败，把状态写成 `ok=0`，触发每 15 秒反复重注入）。
  所以只能等进程或车机重启后重新注入才用上新配置。

### ICCOA 尺寸与 dpi

`dpi` 决定 UI 的物理大小，改了 `iccoa_video` 就要同步改，否则界面会整体放大/缩小：

```
dpi ≈ 320 × video_w / 1080
```

**默认 1080×1440 送流 + surface 1440×1920 + dpi 320**：视频流是面板的 3/4
（同为 4:3），放大回面板是 1.33 倍整数比缩放，不变形也不留边。手机侧编码和车机侧
解码（`MtkOmxVenc`）的像素数从 276 万降到 156 万（−44%），是「手机和车机都卡」时
性价比最高的一刀。surface 保持与面板 1:1，本地不再多做一次缩放。

| 视频尺寸 | dpi | 逻辑宽度 | 效果 |
|---|---|---|---|
| **1080×1440（默认）** | **320** | 540dp | 面板的 3/4，1.33× 放大，负载最低 |
| 1200×1600 | 356 | 540dp | 折中，像素 +23% |
| 1440×1920 | 427 | 540dp | 1:1 直通，最清晰，解码约 1.8 核 |

`setDpi` 允许 120~640。1440×1920 在 MT8666 上解码约 1.8 核、整机 busy 58%，
默认降到 1080×1440 就是为了把这块余量留出来；反过来，如果你能接受更高负载、
只追求清晰度，可以自己调回 1440×1920 + dpi 427。

## 运行时文件

| 路径 | 说明 |
|---|---|
| `/data/local/tmp/hook-native/` | payload、模块、脚本、`nhinject` |
| `/data/local/tmp/hook-native/.version` | 已部署版本，和 `NativeHook.VERSION` 不一致就重部署 |
| `/data/system/nativehook-<pkg>.status` | 每个进程的注入结果，常驻巡检读它 |
| `/data/local/tmp/hook-native/nhkeepd.hb` | 守护进程心跳，15 秒更新一次 |
| `/data/local/tmp/hook-native/nhkeepd.log` | 守护进程日志（512 KB 滚动） |
| `/data/data/com.changan.nativehook/files/logs/keepalive-*.log` | App 侧巡检日志 |

## 排障

| 现象 | 原因 | 状态 |
|---|---|---|
| 进 ICCOA 必闪退，`dpi must be between 120 and 640` | `CallBackup` 用 `Modifier.isStatic(backup.getModifiers())` 判断，实例方法的原方法被整体跳过，`initDisplayMetrics` 没执行 | 1.20 修复 |
| 下拉状态栏 SystemUI 闪退 | 同上，`WindowManagerGlobal.addView` 没执行 | 1.20 修复 |
| 连点两次「开启 Hook」车机软重启 | 重复 dlopen payload，LSPlant 二次 `Init` 失败 | 1.21 修复 |
| 开机后一分多钟才注入 | `ensureServices()` 等 DM 回调，开机早期要 60s 超时 | 1.25 修复 |
| 注入成功却弹「注入失败」 | status 文件被别的 uid 以 0600 建过，进程写不进去 | 1.25 修复 |
| 用一段时间后 hook 全丢、App 也不在了 | App 被 force-stop（`stopped=true`），进程内巡检线程跟着消失 | 1.26 起由 `nhkeepd` 兜底 |

抓日志：

```bash
logcat -d -v time -s NHookNative | tail -50
for f in /data/system/nativehook-*.status; do echo "--$f"; cat $f; done
```

## 已知问题

1. `statusbar_touch` / `hide_presentation` 用 `GetStaticFieldID` 找
   `WindowManagerGlobal.mViews`，但它是**实例字段**，导致每 3 秒刷 `mViews missing`，
   sweep 逻辑实际没生效。
2. 开机阶段 DM 通道会在 60 秒后打一条 `services ensure callback fail`，仅日志噪音。
3. `com.android.inputmethod.latin` 开机崩溃（`not in white list`）是原厂问题，与本项目无关。

## 适配范围

只在下面这套组合上验证过。**不是通用方案**，三个前提缺一不可：

| 前提 | 本机情况 | 怎么自查 |
|---|---|---|
| 链接器允许从 `/data` 加载 | `namespace.default.permitted.paths += /data` | `grep permitted.paths /system/etc/ld.config.*.txt` |
| SELinux 为 Permissive | `getenforce` → `Permissive` | `getenforce` |
| `libart.so` 符号表完整 | 约 12,171 个导出符号 | `nm -D /system/lib64/libart.so \| wc -l` |

| 项 | 值 |
|---|---|
| 车型 | 长安启源 A05（CD569） |
| 系统 | Android 9 / arm64 / MT8666 |
| 提权 | 厂商 DM01 通道（root + SELinux Permissive） |

`ArtMethod` 的内存布局每个 Android 版本都可能不同。LSPlant 官方声明支持 Android 5–17
（上游说法，非本车实测），但**每个 ROM 仍然要实测**。换车、换版本、甚至一次 OTA，
都可能让这套东西失效。

## 致谢与许可

本仓库自有代码用 **MIT** 许可，见 [LICENSE](LICENSE)。

第三方组件不随仓库分发，由 `fetch-deps.sh` 拉取，许可证如下：

| 组件 | 用途 | 许可证 |
|---|---|---|
| [LSPlant](https://github.com/LSPosed/LSPlant) | ART Java 方法 hook | LGPL-3.0 |
| [Dobby](https://github.com/jmpews/Dobby) | inline hook 框架 | Apache-2.0 |

完整说明见 [THIRD-PARTY.md](THIRD-PARTY.md)。

> **LGPL 注意**：本项目把 LSPlant 作为**动态库**（`liblsplant.so`）链接，
> 未修改其源码，符合 LGPL-3.0 的动态链接要求。如果你要**分发**构建产物，
> 需要一并提供 LSPlant 的源码获取方式，并保留其许可证声明。
