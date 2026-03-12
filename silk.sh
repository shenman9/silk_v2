#!/bin/bash

# ============================================================
# Silk 系统管理脚本
# ============================================================
# 用法:
#   ./silk.sh deploy   - 一键部署 (构建全部 + 启动，含 Weaviate)
#   ./silk.sh start    - 启动所有服务 (后端 + 前端 + Weaviate)
#   ./silk.sh stop     - 停止所有服务
#   ./silk.sh restart  - 重启所有服务
#   ./silk.sh logs     - 查看日志
#   ./silk.sh build    - 构建前端 (WebApp)
#   ./silk.sh build-apk - 构建 Android APK
#   ./silk.sh build-all - 构建全部 (WebApp + APK)
#   ./silk.sh status   - 检查所有服务状态
#   ./silk.sh weaviate - Weaviate 管理 (start/stop/status/schema)
# ============================================================


# Java Home - 支持 macOS Homebrew 和 Linux
if [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
elif [ -d "/usr/lib/jvm/java-17-openjdk" ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
else
    export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java) 2>/dev/null || echo "/usr/bin/java") 2>/dev/null) 2>/dev/null)
fi
export PATH=$JAVA_HOME/bin:$PATH

# Android SDK - 支持 macOS Homebrew、Linux 和 Android Studio
if [ -d "/opt/homebrew/share/android-commandlinetools" ]; then
    export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
elif [ -d "/usr/lib/android-sdk" ]; then
    export ANDROID_HOME=/usr/lib/android-sdk
fi
if [ -n "$ANDROID_HOME" ]; then
    export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
fi

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color

# 项目目录
SILK_DIR="$(cd "$(dirname "$0")" && pwd)"

# 加载 .env（API Key、BACKEND_HOST 等，构建 APK 时会用到）
if [ -f "$SILK_DIR/.env" ]; then
    # 转换 CRLF 为 LF 并加载
    TMP_ENV=$(mktemp)
    tr -d '\r' < "$SILK_DIR/.env" > "$TMP_ENV"
    set -a
    source "$TMP_ENV"
    set +a
    rm -f "$TMP_ENV"
fi

# 端口定义
BACKEND_PORT=8006
FRONTEND_PORT=8005
WEAVIATE_HTTP_PORT=8008
WEAVIATE_GRPC_PORT=50051

# Weaviate URL 处理：优先使用 .env 中的 WEAVIATE_URL，否则使用本地
WEAVIATE_CHECK_URL="${WEAVIATE_URL:-http://localhost:$WEAVIATE_HTTP_PORT}"
# 判断是否使用远程 Weaviate
WEAVIATE_IS_REMOTE=false
if [ -n "$WEAVIATE_URL" ] && [[ ! "$WEAVIATE_URL" =~ localhost|127\.0\.0\.1 ]]; then
    WEAVIATE_IS_REMOTE=true
fi
# APK 输出目录
APK_OUTPUT_DIR="$SILK_DIR/backend/static"

# ============================================================
# 辅助函数
# ============================================================

print_header() {
    echo ""
    echo -e "${CYAN}════════════════════════════════════════════════════════════${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}════════════════════════════════════════════════════════════${NC}"
}

print_status() {
    if [ "$2" == "running" ]; then
        echo -e "  $1: ${GREEN}● 运行中${NC} $3"
    elif [ "$2" == "stopped" ]; then
        echo -e "  $1: ${RED}○ 已停止${NC}"
    else
        echo -e "  $1: ${YELLOW}? 未知${NC}"
    fi
}

check_port() {
    lsof -i:$1 -sTCP:LISTEN > /dev/null 2>&1
    return $?
}

get_pid_on_port() {
    lsof -ti:$1 2>/dev/null
}

