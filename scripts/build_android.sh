#!/bin/bash
# Android 构建脚本：预处理 + 构建 + 后处理

set -e

PROJECT_DIR="/root/workspace/projects/xingde/openx2.2.1.11_src_buildcache"
SYSTEM_AAPT2="/usr/bin/aapt2"

echo "=== 步骤 1: 预处理 aapt2 ==="
bash "$PROJECT_DIR/scripts/prebuild_aapt2.sh"

echo "=== 步骤 2: 执行构建 ==="
cd "$PROJECT_DIR"
./gradlew clean assembleDebug --no-daemon

echo "=== 步骤 3: 后处理 aapt2 ==="
bash "$PROJECT_DIR/scripts/prebuild_aapt2.sh"

echo "=== 构建完成 ==="
