#!/usr/bin/env python3
"""
Silk Context Search - Weaviate Schema Definition

多用户多会话隔离设计：
- 每个文档关联 sessionId 和 participants (参与用户列表)
- 搜索时通过 participants 过滤确保用户隔离
- 支持 foreground (当前会话) 和 background (其他参与会话) 搜索

运行: python schema.py
"""

import weaviate
from weaviate.classes.config import Configure, Property, DataType, Tokenization
import sys

import os

# 支持环境变量配置，默认本地
WEAVIATE_URL = os.environ.get("WEAVIATE_URL", "http://localhost:8008")


def create_schema():
    """创建支持多用户隔离的 Schema"""
    
    # 解析 WEAVIATE_URL
    url = WEAVIATE_URL.replace("http://", "").replace("https://", "")
    host = url.split(":")[0]
    port = int(url.split(":")[1].split("/")[0]) if ":" in url else 8080
    
    # 检测是否是本地连接（用于 gRPC）
    is_local = host in ["localhost", "127.0.0.1"]
    grpc_port = 50051 if is_local else port + 1  # 远程通常使用相同端口或 +1
    
    client = weaviate.connect_to_local(
        host=host,
        port=port,
        grpc_port=grpc_port
    )
    
    try:
        # ===== 1. 会话元数据集合 =====
        if client.collections.exists("SilkSession"):
            print("⚠️  删除已存在的 SilkSession 集合...")
            client.collections.delete("SilkSession")
        
        print("📦 创建 SilkSession 集合 (会话元数据)...")
        
        client.collections.create(
            name="SilkSession",
            description="会话/群组元数据，用于用户权限管理",
            
            vectorizer_config=Configure.Vectorizer.none(),  # 不需要向量化
            
            properties=[
                Property(
                    name="sessionId",
                    data_type=DataType.TEXT,
                    description="会话唯一标识",
                    tokenization=Tokenization.FIELD,
                    skip_vectorization=True
                ),
                Property(
                    name="sessionName",
                    data_type=DataType.TEXT,
                    description="会话名称",
                    skip_vectorization=True
                ),
                Property(
                    name="participants",
                    data_type=DataType.TEXT_ARRAY,
                    description="参与用户 ID 列表",
                    skip_vectorization=True
                ),
                Property(
                    name="ownerId",
                    data_type=DataType.TEXT,
                    description="创建者/所有者 ID",
                    tokenization=Tokenization.FIELD,
                    skip_vectorization=True
                ),
                Property(
                    name="createdAt",
                    data_type=DataType.DATE,
                    description="创建时间",
                    skip_vectorization=True
                ),
                Property(
                    name="lastActiveAt",
                    data_type=DataType.DATE,
                    description="最后活跃时间",
                    skip_vectorization=True
                ),
                Property(
                    name="isArchived",
                    data_type=DataType.BOOL,
                    description="是否已归档",
                    skip_vectorization=True
                ),
            ]
        )
        print("✅ SilkSession 创建成功")
        
        # ===== 2. 主内容集合 =====
        if client.collections.exists("SilkContext"):
            print("⚠️  删除已存在的 SilkContext 集合...")
            client.collections.delete("SilkContext")
        
        print("📦 创建 SilkContext 集合 (主内容)...")
        
        client.collections.create(
            name="SilkContext",
            description="Silk 统一上下文索引 - 支持多用户隔离",
            
            vectorizer_config=Configure.Vectorizer.none(),  # 使用 BM25 搜索，不需要向量化
            
            properties=[
                # ===== 核心内容 =====
                Property(
                    name="content",
                    data_type=DataType.TEXT,
                    description="主要文本内容",
                    tokenization=Tokenization.WORD,
                    skip_vectorization=False
                ),
                Property(
                    name="title",
                    data_type=DataType.TEXT,
                    description="标题或文件名",
                    tokenization=Tokenization.WORD,
                    skip_vectorization=False
                ),
                Property(
                    name="summary",
                    data_type=DataType.TEXT,
                    description="内容摘要",
                    skip_vectorization=False
                ),
                
                # ===== 多用户隔离关键字段 =====
                Property(
                    name="sessionId",
                    data_type=DataType.TEXT,
                    description="所属会话 ID",
                    tokenization=Tokenization.FIELD,
                    skip_vectorization=True,
                    index_filterable=True,  # 必须可过滤
                    index_searchable=False
                ),
                Property(
                    name="participants",
                    data_type=DataType.TEXT_ARRAY,
                    description="可访问此内容的用户 ID 列表 (从会话继承)",
                    skip_vectorization=True,
                    index_filterable=True  # 必须可过滤
                ),
                Property(
                    name="authorId",
                    data_type=DataType.TEXT,
                    description="内容作者 ID",
                    tokenization=Tokenization.FIELD,
                    skip_vectorization=True,
                    index_filterable=True
                ),
                Property(
                    name="authorName",
                    data_type=DataType.TEXT,
                    description="作者名称",
                    skip_vectorization=True
                ),
                
                # ===== 分类与来源 =====
                Property(
                    name="sourceType",
                    data_type=DataType.TEXT,
                    description="CHAT, FILE, WEBPAGE, TOOL_OUTPUT, AI_GENERATED",
                    tokenization=Tokenization.FIELD,
                    skip_vectorization=True,
                    index_filterable=True
                ),
                Property(
                    name="fileType",
                    data_type=DataType.TEXT,
                    description="文件类型",
                    tokenization=Tokenization.FIELD,
                    skip_vectorization=True
                ),
                Property(
                    name="mimeType",
                    data_type=DataType.TEXT,
                    skip_vectorization=True
                ),
                
                # ===== 路径与来源 =====
                Property(
                    name="filePath",
                    data_type=DataType.TEXT,
                    description="文件路径",
                    skip_vectorization=True
                ),
                Property(
                    name="sourceUrl",
                    data_type=DataType.TEXT,
                    description="来源 URL",
                    skip_vectorization=True
                ),
                
                # ===== 时间线 =====
                Property(
                    name="timestamp",
                    data_type=DataType.DATE,
                    description="内容时间戳",
                    skip_vectorization=True,
                    index_filterable=True,
                    index_range_filters=True  # 支持时间范围查询
                ),
                Property(
                    name="indexedAt",
                    data_type=DataType.DATE,
                    description="索引时间",
                    skip_vectorization=True
                ),
                
                # ===== 分块信息 =====
                Property(
                    name="chunkIndex",
                    data_type=DataType.INT,
                    skip_vectorization=True
                ),
                Property(
                    name="totalChunks",
                    data_type=DataType.INT,
                    skip_vectorization=True
                ),
                Property(
                    name="parentId",
                    data_type=DataType.TEXT,
                    skip_vectorization=True
                ),
                
                # ===== 元数据 =====
                Property(
                    name="tags",
                    data_type=DataType.TEXT_ARRAY,
                    skip_vectorization=True,
                    index_filterable=True
                ),
                Property(
                    name="metadata",
                    data_type=DataType.OBJECT,
                    skip_vectorization=True,
                    nested_properties=[
                        Property(name="author", data_type=DataType.TEXT),
                        Property(name="pageCount", data_type=DataType.INT),
                        Property(name="wordCount", data_type=DataType.INT),
                        Property(name="language", data_type=DataType.TEXT),
                        Property(name="toolName", data_type=DataType.TEXT),
                    ]
                ),
                
                # ===== 搜索权重提示 =====
                Property(
                    name="importance",
                    data_type=DataType.NUMBER,
                    description="重要性权重 (0-1)，用于排序",
                    skip_vectorization=True
                ),
            ],
            
            inverted_index_config=Configure.inverted_index(
                bm25_b=0.75,
                bm25_k1=1.2,
                index_timestamps=True,
                index_null_state=True,
                index_property_length=True
            ),
            
            vector_index_config=Configure.VectorIndex.hnsw(
                distance_metric=weaviate.classes.config.VectorDistances.COSINE,
                ef_construction=128,
                max_connections=64,
                ef=100
            ),
        )
        print("✅ SilkContext 创建成功")
        
        # ===== 3. 图像集合 =====
        if client.collections.exists("SilkImages"):
            client.collections.delete("SilkImages")
        
        print("📦 创建 SilkImages 集合...")
        
        client.collections.create(
            name="SilkImages",
            description="图像索引 - 支持多用户隔离",
            
            vectorizer_config=Configure.Vectorizer.none(),  # 不需要向量化
            
            properties=[
                Property(name="image", data_type=DataType.BLOB),
                Property(name="caption", data_type=DataType.TEXT),
                Property(name="ocrText", data_type=DataType.TEXT),
                Property(
                    name="sessionId",
                    data_type=DataType.TEXT,
                    tokenization=Tokenization.FIELD,
                    skip_vectorization=True,
                    index_filterable=True
                ),
                Property(
                    name="participants",
                    data_type=DataType.TEXT_ARRAY,
                    skip_vectorization=True,
                    index_filterable=True
                ),
                Property(name="filePath", data_type=DataType.TEXT, skip_vectorization=True),
                Property(name="timestamp", data_type=DataType.DATE, skip_vectorization=True),
                Property(name="authorId", data_type=DataType.TEXT, skip_vectorization=True),
            ]
        )
        print("✅ SilkImages 创建成功")
        
        # 验证
        print("\n📊 Schema 创建完成:")
        for name in ["SilkSession", "SilkContext", "SilkImages"]:
            col = client.collections.get(name)
            props = col.config.get().properties
            print(f"   - {name}: {len(props)} 个属性")
        
        print("\n🔐 多用户隔离设计:")
        print("   - participants: 用户 ID 数组，用于权限过滤")
        print("   - sessionId: 会话 ID，用于 foreground/background 分离")
        print("   - authorId: 作者 ID，用于内容归属")
        
    finally:
        client.close()


if __name__ == "__main__":
    create_schema()
