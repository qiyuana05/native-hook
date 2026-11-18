#!/usr/bin/env bash
# Hooker.java → classes.dex → src/hooker_dex.h（编译期内嵌到 payload）。
#
# 用法：
#   export JDK_ROOT=/path/to/jdk-17                  # javac / java
#   export ANDROID_SDK=/path/to/android-sdk          # 提供 build-tools/*/lib/d8.jar
#   bash hooker/build-dex.sh
set -euo pipefail
cd "$(dirname "$0")"

OUT=../../build/hooker-dex
HDR=../src/hooker_dex.h

# --- JDK --------------------------------------------------------------------
if [ -z "${JDK_ROOT:-}" ]; then
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JDK_ROOT=$JAVA_HOME
  else
    echo "请 export JDK_ROOT=/path/to/jdk-17（或设置 JAVA_HOME）" >&2
    exit 2
  fi
fi
JAVAC="$JDK_ROOT/bin/javac"; JAVA="$JDK_ROOT/bin/java"
[ -x "$JAVAC" ] || { echo "找不到 javac: $JAVAC" >&2; exit 2; }

# --- d8 ---------------------------------------------------------------------
if [ -z "${D8_JAR:-}" ]; then
  [ -n "${ANDROID_SDK:-}" ] || { echo "请 export ANDROID_SDK=/path/to/android-sdk" >&2; exit 2; }
  D8_JAR=$(ls -1 "$ANDROID_SDK"/build-tools/*/lib/d8.jar 2>/dev/null | sort -V | tail -1)
fi
[ -f "${D8_JAR:-}" ] || { echo "找不到 d8.jar，请设置 D8_JAR" >&2; exit 2; }

# --- python（把 dex 转成 C 数组）--------------------------------------------
if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
elif command -v py >/dev/null 2>&1; then PY="py -3"
else echo "需要 python3 把 dex 转成 C 数组" >&2; exit 2; fi

rm -rf "$OUT"
mkdir -p "$OUT/classes"

"$JAVAC" -encoding UTF-8 -source 8 -target 8 -d "$OUT/classes" Hooker.java
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --min-api 26 --output "$OUT" "$OUT/classes/com/changan/nh/Hooker.class"

$PY - "$OUT/classes.dex" "$HDR" <<'PY'
import sys

src, dst = sys.argv[1], sys.argv[2]
data = open(src, 'rb').read()
out = [
    '// 自动生成，勿手改：hooker/build-dex.sh',
    '#pragma once',
    '#include <cstddef>',
    '#include <cstdint>',
    '',
    'static const uint8_t kHookerDex[] = {',
]
for i in range(0, len(data), 16):
    out.append('    ' + ', '.join('0x%02x' % b for b in data[i:i + 16]) + ',')
out.append('};')
out.append('static const size_t kHookerDexSize = %d;' % len(data))
open(dst, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('%s: %d bytes' % (dst, len(data)))
PY
