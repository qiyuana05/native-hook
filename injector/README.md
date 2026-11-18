# Injector：nhinject

arm64 可执行文件，由 root（DM01）运行，用 ptrace 把 `liblsplant.so` 和
`libnhpayload.so` 加载进目标进程。用来替换 `frida-inject`。

## 构建

```bash
export ANDROID_NDK=/path/to/android-ndk-r26d
bash injector/build.sh
```

产物约 14 KB，动态链接 libc。

## 用法

```sh
# 默认加载 /data/local/tmp/hook-native/liblsplant.so + libnhpayload.so
./nhinject $(pidof system_server)
./nhinject $(pidof com.vecentek.decoreapp)

# 或显式指定库
./nhinject <pid> /path/lib1.so /path/lib2.so
```

## 实现

1. 从 `/proc/<pid>/maps` 找 `libdl.so`、`libc.so` 的基址和 `[stack]` 范围。
2. 从本地 `/system/lib64/libdl.so` 的 `.dynsym` 解析 `dlopen` 偏移。
3. 从本地 `/system/lib64/libc.so` 的可执行段找第一条 `brk #0`（`0xD4200000`）。
4. `PTRACE_ATTACH` 目标进程，保存 arm64 寄存器。
5. 把 `.so` 路径写到目标栈底附近（避开当前 SP 和 dlopen 的栈增长）。
6. 设置 `x0=path`、`x1=RTLD_NOW|RTLD_GLOBAL`、`pc=dlopen`、`lr=brk #0`。
7. `PTRACE_CONT`，等 `SIGTRAP`；读 `x0` 作为 dlopen 返回值。
8. 恢复原寄存器，处理下一个库；全部完成后 `PTRACE_DETACH`。

因为固件是 `userdebug` + Permissive，`system_server` 可以直接从
`/data/local/tmp` `dlopen`，不需要 Frida 的 memfd/custom ELF loader。

## 测试顺序

1. 重启目标进程（或整车），清掉之前 Frida 注入的 payload。
   `dlopen` 对相同路径不会重新执行 constructor，残留 payload 会让新库不生效。
2. 先注入 VecentekApp，确认状态文件 `uninstall-hook-native-sec.status`。
3. 再注入 system_server，确认 `uninstall-hook-native.status` 里有 `auth=`。
4. 测试安装和卸载。

## 注意

- ptrace 只 attach 目标主线程；如果 `dlopen` 与其他线程争 linker 锁，注入器可能
  卡在 `waitpid`。此时 Ctrl-C 结束注入器，目标进程通常不受影响。
- 注入失败时先看 stderr 里的 `dlopen=` / `brk=` 地址是否合理。
- 目标进程必须是 64 位；32 位应用需要单独的 arm 版本。
- 不写 `/system`，不刷分区，只改目标进程内存；重启即恢复。