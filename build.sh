#!/usr/bin/env bash
# 一键构建：payload → injector → daemon → APK
#
# 前置环境变量：
#   ANDROID_NDK=/path/to/android-ndk-r26d
#   ANDROID_SDK=/path/to/android-sdk          （打 APK 用）
#   JDK_ROOT=/path/to/jdk-17                  （打 APK / 生成 Hooker dex 用）
#   可选：KEYSTORE / KS_PASS / KS_ALIAS       （签名用，不设则出 unsigned）
set -euo pipefail
cd "$(dirname "$0")"

echo "=== 1/4 payload + modules ==="
bash payload/build.sh

echo "=== 2/4 injector ==="
bash injector/build.sh

echo "=== 3/4 daemon ==="
bash daemon/build.sh

echo "=== 4/4 apk ==="
bash apk/build.sh

echo
echo "构建完成，产物在 build/ 下。"
ls -lh build/*.apk 2>/dev/null || true