# 自动清理端口占用
kill_port_process() {
    local port=$1
    local service_name=$2
    local force=${3:-false}
    
    if check_port $port; then
        local PID=$(get_pid_on_port $port)
        local PROC_NAME=$(ps -p $PID -o comm= 2>/dev/null || echo "unknown")
        
        if [ "$force" == "true" ]; then
            echo -e "  ${YELLOW}⚠ 端口 $port 被 $PROC_NAME (PID: $PID) 占用，正在终止...${NC}"
            kill -9 $PID 2>/dev/null
            sleep 1
            if check_port $port; then
                echo -e "  ${RED}❌ 无法释放端口 $port${NC}"
                return 1
            fi
            echo -e "  ${GREEN}✓ 端口 $port 已释放${NC}"
        else
            echo -e "  ${YELLOW}⚠ 端口 $port 被 $PROC_NAME (PID: $PID) 占用${NC}"
            echo -n "  是否终止? [y/N]: "
            read -t 5 answer
            if [ "$answer" == "y" ] || [ "$answer" == "Y" ]; then
                kill -9 $PID 2>/dev/null
                sleep 1
                echo -e "  ${GREEN}✓ 端口已释放${NC}"
            else
                echo -e "  ${RED}✗ 跳过${NC}"
                return 1
            fi
        fi
    fi
    return 0
}

# 清理所有服务端口
kill_all_ports() {
    local force=${1:-false}
    echo -e "${BLUE}检查端口冲突...${NC}"
    
    kill_port_process $BACKEND_PORT "Silk Backend" $force
    kill_port_process $FRONTEND_PORT "Silk Frontend" $force
    kill_port_process $WEAVIATE_HTTP_PORT "Weaviate HTTP" $force
    kill_port_process $WEAVIATE_GRPC_PORT "Weaviate gRPC" $force
}

# ============================================================
# Weaviate 管理
# ============================================================

weaviate_status() {
    echo -e "${BLUE}【Weaviate】${NC} (HTTP: $WEAVIATE_HTTP_PORT, gRPC: $WEAVIATE_GRPC_PORT)"
    
    # 检查 Docker 容器
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "silk-weaviate"; then
        local CONTAINER_STATUS=$(docker ps --format '{{.Status}}' --filter "name=silk-weaviate")
        echo -e "  Docker 容器: ${GREEN}● 运行中${NC} ($CONTAINER_STATUS)"
        
        # 检查就绪状态
        local READY=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
        if [ "$READY" == "200" ]; then
            echo -e "  就绪状态: ${GREEN}✓ Ready${NC}"
        else
            echo -e "  就绪状态: ${YELLOW}⏳ Not Ready${NC}"
        fi
        
        # 显示版本
        local VERSION=$(curl -s "http://localhost:$WEAVIATE_HTTP_PORT/v1/meta" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('version','?'))" 2>/dev/null)
        echo -e "  版本: $VERSION"
    else
        # 检查本地进程
        if check_port $WEAVIATE_HTTP_PORT; then
            local PID=$(get_pid_on_port $WEAVIATE_HTTP_PORT)
            echo -e "  本地进程: ${GREEN}● 运行中${NC} (PID: $PID)"
        else
            echo -e "  状态: ${RED}○ 未运行${NC}"
        fi
    fi
}

