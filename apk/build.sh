#!/usr/bin/env bash
# 打控制台 APK（手写构建，不用 Gradle）。
#
# 前置：
#   1) bash payload/build.sh     -> build/payload/{liblsplant,libnhpayload}.so + modules/
#   2) bash injector/build.sh    -> build/injector/nhinject
#   3) bash daemon/build.sh      -> build/daemon/nhkeepd
#
# 可选（不在本仓库，缺了会跳过并告警）：
#   ../../root-term/assets/rt_shell、rt_httpd   root shell / 文件管理页
#   ../../evs-dvr/build/{evsdvr,libevsdvr.so}   后摄记录仪
#
# 用法：
#   export ANDROID_SDK=/path/to/android-sdk
#   export JDK_ROOT=/path/to/jdk-17
#   export KS_PASS=...            # 不设则只出 unsigned
#   bash apk/build.sh
set -euo pipefail
cd "$(dirname "$0")"

VERSION=1.35

# --- 主机与工具 -------------------------------------------------------------
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SUF=.exe; D8=d8.bat; SIGNER=apksigner.bat ;;
  *)                    SUF=;    D8=d8;      SIGNER=apksigner ;;
esac

if [ -z "${ANDROID_SDK:-}" ]; then
  echo "请 export ANDROID_SDK=/path/to/android-sdk" >&2
  exit 2
