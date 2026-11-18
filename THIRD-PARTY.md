# 第三方组件与许可证

本仓库**不包含**任何第三方源码或预编译二进制。`fetch-deps.sh` 会在本地拉取到
`third_party/`（已 gitignore）。

| 组件 | 版本 | 用途 | 许可证 | 引入方式 |
|---|---|---|---|---|
| [LSPlant](https://github.com/LSPosed/LSPlant) | 6.4（`lsplant-standalone`） | ART Java 方法 hook | **LGPL-3.0** | 动态链接 `liblsplant.so`，随 APK 释放到目标机 |
| [Dobby](https://github.com/jmpews/Dobby) | 1.2（预编译 AAR） | inline hook 框架 | **Apache-2.0** | 静态链接进 `libnhpayload.so` |
| [Pine](https://github.com/canyie/pine) | — | 早期 PoC 备选，**未进入生产路径** | Anti-996 | `fetch-deps.sh` 会克隆，但构建不使用 |

## LGPL-3.0 合规说明

LSPlant 是 LGPL-3.0。本项目对它的使用方式是**动态链接**：

- 构建时以 `liblsplant.so` 作为独立共享库链接（`payload/build.sh`），
  **未修改 LSPlant 源码**；
- 产物中 `liblsplant.so` 与 `libnhpayload.so` 是两个独立的 `.so`，
  通过 soname 动态依赖，用户可自行替换 `liblsplant.so` 为其他构建版本。

因此本项目按 LGPL-3.0 §4(d) 的「动态链接 + 提供替换手段」方式使用，自有代码可
采用任意许可证（本仓库为 MIT）。

**如果你分发构建产物**（例如打包成 APK 给别人安装），需要：

1. 附带 LSPlant 的许可证原文与版权声明；
2. 说明如何获取 LSPlant 对应版本的源码（上游仓库地址 + 具体 tag/commit 即可）；
3. 保留用户替换 `liblsplant.so` 的能力（不要把它静态合并进 `libnhpayload.so`）。

## Apache-2.0 合规说明

Dobby 以静态库形式链接进 `libnhpayload.so`。分发产物时需保留 Apache-2.0 许可证文本
及 NOTICE 文件（如上游提供）。

## 其它参考

- [ptrace(2)](https://man7.org/linux/man-pages/man2/ptrace.2.html) — 注入器的系统调用语义
- Android `ld.config.*.txt` — 链接器 namespace 配置格式
