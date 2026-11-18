# Native Hook PoC：最小 payload 加载

目标：只验证 **system_server 能不能从 /data 直接加载我们的 .so**，不做任何 hook。

## 文件

- `libpoc.so`：aarch64，constructor 里打 `NativeHookPOC: payload loaded`，
  并写 `/data/local/tmp/hook-native/poc.loaded`。
- `load-poc.js`：Frida 脚本，`Module.load('/data/local/tmp/hook-native/libpoc.so')`。

## 构建

```bash
export ANDROID_NDK=/path/to/android-ndk-r26d
bash poc/build-poc.sh          # -> poc/libpoc.so
```

`libpoc.so` 不入库，需要自己编。

## 上传到车机（任选一种）

### 4466 文件管理

```sh
curl -X PUT --data-binary @libpoc.so \
  "http://<车机IP>:4466/ul?path=/data/local/tmp/hook-native/libpoc.so"
curl -X PUT --data-binary @load-poc.js \
  "http://<车机IP>:4466/ul?path=/data/local/tmp/hook-native/load-poc.js"
```

### ADB

```sh
adb shell mkdir -p /data/local/tmp/hook-native
adb push libpoc.so /data/local/tmp/hook-native/
adb push load-poc.js /data/local/tmp/hook-native/
```

## 执行 PoC

在 root shell（4455/rt_shell）里：

```sh
# 先停掉现有 hook，避免多个 agent
pkill -f /data/local/tmp/hook/frida-inject

# 注入 system_server
/data/local/tmp/hook/frida-inject -p $(pidof system_server) \
  -s /data/local/tmp/hook-native/load-poc.js \
  > /data/local/tmp/hook-native/poc.log 2>&1 &

sleep 2
cat /data/local/tmp/hook-native/poc.log
logcat -d -s NativeHookPOC | tail
cat /data/local/tmp/hook-native/poc.loaded
```

> 如果 `frida-inject` 不在 `/data/local/tmp/hook/`，先 `find /data/local/tmp -name frida-inject`。

## 预期结果

```
[poc] loaded /data/local/tmp/hook-native/libpoc.so
```

logcat：

```
NativeHookPOC: payload loaded pid=...
```

文件：

```
/data/local/tmp/hook-native/poc.loaded
```

## 失败时

- `Module.load` 报错：把 `poc.log` 发回，说明 linker/SELinux 还有限制；
- 没有 logcat 但进程还在：确认附加的是 `system_server`，不是别的进程；
- 加载成功但 `poc.loaded` 没写：system_server 对 `/data/local/tmp` 写权限受 SELinux 影响，
  改写到 `/data/system/hook-native/poc.loaded` 再试。

## 通过之后

下一步才是 LSPlant + Dobby payload：hook `WhiteListHelper.checkWhiteList`，
再逐步加 `CertificateManager` 和 `c.a.b`。