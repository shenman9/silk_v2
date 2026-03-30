#!/bin/bash

# ============================================================
# Silk 系统管理脚本
# ============================================================
# 用法:
#   ./silk.sh deploy   - 一键部署 (WebApp+APK+鸿蒙HAP + 启动，含 Weaviate)
#   ./silk.sh start    - 启动所有服务 (后端 + 前端 + Weaviate)
#   ./silk.sh stop     - 停止所有服务
#   ./silk.sh restart  - 重启所有服务
#   ./silk.sh logs     - 查看日志
#   ./silk.sh build    - 构建前端 (WebApp)
#   ./silk.sh build-apk - 构建 Android APK
#   ./silk.sh build-hap - 鸿蒙：ohpm → hvigor sync/assembleHap（与 DevEco CLI 一致）→ hdc install → aa start（脚本不结束模拟器；SILK_HARMONY_NO_START=1 仅跳过 aa start；deploy 仍 SILK_SKIP_HARMONY_RUN 跳过 hdc）
#   ./silk.sh build-all - 构建全部 (WebApp + APK + HAP)
#   ./silk.sh status   - 检查所有服务状态
#   ./silk.sh weaviate - Weaviate 管理 (start/stop/status/schema)
# ============================================================


# Java Home - 支持 macOS Homebrew 和 Linux
if [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
elif [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
    export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
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
elif [ -d "/root/Android/Sdk" ]; then
    export ANDROID_HOME=/root/Android/Sdk
elif [ -d "/root/android-sdk" ]; then
    export ANDROID_HOME=/root/android-sdk
fi
if [ -n "$ANDROID_HOME" ]; then
    export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
fi

# AAPT2 架构自动检测与配置
# ARM64 系统需要指定本地 ARM64 AAPT2，否则 Gradle 会下载 x86-64 版本导致无法执行
setup_aapt2_for_arch() {
    local CURRENT_ARCH=$(uname -m)
    local LOCAL_PROPERTIES="$SILK_DIR/local.properties"
    
    # 只在 ARM64 系统上需要特殊处理
    if [ "$CURRENT_ARCH" != "aarch64" ]; then
        # 非 ARM64 系统，移除 override 配置（如果存在）
        if grep -q "android.aapt2FromMavenOverride" "$LOCAL_PROPERTIES" 2>/dev/null; then
            sed -i '/android.aapt2FromMavenOverride/d' "$LOCAL_PROPERTIES"
        fi
        return 0
    fi
    
    # ARM64 系统：查找本地 ARM64 AAPT2
    local ARM64_AAPT2=""
    for build_tools_dir in "$ANDROID_HOME/build-tools"/*/; do
        if [ -x "${build_tools_dir}aapt2" ]; then
            local aapt2_arch=$(file "${build_tools_dir}aapt2" 2>/dev/null | grep -oP 'aarch64|ARM aarch64' | head -1)
            if [ -n "$aapt2_arch" ]; then
                ARM64_AAPT2="${build_tools_dir}aapt2"
                break
            fi
        fi
    done
    
    if [ -z "$ARM64_AAPT2" ]; then
        echo -e "  ${YELLOW}⚠ ARM64 系统但未找到 ARM64 AAPT2，APK 构建可能失败${NC}"
        return 1
    fi
    
    # 写入 local.properties
    if grep -q "android.aapt2FromMavenOverride" "$LOCAL_PROPERTIES" 2>/dev/null; then
        sed -i "s|android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=$ARM64_AAPT2|" "$LOCAL_PROPERTIES"
    else
        echo "" >> "$LOCAL_PROPERTIES"
        echo "# ARM64 aapt2 override - 自动检测" >> "$LOCAL_PROPERTIES"
        echo "android.aapt2FromMavenOverride=$ARM64_AAPT2" >> "$LOCAL_PROPERTIES"
    fi
    
    echo -e "  ${GREEN}✓ ARM64 系统已配置 AAPT2: $ARM64_AAPT2${NC}"
}
# 延迟执行 AAPT2 配置（在 SILK_DIR 定义之后）
_SETUP_AAPT2_HOOK=true

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

# 端口定义：从 .env 读取，未设置时使用默认值
# BACKEND_PORT 优先跟随 BACKEND_HTTP_PORT（APK 公网访问端口）
BACKEND_PORT=${BACKEND_HTTP_PORT:-${BACKEND_PORT:-8003}}
FRONTEND_PORT=${FRONTEND_PORT:-8005}
WEAVIATE_HTTP_PORT=${WEAVIATE_HTTP_PORT:-8008}
WEAVIATE_GRPC_PORT=${WEAVIATE_GRPC_PORT:-50051}

# curl 访问 Weaviate 时附加 API Key（与 AUTHENTICATION_APIKEY 一致）
CURL_WEAVIATE_AUTH=()
if [ -n "$WEAVIATE_API_KEY" ]; then
    CURL_WEAVIATE_AUTH=(-H "Authorization: Bearer $WEAVIATE_API_KEY")
fi

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

# 仅允许清理由 silk.sh 管理的进程，避免误伤 DevEco/模拟器等外部进程
is_silk_managed_pid() {
    local pid="$1"
    local cmdline
    cmdline=$(ps -p "$pid" -o command= 2>/dev/null)
    if [ -z "$cmdline" ]; then
        return 1
    fi

    case "$cmdline" in
        *"$SILK_DIR"*|*":backend:run"*|*"python3 -m http.server $FRONTEND_PORT"*|*"python -m http.server $FRONTEND_PORT"*)
            return 0
            ;;
    esac
    return 1
}

# 自动清理端口占用
# 第 $4 参数 kill_any=true：deploy 等场景下无条件终止该端口全部监听进程（用于 FRONTEND_PORT：
# Homebrew Python 在 ps 中常显示为 Python 而非 python3，原有 is_silk_managed_pid 会误判为「非 Silk」）
kill_port_process() {
    local port=$1
    local service_name=$2
    local force=${3:-false}
    local kill_any=${4:-false}
    
    if check_port $port; then
        local PIDS
        PIDS=$(get_pid_on_port "$port")
        local PROC_NAME
        PROC_NAME=$(ps -p "$(echo "$PIDS" | head -1)" -o comm= 2>/dev/null || echo "unknown")
        
        if [ "$force" == "true" ]; then
            if [ "$kill_any" == "true" ]; then
                echo -e "  ${YELLOW}⚠ 端口 $port（$service_name）被 $PROC_NAME (PID: $(echo "$PIDS" | tr '\n' ' ')) 占用，deploy 强制释放该端口...${NC}"
            else
                echo -e "  ${YELLOW}⚠ 端口 $port 被 $PROC_NAME (PID: $(echo "$PIDS" | tr '\n' ' ')) 占用，检查是否为 Silk 进程...${NC}"
            fi
            local killed_any=false
            local skipped_any=false
            for PID in $PIDS; do
                if [ "$kill_any" == "true" ] || is_silk_managed_pid "$PID"; then
                    kill -TERM "$PID" 2>/dev/null
                    killed_any=true
                else
                    skipped_any=true
                fi
            done

            sleep 1
            if check_port "$port" && [ "$killed_any" = true ]; then
                for PID in $PIDS; do
                    if [ "$kill_any" == "true" ] || is_silk_managed_pid "$PID"; then
                        kill -9 "$PID" 2>/dev/null
                    fi
                done
                sleep 1
            fi

            if [ "$kill_any" != "true" ] && [ "$skipped_any" = true ]; then
                echo -e "  ${YELLOW}⚠ 发现非 Silk 进程占用端口 $port，已跳过（防止误杀外部程序/模拟器）${NC}"
            fi
            if check_port $port; then
                echo -e "  ${RED}❌ 无法释放端口 $port${NC}"
                return 1
            fi
            echo -e "  ${GREEN}✓ 端口 $port 已释放${NC}"
        else
            echo -e "  ${YELLOW}⚠ 端口 $port 被 $PROC_NAME (PID: $(echo "$PIDS" | tr '\n' ' ')) 占用${NC}"
            echo -n "  是否终止? [y/N]: "
            read -t 5 answer
            if [ "$answer" == "y" ] || [ "$answer" == "Y" ]; then
                local killed_any=false
                for PID in $PIDS; do
                    if [ "$kill_any" == "true" ] || is_silk_managed_pid "$PID"; then
                        kill -TERM "$PID" 2>/dev/null
                        killed_any=true
                    else
                        echo -e "  ${YELLOW}⚠ 跳过非 Silk 进程 PID=$PID（防止误杀）${NC}"
                    fi
                done
                if [ "$killed_any" = true ]; then
                    sleep 1
                    for PID in $PIDS; do
                        if [ "$kill_any" == "true" ] || is_silk_managed_pid "$PID"; then
                            kill -9 "$PID" 2>/dev/null
                        fi
                    done
                fi
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
    # 前端端口：deploy 等场景下必须清空，避免旧 Python http.server（进程名可为 Python）占坑导致无法启动
    kill_port_process $FRONTEND_PORT "Silk Frontend" $force true
    
    # 永不清理 Weaviate 端口：若 8008 已有 Weaviate 在跑则直接复用，不停止、不杀进程
    echo -e "  ${GREEN}✓ 跳过 Weaviate 端口 ($WEAVIATE_HTTP_PORT/$WEAVIATE_GRPC_PORT)，保持已有实例${NC}"
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
        local READY=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
        if [ "$READY" == "200" ]; then
            echo -e "  就绪状态: ${GREEN}✓ Ready${NC}"
        else
            echo -e "  就绪状态: ${YELLOW}⏳ Not Ready${NC}"
        fi
        
        # 显示版本
        local VERSION=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/meta" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('version','?'))" 2>/dev/null)
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
        local READY=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" -o /dev/null -w "%{http_code}" "$WEAVIATE_URL/v1/.well-known/ready" 2>/dev/null)
        if [ "$READY" == "200" ]; then
            echo -e "  ${GREEN}✓ 远程 Weaviate 已就绪: $WEAVIATE_URL${NC}"
            return 0
        else
            echo -e "  ${YELLOW}⚠ 远程 Weaviate ($WEAVIATE_URL) 不可用 (HTTP $READY)${NC}"
            echo -e "  ${YELLOW}  将启动本地 Weaviate...${NC}"
        fi
    fi
    
    # 本地：若 8008 上已有 Weaviate 在跑且就绪，直接复用，不启动、不杀进程
    if check_port $WEAVIATE_HTTP_PORT; then
        local READY=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
        if [ "$READY" == "200" ]; then
            echo -e "  ${GREEN}✓ 端口 $WEAVIATE_HTTP_PORT 已有 Weaviate 在运行，直接使用${NC}"
            return 0
        fi
    fi
    
    # 首先检查 Docker 容器是否已存在且运行中
    local CONTAINER_STATUS=$(docker inspect -f '{{.State.Status}}' silk-weaviate 2>/dev/null)
    if [ "$CONTAINER_STATUS" == "running" ]; then
        local READY=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
        if [ "$READY" == "200" ]; then
            echo -e "  ${GREEN}✓ 本地 Weaviate 已就绪，跳过启动${NC}"
            return 0
        else
            # 容器运行但未就绪（可能是端口转发问题），重启容器
            echo -e "  ${YELLOW}⚠ Weaviate 容器运行中但未就绪，重启容器...${NC}"
            docker restart silk-weaviate
            # 等待就绪
            for i in {1..15}; do
                sleep 2
                READY=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
                if [ "$READY" == "200" ]; then
                    echo -e "  ${GREEN}✓ Weaviate 重启后已就绪！${NC}"
                    weaviate_schema
                    return 0
                fi
                echo -n "."
            done
            echo -e "  ${YELLOW}⚠ Weaviate 重启后仍未就绪，尝试重新创建容器${NC}"
            docker rm -f silk-weaviate 2>/dev/null
        fi
    elif [ -n "$CONTAINER_STATUS" ]; then
        # 容器存在但不是 running 状态（可能是 exited 等）
        echo -e "  ${YELLOW}⚠ Weaviate 容器状态异常 ($CONTAINER_STATUS)，重新创建...${NC}"
        docker rm -f silk-weaviate 2>/dev/null
    else
        # 端口已被占用时：若是 Weaviate 则直接复用，不杀进程
        if check_port $WEAVIATE_HTTP_PORT; then
            local READY=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
            if [ "$READY" == "200" ]; then
                echo -e "  ${GREEN}✓ 端口 $WEAVIATE_HTTP_PORT 已有 Weaviate 在运行，直接使用${NC}"
                return 0
            fi
            echo -e "  ${YELLOW}⚠ 端口 $WEAVIATE_HTTP_PORT 被非 Weaviate 进程占用，请先释放端口${NC}"
            return 1
        fi
    fi
    
    # 使用 Docker 直接启动 (避免 docker-compose 版本问题)
    if command -v docker &> /dev/null; then
        echo -e "  使用 Docker 启动 Weaviate (端口 $WEAVIATE_HTTP_PORT)..."
        
        if [ -z "$WEAVIATE_API_KEY" ]; then
            echo -e "  ${RED}✗ 未设置 WEAVIATE_API_KEY，无法以安全模式启动 Weaviate（请在 .env 中配置）${NC}"
            return 1
        fi
        docker run -d --name silk-weaviate \
            --restart unless-stopped \
            -p 127.0.0.1:$WEAVIATE_HTTP_PORT:8080 \
            -p 127.0.0.1:$WEAVIATE_GRPC_PORT:50051 \
            -v "$SILK_DIR/search/weaviate_data:/var/lib/weaviate" \
            -e QUERY_DEFAULTS_LIMIT=25 \
            -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=false \
            -e AUTHENTICATION_APIKEY_ENABLED=true \
            -e AUTHENTICATION_APIKEY_ALLOWED_KEYS="$WEAVIATE_API_KEY" \
            -e AUTHENTICATION_APIKEY_USERS=silk-weaviate-admin \
            -e AUTHORIZATION_ADMINLIST_ENABLED=true \
            -e AUTHORIZATION_ADMINLIST_USERS=silk-weaviate-admin \
            -e PERSISTENCE_DATA_PATH=/var/lib/weaviate \
            -e DEFAULT_VECTORIZER_MODULE=none \
            -e CLUSTER_HOSTNAME=node1 \
            semitechnologies/weaviate:1.27.0
        
        if [ $? -eq 0 ]; then
            echo -e "  ${GREEN}✓ Weaviate 容器启动中...${NC}"
            
            # 等待就绪
            for i in {1..30}; do
                sleep 2
                local READY=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
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
    
    # 若 8008 上已有 Weaviate 在跑，一律不停止、不杀进程，直接复用
    if check_port $WEAVIATE_HTTP_PORT; then
        local READY=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
        if [ "$READY" == "200" ]; then
            echo -e "  ${GREEN}✓ 端口 $WEAVIATE_HTTP_PORT 上 Weaviate 正在运行，保持不停止${NC}"
            return 0
        fi
    fi
    
    # 停止 Docker 容器（仅限本脚本启动的 silk-weaviate 容器）
    if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "silk-weaviate"; then
        cd "$SILK_DIR/search"
        docker-compose down 2>/dev/null
        echo -e "  ${GREEN}✓ Docker 容器已停止${NC}"
    fi
    
    # 停止本地进程（仅当端口占用且不是 Weaviate 就绪时）
    if check_port $WEAVIATE_HTTP_PORT; then
        local READY=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" -o /dev/null -w "%{http_code}" "http://localhost:$WEAVIATE_HTTP_PORT/v1/.well-known/ready" 2>/dev/null)
        if [ "$READY" != "200" ]; then
            local PID=$(get_pid_on_port $WEAVIATE_HTTP_PORT)
            kill -9 $PID 2>/dev/null
            echo -e "  ${GREEN}✓ 本地进程已停止 (PID: $PID)${NC}"
        fi
    fi
}

weaviate_schema() {
    echo -e "${BLUE}初始化 Weaviate Schema...${NC}"
    
    # 确定要检查的 Weaviate URL
    local CHECK_URL="${WEAVIATE_URL:-http://localhost:$WEAVIATE_HTTP_PORT}"
    
    # 检查是否已就绪
    local READY=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" -o /dev/null -w "%{http_code}" "$CHECK_URL/v1/.well-known/ready" 2>/dev/null)
    if [ "$READY" != "200" ]; then
        echo -e "  ${YELLOW}⚠ Weaviate ($CHECK_URL) 未就绪，跳过 Schema 初始化${NC}"
        return 1
    fi
    
    # 检查 Schema 是否已存在
    local SCHEMA=$(curl -s "${CURL_WEAVIATE_AUTH[@]}" "$CHECK_URL/v1/schema" 2>/dev/null)
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
# 清理架构不匹配的 AAPT2 缓存 (ARM64 服务器上可能缓存了 x86_64 的 AAPT2)
# 并用本地 Android SDK 中的 ARM64 AAPT2 替换
# ============================================================
clean_aapt2_cache() {
    local GRADLE_CACHES_DIR="$HOME/.gradle/caches"
    local TRANSFORMS_DIR="$GRADLE_CACHES_DIR/transforms-3"
    local CURRENT_ARCH=$(uname -m)
    
    # 只在 ARM64 系统上检查
    if [ "$CURRENT_ARCH" != "aarch64" ]; then
        return 0
    fi
    
    if [ ! -d "$TRANSFORMS_DIR" ]; then
        return 0
    fi
    
    # 使用全局变量 AAPT2_OVERRIDE_PATH (由 setup_aapt2_override 设置)
    local LOCAL_AAPT2="$AAPT2_OVERRIDE_PATH"
    
    # 如果全局变量未设置，尝试查找
    if [ -z "$LOCAL_AAPT2" ] && [ -n "$ANDROID_HOME" ]; then
        for build_tools_dir in "$ANDROID_HOME/build-tools"/*/; do
            if [ -x "${build_tools_dir}aapt2" ]; then
                local aapt2_arch=$(file "${build_tools_dir}aapt2" 2>/dev/null | grep -oP 'x86-64|aarch64|ARM aarch64' | head -1)
                if [[ "$aapt2_arch" == "aarch64" || "$aapt2_arch" == "ARM aarch64" ]]; then
                    LOCAL_AAPT2="${build_tools_dir}aapt2"
                    break
                fi
            fi
        done
    fi
    
    # 替换所有 x86-64 的 AAPT2 为本地 ARM64 版本
    local REPLACED=false
    while IFS= read -r aapt2_file; do
        if [ -x "$aapt2_file" ]; then
            local aapt2_arch=$(file "$aapt2_file" 2>/dev/null | grep -oP 'x86-64|aarch64|ARM aarch64' | head -1)
            if [ "$aapt2_arch" = "x86-64" ]; then
                if [ -n "$LOCAL_AAPT2" ]; then
                    echo -e "  ${YELLOW}替换 x86-64 AAPT2 -> ARM64: $(basename $(dirname $(dirname "$aapt2_file")))${NC}"
                    /bin/cp -f "$LOCAL_AAPT2" "$aapt2_file"
                    chmod +x "$aapt2_file"
                    REPLACED=true
                fi
            fi
        fi
    done < <(find "$TRANSFORMS_DIR" -name "aapt2" -type f 2>/dev/null)
    
    if [ "$REPLACED" = "true" ]; then
        echo -e "  ${GREEN}✓ 已替换架构不匹配的 AAPT2${NC}"
    fi
}

# Kotlin 增量编译 snapshot 在部分环境（NFS、中断构建）下 Gradle 无法删除，导致 compileKotlin / compileDebugKotlin 失败
clean_gradle_kotlin_snapshots() {
    rm -rf "$SILK_DIR/backend/build/snapshot"
    rm -rf "$SILK_DIR/frontend/androidApp/build/snapshot"
}

# ============================================================
# 构建 Android APK
# ============================================================

build_apk() {
    print_header "📱 构建 Android APK"
    
    # ARM64 系统需要配置 AAPT2 override
    if [ "$_SETUP_AAPT2_HOOK" = "true" ]; then
        setup_aapt2_for_arch
    fi
    
    # 从 .env 取后端地址并传给 Gradle（避免 daemon 未继承环境变量）
    if [ -n "$BACKEND_BASE_URL" ]; then
        APK_BACKEND_URL="$BACKEND_BASE_URL"
    elif [ -n "$BACKEND_HOST" ]; then
        APK_BACKEND_URL="http://${BACKEND_HOST}:${BACKEND_HTTP_PORT:-8003}"
    else
        APK_BACKEND_URL="http://10.0.2.2:${BACKEND_HTTP_PORT:-8003}"
    fi
    echo -e "  后端地址将注入 APK: ${CYAN}$APK_BACKEND_URL${NC}"
    
    # ARM64 系统需要传递 AAPT2 override 参数（local.properties 设置不可靠）
    AAPT2_OVERRIDE_PARAM=""
    if [ "$(uname -m)" = "aarch64" ]; then
        # 查找本地 ARM64 AAPT2
        for build_tools_dir in "$ANDROID_HOME/build-tools"/*/; do
            if [ -x "${build_tools_dir}aapt2" ]; then
                local aapt2_arch=$(file "${build_tools_dir}aapt2" 2>/dev/null | grep -oP 'aarch64|ARM aarch64' | head -1)
                if [ -n "$aapt2_arch" ]; then
                    AAPT2_OVERRIDE_PARAM="-Pandroid.aapt2FromMavenOverride=${build_tools_dir}aapt2"
                    break
                fi
            fi
        done
    fi
    
    echo ""
    echo -e "${BLUE}正在构建 Android APK (Debug)...${NC}"
    cd "$SILK_DIR"
    clean_gradle_kotlin_snapshots
    ./gradlew -PBACKEND_BASE_URL="$APK_BACKEND_URL" $AAPT2_OVERRIDE_PARAM :frontend:androidApp:assembleDebug
    
    if [ $? -eq 0 ]; then
        # 找到生成的 APK
        APK_FILE=$(ls -t $SILK_DIR/frontend/androidApp/build/outputs/apk/debug/*.apk 2>/dev/null | head -1)
        
        if [ -n "$APK_FILE" ]; then
            APK_SIZE=$(du -h "$APK_FILE" | cut -f1)
            
            # 从生成的 BuildConfig.java 获取版本号
            BUILD_CONFIG="$SILK_DIR/frontend/androidApp/build/generated/source/buildConfig/debug/com/silk/android/BuildConfig.java"
            if [ -f "$BUILD_CONFIG" ]; then
                APK_VERSION=$(grep "VERSION_NAME" "$BUILD_CONFIG" | grep -oP '"\K[^"]+' | head -1)
            fi
            # 如果无法获取版本号，使用时间戳
            if [ -z "$APK_VERSION" ]; then
                APK_VERSION=$(date +"%Y.%m%d.%H%M")
            fi
            
            # 重命名为 silk-{version}.apk
            APK_NAME="silk-${APK_VERSION}.apk"
            
            echo ""
            echo -e "${GREEN}✅ APK 构建成功${NC}"
            echo -e "  大小: $APK_SIZE"
            echo -e "  版本: $APK_VERSION"
            echo -e "  路径: $APK_FILE"
            
            # 复制到 backend/static 目录供下载，重命名为 silk-{version}.apk
            echo ""
            echo -e "${BLUE}复制到 backend/static 目录供下载...${NC}"
            cp "$APK_FILE" "$APK_OUTPUT_DIR/$APK_NAME"
            echo -e "${GREEN}✅ 已复制到: $APK_OUTPUT_DIR/$APK_NAME${NC}"
            
            # 创建符号链接 silk.apk 指向最新版本
            ln -sf "$APK_OUTPUT_DIR/$APK_NAME" "$APK_OUTPUT_DIR/silk.apk"
            echo -e "${GREEN}✅ 已创建链接: $APK_OUTPUT_DIR/silk.apk${NC}"
            
            # 同时更新 static/files/androidApp-debug.apk 供后端路由查找
            mkdir -p "$APK_OUTPUT_DIR/files"
            cp "$APK_FILE" "$APK_OUTPUT_DIR/files/androidApp-debug.apk"
            echo -e "${GREEN}✅ 已更新: $APK_OUTPUT_DIR/files/androidApp-debug.apk${NC}"
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
# 构建 HarmonyOS HAP
# ============================================================

build_hap() {
    print_header "📱 构建 HarmonyOS HAP"
    
    local HARMONY_DIR="$SILK_DIR/frontend/harmonyApp"
    
    # DevEco Studio 根目录（Contents 或安装根目录，与 scripts/deploy_win.sh 一致）
    if [ -z "$DEVECO_HOME" ]; then
        if [ -d "/Applications/DevEco-Studio.app" ]; then
            DEVECO_HOME="/Applications/DevEco-Studio.app/Contents"
        elif [ -d "$HOME/DevEco-Studio" ]; then
            DEVECO_HOME="$HOME/DevEco-Studio"
        elif [ -d "/opt/DevEco-Studio" ]; then
            DEVECO_HOME="/opt/DevEco-Studio"
        fi
    fi
    
    # 兼容部分环境将 DEVECO_HOME 指向 .app 本体
    if [ -d "$DEVECO_HOME/Contents/tools/hvigor/bin" ]; then
        DEVECO_HOME="$DEVECO_HOME/Contents"
    fi
    
    local OHPM_CMD=""
    local HVIGORW_CMD=""
    local DEVECO_SDK_HOME=""
    
    if [ -n "$DEVECO_HOME" ] && [ -d "$DEVECO_HOME/tools/ohpm/bin" ]; then
        OHPM_CMD="$DEVECO_HOME/tools/ohpm/bin/ohpm"
        HVIGORW_CMD="$DEVECO_HOME/tools/hvigor/bin/hvigorw"
        DEVECO_SDK_HOME="$DEVECO_HOME/sdk"
    fi
    
    # 回退：项目内 hvigorw（旧流程）
    if [ -z "$HVIGORW_CMD" ] || [ ! -x "$HVIGORW_CMD" ]; then
        if [ -f "$HARMONY_DIR/hvigorw" ]; then
            HVIGORW_CMD="$HARMONY_DIR/hvigorw"
        fi
    fi
    
    if [ -z "$HVIGORW_CMD" ] || [ ! -x "$HVIGORW_CMD" ]; then
        echo -e "${YELLOW}⚠ 未找到 DevEco 自带 hvigorw${NC}"
        echo -e "  请安装 DevEco Studio，或设置 ${CYAN}DEVECO_HOME${NC} 指向安装目录（macOS 一般为 ${CYAN}/Applications/DevEco-Studio.app/Contents${NC}）"
        echo ""
        echo -e "${CYAN}或手动:${NC} 在 DevEco 中打开 $HARMONY_DIR 并完成 Sync / 构建"
        return 1
    fi
    
    # 从 .env 取后端地址（hvigor sync 会据此重写 EnvConfig.ets）
    if [ -n "$BACKEND_BASE_URL" ]; then
        HAP_BACKEND_URL="$BACKEND_BASE_URL"
    elif [ -n "$BACKEND_HOST" ]; then
        HAP_BACKEND_URL="http://${BACKEND_HOST}:${BACKEND_HTTP_PORT:-8003}"
    else
        HAP_BACKEND_URL="http://localhost:${BACKEND_HTTP_PORT:-8003}"
    fi
    echo -e "  根目录 .env → HAP 后端: ${CYAN}$HAP_BACKEND_URL${NC}（sync 时写入 EnvConfig.ets）"
    
    # DevEco hdc（CLI 安装 HAP；可用环境变量 HDC 覆盖）
    local HDC_CMD="${HDC:-}"
    if [ -z "$HDC_CMD" ] || [ ! -x "$HDC_CMD" ]; then
        for cand in \
            "${DEVECO_HOME}/sdk/default/openharmony/toolchains/hdc" \
            "/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/hdc"; do
            if [ -n "$cand" ] && [ -x "$cand" ]; then
                HDC_CMD="$cand"
                break
            fi
        done
    fi
    
    cd "$HARMONY_DIR"
    
    # ---------- 鸿蒙环境（hvigor 参数与 DevEco-Studio 命令行一致：--parallel --incremental --daemon）----------
    run_hvigor() {
        (
            if [ -n "$DEVECO_HOME" ] && [ -d "$DEVECO_HOME/jbr" ]; then
                export JAVA_HOME="$DEVECO_HOME/jbr"
                export PATH="$DEVECO_HOME/jbr/bin:$PATH"
            fi
            if [ -n "$DEVECO_SDK_HOME" ] && [ -d "$DEVECO_SDK_HOME" ]; then
                export DEVECO_SDK_HOME="$DEVECO_SDK_HOME"
            fi
            "$HVIGORW_CMD" "$@"
        )
    }
    
    local HVIGOR_IDE_FLAGS="-p product=default --analyze=normal --parallel --incremental --daemon"
    
    # 与 DevEco「刷新依赖 → 同步工程 → entry 模块 HAP」一致
    if [ -n "$OHPM_CMD" ] && [ -x "$OHPM_CMD" ]; then
        echo ""
        echo -e "${BLUE}[Harmony 1/4] Refresh 依赖 (ohpm install)...${NC}"
        "$OHPM_CMD" install 2>&1
        if [ $? -ne 0 ]; then
            echo -e "${RED}❌ ohpm install 失败${NC}"
            return 1
        fi
    else
        echo -e "${YELLOW}⚠ 未找到 DevEco ohpm，跳过依赖安装（若构建失败请先安装 DevEco Studio）${NC}"
    fi
    
    echo ""
    echo -e "${BLUE}[Harmony 2/4] Sync 工程 (hvigor --sync, .env → EnvConfig.ets)...${NC}"
    run_hvigor --sync $HVIGOR_IDE_FLAGS 2>&1
    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ hvigor --sync 失败${NC}"
        return 1
    fi
    
    echo ""
    echo -e "${BLUE}[Harmony 3/4] Build entry HAP (hvigor assembleHap --mode module)...${NC}"
    run_hvigor assembleHap --mode module -p module=entry@default $HVIGOR_IDE_FLAGS 2>&1

    if [ $? -ne 0 ]; then
        echo ""
        echo -e "${RED}❌ HAP 构建失败${NC}"
        return 1
    fi
    
    local HAP_FILE=""
    for cand in \
        "$HARMONY_DIR/entry/build/default/outputs/default/entry-default-unsigned.hap" \
        "$HARMONY_DIR/entry/build/default/outputs/default/app/entry-default.hap"; do
        if [ -f "$cand" ]; then
            HAP_FILE="$cand"
            break
        fi
    done
    if [ -z "$HAP_FILE" ]; then
        HAP_FILE=$(find "$HARMONY_DIR/entry/build" -name "*.hap" -type f 2>/dev/null | head -1)
    fi
    if [ -z "$HAP_FILE" ]; then
        echo ""
        echo -e "${YELLOW}⚠ 构建未报错但未找到 .hap 输出${NC}"
        return 1
    fi
    
    local HAP_SIZE=$(du -h "$HAP_FILE" | cut -f1)
    echo ""
    echo -e "${GREEN}✅ HAP 构建成功${NC}"
    echo -e "  大小: $HAP_SIZE"
    echo -e "  路径: $HAP_FILE"
    
    echo ""
    echo -e "${BLUE}复制到 backend/static 目录供下载...${NC}"
    mkdir -p "$APK_OUTPUT_DIR"
    cp "$HAP_FILE" "$APK_OUTPUT_DIR/"
    local HAP_NAME=$(basename "$HAP_FILE")
    ln -sf "$APK_OUTPUT_DIR/$HAP_NAME" "$APK_OUTPUT_DIR/silk.hap"
    echo -e "${GREEN}✅ 已复制到: $APK_OUTPUT_DIR/$HAP_NAME${NC}"
    echo -e "${GREEN}✅ 已创建链接: $APK_OUTPUT_DIR/silk.hap${NC}"
    
    # hdc install → aa start（默认拉起 entry，与 IDE 运行一致；不调用 hdc/emulator 结束设备）
    # SILK_HARMONY_NO_START=1 跳过 aa start；./silk.sh deploy 仍通过 SILK_SKIP_HARMONY_RUN=1 跳过 hdc
    if [ -n "${SILK_SKIP_HARMONY_RUN:-}" ]; then
        echo ""
        echo -e "${CYAN}已跳过 hdc (SILK_SKIP_HARMONY_RUN)；手动示例:${NC}"
        if [ -n "$HDC_CMD" ] && [ -x "$HDC_CMD" ]; then
            echo -e "  \"$HDC_CMD\" -t 127.0.0.1:5555 install -r \"$HAP_FILE\""
            echo -e "  \"$HDC_CMD\" -t 127.0.0.1:5555 shell aa start -a EntryAbility -b com.silk.harmony  # 可选"
        fi
        return 0
    fi
    
    if [ -z "$HDC_CMD" ] || [ ! -x "$HDC_CMD" ]; then
        echo ""
        echo -e "${YELLOW}⚠ 未检测到 hdc，跳过安装启动。可设置环境变量 ${CYAN}HDC${NC} 为 hdc 可执行文件路径。${NC}"
        return 0
    fi
    
    echo ""
    echo -e "${BLUE}[Harmony 4/4] hdc install + aa start...${NC}"
    local HDC_TARGET=""
    HDC_TARGET=$(echo "${SILK_HDC_TARGET:-}" | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | head -1)
    if [ -z "$HDC_TARGET" ]; then
        HDC_TARGET=$("$HDC_CMD" list targets 2>/dev/null | tr -d '\r' | sed '/^[[:space:]]*$/d' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:[0-9]+' | head -1)
    fi
    if [ -z "$HDC_TARGET" ]; then
        HDC_TARGET=$("$HDC_CMD" list targets 2>/dev/null | tr -d '\r' | sed '/^[[:space:]]*$/d' | head -1)
    fi
    if [ -z "$HDC_TARGET" ]; then
        HDC_TARGET="127.0.0.1:5555"
        echo -e "  ${YELLOW}⚠ hdc list targets 无输出，使用默认设备 ${CYAN}$HDC_TARGET${NC}（可设置 ${CYAN}SILK_HDC_TARGET${NC}）"
    fi

    echo -e "  设备: ${CYAN}$HDC_TARGET${NC}"
    if ! "$HDC_CMD" -t "$HDC_TARGET" install -r "$HAP_FILE" 2>&1; then
        echo -e "${RED}❌ hdc install 失败${NC}"
        return 1
    fi
    local no_aa="${SILK_HARMONY_NO_START:-}"
    local legacy_off="${SILK_HARMONY_AUTO_START:-}"
    if [ "$no_aa" = "1" ] || [ "$no_aa" = "true" ] || [ "$no_aa" = "yes" ] \
        || [ "$legacy_off" = "0" ] || [ "$legacy_off" = "false" ] || [ "$legacy_off" = "no" ]; then
        echo -e "${GREEN}✅ 已安装 HAP（已跳过 aa start：SILK_HARMONY_NO_START 或 SILK_HARMONY_AUTO_START=0）${NC}"
        echo -e "  手动: ${CYAN}\"$HDC_CMD\" -t \"$HDC_TARGET\" shell aa start -a EntryAbility -b com.silk.harmony${NC}"
    else
        if ! "$HDC_CMD" -t "$HDC_TARGET" shell aa start -a EntryAbility -b com.silk.harmony 2>&1; then
            echo -e "${RED}❌ aa start EntryAbility 失败${NC}"
            return 1
        fi
        echo -e "${GREEN}✅ 已安装并已启动 entry${NC}"
    fi
}

# ============================================================
# 构建全部
# ============================================================

build_all() {
    print_header "🔨 构建全部 (WebApp + APK)"
    
    # 清理架构不匹配的 AAPT2 缓存（在构建开始前清理，避免 daemon 缓存不一致）
    clean_aapt2_cache
    
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
    echo -e "${BLUE}[1/5] 清理端口冲突...${NC}"
    kill_all_ports true
    sleep 2
    
    # 2. WebApp + Android APK
    echo ""
    echo -e "${BLUE}[2/5] 构建 WebApp + APK...${NC}"
    build_all
    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ 构建失败，终止部署${NC}"
        return 1
    fi
    
    # 3. HarmonyOS：ohpm + hvigor sync + assembleHap(entry) + hdc install + aa start(entry)
    echo ""
    echo -e "${BLUE}[3/5] 构建并安装鸿蒙应用（ohpm / sync / assembleHap / hdc install / aa start）...${NC}"
    build_hap
    if [ $? -ne 0 ]; then
        echo -e "${RED}❌ 鸿蒙 HAP 构建失败，终止部署${NC}"
        return 1
    fi
    
    # 4. 启动 Weaviate
    echo ""
    echo -e "${BLUE}[4/5] 启动 Weaviate...${NC}"
    weaviate_start
    sleep 2
    
    # 5. 启动后端和前端
    echo ""
    echo -e "${BLUE}[5/5] 启动后端和前端...${NC}"
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
    clean_gradle_kotlin_snapshots
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
        clean_gradle_kotlin_snapshots
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
    clean_gradle_kotlin_snapshots
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

# 在需要构建 APK 时自动配置 AAPT2
if [[ "$1" == "build-apk" || "$1" == "ba" || "$1" == "build-all" || "$1" == "bp" || "$1" == "deploy" || "$1" == "d" ]]; then
    setup_aapt2_for_arch
fi

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
    build-hap|bh)
        build_hap
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
        echo "  deploy, d     一键部署 (WebApp+APK+鸿蒙HAP + 启动 + Weaviate)"
        echo "  start         启动所有服务 (后端 + 前端 + Weaviate)"
        echo "  stop          停止所有服务"
        echo "  restart, r    重启所有服务"
        echo "  quick, q      快速重启"
        echo "  logs, l       查看日志"
        echo "  build, b      构建前端 (WebApp)"
        echo "  build-apk, ba 构建 Android APK"
        echo "  build-hap, bh 鸿蒙: ohpm→sync→assembleHap→hdc install→aa start（SILK_HARMONY_NO_START=1 跳过启动；SILK_HDC_TARGET 默认 list targets / 127.0.0.1:5555）"
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
