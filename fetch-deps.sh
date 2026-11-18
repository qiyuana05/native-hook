#!/usr/bin/env bash
# 拉取 native-hook 依赖（不入库，third_party/ 已 gitignore）。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p third_party

if [ ! -d third_party/lsplant/.git ]; then
  git clone --depth 1 https://github.com/LSPosed/LSPlant.git third_party/lsplant
fi
if [ ! -d third_party/pine/.git ]; then
  git clone --depth 1 https://github.com/canyie/pine.git third_party/pine
fi
# Dobby master 处于重构中间态，源码编译与旧 Backend 代码不兼容；
# 改用 LSPlant 官方测试依赖的预编译 AAR（含 prefab arm64 libdobby.a）。
if [ ! -f third_party/dobby-aar/dobby-1.2.aar ]; then
  mkdir -p third_party/dobby-aar
  curl -L -o third_party/dobby-aar/dobby-1.2.aar \
    https://repo1.maven.org/maven2/io/github/vvb2060/ndk/dobby/1.2/dobby-1.2.aar
  unzip -o -q third_party/dobby-aar/dobby-1.2.aar \
    -d third_party/dobby-aar/extracted
fi

if [ ! -f third_party/lsplant-aar/lsplant-standalone-6.4.aar ]; then
  mkdir -p third_party/lsplant-aar
  curl -L -o third_party/lsplant-aar/lsplant-standalone-6.4.aar \
    https://repo1.maven.org/maven2/org/lsposed/lsplant/lsplant-standalone/6.4/lsplant-standalone-6.4.aar
  unzip -o -q third_party/lsplant-aar/lsplant-standalone-6.4.aar \
    -d third_party/lsplant-aar/extracted
fi

echo "OK: third_party/lsplant, pine, dobby-aar, lsplant-aar"
find third_party -maxdepth 2 -type d | sort | head -30