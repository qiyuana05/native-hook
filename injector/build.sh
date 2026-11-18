#!/usr/bin/env bash
# 构建 ptrace 注入器 nhinject（arm64，静态位置的 libc，产物约 15 KB）。
#
# 用法：
#   export ANDROID_NDK=/path/to/android-ndk-r26d
#   bash injector/build.sh
set -euo pipefail
cd "$(dirname "$0")"

ROOT=$(cd .. && pwd)

if [ -z "${ANDROID_NDK:-}" ]; then
  echo "请 export ANDROID_NDK=/path/to/android-ndk-r26d" >&2
  exit 2
fi
case "$(uname -s)" in
  Linux*)               NDK_HOST=linux-x86_64 ;;
  Darwin*)              NDK_HOST=darwin-x86_64 ;;
  MINGW*|MSYS*|CYGWIN*) NDK_HOST=windows-x86_64 ;;
  *) echo "不支持的主机系统: $(uname -s)" >&2; exit 2 ;;
esac
NDK="$ANDROID_NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin"

mkdir -p "$ROOT/build/injector"
"$NDK/aarch64-linux-android28-clang" -O2 -Wall -Wextra \
  -o "$ROOT/build/injector/nhinject" nhinject.c

echo "OK -> build/injector/nhinject"
ls -l "$ROOT/build/injector/nhinject"
