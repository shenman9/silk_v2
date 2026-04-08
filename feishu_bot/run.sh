#!/usr/bin/env bash
# Silk 飞书机器人服务管理脚本
# 用法: ./run.sh {start|stop|restart|status|log}

set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$PROJECT_DIR/.bot.pid"
LOG_FILE="$PROJECT_DIR/.bot.log"
PYTHON="${PYTHON:-python3}"
PROC_NAME="silk_feishu_bot"

_is_running() {
    if [ ! -f "$PID_FILE" ]; then
        return 1
    fi
    local pid
    pid=$(cat "$PID_FILE")
    kill -0 "$pid" 2>/dev/null || return 1
    if [ -f "/proc/${pid}/cmdline" ]; then
        grep -q "$PROC_NAME" "/proc/${pid}/cmdline" 2>/dev/null
    else
        ps -p "$pid" -o args= 2>/dev/null | grep -q "$PROC_NAME"
    fi
}

_preflight_check() {
    local failed=false

    if ! "$PYTHON" -c "" 2>/dev/null; then
        echo "  [错误] Python 解释器不可用: $PYTHON"
        failed=true
    fi

    if [ ! -f "$PROJECT_DIR/config/config.yaml" ]; then
        echo "  [错误] 配置文件不存在，请参考 config/config.yaml.example 创建 config/config.yaml"
        failed=true
    fi

    if [ ! -f "$PROJECT_DIR/main.py" ]; then
        echo "  [错误] 主程序不存在: $PROJECT_DIR/main.py"
        failed=true
    fi

    [ "$failed" = false ]
}

do_start() {
    if _is_running; then
        echo "机器人已在运行中 (PID: $(cat "$PID_FILE"))"
        return 0
    fi
    echo "正在检查运行环境..."
    if ! _preflight_check; then
        echo "预检未通过，启动取消"
        return 1
    fi
    echo "正在启动 Silk 飞书机器人..."
    cd "$PROJECT_DIR"
    nohup bash -c "exec -a \"$PROC_NAME\" \"$PYTHON\" main.py" >> "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
    sleep 1
    if _is_running; then
        echo "启动成功 (PID: $(cat "$PID_FILE"))，日志: $LOG_FILE"
    else
        echo "启动失败，请查看日志: $LOG_FILE"
        rm -f "$PID_FILE"
        tail -20 "$LOG_FILE"
        return 1
    fi
}

do_stop() {
    if ! _is_running; then
        echo "机器人未在运行"
        rm -f "$PID_FILE"
        return 0
    fi
    local pid
    pid=$(cat "$PID_FILE")
    echo "正在停止机器人 (PID: $pid)..."
    kill "$pid"
    for i in $(seq 1 10); do
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "已停止"
            rm -f "$PID_FILE"
            return 0
        fi
        sleep 1
    done
    echo "进程未响应，强制终止..."
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$PID_FILE"
    echo "已强制停止"
}

do_status() {
    if _is_running; then
        echo "机器人正在运行 (PID: $(cat "$PID_FILE"))"
    else
        echo "机器人未在运行"
        rm -f "$PID_FILE"
    fi
}

do_log() {
    if [ ! -f "$LOG_FILE" ]; then
        echo "日志文件不存在"
        return 1
    fi
    tail -50 "$LOG_FILE"
}

case "${1:-}" in
    start)   do_start ;;
    stop)    do_stop ;;
    restart) do_stop; do_start ;;
    status)  do_status ;;
    log)     do_log ;;
    *)
        echo "用法: $0 {start|stop|restart|status|log}"
        exit 1
        ;;
esac
