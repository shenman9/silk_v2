#!/bin/bash
# 重新索引文件脚本
# 删除 Weaviate 中旧的 FILE 类型文档，然后重新索引

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ -f "$ROOT_DIR/.env" ]; then
    TMP_ENV=$(mktemp)
    tr -d '\r' < "$ROOT_DIR/.env" > "$TMP_ENV"
    set -a
    # shellcheck disable=SC1090
    source "$TMP_ENV"
    set +a
    rm -f "$TMP_ENV"
fi

SESSION_ID="${1:?用法: $0 <session_id>}"
WEAVIATE_URL="${WEAVIATE_URL:-http://localhost:8008}"

CURL_AUTH=()
if [ -n "$WEAVIATE_API_KEY" ]; then
    CURL_AUTH=(-H "Authorization: Bearer $WEAVIATE_API_KEY")
fi

echo "🔄 重新索引文件..."
echo "Session: $SESSION_ID"
echo ""

# 1. 获取并删除旧的 FILE 类型文档
echo "📝 步骤 1: 查找旧的 FILE 文档..."

# 获取所有 FILE 类型的文档 ID
OLD_IDS=$(curl -s "${CURL_AUTH[@]}" "$WEAVIATE_URL/v1/graphql" \
  -H "Content-Type: application/json" \
  -d "{\"query\": \"{ Get { SilkContext(where: {operator: And, operands: [{path: [\\\"sessionId\\\"], operator: Equal, valueText: \\\"$SESSION_ID\\\"}, {path: [\\\"sourceType\\\"], operator: Equal, valueText: \\\"FILE\\\"}]}, limit: 100) { _additional { id } } } }\"}" \
  | python3 -c "import sys,json; d=json.load(sys.stdin); ids=[x['_additional']['id'] for x in d.get('data',{}).get('Get',{}).get('SilkContext',[]) if x.get('_additional')]; print(' '.join(ids))")

if [ -z "$OLD_IDS" ]; then
    echo "   没有找到旧的 FILE 文档"
else
    echo "   找到文档 IDs: $OLD_IDS"
    
    # 删除旧文档
    echo ""
    echo "📝 步骤 2: 删除旧文档..."
    for id in $OLD_IDS; do
        echo "   删除: $id"
        curl -s "${CURL_AUTH[@]}" -X DELETE "$WEAVIATE_URL/v1/objects/SilkContext/$id" > /dev/null
    done
    echo "   ✅ 旧文档已删除"
fi

echo ""
echo "📝 步骤 3: 请重新上传文件到群组，或在群组中发送文件链接"
echo ""
echo "💡 提示: 新上传的文件会使用改进后的索引（自动清理中文空格）"
echo "   这样搜索 '望远镜金额' 就能正确匹配 '望远镜' 和 '金额' 了"
