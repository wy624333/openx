#!/bin/bash
# Android 构建预处理脚本：替换所有 x86_64 aapt2 为 AArch64 版本

set -e

# 系统 aapt2 路径（AArch64）
SYSTEM_AAPT2="/usr/bin/aapt2"

echo "预处理 aapt2..."

# 查找所有 aapt2 文件
find /root/.gradle/caches -name "aapt2" -type f 2>/dev/null | while read aapt2_file; do
    ARCH=$(readelf -h "$aapt2_file" 2>/dev/null | grep "Machine:" | awk '{print $NF}')
    if [ "$ARCH" = "X86-64" ]; then
        echo "替换 x86_64 aapt2: $aapt2_file"
        cp "$SYSTEM_AAPT2" "$aapt2_file"
        chmod +x "$aapt2_file"
    fi
done

echo "预处理完成"
