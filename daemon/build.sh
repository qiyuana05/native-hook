#!/usr/bin/env bash
# 构建 root 侧巡检守护进程 nhkeepd（arm64）。
#
# 用法：
#   export ANDROID_NDK=/path/to/android-ndk-r26d
#   bash daemon/build.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -z "${ANDROID_NDK:-}" ]; then
  echo "请 export ANDROID_NDK=/path/to/android-ndk-r26d" >&2
  exit 2
fi
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$host/bin"
  [ -x "$BIN/aarch64-linux-android28-clang++" ] && break
  BIN=""
done
[ -n "$BIN" ] || { echo "找不到 aarch64 clang++，检查 ANDROID_NDK" >&2; exit 2; }

mkdir -p build/daemon
"$BIN/aarch64-linux-android28-clang++" -O2 -Wall -Wextra \
  -o build/daemon/nhkeepd daemon/nhkeepd.cpp -llog

echo "OK -> build/daemon/nhkeepd"
ls -l build/daemon/nhkeepd
