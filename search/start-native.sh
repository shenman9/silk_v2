#!/bin/bash
# Silk Search Engine - Native Weaviate Startup Script
# 不使用Docker，直接运行原生Weaviate

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WEAVIATE_BIN="$SCRIPT_DIR/bin/weaviate"
DATA_DIR="$SCRIPT_DIR/weaviate_data"
LOG_FILE="$SCRIPT_DIR/weaviate.log"
PID_FILE="$SCRIPT_DIR/weaviate.pid"

# 从 .env 读取端口，默认 16701
SILK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ -f "$SILK_DIR/.env" ]; then
    source <(grep -E '^(WEAVIATE_HTTP_PORT|WEAVIATE_GRPC_PORT|WEAVIATE_API_KEY)=' "$SILK_DIR/.env")
fi
WEAVIATE_PORT=${WEAVIATE_HTTP_PORT:-16701}
WEAVIATE_GRPC_PORT=${WEAVIATE_GRPC_PORT:-50051}

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${BLUE}[Weaviate]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[✓]${NC} $1"
}

print_error() {
    echo -e "${RED}[✗]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[!]${NC} $1"
}

start_weaviate() {
    # 检查是否已经运行
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            print_warning "Weaviate 已经在运行 (PID: $PID)"
            return 0
        else
            rm -f "$PID_FILE"
        fi
    fi

    # 检查端口是否被占用
    if lsof -i :$WEAVIATE_PORT -sTCP:LISTEN > /dev/null 2>&1; then
        print_error "端口 $WEAVIATE_PORT 已被占用"
        return 1
    fi

    # 创建数据目录
    mkdir -p "$DATA_DIR"

    print_status "启动 Weaviate (端口: $WEAVIATE_PORT)..."

    # 设置环境变量并启动 Weaviate
    # 注意：原生运行时，向量化由客户端或外部API完成
    export PERSISTENCE_DATA_PATH="$DATA_DIR"
    export QUERY_DEFAULTS_LIMIT=25
    export AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true
    export DEFAULT_VECTORIZER_MODULE=none
    export CLUSTER_HOSTNAME=node1
    export LOG_LEVEL=info
    
    # 后台运行
    nohup "$WEAVIATE_BIN" \
        --host 0.0.0.0 \
        --port $WEAVIATE_PORT \
        --scheme http \
        > "$LOG_FILE" 2>&1 &
    
    echo $! > "$PID_FILE"
    
    # 等待启动
    print_status "等待 Weaviate 启动..."
    for i in {1..30}; do
        if curl -s "http://localhost:$WEAVIATE_PORT/v1/.well-known/ready" 2>/dev/null | grep -q "status"; then
            print_success "Weaviate 启动成功!"
            print_status "HTTP: http://localhost:$WEAVIATE_PORT"
            print_status "日志: $LOG_FILE"
            return 0
        fi
        sleep 1
        echo -n "."
    done
    echo ""
    print_error "Weaviate 启动超时，请检查日志: $LOG_FILE"
    cat "$LOG_FILE" | tail -20
    return 1
}

stop_weaviate() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            print_status "停止 Weaviate (PID: $PID)..."
            kill "$PID"
            sleep 2
            if kill -0 "$PID" 2>/dev/null; then
                kill -9 "$PID"
            fi
            rm -f "$PID_FILE"
            print_success "Weaviate 已停止"
        else
            print_warning "Weaviate 进程不存在"
            rm -f "$PID_FILE"
        fi
    else
        # 尝试通过端口查找
        PID=$(lsof -ti :$WEAVIATE_PORT 2>/dev/null || true)
        if [ -n "$PID" ]; then
            print_status "停止 Weaviate (PID: $PID)..."
            kill "$PID" 2>/dev/null || true
            sleep 1
            kill -9 "$PID" 2>/dev/null || true
            print_success "Weaviate 已停止"
        else
            print_warning "Weaviate 未运行"
        fi
    fi
}

status_weaviate() {
    echo -e "\n${BLUE}═══════════════════════════════════════${NC}"
    echo -e "${BLUE}  🔍 Weaviate 状态检查${NC}"
    echo -e "${BLUE}═══════════════════════════════════════${NC}\n"

    # 检查进程
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            echo -e "进程状态: ${GREEN}● 运行中${NC} (PID: $PID)"
        else
            echo -e "进程状态: ${RED}○ 已停止${NC} (PID文件存在但进程不存在)"
        fi
    else
        PID=$(lsof -ti :$WEAVIATE_PORT 2>/dev/null || true)
        if [ -n "$PID" ]; then
            echo -e "进程状态: ${GREEN}● 运行中${NC} (PID: $PID)"
        else
            echo -e "进程状态: ${RED}○ 未运行${NC}"
        fi
    fi

    # 健康检查
    HEALTH=$(curl -s "http://localhost:$WEAVIATE_PORT/v1/.well-known/ready" 2>/dev/null || echo "无法连接")
    if echo "$HEALTH" | grep -q '"status"'; then
        echo -e "健康检查: ${GREEN}✓ OK${NC}"
    else
        echo -e "健康检查: ${RED}✗ 失败${NC} ($HEALTH)"
    fi

    # 数据目录
    if [ -d "$DATA_DIR" ]; then
        SIZE=$(du -sh "$DATA_DIR" 2>/dev/null | cut -f1)
        echo -e "数据目录: $DATA_DIR ($SIZE)"
    fi

    echo ""
}

logs_weaviate() {
    if [ -f "$LOG_FILE" ]; then
        tail -f "$LOG_FILE"
    else
        print_warning "日志文件不存在: $LOG_FILE"
    fi
}

case "$1" in
    start)
        start_weaviate
        ;;
    stop)
        stop_weaviate
        ;;
    restart)
        stop_weaviate
        sleep 2
        start_weaviate
        ;;
    status)
        status_weaviate
        ;;
    logs)
        logs_weaviate
        ;;
    *)
        echo "用法: $0 {start|stop|restart|status|logs}"
        echo ""
        echo "  start   - 启动 Weaviate"
        echo "  stop    - 停止 Weaviate"
        echo "  restart - 重启 Weaviate"
        echo "  status  - 检查状态"
        echo "  logs    - 查看日志"
        exit 1
        ;;
esac

