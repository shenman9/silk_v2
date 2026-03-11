#!/bin/bash
#
# Silk Context Search - 一键启动脚本
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "🔍 Silk Context Search Engine"
echo "=============================="
echo ""

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo "❌ 请先安装 Docker"
    echo "   https://docs.docker.com/get-docker/"
    exit 1
fi

if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    echo "❌ 请先安装 Docker Compose"
    exit 1
fi

# 检查 Python
if ! command -v python3 &> /dev/null; then
    echo "❌ 请先安装 Python 3"
    exit 1
fi

case "${1:-help}" in
    start)
        echo "🚀 启动 Weaviate 服务..."
        docker-compose up -d
        
        echo ""
        echo "⏳ 等待服务就绪 (首次启动需要下载模型，约 2-5 分钟)..."
        
        for i in {1..60}; do
            if curl -s http://localhost:8008/v1/.well-known/ready > /dev/null 2>&1; then
                echo ""
                echo "✅ Weaviate 已就绪!"
                break
            fi
            printf "."
            sleep 5
        done
        
        echo ""
        echo "📦 初始化 Schema..."
        pip3 install -q -r requirements.txt
        python3 schema.py
        
        echo ""
        echo "=============================="
        echo "✅ 搜索引擎已启动!"
        echo ""
        echo "端口:"
        echo "  - Weaviate REST: http://localhost:8008"
        echo "  - Weaviate gRPC: localhost:50051"
        echo "  - Tika: http://localhost:9998"
        echo ""
        echo "下一步:"
        echo "  1. 索引内容: ./start.sh index"
        echo "  2. 启动监视: ./start.sh watch"
        echo "  3. 测试搜索: ./start.sh search '你的查询'"
        ;;
    
    stop)
        echo "🛑 停止服务..."
        docker-compose down
        echo "✅ 已停止"
        ;;
    
    index)
        CHAT_HISTORY="${2:-../backend/chat_history}"
        SESSION_ID="${3:-default}"
        
        echo "📚 索引目录: $CHAT_HISTORY"
        python3 indexer.py --index "$CHAT_HISTORY" --session "$SESSION_ID"
        ;;
    
    watch)
        CHAT_HISTORY="${2:-../backend/chat_history}"
        
        echo "👁️  监视目录: $CHAT_HISTORY"
        echo "   按 Ctrl+C 停止"
        python3 indexer.py --watch "$CHAT_HISTORY"
        ;;
    
    search)
        if [ -z "$2" ]; then
            echo "用法: ./start.sh search '查询内容'"
            exit 1
        fi
        
        python3 indexer.py --search "$2"
        ;;
    
    status)
        echo "📊 服务状态:"
        docker-compose ps
        
        echo ""
        echo "📈 索引统计:"
        curl -s http://localhost:8008/v1/nodes 2>/dev/null | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    for node in data.get('nodes', []):
        print(f\"  节点: {node.get('name', 'unknown')}\")
        for shard in node.get('shards', []):
            print(f\"    - {shard.get('class', '?')}: {shard.get('objectCount', 0)} 条记录\")
except:
    print('  无法获取统计信息')
" || echo "  Weaviate 未运行"
        ;;
    
    logs)
        docker-compose logs -f "${2:-weaviate}"
        ;;
    
    reset)
        echo "⚠️  这将删除所有索引数据!"
        read -p "确定要继续吗? (y/N) " confirm
        if [ "$confirm" = "y" ] || [ "$confirm" = "Y" ]; then
            docker-compose down -v
            rm -rf weaviate_data
            echo "✅ 已重置"
        fi
        ;;
    
    help|*)
        echo "用法: ./start.sh <命令>"
        echo ""
        echo "命令:"
        echo "  start   - 启动搜索服务"
        echo "  stop    - 停止搜索服务"
        echo "  index   - 索引聊天历史 (一次性)"
        echo "  watch   - 启动实时索引监视"
        echo "  search  - 执行搜索测试"
        echo "  status  - 查看服务状态"
        echo "  logs    - 查看日志"
        echo "  reset   - 重置所有数据"
        echo ""
        echo "示例:"
        echo "  ./start.sh start"
        echo "  ./start.sh index ../backend/chat_history"
        echo "  ./start.sh search '如何使用 AI'"
        ;;
esac

