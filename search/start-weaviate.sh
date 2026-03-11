#!/bin/bash

# Weaviate 本地启动脚本（无需 Docker）
# 
# 用法:
#   ./start-weaviate.sh        # 前台启动
#   ./start-weaviate.sh start  # 后台启动
#   ./start-weaviate.sh stop   # 停止
#   ./start-weaviate.sh status # 检查状态

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WEAVIATE_BIN="$SCRIPT_DIR/bin/weaviate"
DATA_DIR="$SCRIPT_DIR/weaviate_data"
LOG_FILE="$SCRIPT_DIR/weaviate.log"
PID_FILE="$SCRIPT_DIR/weaviate.pid"

# Weaviate 配置
export PERSISTENCE_DATA_PATH="$DATA_DIR"
export AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true
export QUERY_DEFAULTS_LIMIT=25
export DEFAULT_VECTORIZER_MODULE=none
export CLUSTER_HOSTNAME=node1
export CLUSTER_GOSSIP_BIND_PORT=7100
export CLUSTER_DATA_BIND_PORT=7101

# 端口配置
HTTP_PORT=8007
GRPC_PORT=50051

# 检查并修复数据目录权限
fix_data_dir_permissions() {
    if [ ! -d "$DATA_DIR" ]; then
        mkdir -p "$DATA_DIR"
        return 0
    fi
    
    # 检查当前用户
    CURRENT_USER=$(whoami)
    CURRENT_UID=$(id -u)
    
    # 检查目录所有者
    DIR_OWNER=$(stat -c '%U' "$DATA_DIR" 2>/dev/null || stat -f '%Su' "$DATA_DIR" 2>/dev/null)
    DIR_UID=$(stat -c '%u' "$DATA_DIR" 2>/dev/null || stat -f '%u' "$DATA_DIR" 2>/dev/null)
    
    # 如果所有者不是当前用户，尝试修复
    if [ "$DIR_UID" != "$CURRENT_UID" ]; then
        echo "🔧 检测到权限问题，正在修复数据目录权限..."
        echo "   目录: $DATA_DIR"
        echo "   当前所有者: $DIR_OWNER"
        echo "   需要所有者: $CURRENT_USER"
        
        # 尝试使用 sudo 修复权限
        if sudo chown -R "$CURRENT_USER:$CURRENT_USER" "$DATA_DIR" 2>/dev/null; then
            echo "✅ 权限已修复"
            return 0
        else
            echo "❌ 无法自动修复权限（需要 sudo 权限）"
            echo ""
            echo "请手动运行以下命令修复权限:"
            echo "  sudo chown -R $CURRENT_USER:$CURRENT_USER $DATA_DIR"
            return 1
        fi
    fi
    
    # 检查子目录权限（特别是 raft/snapshots）
    if [ -d "$DATA_DIR/raft/snapshots" ]; then
        SNAPSHOT_OWNER=$(stat -c '%U' "$DATA_DIR/raft/snapshots" 2>/dev/null || stat -f '%Su' "$DATA_DIR/raft/snapshots" 2>/dev/null)
        SNAPSHOT_UID=$(stat -c '%u' "$DATA_DIR/raft/snapshots" 2>/dev/null || stat -f '%u' "$DATA_DIR/raft/snapshots" 2>/dev/null)
        
        if [ "$SNAPSHOT_UID" != "$CURRENT_UID" ]; then
            echo "🔧 检测到 raft/snapshots 目录权限问题，正在修复..."
            if sudo chown -R "$CURRENT_USER:$CURRENT_USER" "$DATA_DIR" 2>/dev/null; then
                echo "✅ 权限已修复"
                return 0
            else
                echo "❌ 无法自动修复权限（需要 sudo 权限）"
                echo ""
                echo "请手动运行以下命令修复权限:"
                echo "  sudo chown -R $CURRENT_USER:$CURRENT_USER $DATA_DIR"
                return 1
            fi
        fi
    fi
    
    return 0
}

case "$1" in
    start)
        if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
            echo "⚠️  Weaviate 已在运行 (PID: $(cat $PID_FILE))"
            exit 0
        fi
        
        # 检查并修复权限
        if ! fix_data_dir_permissions; then
            exit 1
        fi
        
        echo "🚀 启动 Weaviate..."
        echo "   HTTP: http://localhost:$HTTP_PORT"
        echo "   gRPC: localhost:$GRPC_PORT"
        echo "   数据: $DATA_DIR"
        echo "   日志: $LOG_FILE"
        
        nohup "$WEAVIATE_BIN" \
            --host 0.0.0.0 \
            --port $HTTP_PORT \
            --scheme http \
            > "$LOG_FILE" 2>&1 &
        
        echo $! > "$PID_FILE"
        
        # 等待启动
        echo -n "   等待启动"
        for i in {1..30}; do
            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
            if [ "$HTTP_CODE" = "200" ]; then
                echo ""
                echo "✅ Weaviate 已就绪！"
                exit 0
            fi
            echo -n "."
            sleep 1
        done
        
        echo ""
        echo "⚠️  Weaviate 启动超时，请检查日志: $LOG_FILE"
        ;;
        
    stop)
        if [ -f "$PID_FILE" ]; then
            PID=$(cat "$PID_FILE")
            if kill -0 $PID 2>/dev/null; then
                echo "🛑 停止 Weaviate (PID: $PID)..."
                kill $PID
                rm -f "$PID_FILE"
                echo "✅ 已停止"
            else
                echo "⚠️  进程不存在，清理 PID 文件"
                rm -f "$PID_FILE"
            fi
        else
            echo "⚠️  Weaviate 未运行"
        fi
        ;;
        
    status)
        if [ -f "$PID_FILE" ] && kill -0 $(cat "$PID_FILE") 2>/dev/null; then
            PID=$(cat "$PID_FILE")
            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
            if [ "$HTTP_CODE" = "200" ]; then
                echo "✅ Weaviate 运行中 (PID: $PID)"
                echo "   HTTP: http://localhost:$HTTP_PORT"
            else
                echo "⚠️  Weaviate 进程存在但未就绪 (PID: $PID, HTTP: $HTTP_CODE)"
            fi
        else
            echo "❌ Weaviate 未运行"
        fi
        ;;
        
    restart)
        $0 stop
        sleep 2
        $0 start
        ;;
        
    *)
        # 前台运行
        # 检查并修复权限
        if ! fix_data_dir_permissions; then
            exit 1
        fi
        
        echo "🚀 启动 Weaviate (前台模式)..."
        echo "   HTTP: http://localhost:$HTTP_PORT"
        echo "   gRPC: localhost:$GRPC_PORT"
        echo "   数据: $DATA_DIR"
        echo ""
        echo "按 Ctrl+C 停止"
        echo ""
        
        "$WEAVIATE_BIN" \
            --host 0.0.0.0 \
            --port $HTTP_PORT \
            --scheme http
        ;;
esac

