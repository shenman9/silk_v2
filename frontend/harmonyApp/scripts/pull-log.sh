#!/bin/bash
# ============================================================
#  拉取模拟器上 Silk App 的日志文件
#  用法:  bash scripts/pull-log.sh [--clear]
#
#  选项:
#    --clear   拉取后清空模拟器上的日志文件
# ============================================================

BUNDLE_NAME="com.silk.harmony"
DEVICE="127.0.0.1:5555"
# 注意: 使用设备物理路径（非 App 沙箱虚拟路径）
LOG_REMOTE="/data/app/el2/100/base/$BUNDLE_NAME/haps/entry/files/silk_debug.log"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOCAL_LOG="$SCRIPT_DIR/silk_debug.log"

CLEAR=false
for arg in "$@"; do
  case $arg in
    --clear) CLEAR=true ;;
  esac
done

echo "从模拟器拉取日志..."
# 使用 MSYS_NO_PATHCONV 防止 Git Bash 转换远程路径
LOG_CONTENT=$(MSYS_NO_PATHCONV=1 hdc -t "$DEVICE" shell cat "$LOG_REMOTE" 2>&1)

if echo "$LOG_CONTENT" | grep -q "No such file"; then
  echo "日志文件不存在（App 可能还未写入日志）"
else
  echo "$LOG_CONTENT" > "$LOCAL_LOG"
  echo ""
  echo "$LOG_CONTENT"
  echo ""
  echo "日志已保存到: $LOCAL_LOG"

  if [ "$CLEAR" = true ]; then
    MSYS_NO_PATHCONV=1 hdc -t "$DEVICE" shell "echo -n > $LOG_REMOTE" 2>/dev/null
    echo "模拟器上的日志已清空"
  fi
fi