weaviate_start() {
    echo -e "${BLUE}启动 Weaviate...${NC}"
    
    # 检查 .env 中是否配置了远程 WEAVIATE_URL
    if [ -n "$WEAVIATE_URL" ]; then
        # 提取 host:port (去掉 http:// 前缀)
        local WEAVIATE_ENDPOINT="${WEAVIATE_URL#http://}"
        WEAVIATE_ENDPOINT="${WEAVIATE_ENDPOINT#https://}"
        
        # 检查远程 Weaviate 是否可用
        local READY=$(curl -s -o /dev/null -w "%{http_code}" "$WEAVIATE_URL/v1/.well-known/ready" 2>/dev/null)
        if [ "$READY" == "200" ]; then
            echo -e "  ${GREEN}✓ 远程 Weaviate 已就绪: $WEAVIATE_URL${NC}"
            return 0
        else
            echo -e "  ${YELLOW}⚠ 远程 Weaviate ($WEAVIATE_URL) 不可用 (HTTP $READY)${NC}"
            echo -e "  ${YELLOW}  将启动本地 Weaviate...${NC}"
        fi
    fi
    
    # 检查本地是否已运行且就绪
    if check_port $WEAVIATE_HTTP_PORT; then
        local READY=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
        if [ "$READY" == "200" ]; then
            echo -e "  ${GREEN}✓ 本地 Weaviate 已就绪，跳过启动${NC}"
            return 0
        else
            echo -e "  ${YELLOW}⚠ 端口 $WEAVIATE_HTTP_PORT 被占用但不是 Weaviate，尝试清理...${NC}"
            docker rm -f silk-weaviate 2>/dev/null
            kill_port_process $WEAVIATE_HTTP_PORT "Weaviate HTTP" true
            kill_port_process $WEAVIATE_GRPC_PORT "Weaviate gRPC" true
            sleep 2
        fi
    fi
    
    # 清理可能存在的旧容器
    docker rm -f silk-weaviate 2>/dev/null
    
    # 使用 Docker 直接启动 (避免 docker-compose 版本问题)
    if command -v docker &> /dev/null; then
        echo -e "  使用 Docker 启动 Weaviate (端口 $WEAVIATE_HTTP_PORT)..."
        
        docker run -d --name silk-weaviate \
            --restart unless-stopped \
            -p $WEAVIATE_HTTP_PORT:8080 \
            -p $WEAVIATE_GRPC_PORT:50051 \
            -v "$SILK_DIR/search/weaviate_data:/var/lib/weaviate" \
            -e QUERY_DEFAULTS_LIMIT=25 \
            -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true \
            -e PERSISTENCE_DATA_PATH=/var/lib/weaviate \
            -e DEFAULT_VECTORIZER_MODULE=none \
            -e CLUSTER_HOSTNAME=node1 \
            semitechnologies/weaviate:1.27.0
        
        if [ $? -eq 0 ]; then
            echo -e "  ${GREEN}✓ Weaviate 容器启动中...${NC}"
            
            # 等待就绪
            for i in {1..30}; do
                sleep 2
                local READY=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
                if [ "$READY" == "200" ]; then
                    echo -e "  ${GREEN}✓ Weaviate 就绪！${NC}"
                    
                    # 自动创建 schema
                    weaviate_schema
                    return 0
                fi
                echo -n "."
            done
            echo -e "  ${YELLOW}⚠ Weaviate 启动超时${NC}"
            return 1
        fi
    fi
    
    echo -e "  ${RED}✗ 无法启动 Weaviate${NC}"
    return 1
}

weaviate_stop() {
    echo -e "${BLUE}停止 Weaviate...${NC}"
    
    # 停止 Docker 容器
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "silk-weaviate"; then
        cd "$SILK_DIR/search"
        docker-compose down 2>/dev/null
        echo -e "  ${GREEN}✓ Docker 容器已停止${NC}"
    fi
    
    # 停止本地进程
    if check_port $WEAVIATE_HTTP_PORT; then
        local PID=$(get_pid_on_port $WEAVIATE_HTTP_PORT)
        kill -9 $PID 2>/dev/null
        echo -e "  ${GREEN}✓ 本地进程已停止 (PID: $PID)${NC}"
    fi
}

weaviate_schema() {
    echo -e "${BLUE}初始化 Weaviate Schema...${NC}"
    
    # 确定要检查的 Weaviate URL
    local CHECK_URL="${WEAVIATE_URL:-http://localhost:$WEAVIATE_HTTP_PORT}"
    
    # 检查是否已就绪
    local READY=$(curl -s -o /dev/null -w "%{http_code}" "$CHECK_URL/v1/.well-known/ready" 2>/dev/null)
    if [ "$READY" != "200" ]; then
        echo -e "  ${YELLOW}⚠ Weaviate ($CHECK_URL) 未就绪，跳过 Schema 初始化${NC}"
        return 1
    fi
    
    # 检查 Schema 是否已存在
    local SCHEMA=$(curl -s "$CHECK_URL/v1/schema" 2>/dev/null)
    if echo "$SCHEMA" | grep -q "SilkContext"; then
        echo -e "  ${GREEN}✓ Schema 已存在${NC}"
        return 0
    fi
    
    # 运行 schema.py (会使用环境变量 WEAVIATE_URL)
    if [ -f "$SILK_DIR/search/schema.py" ]; then
        cd "$SILK_DIR/search"
        # 传递 WEAVIATE_URL 给 schema.py
        WEAVIATE_URL="$CHECK_URL" python3 schema.py > /tmp/weaviate_schema.log 2>&1
        if [ $? -eq 0 ]; then
            echo -e "  ${GREEN}✓ Schema 创建成功${NC}"
        else
            echo -e "  ${YELLOW}⚠ Schema 创建失败，查看 /tmp/weaviate_schema.log${NC}"
        fi
    fi
}

