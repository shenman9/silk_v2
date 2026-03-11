#!/usr/bin/env python3
"""修复 silk.sh 中的 weaviate_start 函数。在项目根目录执行：python3 fix_weaviate_start.py"""

import os
import re

# 脚本所在目录即项目根目录
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
SILK_SH = os.path.join(SCRIPT_DIR, "silk.sh")

# 读取文件
with open(SILK_SH, 'r') as f:
    content = f.read()

# 新的 weaviate_start 函数
new_function = '''weaviate_start() {
    echo -e "${BLUE}启动 Weaviate...${NC}"
    
    # 检查是否已运行
    if check_port $WEAVIATE_HTTP_PORT; then
        echo -e "  ${YELLOW}Weaviate 已在运行${NC}"
        return 0
    fi
    
    # 清理可能存在的旧容器
    docker rm -f silk-weaviate 2>/dev/null
    
    # 使用 Docker 直接启动 (避免 docker-compose 版本问题)
    if command -v docker &> /dev/null; then
        echo -e "  使用 Docker 启动 Weaviate (端口 $WEAVIATE_HTTP_PORT)..."
        
        docker run -d --name silk-weaviate \\
            --restart unless-stopped \\
            -p $WEAVIATE_HTTP_PORT:8080 \\
            -p $WEAVIATE_GRPC_PORT:50051 \\
            -v "$SILK_DIR/search/weaviate_data:/var/lib/weaviate" \\
            -e QUERY_DEFAULTS_LIMIT=25 \\
            -e AUTHENTICATION_ANONYMOUS_ACCESS_ENABLED=true \\
            -e PERSISTENCE_DATA_PATH=/var/lib/weaviate \\
            -e DEFAULT_VECTORIZER_MODULE=none \\
            -e CLUSTER_HOSTNAME=node1 \\
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
}'''

# 替换旧的函数
pattern = r'weaviate_start\(\) \{[^}]+\{[^}]+\}[^}]+\}'
# 找到函数开始和结束
start = content.find('weaviate_start() {')
if start != -1:
    # 找到函数结束 (下一个空行后的 }
    brace_count = 0
    end = start
    found_start = False
    for i in range(start, len(content)):
        if content[i] == '{':
            brace_count += 1
            found_start = True
        elif content[i] == '}':
            brace_count -= 1
            if found_start and brace_count == 0:
                end = i + 1
                break
    
    # 替换函数
    new_content = content[:start] + new_function + content[end:]
    
    with open(SILK_SH, 'w') as f:
        f.write(new_content)
    
    print("✓ weaviate_start 函数已更新")
else:
    print("✗ 未找到 weaviate_start 函数")
