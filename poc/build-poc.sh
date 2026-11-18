#!/usr/bin/env bash
# 构建最小 PoC payload（只验证能从 /data 加载 .so，不做任何 hook）。
#
# 用法：
#   export ANDROID_NDK=/path/to/android-ndk-r26d
#   bash poc/build-poc.sh
set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${ANDROID_NDK:-}" ]; then
  echo "请 export ANDROID_NDK=/path/to/android-ndk-r26d" >&2
  exit 2
fi
for host in linux-x86_64 darwin-x86_64 windows-x86_64; do
  BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$host/bin"
  [ -x "$BIN/aarch64-linux-android28-clang" ] && break
  BIN=""
done
[ -n "$BIN" ] || { echo "找不到 aarch64 clang，检查 ANDROID_NDK" >&2; exit 2; }

"$BIN/aarch64-linux-android28-clang" -shared -fPIC -O2 -o libpoc.so poc.c -llog
file libpoc.so 2>/dev/null || true
ls -lh libpoc.so
