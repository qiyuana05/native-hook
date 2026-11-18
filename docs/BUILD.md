# 构建

本仓库**只包含源码**。所有二进制（`.so`、可执行文件、APK）都在 `.gitignore` 里，
需要本地构建。

## 1. 环境要求

| 项 | 版本 | 用途 | 环境变量 |
|---|---|---|---|
| Android NDK | **r26d+**（arm64 clang） | 编 payload / 模块 / 注入器 / 守护进程 | `ANDROID_NDK` |
| JDK | **17** | `javac` 编 App，`d8` 编 dex | `JDK_ROOT`（或 `JAVA_HOME`） |
| Android SDK | build-tools **35** + platform **35** | `aapt2` / `d8` / `zipalign` / `apksigner` | `ANDROID_SDK` |
| Python | 3.x | 把 `classes.dex` 合进 APK、生成 `hooker_dex.h` | — |
| bash | 4+ | 构建脚本 | — |

支持 **Linux / macOS / Windows(MSYS2 或 Git Bash)**。脚本会自动探测 NDK 的 host tag
（`linux-x86_64` / `darwin-x86_64` / `windows-x86_64`）和 Windows 下工具的 `.exe`/`.bat` 后缀。

> 早期版本把 NDK 路径写死成个人机器上的 `F:\env\tools\android-ndk`，
> 现在全部改成环境变量，没有默认值假设。

## 2. 拉取依赖

```bash
./fetch-deps.sh
```

它会下载到 `third_party/`（已 gitignore，不入库）：

| 内容 | 来源 | 许可证 |
|---|---|---|
| `lsplant-aar/lsplant-standalone-6.4.aar` | Maven Central | LGPL-3.0 |
| `dobby-aar/dobby-1.2.aar` | Maven Central（`io.github.vvb2060.ndk:dobby`） | Apache-2.0 |
| `lsplant/`、`pine/` | GitHub 源码克隆 | 见各自仓库 |

> **为什么用 AAR 而不是从源码编 Dobby？**
> Dobby master 处于重构中间态，源码编译和旧 Backend 代码不兼容；
> 改用 LSPlant 官方测试依赖的预编译 AAR（含 prefab 的 arm64 `libdobby.a`）更稳。

`pine` 只是早期 PoC 的备选，**生产路径不使用**，但 `fetch-deps.sh` 仍会克隆它。

## 3. 构建

一键（Linux/macOS/Git Bash）：

```bash
export ANDROID_NDK=/path/to/android-ndk-r26d
export ANDROID_SDK=/path/to/android-sdk
export JDK_ROOT=/path/to/jdk-17
bash build.sh
```

或者分步，便于定位问题：

```bash
bash payload/build.sh    # -> build/payload/libnhpayload.so + liblsplant.so + modules/*.so
bash injector/build.sh   # -> build/injector/nhinject
bash daemon/build.sh     # -> build/daemon/nhkeepd
bash apk/build.sh        # -> build/native-hook-1.35-*.apk
```

`payload/build.sh` 第一次跑时，如果 `payload/src/hooker_dex.h` 不存在，会自动调
`payload/hooker/build-dex.sh` 生成（需要 `JDK_ROOT` + `ANDROID_SDK`）。

### 产物

```
build/
├── payload/
│   ├── libnhpayload.so        运行时（~407 KB）
│   ├── liblsplant.so          LSPlant 6.4（~103 KB）
│   └── modules/*.so           四个业务模块
├── injector/nhinject          ptrace 注入器（~14 KB）
├── daemon/nhkeepd             root 侧巡检
└── native-hook-1.35-*.apk     控制台 App
```

## 4. 本仓库外的可选依赖

`apk/build.sh` 会尝试从同级目录取两组**不在本仓库**的产物，取不到就跳过并告警 ——
不影响 APK 构建，只是相应功能缺失：

| 路径 | 缺了会怎样 |
|---|---|
| `../../root-term/assets/{rt_shell,rt_httpd}` | APK 不含 4455/4466 通道（root shell 与文件管理页）。App 的部署按钮会失败，但可以手工 `sh start.sh` |
| `../../evs-dvr/build/{evsdvr,libevsdvr.so}` | 后摄记录仪功能不可用（`evs_dvr` 开关无效） |

## 5. 签名

覆盖安装原厂应用**必须与原厂签名一致**，否则 `pm install -r` 会报签名冲突。
`apk/build.sh` 不提供任何默认证书或密码：

```bash
export KEYSTORE=/path/to/your.p12
export KS_PASS=...        # 必填，不设则只产出 unsigned
export KS_ALIAS=cert      # 默认 cert
```

没有证书时输出 `build/native-hook-1.35-unsigned.apk`，需要用
`apksigner sign` 自己签，或者先卸载原厂应用再安装（会丢原厂应用的登录态）。

> 本仓库**永不包含**签名材料。`.gitignore` 里已屏蔽 `*.p12` / `*.jks` / `*.keystore`。

## 6. 改版本号

这个项目有**两个版本号**，混了就会出问题：

| 版本 | 存在哪 | 作用 |
|---|---|---|
| **APK 版本** | `apk/AndroidManifest.xml` 的 `versionCode`/`versionName`、`apk/build.sh` 的 `VERSION`、`MainActivity2.java` 里的显示文案（`"v1.35 · native 1.29"`） | 给人看的，也是 `pm install -r` 的覆盖判断依据 |
| **native payload 版本** | `NativeHook.java` 的 `NativeHook.VERSION`（形如 `1.29-native29`） | 和车机上的 `/data/local/tmp/hook-native/.version` 比对，**不一致就整套重部署 17 个文件** |

所以升级分两种情况：

| 改了什么 | APK 版本 | `NativeHook.VERSION` |
|---|---|---|
| 只改界面或配置默认值（如 1.30~1.35） | 要升 | **不动** —— 否则每次都要白白重部署一遍 native 文件 |
| 改了 payload / 模块 / 脚本 | 要升 | **必须升**（`1.29-native29` → `1.30-native30`），否则车机上还是旧 payload |

当前状态：APK `1.35` / native `1.29`。

## 7. 常见构建失败

| 报错 | 原因 | 处理 |
|---|---|---|
| `找不到 Android NDK` | 没设 `ANDROID_NDK` | export 一下，或用 `ANDROID_NDK_ROOT` |
| `缺少 .../libdobby.a，请先运行 ./fetch-deps.sh` | `third_party/` 空 | `./fetch-deps.sh` |
| `找不到 d8.jar` | `ANDROID_SDK` 没指向含 build-tools 的目录 | 检查 `$ANDROID_SDK/build-tools/*/lib/d8.jar` |
| payload 加载后目标进程崩，日志 `cannot locate symbol` | 模块或 payload 链了 `libc++_shared.so` | 确认用了 `-static-libstdc++`（payload）/ `-nostdlib++`（模块） |
| `pm install` 报签名冲突 | 证书和原厂不一致 | 见第 5 节 |
