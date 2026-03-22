#!/bin/bash

# Silk 后端测试运行脚本
# 每次新增特性后，运行此脚本确保现有功能正常

echo "════════════════════════════════════════════════════════════"
echo "  🧪 Silk 后端自动化测试"
echo "════════════════════════════════════════════════════════════"

# 设置 JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk

# 运行所有测试
cd /mi/sfs_turbo/lilin_v1/code/silk-fork/backend
./gradlew test --info

# 检查测试结果
if [ $? -eq 0 ]; then
    echo ""
    echo "✅ 所有测试通过！"
    echo ""
else
    echo ""
    echo "❌ 测试失败，请检查错误信息"
    echo ""
    exit 1
fi