fi
BT=$(ls -1d "$ANDROID_SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
BT=${BT%/}
[ -n "$BT" ] || { echo "ANDROID_SDK 下没有 build-tools" >&2; exit 2; }
JAR="$ANDROID_SDK/platforms/android-35/android.jar"

JDK_ROOT=${JDK_ROOT:-${JAVA_HOME:-}}
[ -n "$JDK_ROOT" ] || { echo "请 export JDK_ROOT=/path/to/jdk-17" >&2; exit 2; }
export JAVA_HOME="$JDK_ROOT"
export PATH="$JAVA_HOME/bin:$PATH"

if command -v python3 >/dev/null 2>&1; then PY=python3
elif command -v python >/dev/null 2>&1; then PY=python
elif command -v py >/dev/null 2>&1; then PY="py -3"
else echo "需要 python3 把 classes.dex 合进 APK" >&2; exit 2; fi

# --- 同步 native 产物到 assets ----------------------------------------------
mkdir -p assets/native/modules

if [ -f ../build/injector/nhinject ]; then
  cp ../build/injector/nhinject ../build/payload/liblsplant.so \
     ../build/payload/libnhpayload.so                     assets/native/
  for m in uninstall_guard statusbar_touch hide_presentation iccoa_fullscreen; do
    cp "../build/payload/modules/$m.so" assets/native/modules/
  done
  cp scripts/start.sh scripts/status.sh scripts/ensure_services.sh assets/native/
fi
[ -f ../build/daemon/nhkeepd ] && cp ../build/daemon/nhkeepd assets/native/ || true

# 可选外部资产（不在本仓库）
if [ -f ../../root-term/assets/rt_shell ]; then
  cp ../../root-term/assets/rt_shell ../../root-term/assets/rt_httpd assets/native/
else
  echo "warn: 未找到 root-term 的 rt_shell/rt_httpd，APK 将不含 root shell 通道" >&2
fi
if [ -f ../../evs-dvr/build/evsdvr ] && [ -f ../../evs-dvr/build/libevsdvr.so ]; then
  cp ../../evs-dvr/build/evsdvr         assets/native/evsdvr
  cp ../../evs-dvr/build/libevsdvr.so   assets/native/libevsdvr.so
  cp ../../evs-dvr/boot.sh              assets/native/evsdvr-boot.sh
  cp ../../evs-dvr/run.sh               assets/native/evsdvr-run.sh
  chmod 755 assets/native/evsdvr assets/native/evsdvr-boot.sh assets/native/evsdvr-run.sh
else
  echo "warn: 未找到 evs-dvr 产物，跳过记录仪模块" >&2
fi

# 必需项：缺任何一个都不打半成品
REQUIRED="assets/native/nhinject assets/native/liblsplant.so assets/native/libnhpayload.so
          assets/native/modules/uninstall_guard.so assets/native/modules/statusbar_touch.so
          assets/native/modules/hide_presentation.so assets/native/modules/iccoa_fullscreen.so
          assets/native/start.sh assets/native/status.sh assets/native/ensure_services.sh
          assets/native/nhkeepd"
for f in $REQUIRED; do
  [ -f "$f" ] || { echo "missing $f; 先构建 payload / injector / daemon" >&2; exit 2; }
done

# --- 编译 ------------------------------------------------------------------
WORK=build/.work
rm -rf "$WORK"
mkdir -p "$WORK/build/obj" "$WORK/build/dex" "$WORK/src" "$WORK/assets/native/modules" "$WORK/res"
cp -r src/. "$WORK/src/"
cp -r res/. "$WORK/res/"
cp AndroidManifest.xml "$WORK/"

# assets：先搬必需项，再搬存在的可选项
cp assets/native/nhinject assets/native/liblsplant.so assets/native/libnhpayload.so \
   "$WORK/assets/native/"
cp assets/native/modules/*.so "$WORK/assets/native/modules/"
cp assets/native/start.sh assets/native/status.sh assets/native/ensure_services.sh \
   assets/native/nhkeepd "$WORK/assets/native/"
for opt in rt_shell rt_httpd libevsdvr.so evsdvr evsdvr-boot.sh evsdvr-run.sh; do
  [ -f "assets/native/$opt" ] && cp "assets/native/$opt" "$WORK/assets/native/" || true
done

find "$WORK/src" -name '*.java' > "$WORK/build/sources.txt"
javac -encoding UTF-8 -source 1.8 -target 1.8 -bootclasspath "$JAR" \
  -d "$WORK/build/obj" @"$WORK/build/sources.txt"

find "$WORK/build/obj" -name '*.class' > "$WORK/build/classes.txt"
"$BT/$D8" --release --lib "$JAR" --output "$WORK/build/dex" @"$WORK/build/classes.txt"

"$BT/aapt2$SUF" compile --dir "$WORK/res" -o "$WORK/build/res.zip"
"$BT/aapt2$SUF" link -o "$WORK/build/base.apk" \
  --manifest "$WORK/AndroidManifest.xml" -I "$JAR" \
  --min-sdk-version 28 --target-sdk-version 28 \
  "$WORK/build/res.zip" \
  -A "$WORK/assets"

# aapt2 不认外部 dex，手工塞进 zip
$PY - "$WORK/build/base.apk" "$WORK/build/dex/classes.dex" "$WORK/build/withdex.apk" <<'PYEOF'
import sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    zout.write(dex, 'classes.dex')
PYEOF

"$BT/zipalign$SUF" -f 4 "$WORK/build/withdex.apk" "$WORK/build/aligned.apk"

# --- 签名 ------------------------------------------------------------------
mkdir -p ../build
ALIGNED="$WORK/build/aligned.apk"
UNSIGNED="../build/native-hook-$VERSION-unsigned.apk"
OUT="../build/native-hook-$VERSION-car-signed.apk"
KEY=${KEYSTORE:-}
KS_ALIAS=${KS_ALIAS:-cert}

if [ "${SKIP_SIGN:-0}" = "1" ] || [ -z "${KS_PASS:-}" ] || [ -z "$KEY" ] || [ ! -f "$KEY" ]; then
  cp "$ALIGNED" "$UNSIGNED"
  echo "=== unsigned ==="
  "$BT/aapt2$SUF" dump badging "$UNSIGNED" | head -2
  echo "WARN: 未提供签名证书或 KS_PASS，输出 $UNSIGNED" >&2
  echo "      覆盖安装原厂应用需要与其一致的签名证书；没证书就只能出 unsigned。" >&2
  ls -lh "$UNSIGNED"
  exit 0
fi

"$BT/$SIGNER" sign --ks "$KEY" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KS_ALIAS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT" "$ALIGNED"

echo "=== badging ==="
"$BT/aapt2$SUF" dump badging "$OUT" | head -2
echo "=== cert ==="
"$BT/$SIGNER" verify --print-certs "$OUT" 2>/dev/null | head -5 || true
echo "OK -> $OUT"
ls -lh "$OUT"
