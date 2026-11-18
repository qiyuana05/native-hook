#!/usr/bin/env bash
# 构建 libnhpayload.so（Dobby 静态）+ 四个模块，并把 liblsplant.so 复制到产物目录。
#
# 依赖：
#   - Android NDK r26d+（arm64 clang）
#   - 已执行 ../fetch-deps.sh（third_party/ 下有 Dobby / LSPlant 的 prefab 产物）
#
# 用法：
#   export ANDROID_NDK=/path/to/android-ndk-r26d
#   bash payload/build.sh
set -euo pipefail
cd "$(dirname "$0")"

ROOT=$(cd .. && pwd)
BUILD="$ROOT/build/payload"
MODULE_OUT="$BUILD/modules"
OUT="$BUILD/libnhpayload.so"

# --- 定位 NDK ---------------------------------------------------------------
if [ -z "${ANDROID_NDK:-}" ]; then
  for cand in "${ANDROID_NDK_ROOT:-}" "$HOME/Android/Sdk/ndk/"*/; do
    [ -n "$cand" ] && [ -d "$cand/toolchains" ] && ANDROID_NDK=$cand && break
  done
fi
if [ -z "${ANDROID_NDK:-}" ] || [ ! -d "${ANDROID_NDK:-}/toolchains" ]; then
  echo "找不到 Android NDK。请 export ANDROID_NDK=/path/to/android-ndk-r26d" >&2
  exit 2
fi

case "$(uname -s)" in
  Linux*)               NDK_HOST=linux-x86_64 ;;
  Darwin*)              NDK_HOST=darwin-x86_64 ;;
  MINGW*|MSYS*|CYGWIN*) NDK_HOST=windows-x86_64 ;;
  *) echo "不支持的主机系统: $(uname -s)" >&2; exit 2 ;;
esac
NDK="$ANDROID_NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin"
[ -x "$NDK/aarch64-linux-android28-clang++" ] || {
  echo "NDK 工具链不完整: $NDK/aarch64-linux-android28-clang++" >&2; exit 2; }

# --- 依赖检查 ---------------------------------------------------------------
DOBBY_INC="$ROOT/third_party/dobby-aar/extracted/prefab/modules/dobby/include"
DOBBY_LIB="$ROOT/third_party/dobby-aar/extracted/prefab/modules/dobby/libs/android.arm64-v8a/libdobby.a"
LSPLANT_INC="$ROOT/third_party/lsplant-aar/extracted/prefab/modules/lsplant/include"
LSPLANT_LIB="$ROOT/third_party/lsplant-aar/extracted/prefab/modules/lsplant/libs/android.arm64-v8a/liblsplant.so"

for f in "$DOBBY_LIB" "$LSPLANT_LIB"; do
  [ -f "$f" ] || { echo "缺少 $f，请先运行 ./fetch-deps.sh" >&2; exit 1; }
done

# Hooker DEX 是编译期内嵌进 payload 的，没生成就先生成。
[ -f src/hooker_dex.h ] || bash hooker/build-dex.sh

mkdir -p "$BUILD" "$MODULE_OUT"

# --- 运行时 -----------------------------------------------------------------
# -static-libstdc++：目标进程的 linker namespace 里没有 libc++_shared.so
# -z max-page-size=16384：兼容 16K page 内核
"$NDK/aarch64-linux-android28-clang++" -std=c++20 -fPIC -shared -O2 \
  -ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden \
  -static-libstdc++ \
  -I"$DOBBY_INC" -I"$LSPLANT_INC" -Iinclude -Isrc \
  src/main.cpp src/elf_symbols.cpp \
  "$DOBBY_LIB" "$LSPLANT_LIB" \
  -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384 \
  -llog -ldl -lz -lm \
  -o "$OUT"

# --- 模块 -------------------------------------------------------------------
# 模块只依赖 libc：-nostdlib++，不链 libc++，体积和依赖都最小。
for m in uninstall_guard statusbar_touch hide_presentation iccoa_fullscreen; do
  "$NDK/aarch64-linux-android28-clang++" -std=c++17 -fPIC -shared -O2 \
    -fvisibility=hidden -nostdlib++ -Iinclude \
    -o "$MODULE_OUT/$m.so" "modules/$m/module.cpp"
done

# 运行时按 soname 依赖 liblsplant.so，必须同目录。
cp "$LSPLANT_LIB" "$BUILD/liblsplant.so"

ls -lh "$OUT" "$BUILD/liblsplant.so" "$MODULE_OUT"/*.so
