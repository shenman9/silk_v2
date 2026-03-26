#!/bin/bash
# ============================================================
#  Silk HarmonyOS App — 一键构建、部署、启动（跨平台）
#  用法:  bash scripts/deploy.sh [--skip-install] [--log]
#
#  选项:
#    --skip-install   跳过 ohpm install（依赖未变时加速）
#    --log            部署后自动拉取 App 日志文件并输出
#
#  支持平台: Windows (Git Bash / MSYS2), macOS, Linux
# ============================================================
set -e

# ---------- 平台检测 ----------
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) OS_TYPE="windows" ;;
  Darwin*)               OS_TYPE="macos" ;;
  *)                     OS_TYPE="linux" ;;
esac

# ---------- 路径配置 ----------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# DevEco Studio 安装路径（可通过环境变量覆盖）
if [ -z "$DEVECO_HOME" ]; then
  if [ "$OS_TYPE" = "windows" ]; then
    DEVECO_HOME="D:/app/DevEco Studio"
  else
    DEVECO_HOME="$HOME/DevEco Studio"
  fi
fi

DEVECO_SDK_HOME="$DEVECO_HOME/sdk"
BUNDLE_NAME="com.silk.harmony"
ABILITY_NAME="EntryAbility"
DEVICE="127.0.0.1:5555"
LOG_REMOTE="/data/app/el2/100/base/$BUNDLE_NAME/haps/entry/files/silk_debug.log"

# 工具路径：Windows 用 .bat，macOS/Linux 用无后缀脚本
if [ "$OS_TYPE" = "windows" ]; then
  OHPM="$DEVECO_HOME/tools/ohpm/bin/ohpm.bat"
  HVIGORW="$DEVECO_HOME/tools/hvigor/bin/hvigorw.bat"
  HAP_PATH="$PROJECT_DIR/entry/build/default/outputs/default/entry-default-unsigned.hap"
else
  OHPM="$DEVECO_HOME/tools/ohpm/bin/ohpm"
  HVIGORW="$DEVECO_HOME/tools/hvigor/bin/hvigorw"
  HAP_PATH="$PROJECT_DIR/entry/build/default/outputs/default/entry-default-unsigned.hap"
fi

# ---------- 解析参数 ----------
SKIP_INSTALL=false
PULL_LOG=false
for arg in "$@"; do
  case $arg in
    --skip-install) SKIP_INSTALL=true ;;
    --log)          PULL_LOG=true ;;
  esac
done

# ---------- 颜色输出 ----------
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'

step() { echo -e "\n${GREEN}[STEP]${NC} $1"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; exit 1; }

# ---------- 构建函数（跨平台） ----------
build_hap() {
  local hvigor_args="assembleHap --mode module -p module=entry@default -p product=default --no-daemon"

  if [ "$OS_TYPE" = "windows" ]; then
    # Windows: Git Bash 的 PATH 格式（冒号分隔）与 .bat 脚本不兼容
    # 生成临时 .bat 在 Windows 原生环境下执行，确保 Java 和 Node 都能找到
    local win_sdk
    win_sdk=$(cygpath -w "$DEVECO_SDK_HOME")
    local win_java
    win_java=$(cygpath -w "$DEVECO_HOME/jbr/bin")
    local win_project
    win_project=$(cygpath -w "$PROJECT_DIR")
    local win_hvigorw
    win_hvigorw=$(cygpath -w "$HVIGORW")

    local tmp_bat="$PROJECT_DIR/scripts/_build_tmp.bat"
    cat > "$tmp_bat" << BAT
@echo off
set DEVECO_SDK_HOME=${win_sdk}
set PATH=%PATH%;${win_java}
cd /d ${win_project}
call "${win_hvigorw}" ${hvigor_args}
BAT
    "$tmp_bat" 2>&1
    local rc=$?
    rm -f "$tmp_bat"
    return $rc
  else
    # macOS / Linux: 直接设置环境变量调用
    JAVA_HOME="$DEVECO_HOME/jbr" \
    PATH="$DEVECO_HOME/jbr/bin:$PATH" \
    DEVECO_SDK_HOME="$DEVECO_SDK_HOME" \
    "$HVIGORW" $hvigor_args 2>&1
  fi
}

# ---------- 前置检查 ----------
step "检查模拟器连接..."
TARGETS=$(hdc list targets 2>/dev/null || true)
if echo "$TARGETS" | grep -q "$DEVICE"; then
  echo "  模拟器已连接: $DEVICE"
else
  fail "未检测到模拟器 $DEVICE，请先启动鸿蒙模拟器"
fi

# ---------- Step 1: 安装依赖 ----------
if [ "$SKIP_INSTALL" = false ]; then
  step "1/4 安装依赖 (ohpm install)..."
  cd "$PROJECT_DIR"
  "$OHPM" install 2>&1 | tail -1
else
  step "1/4 跳过依赖安装 (--skip-install)"
fi

# ---------- Step 2: 构建 HAP ----------
step "2/4 构建 HAP (hvigorw assembleHap)..."
build_hap | grep -E "(ERROR|WARN|BUILD|Finished.*CompileArkTS)"

if [ ! -f "$HAP_PATH" ]; then
  fail "HAP 文件未生成: $HAP_PATH"
fi
echo "  HAP: $HAP_PATH"

# ---------- Step 3: 安装到模拟器 ----------
step "3/4 安装到模拟器 (hdc install)..."
if [ "$OS_TYPE" = "windows" ]; then
  # Windows: hdc install 在项目目录下会拼接 cwd，需要在根目录执行且用反斜杠路径
  cd /
  HAP_WIN=$(cygpath -w "$HAP_PATH")
  hdc -t "$DEVICE" install "$HAP_WIN" 2>&1
else
  hdc -t "$DEVICE" install "$HAP_PATH" 2>&1
fi

# ---------- Step 4: 启动 App ----------
step "4/4 启动 App (hdc shell aa start)..."
hdc -t "$DEVICE" shell aa start -a "$ABILITY_NAME" -b "$BUNDLE_NAME" 2>&1

# ---------- 可选: 拉取日志 ----------
if [ "$PULL_LOG" = true ]; then
  echo ""
  step "拉取 App 日志..."
  sleep 2
  LOG_CONTENT=$(MSYS_NO_PATHCONV=1 hdc -t "$DEVICE" shell cat "$LOG_REMOTE" 2>&1)
  if echo "$LOG_CONTENT" | grep -q "No such file"; then
    warn "日志文件尚未生成（App 可能还未写入日志）"
  else
    LOCAL_LOG="$PROJECT_DIR/scripts/silk_debug.log"
    echo "$LOG_CONTENT" > "$LOCAL_LOG"
    echo "  日志已保存到: $LOCAL_LOG"
    echo "  ---------- 日志内容 ----------"
    echo "$LOG_CONTENT"
  fi
fi

echo ""
echo -e "${GREEN}部署完成!${NC} App 已在模拟器 $DEVICE 上启动"
