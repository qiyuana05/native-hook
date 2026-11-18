# 设计记录（2026-11-03）

> 这是项目立项时的技术选型与可行性分析，保留作背景参考。当前实现与用法见根目录 `README.md`，版本变更见 `CHANGELOG.md`。

## 立项时的 README（原文）

目标：把当前 `uninstall-hook` 的 Frida 引擎（`frida-inject` 51 MB）替换成
**LSPlant/Pine + 极小注入器 + native payload**，把 APK 压到 3~5 MB，并消除首次
51 MB 部署造成的偶发失败。

> 状态：**项目骨架/依赖评估阶段**。还没有可安装的 native APK，现有 v1.8 仍是可用版本。

## 当前环境

- 车机：Android 9 / arm64 / 长安（CD569）与逸达。
- 提权：DM01 `com.incall.spm/.DMService`，root + SELinux Permissive；没有 Magisk/LSPosed。
- 工具链：Android NDK r26d、CMake/Ninja、JDK 17、Android SDK build-tools。
- 已验证：`frida-inject` 能通过 DM01 root 注入 `system_server` 和 `com.vecentek.decoreapp`，
  说明 ptrace/注入通道可用。

## 已确认的技术事实

### LSPlant

- Maven `org.lsposed.lsplant:lsplant-standalone:6.4` 解包后：
  - `prefab/modules/lsplant/libs/android.arm64-v8a/liblsplant.so` 约 105 KB；
  - `prefab/modules/lsplant/include/lsplant.hpp`；
  - `InitInfo` 要求提供：`inline_hooker`、`inline_unhooker`、`art_symbol_resolver`
    （可选 `art_symbol_prefix_resolver`）。
- 也就是说 LSPlant 只负责 ART 方法替换/去优化，**不含 inline hook 引擎和 libart 符号解析**；
  还需要 Dobby（inline hook）和一个能读 `libart.so` `.dynsym/.symtab` 的符号解析器。
- 需要生成一个 Java `Hooker` 类（`Object callback(Object[] args)`），由 LSPlant 生成 stub；
  payload 负责加载这个 DEX、注册 native callback。

### Pine

- `pine/core/src/main/cpp` 自带 trampoline、`elf_image` 符号解析和 ART 适配，
  Android 4.4~15、arm64 支持；native 侧 `Pine_hook0` 已封装好。
- 但公开 API 是 Java（`Pine.hook(Method, MethodHook)`），native payload 要加载 Pine 的
  Java 类/DEX；许可证是 Anti-996，和 LSPlant 的 LGPL-3.0 不同。
- 适合做快速 PoC，不适合作为长期唯一依赖。

## 推荐路线

**LSPlant + Dobby + 最小 ELF 注入器**：

- LSPlant 负责 Java 方法 hook；
- Dobby 提供 inline hook/unhook；
- 自己写 `libart.so` 符号解析（`.dynsym` + `.symtab`，按 LSPlant 要求）；
- payload 内嵌一个小 DEX（`Hooker` 类）和配置解析；
- 注入器负责把 payload 加载进 `system_server` / `com.vecentek.decoreapp`。

## 最大的硬卡点

不是 Java hook，而是 **在 system_server 里加载我们的 .so**：

- Android 9 linker namespace 默认只允许 `/system/lib64`、`/vendor/lib64` 等路径；
  直接 `dlopen("/data/local/tmp/xxx.so")` 大概率失败。
- `frida-inject` 用的是 `Frida.Linjector.inject_library_blob`（memfd/custom ELF loader），
  所以它能绕开 namespace。
- 我们的最小注入器需要同样的能力：
  1. ptrace attach / 保存寄存器；
  2. 在目标进程里 mmap 一段可执行内存；
  3. 写入 payload ELF（或 memfd）；
  4. 解析/重定位、调用入口；
  5. detach，恢复运行。
- 这是第一个 PoC 要验证的东西，不应先写完整 hook 逻辑。

## 分阶段计划

1. **注入 PoC**
   - 用现有 `frida-inject` + 一个只打印 `payload loaded` 的最小 `.so`，
     验证 `Module.load`/memfd 路径是否可行。
   - 如果 `Module.load` 被 namespace 拦住，转为自写 memfd ELF loader。
2. **LSPlant + Dobby PoC**
   - payload 初始化 LSPlant，hook 一个无害方法并打 log；
   - 验证 Android 9 / arm64 的 ART 适配。
3. **业务 hook**
   - `WhiteListHelper.checkWhiteList` → 非 deny 返回 true；
   - `CertificateManager.isCheckAuth(String)` / `(String, Signature)` / `(PackageInfo)`；
   - `com.vecentek.decoreapp.c.a.b(String, int)` → type 1/4 返回 true。
4. **最小注入器**
   - 替换 Frida；保留 DM01 部署、4455/4466、报告收集。
5. **APK 集成**
   - payload 与 DEX 随 APK 部署到 `/data/local/tmp/hook-native/`；
   - 目标 APK 预计 3~5 MB，首次部署 <1 秒。

## 目录

```
projects/native-hook/
├── README.md
├── fetch-deps.sh          # 克隆 Pine/LSPlant/Dobby + 下载 LSPlant AAR
├── payload/               # LSPlant payload（待实现）
├── injector/              # ptrace/memfd 注入器（待实现）
└── third_party/           # 依赖源码（gitignore，不入库）
```

## 与现有 v1.8 的关系

- v1.8 继续作为 fallback：DM01 + frida-inject + sec-guard + 4455/4466 + 报告收集。
- native 版本只替换 hook 引擎，部署/UI/报告逻辑复用。
- 在 native 版本稳定前，不要把 v1.8 从车上卸掉。

## 许可证

- LSPlant：LGPL-3.0（动态链接/开源合规需注意）。
- Dobby：Apache-2.0。
- Pine：Anti-996，若最终产品分发需评估。
- Frida：现有 fallback，保持原状。