weaviate_manage() {
    local action=${1:-status}
    
    print_header "🔮 Weaviate 管理"
    
    case "$action" in
        start)
            weaviate_start
            ;;
        stop)
            weaviate_stop
            ;;
        restart)
            weaviate_stop
            sleep 2
            weaviate_start
            ;;
        status)
            weaviate_status
            ;;
        schema)
            weaviate_schema
            ;;
        *)
            echo "用法: $0 weaviate {start|stop|restart|status|schema}"
            ;;
    esac
}

# ============================================================
# 状态检查
# ============================================================

check_status() {
    print_header "🔍 Silk 系统状态检查"
    
    echo ""
    echo -e "${BLUE}【Silk 后端】${NC} (端口 $BACKEND_PORT)"
    if check_port $BACKEND_PORT; then
        PID=$(get_pid_on_port $BACKEND_PORT)
        print_status "Silk Backend" "running" "(PID: $PID)"
        
        # 测试健康检查
        HEALTH=$(curl -s http://localhost:$BACKEND_PORT/health 2>/dev/null)
        if [ -n "$HEALTH" ]; then
            echo -e "    健康检查: ${GREEN}✓ OK${NC}"
        fi
    else
        print_status "Silk Backend" "stopped"
    fi
    
    echo ""
    echo -e "${BLUE}【Silk 前端】${NC} (端口 $FRONTEND_PORT)"
    if check_port $FRONTEND_PORT; then
        PID=$(get_pid_on_port $FRONTEND_PORT)
        print_status "Silk Frontend" "running" "(PID: $PID)"
    else
        print_status "Silk Frontend" "stopped"
    fi
    
    # Weaviate 状态
    echo ""
    weaviate_status
    
    # 检查 APK 文件
    echo ""
    echo -e "${BLUE}【APK 文件】${NC}"
    APK_FILE=$(ls -t $APK_OUTPUT_DIR/*.apk 2>/dev/null | head -1)
    if [ -n "$APK_FILE" ]; then
        APK_SIZE=$(du -h "$APK_FILE" | cut -f1)
        APK_TIME=$(stat -c %y "$APK_FILE" 2>/dev/null | cut -d. -f1)
        echo -e "  ${GREEN}✓$APK_SIZE${NC} - $APK_TIME"
        echo -e "  路径: $APK_FILE"
    else
        echo -e "  ${YELLOW}○ 未找到 APK 文件${NC}"
        echo -e "  运行 './silk.sh build-apk' 构建"
    fi
    
    echo ""
}

# ============================================================
# 构建前端 (WebApp)
# ============================================================

build_frontend() {
    print_header "🔨 构建前端 (WebApp)"
    
    echo ""
    echo -e "${BLUE}正在构建前端生产版本...${NC}"
    cd "$SILK_DIR"
    ./gradlew :frontend:webApp:browserProductionWebpack
    
    if [ $? -eq 0 ]; then
        echo ""
        echo -e "${GREEN}✅ 前端构建成功${NC}"
        echo -e "  输出目录: $SILK_DIR/frontend/webApp/build/dist/js/productionExecutable"
        
        # 复制到 backend/static 目录
        echo ""
        echo -e "${BLUE}复制到 backend/static 目录...${NC}"
        cp -r $SILK_DIR/frontend/webApp/build/dist/js/productionExecutable/* $SILK_DIR/backend/static/
        echo -e "${GREEN}✅ 已更新 backend/static${NC}"
    else
        echo ""
        echo -e "${RED}❌ 前端构建失败${NC}"
        return 1
    fi
}

# ============================================================
# 构建 Android APK
# ============================================================

build_apk() {
    print_header "📱 构建 Android APK"
    
    # 从 .env 取后端地址并传给 Gradle（避免 daemon 未继承环境变量）
    if [ -n "$BACKEND_BASE_URL" ]; then
        APK_BACKEND_URL="$BACKEND_BASE_URL"
    elif [ -n "$BACKEND_HOST" ]; then
        APK_BACKEND_URL="http://${BACKEND_HOST}:${BACKEND_HTTP_PORT:-8006}"
    else
        APK_BACKEND_URL="http://10.0.2.2:8006"
    fi
    echo -e "  后端地址将注入 APK: ${CYAN}$APK_BACKEND_URL${NC}"
    
    echo ""
    echo -e "${BLUE}正在构建 Android APK (Debug)...${NC}"
    cd "$SILK_DIR"
    ./gradlew -PBACKEND_BASE_URL="$APK_BACKEND_URL" :frontend:androidApp:assembleDebug
    
    if [ $? -eq 0 ]; then
        # 找到生成的 APK
        APK_FILE=$(ls -t $SILK_DIR/frontend/androidApp/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
        
        if [ -n "$APK_FILE" ]; then
            APK_SIZE=$(du -h "$APK_FILE" | cut -f1)
            APK_NAME=$(basename "$APK_FILE")
            
            echo ""
            echo -e "${GREEN}✅ APK 构建成功${NC}"
            echo -e "  大小: $APK_SIZE"
            echo -e "  路径: $APK_FILE"
            
            # 复制到 backend/static 目录供下载
            echo ""
            echo -e "${BLUE}复制到 backend/static 目录供下载...${NC}"
            cp "$APK_FILE" "$APK_OUTPUT_DIR/$APK_NAME"
            echo -e "${GREEN}✅ 已复制到: $APK_OUTPUT_DIR/$APK_NAME${NC}"
            
            # 创建符号链接 silk.apk
            ln -sf "$APK_OUTPUT_DIR/$APK_NAME" "$APK_OUTPUT_DIR/silk.apk"
            echo -e "${GREEN}✅ 已创建链接: $APK_OUTPUT_DIR/silk.apk${NC}"
        else
            echo ""
            echo -e "${RED}❌ 未找到生成的 APK 文件${NC}"
            return 1
        fi
    else
        echo ""
        echo -e "${RED}❌ APK 构建失败${NC}"
        return 1
    fi
}

# ============================================================
# 构建全部
# ============================================================

build_all() {
    print_header "🔨 构建全部 (WebApp + APK)"
    
    # 1. 构建前端
    echo ""
    echo -e "${BLUE}[1/2] 构建前端...${NC}"
    build_frontend
    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ 前端构建失败，终止${NC}"
        return 1
    fi
    
    # 2. 构建 APK
    echo ""
    echo -e "${BLUE}[2/2] 构建 APK...${NC}"
    build_apk
    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ APK 构建失败${NC}"
        return 1
    fi
    
    echo ""
    echo -e "${GREEN}✅ 全部构建完成！${NC}"
}

# ============================================================
# 一键部署
# ============================================================

deploy() {
    print_header "🚀 一键部署 Silk 系统"
    
    # 1. 清理端口冲突 (强制)
    echo ""
    echo -e "${BLUE}[1/4] 清理端口冲突...${NC}"
    kill_all_ports true
    sleep 2
    
    # 2. 构建全部
    echo ""
    echo -e "${BLUE}[2/4] 构建全部...${NC}"
    build_all
    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ 构建失败，终止部署${NC}"
        return 1
    fi
    
    # 3. 启动 Weaviate
    echo ""
    echo -e "${BLUE}[3/4] 启动 Weaviate...${NC}"
    weaviate_start
    sleep 2
    
    # 4. 启动后端和前端
    echo ""
    echo -e "${BLUE}[4/4] 启动后端和前端...${NC}"
    start_services_internal
}

# ============================================================
# 启动服务 (内部函数)
# ============================================================

start_services_internal() {
    # 启动后端
    echo ""
    echo -e "${BLUE}启动 Silk 后端...${NC}"
    cd "$SILK_DIR"
    nohup ./gradlew :backend:run > /tmp/silk_backend.log 2>&1 &
    echo -e "  ${GREEN}后端启动命令已执行${NC}"
    echo -e "  日志: /tmp/silk_backend.log"
    
    # 启动前端
    echo ""
    echo -e "${BLUE}启动 Silk 前端...${NC}"
    STATIC_DIR="$SILK_DIR/frontend/webApp/build/dist/js/productionExecutable"
    
    if [ ! -f "$STATIC_DIR/webApp.js" ]; then
        echo -e "  ${YELLOW}前端文件不存在，请先运行: ./silk.sh build${NC}"
        return 1
    fi
    
    cd "$STATIC_DIR"
    nohup python3 -m http.server $FRONTEND_PORT --bind 0.0.0.0 > /tmp/silk_frontend.log 2>&1 &
    echo -e "  ${GREEN}前端静态服务器已启动${NC}"
    echo -e "  日志: /tmp/silk_frontend.log"
    
    echo ""
    echo -e "${CYAN}等待服务就绪...${NC}"
    
    for i in {1..12}; do
        sleep 5
        if check_port $BACKEND_PORT && check_port $FRONTEND_PORT; then
            echo ""
            echo -e "${GREEN}✅ 部署完成！${NC}"
            echo ""
            local HOST="${BACKEND_HOST:-localhost}"
            echo -e "  本机访问:"
            echo -e "    后端 API: ${GREEN}http://localhost:$BACKEND_PORT${NC}"
            echo -e "    前端 Web: ${GREEN}http://localhost:$FRONTEND_PORT${NC}"
            echo -e "  ${CYAN}其他设备访问（请用 .env 中 BACKEND_HOST 或本机 IP）:${NC}"
            echo -e "    前端: ${GREEN}http://$HOST:$FRONTEND_PORT${NC}"
            echo -e "    APK 下载: ${GREEN}http://$HOST:$BACKEND_PORT/api/files/download-apk${NC}"
            echo -e "  Weaviate: http://localhost:$WEAVIATE_HTTP_PORT"
            echo ""
            if [ "$HOST" = "localhost" ] || [ -z "$BACKEND_HOST" ]; then
                echo -e "  ${YELLOW}提示: 若需手机/其他电脑访问，请在 .env 中设置 BACKEND_HOST=本机IP，并重新 build-apk。${NC}"
            fi
            echo ""
            return 0
        fi
        echo -n "."
    done
    
    echo ""
    echo -e "${YELLOW}⚠ 服务可能仍在启动中，请运行 './silk.sh status' 检查${NC}"
}

# ============================================================
# 启动服务
# ============================================================

start_services() {
    print_header "🚀 启动 Silk 系统"
    
    # 检查端口冲突
    echo ""
    echo -e "${BLUE}检查端口冲突...${NC}"
    if check_port $BACKEND_PORT || check_port $FRONTEND_PORT || check_port $WEAVIATE_HTTP_PORT; then
        echo -e "  ${YELLOW}检测到端口占用，是否自动清理? [y/N]: ${NC}"
        read -t 5 answer
        if [ "$answer" == "y" ] || [ "$answer" == "Y" ]; then
            kill_all_ports true
            sleep 2
        fi
    fi
    
    # 启动 Weaviate
    echo ""
    echo -e "${BLUE}[1/3] 启动 Weaviate...${NC}"
    if check_port $WEAVIATE_HTTP_PORT; then
        echo -e "  ${YELLOW}Weaviate 已在运行${NC}"
    else
        weaviate_start
    fi
    
    # 启动后端
    echo ""
    echo -e "${BLUE}[2/3] 启动 Silk 后端...${NC}"
    if check_port $BACKEND_PORT; then
        echo -e "  ${YELLOW}后端已在运行${NC}"
    else
        cd "$SILK_DIR"
        nohup ./gradlew :backend:run > /tmp/silk_backend.log 2>&1 &
        echo -e "  ${GREEN}后端启动命令已执行${NC}"
        echo -e "  日志: /tmp/silk_backend.log"
    fi
    
    # 启动前端 (使用预编译的生产版本 + Python静态服务器)
    echo ""
    echo -e "${BLUE}[3/3] 启动 Silk 前端...${NC}"
    if check_port $FRONTEND_PORT; then
        echo -e "  ${YELLOW}前端已在运行${NC}"
    else
        cd "$SILK_DIR"
        STATIC_DIR="$SILK_DIR/frontend/webApp/build/dist/js/productionExecutable"
        
        # 检查是否有预编译的文件
        if [ -f "$STATIC_DIR/webApp.js" ]; then
            echo -e "  ${GREEN}使用预编译的生产版本${NC}"
        else
            echo -e "  ${YELLOW}预编译文件不存在，正在构建...${NC}"
            ./gradlew :frontend:webApp:browserProductionWebpack > /tmp/silk_frontend_build.log 2>&1
            if [ $? -ne 0 ]; then
                echo -e "  ${RED}前端构建失败，请检查 /tmp/silk_frontend_build.log${NC}"
                return 1
            fi
            echo -e "  ${GREEN}前端构建完成${NC}"
        fi
        
        # 使用Python静态服务器提供服务
        cd "$STATIC_DIR"
        nohup python3 -m http.server $FRONTEND_PORT --bind 0.0.0.0 > /tmp/silk_frontend.log 2>&1 &
        echo -e "  ${GREEN}前端静态服务器已启动${NC}"
        echo -e "  日志: /tmp/silk_frontend.log"
    fi
    
    echo ""
    echo -e "${CYAN}启动完成！等待服务就绪...${NC}"
    echo ""
    
    # 等待并检查状态
    sleep 5
    echo -e "${YELLOW}正在检查服务状态...${NC}"
    
    for i in {1..12}; do
        BACKEND_UP=false
        FRONTEND_UP=false
        WEAVIATE_UP=false
        
        check_port $BACKEND_PORT && BACKEND_UP=true
        check_port $FRONTEND_PORT && FRONTEND_UP=true
        check_port $WEAVIATE_HTTP_PORT && WEAVIATE_UP=true
        
        if $BACKEND_UP && $FRONTEND_UP && $WEAVIATE_UP; then
            echo ""
            echo -e "${GREEN}✅ 所有服务已就绪！${NC}"
            echo ""
            echo -e "  后端: ${GREEN}http://localhost:$BACKEND_PORT${NC}"
            echo -e "  前端: ${GREEN}http://localhost:$FRONTEND_PORT${NC}"
            echo -e "  Weaviate: ${GREEN}http://localhost:$WEAVIATE_HTTP_PORT${NC}"
            echo ""
            return 0
        fi
        
        echo -n "."
        sleep 5
    done
    
    echo ""
    echo -e "${YELLOW}⚠ 部分服务可能仍在启动中，请运行 './silk.sh status' 检查${NC}"
}

# ============================================================
# 停止服务
# ============================================================

stop_services() {
    print_header "🛑 停止 Silk 系统"
    
    echo ""
    echo -e "${BLUE}[1/3] 停止 Silk 后端...${NC}"
    if check_port $BACKEND_PORT; then
        PID=$(get_pid_on_port $BACKEND_PORT)
        kill -9 $PID 2>/dev/null
        echo -e "  ${GREEN}后端已停止 (PID: $PID)${NC}"
    else
        echo -e "  ${YELLOW}后端未运行${NC}"
    fi
    
    echo ""
    echo -e "${BLUE}[2/3] 停止 Silk 前端...${NC}"
    if check_port $FRONTEND_PORT; then
        PID=$(get_pid_on_port $FRONTEND_PORT)
        kill -9 $PID 2>/dev/null
        echo -e "  ${GREEN}前端已停止 (PID: $PID)${NC}"
    else
        echo -e "  ${YELLOW}前端未运行${NC}"
    fi
    
    echo ""
    echo -e "${BLUE}[3/3] 停止 Weaviate...${NC}"
    weaviate_stop
    
    echo ""
    echo -e "${GREEN}✅ 所有服务已停止${NC}"
    echo ""
}

# ============================================================
# 重启服务
# ============================================================

restart_services() {
    print_header "🔄 重启 Silk 系统"
    stop_services
    sleep 2
    start_services
}

# ============================================================
# 查看日志
# ============================================================

show_logs() {
    print_header "📋 Silk 系统日志"
    
    echo ""
    echo -e "${BLUE}选择要查看的日志:${NC}"
    echo "  1) 后端日志"
    echo "  2) 前端日志"
    echo "  3) Weaviate 日志"
    echo "  4) 全部日志 (实时)"
    echo ""
    read -p "请选择 [1-4]: " choice
    
    case $choice in
        1)
            echo -e "${CYAN}=== 后端日志 ===${NC}"
            tail -100 /tmp/silk_backend.log 2>/dev/null || echo "日志文件不存在"
            ;;
        2)
            echo -e "${CYAN}=== 前端日志 ===${NC}"
            tail -100 /tmp/silk_frontend.log 2>/dev/null || echo "日志文件不存在"
            ;;
        3)
            echo -e "${CYAN}=== Weaviate 日志 ===${NC}"
            docker logs silk-weaviate --tail 100 2>/dev/null || tail -100 "$SILK_DIR/search/weaviate.log" 2>/dev/null || echo "日志文件不存在"
            ;;
        4)
            echo -e "${CYAN}=== 实时日志 (Ctrl+C 退出) ===${NC}"
            tail -f /tmp/silk_backend.log /tmp/silk_frontend.log 2>/dev/null
            ;;
        *)
            echo "无效选择"
            ;;
    esac
}

# ============================================================
# 快速重启
# ============================================================

quick_restart() {
    print_header "⚡ 快速重启 (后端 + 前端 + Weaviate)"
    
    echo ""
    echo -e "${BLUE}[1/2] 停止所有服务...${NC}"
    
    # 停止
    PID=$(get_pid_on_port $BACKEND_PORT)
    [ -n "$PID" ] && kill -9 $PID 2>/dev/null && echo "  后端已停止"
    
    PID=$(get_pid_on_port $FRONTEND_PORT)
    [ -n "$PID" ] && kill -9 $PID 2>/dev/null && echo "  前端已停止"
    
    weaviate_stop
    
    sleep 2
    
    echo ""
    echo -e "${BLUE}[2/2] 启动所有服务...${NC}"
    
    # 启动 Weaviate
    weaviate_start
    
    # 启动后端
    cd "$SILK_DIR"
    nohup ./gradlew :backend:run > /tmp/silk_backend.log 2>&1 &
    echo "  后端启动中..."
    
    # 启动前端 (使用预编译的生产版本)
    STATIC_DIR="$SILK_DIR/frontend/webApp/build/dist/js/productionExecutable"
    if [ -f "$STATIC_DIR/webApp.js" ]; then
        cd "$STATIC_DIR"
        nohup python3 -m http.server $FRONTEND_PORT --bind 0.0.0.0 > /tmp/silk_frontend.log 2>&1 &
        echo "  前端启动中 (静态服务器)..."
    else
        echo "  ${YELLOW}前端预编译文件不存在，请先运行: ./silk.sh build${NC}"
    fi
    
    echo ""
    echo -e "${YELLOW}等待服务就绪 (约 30-60 秒)...${NC}"
    
    for i in {1..12}; do
        sleep 5
        if check_port $BACKEND_PORT && check_port $FRONTEND_PORT && check_port $WEAVIATE_HTTP_PORT; then
            echo ""
            echo -e "${GREEN}✅ 重启完成！${NC}"
            echo -e "  后端: http://localhost:$BACKEND_PORT"
            echo -e "  前端: http://localhost:$FRONTEND_PORT"
            echo -e "  Weaviate: http://localhost:$WEAVIATE_HTTP_PORT"
            echo ""
            return 0
        fi
        echo -n "."
    done
    
    echo ""
    echo -e "${YELLOW}请运行 './silk.sh status' 检查状态${NC}"
}

# ============================================================
# 主入口
# ============================================================

case "$1" in
    status|s)
        check_status
        ;;
    start)
        start_services
        ;;
    stop)
        stop_services
        ;;
    restart|r)
        restart_services
        ;;
    quick|q)
        quick_restart
        ;;
    logs|l)
        show_logs
        ;;
    build|b)
        build_frontend
        ;;
    build-apk|ba)
        build_apk
        ;;
    build-all|bp)
        build_all
        ;;
    deploy|d)
        deploy
        ;;
    weaviate|w)
        weaviate_manage $2
        ;;
    *)
        echo ""
        echo -e "${CYAN}Silk 系统管理脚本${NC}"
        echo ""
        echo "用法: $0 <命令>"
        echo ""
        echo "命令:"
        echo "  deploy, d     一键部署 (构建全部 + 启动 + Weaviate)"
        echo "  start         启动所有服务 (后端 + 前端 + Weaviate)"
        echo "  stop          停止所有服务"
        echo "  restart, r    重启所有服务"
        echo "  quick, q      快速重启"
        echo "  logs, l       查看日志"
        echo "  build, b      构建前端 (WebApp)"
        echo "  build-apk, ba 构建 Android APK"
        echo "  build-all, bp 构建全部 (WebApp + APK)"
        echo "  status, s     检查所有服务状态"
        echo "  weaviate, w   Weaviate 管理 (start/stop/status/schema)"
        echo ""
        echo "示例:"
        echo "  $0 deploy           # 一键部署 (推荐)"
        echo "  $0 build-all        # 构建全部"
        echo "  $0 start            # 启动服务"
        echo "  $0 status           # 检查状态"
        echo "  $0 weaviate status  # 检查 Weaviate 状态"
        echo "  $0 weaviate schema  # 初始化 Weaviate Schema"
        echo ""
        ;;
esac
