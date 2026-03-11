#!/usr/bin/env python3
"""
Silk Context Indexer - 实时文档索引服务

功能：
- 监视会话文件夹的变化
- 解析多种文件格式 (PDF, DOCX, 图片等)
- 实时索引到 Weaviate
- 支持增量更新

运行: python indexer.py --watch /path/to/chat_history
"""

import os
import sys
import json
import time
import hashlib
import argparse
import mimetypes
from pathlib import Path
from datetime import datetime, timezone
from typing import Optional, Dict, Any, List
from dataclasses import dataclass
from concurrent.futures import ThreadPoolExecutor
import threading

import weaviate
from weaviate.classes.query import Filter
from weaviate.util import generate_uuid5
import requests
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler, FileCreatedEvent, FileModifiedEvent

# 配置
WEAVIATE_URL = "http://localhost:8008"
TIKA_URL = "http://localhost:9998"
CHUNK_SIZE = 1000  # 每个分块的最大字符数
CHUNK_OVERLAP = 100  # 分块重叠

# 支持的文件类型
SUPPORTED_TEXT_TYPES = {
    '.txt', '.md', '.json', '.yaml', '.yml', '.xml', '.html', '.htm',
    '.py', '.js', '.ts', '.kt', '.java', '.c', '.cpp', '.h', '.go', '.rs',
    '.sql', '.sh', '.bash', '.css', '.scss', '.less'
}

SUPPORTED_DOCUMENT_TYPES = {
    '.pdf', '.docx', '.doc', '.xlsx', '.xls', '.pptx', '.ppt',
    '.odt', '.ods', '.odp', '.rtf', '.epub'
}

SUPPORTED_IMAGE_TYPES = {
    '.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.tiff'
}


@dataclass
class IndexedDocument:
    """索引文档结构"""
    id: str
    content: str
    title: str
    source_type: str  # CHAT, FILE, WEBPAGE, TOOL_OUTPUT, AI_GENERATED
    file_type: Optional[str]
    mime_type: Optional[str]
    session_id: str
    participants: List[str]  # 可访问此文档的用户列表
    file_path: Optional[str]
    source_url: Optional[str]
    timestamp: datetime
    author_id: Optional[str]
    author_name: Optional[str]
    metadata: Dict[str, Any]
    tags: List[str]
    chunk_index: int = 0
    total_chunks: int = 1
    parent_id: Optional[str] = None
    importance: float = 0.5


class DocumentParser:
    """文档解析器 - 使用 Apache Tika"""
    
    def __init__(self, tika_url: str = TIKA_URL):
        self.tika_url = tika_url
    
    def parse(self, file_path: Path) -> Optional[str]:
        """解析文档，返回提取的文本"""
        try:
            with open(file_path, 'rb') as f:
                response = requests.put(
                    f"{self.tika_url}/tika",
                    data=f,
                    headers={
                        'Accept': 'text/plain',
                        'Content-Type': mimetypes.guess_type(str(file_path))[0] or 'application/octet-stream'
                    },
                    timeout=60
                )
                if response.status_code == 200:
                    return response.text.strip()
        except Exception as e:
            print(f"⚠️  Tika 解析失败 {file_path}: {e}")
        return None
    
    def get_metadata(self, file_path: Path) -> Dict[str, Any]:
        """获取文档元数据"""
        try:
            with open(file_path, 'rb') as f:
                response = requests.put(
                    f"{self.tika_url}/meta",
                    data=f,
                    headers={
                        'Accept': 'application/json',
                        'Content-Type': mimetypes.guess_type(str(file_path))[0] or 'application/octet-stream'
                    },
                    timeout=30
                )
                if response.status_code == 200:
                    return response.json()
        except Exception as e:
            print(f"⚠️  获取元数据失败: {e}")
        return {}


class TextChunker:
    """文本分块器 - 智能分块大文档"""
    
    def __init__(self, chunk_size: int = CHUNK_SIZE, overlap: int = CHUNK_OVERLAP):
        self.chunk_size = chunk_size
        self.overlap = overlap
    
    def chunk(self, text: str, doc_id: str) -> List[Dict[str, Any]]:
        """将长文本分成多个块"""
        if len(text) <= self.chunk_size:
            return [{"content": text, "chunk_index": 0, "total_chunks": 1, "parent_id": None}]
        
        chunks = []
        start = 0
        chunk_index = 0
        
        while start < len(text):
            end = start + self.chunk_size
            
            # 尝试在句子边界分割
            if end < len(text):
                # 查找最近的句号、换行符
                for sep in ['\n\n', '\n', '。', '.', '!', '?']:
                    sep_pos = text.rfind(sep, start + self.chunk_size // 2, end)
                    if sep_pos > start:
                        end = sep_pos + len(sep)
                        break
            
            chunk_text = text[start:end].strip()
            if chunk_text:
                chunks.append({
                    "content": chunk_text,
                    "chunk_index": chunk_index,
                    "parent_id": doc_id if chunk_index > 0 else None
                })
                chunk_index += 1
            
            start = end - self.overlap
        
        # 更新总块数
        for chunk in chunks:
            chunk["total_chunks"] = len(chunks)
        
        return chunks


class SilkIndexer:
    """Silk 上下文索引器 - 支持多用户隔离"""
    
    def __init__(self, weaviate_url: str = WEAVIATE_URL):
        self.client = weaviate.connect_to_local(
            host="localhost",
            port=8085,
            grpc_port=50051
        )
        self.parser = DocumentParser()
        self.chunker = TextChunker()
        self.indexed_files: Dict[str, str] = {}  # file_path -> hash
        self.executor = ThreadPoolExecutor(max_workers=4)
        self.session_participants: Dict[str, List[str]] = {}  # session_id -> participants
        
    def close(self):
        self.client.close()
        self.executor.shutdown()
    
    def _file_hash(self, file_path: Path) -> str:
        """计算文件哈希"""
        hasher = hashlib.md5()
        with open(file_path, 'rb') as f:
            for chunk in iter(lambda: f.read(8192), b''):
                hasher.update(chunk)
        return hasher.hexdigest()
    
    def _generate_id(self, file_path: str, chunk_index: int = 0) -> str:
        """生成文档 ID"""
        return generate_uuid5(f"{file_path}:{chunk_index}")
    
    def index_chat_history(self, session_id: str, history_file: Path, participants: List[str] = None):
        """索引聊天历史 - 带用户隔离"""
        try:
            with open(history_file, 'r', encoding='utf-8') as f:
                data = json.load(f)
            
            messages = data if isinstance(data, list) else data.get('messages', [])
            
            # 如果没有提供 participants，从消息中提取
            if participants is None:
                participants = list(set(
                    msg.get('userId', msg.get('senderId', 'unknown'))
                    for msg in messages
                    if msg.get('userId') or msg.get('senderId')
                ))
            
            # 缓存会话参与者
            self.session_participants[session_id] = participants
            
            collection = self.client.collections.get("SilkContext")
            
            indexed = 0
            for msg in messages:
                doc_id = self._generate_id(f"{session_id}:{msg.get('id', msg.get('timestamp'))}")
                
                # 检查是否已存在
                try:
                    existing = collection.query.fetch_object_by_id(doc_id)
                    if existing:
                        continue
                except:
                    pass
                
                author_id = msg.get('userId', msg.get('senderId'))
                
                collection.data.insert(
                    uuid=doc_id,
                    properties={
                        "content": msg.get('content', ''),
                        "title": f"Chat: {msg.get('userName', 'Unknown')}",
                        "sourceType": "CHAT",
                        "fileType": "MESSAGE",
                        "sessionId": session_id,
                        "participants": participants,  # 用户隔离关键字段
                        "authorId": author_id,
                        "authorName": msg.get('userName', msg.get('senderName')),
                        "timestamp": datetime.fromtimestamp(
                            msg.get('timestamp', 0) / 1000, 
                            tz=timezone.utc
                        ).isoformat(),
                        "tags": msg.get('tags', []),
                        "importance": 0.5,
                        "metadata": {
                            "messageType": msg.get('type', 'TEXT')
                        }
                    }
                )
                indexed += 1
            
            if indexed > 0:
                print(f"✅ 索引了 {indexed} 条聊天消息 (session: {session_id}, participants: {len(participants)})")
                
        except Exception as e:
            print(f"❌ 索引聊天历史失败: {e}")
    
    def index_file(self, file_path: Path, session_id: str, participants: List[str] = None):
        """索引单个文件 - 带用户隔离"""
        suffix = file_path.suffix.lower()
        
        # 检查文件是否变化
        file_hash = self._file_hash(file_path)
        if str(file_path) in self.indexed_files:
            if self.indexed_files[str(file_path)] == file_hash:
                return  # 文件未变化
        
        # 获取会话参与者
        if participants is None:
            participants = self.session_participants.get(session_id, [])
        
        print(f"📄 索引文件: {file_path}")
        
        content = None
        file_type = "UNKNOWN"
        
        # 纯文本文件
        if suffix in SUPPORTED_TEXT_TYPES:
            try:
                content = file_path.read_text(encoding='utf-8')
                file_type = "TEXT" if suffix == '.txt' else suffix[1:].upper()
            except:
                content = file_path.read_text(encoding='latin-1')
        
        # 文档文件 (通过 Tika)
        elif suffix in SUPPORTED_DOCUMENT_TYPES:
            content = self.parser.parse(file_path)
            file_type = suffix[1:].upper()
        
        # 图片文件
        elif suffix in SUPPORTED_IMAGE_TYPES:
            self._index_image(file_path, session_id, participants)
            return
        
        if not content:
            print(f"⚠️  无法解析: {file_path}")
            return
        
        # 分块处理
        doc_id = self._generate_id(str(file_path))
        chunks = self.chunker.chunk(content, doc_id)
        
        collection = self.client.collections.get("SilkContext")
        mime_type = mimetypes.guess_type(str(file_path))[0]
        
        # 获取元数据
        metadata = self.parser.get_metadata(file_path) if suffix in SUPPORTED_DOCUMENT_TYPES else {}
        
        for chunk in chunks:
            chunk_id = self._generate_id(str(file_path), chunk["chunk_index"])
            
            collection.data.insert(
                uuid=chunk_id,
                properties={
                    "content": chunk["content"],
                    "title": file_path.name,
                    "sourceType": "FILE",
                    "fileType": file_type,
                    "mimeType": mime_type,
                    "sessionId": session_id,
                    "participants": participants,  # 用户隔离
                    "filePath": str(file_path),
                    "timestamp": datetime.fromtimestamp(
                        file_path.stat().st_mtime, 
                        tz=timezone.utc
                    ).isoformat(),
                    "chunkIndex": chunk["chunk_index"],
                    "totalChunks": chunk["total_chunks"],
                    "parentId": chunk.get("parent_id"),
                    "importance": 0.5,
                    "metadata": {
                        "author": metadata.get("Author"),
                        "pageCount": metadata.get("Page-Count"),
                        "wordCount": len(chunk["content"].split()),
                    },
                    "tags": []
                }
            )
        
        self.indexed_files[str(file_path)] = file_hash
        print(f"✅ 已索引: {file_path.name} ({len(chunks)} 块)")
    
    def _index_image(self, file_path: Path, session_id: str, participants: List[str] = None):
        """索引图片 (使用 CLIP) - 带用户隔离"""
        import base64
        
        if participants is None:
            participants = self.session_participants.get(session_id, [])
        
        try:
            with open(file_path, 'rb') as f:
                image_data = base64.b64encode(f.read()).decode('utf-8')
            
            collection = self.client.collections.get("SilkImages")
            doc_id = self._generate_id(str(file_path))
            
            # OCR (可选 - 需要 Tesseract)
            ocr_text = ""
            try:
                response = requests.put(
                    f"{TIKA_URL}/tika",
                    data=open(file_path, 'rb'),
                    headers={'Accept': 'text/plain'},
                    timeout=30
                )
                if response.status_code == 200:
                    ocr_text = response.text.strip()
            except:
                pass
            
            collection.data.insert(
                uuid=doc_id,
                properties={
                    "image": image_data,
                    "caption": file_path.stem,
                    "ocrText": ocr_text,
                    "sessionId": session_id,
                    "participants": participants,  # 用户隔离
                    "filePath": str(file_path),
                    "timestamp": datetime.fromtimestamp(
                        file_path.stat().st_mtime,
                        tz=timezone.utc
                    ).isoformat()
                }
            )
            
            self.indexed_files[str(file_path)] = self._file_hash(file_path)
            print(f"🖼️  已索引图片: {file_path.name}")
            
        except Exception as e:
            print(f"❌ 图片索引失败: {e}")
    
    def index_directory(self, directory: Path, session_id: str):
        """递归索引目录"""
        if not directory.exists():
            print(f"❌ 目录不存在: {directory}")
            return
        
        print(f"\n📁 扫描目录: {directory}")
        
        for item in directory.rglob('*'):
            if item.is_file():
                # 跳过隐藏文件和临时文件
                if item.name.startswith('.') or item.name.endswith('~'):
                    continue
                
                # 检查是否是聊天历史
                if item.name in ['chat_history.json', 'session.json', 'messages.json']:
                    self.index_chat_history(session_id, item)
                else:
                    self.index_file(item, session_id)
    
    def search(self, query: str, user_id: str, session_id: Optional[str] = None, 
               limit: int = 10, alpha: float = 0.5, 
               mode: str = "foreground_first") -> Dict[str, List[Dict]]:
        """
        隔离搜索 - 支持 foreground/background 分离
        
        Args:
            query: 搜索查询
            user_id: 当前用户 ID (用于权限过滤)
            session_id: 当前会话 ID (用于 foreground/background 分离)
            limit: 结果数量限制
            alpha: 混合搜索参数 (0=关键词, 1=语义)
            mode: 搜索模式
                - "foreground_only": 仅当前会话
                - "background_only": 仅其他会话
                - "foreground_first": 返回两者，分开显示
                - "merged": 合并排序
        
        Returns:
            {"foreground": [...], "background": [...]}
        """
        collection = self.client.collections.get("SilkContext")
        
        def execute_search(filters) -> List[Dict]:
            results = collection.query.hybrid(
                query=query,
                alpha=alpha,
                filters=filters,
                limit=limit,
                return_metadata=weaviate.classes.query.MetadataQuery(score=True)
            )
            return [
                {
                    "id": str(obj.uuid),
                    "content": obj.properties.get("content", "")[:500],
                    "title": obj.properties.get("title"),
                    "sourceType": obj.properties.get("sourceType"),
                    "sessionId": obj.properties.get("sessionId"),
                    "filePath": obj.properties.get("filePath"),
                    "score": obj.metadata.score if obj.metadata else 0,
                    "timestamp": obj.properties.get("timestamp")
                }
                for obj in results.objects
            ]
        
        # 用户权限过滤
        user_filter = Filter.by_property("participants").contains_any([user_id])
        
        foreground = []
        background = []
        
        if mode in ["foreground_only", "foreground_first", "merged"]:
            if session_id:
                # Foreground: 当前会话 + 用户权限
                fg_filter = user_filter & Filter.by_property("sessionId").equal(session_id)
                foreground = execute_search(fg_filter)
        
        if mode in ["background_only", "foreground_first", "merged"]:
            if session_id:
                # Background: 其他会话 + 用户权限
                bg_filter = user_filter & Filter.by_property("sessionId").not_equal(session_id)
                background = execute_search(bg_filter)
            else:
                # 没有指定会话，搜索用户所有可访问内容
                background = execute_search(user_filter)
        
        if mode == "merged":
            # 合并排序，foreground 加权
            for item in foreground:
                item["score"] = item["score"] * 1.5
                item["isForeground"] = True
            for item in background:
                item["isForeground"] = False
            
            merged = sorted(foreground + background, key=lambda x: x["score"], reverse=True)[:limit]
            return {
                "foreground": [x for x in merged if x.get("isForeground")],
                "background": [x for x in merged if not x.get("isForeground")],
                "merged": merged
            }
        
        return {
            "foreground": foreground,
            "background": background
        }


class FileWatcher(FileSystemEventHandler):
    """文件系统监视器 - 实时索引"""
    
    def __init__(self, indexer: SilkIndexer, base_path: Path):
        self.indexer = indexer
        self.base_path = base_path
        self._debounce = {}
        self._lock = threading.Lock()
    
    def _get_session_id(self, file_path: Path) -> str:
        """从路径提取会话 ID"""
        rel_path = file_path.relative_to(self.base_path)
        parts = rel_path.parts
        
        # 假设结构: chat_history/group_xxx/...
        for part in parts:
            if part.startswith('group_') or part.startswith('session_'):
                return part
        
        return "default"
    
    def _debounced_index(self, file_path: Path):
        """防抖处理"""
        with self._lock:
            key = str(file_path)
            now = time.time()
            
            if key in self._debounce:
                if now - self._debounce[key] < 1.0:
                    return
            
            self._debounce[key] = now
        
        session_id = self._get_session_id(file_path)
        
        if file_path.name in ['chat_history.json', 'session.json', 'messages.json']:
            self.indexer.index_chat_history(session_id, file_path)
        else:
            self.indexer.index_file(file_path, session_id)
    
    def on_created(self, event):
        if not event.is_directory:
            self._debounced_index(Path(event.src_path))
    
    def on_modified(self, event):
        if not event.is_directory:
            self._debounced_index(Path(event.src_path))


def main():
    parser = argparse.ArgumentParser(description='Silk Context Indexer - 多用户隔离搜索')
    parser.add_argument('--watch', type=str, help='监视目录路径')
    parser.add_argument('--index', type=str, help='一次性索引目录')
    parser.add_argument('--session', type=str, default='default', help='会话 ID')
    parser.add_argument('--user', type=str, default='default_user', help='用户 ID (用于搜索隔离)')
    parser.add_argument('--search', type=str, help='搜索查询')
    parser.add_argument('--mode', type=str, default='foreground_first',
                        choices=['foreground_only', 'background_only', 'foreground_first', 'merged'],
                        help='搜索模式')
    args = parser.parse_args()
    
    indexer = SilkIndexer()
    
    try:
        if args.search:
            print(f"\n🔍 隔离搜索: {args.search}")
            print(f"   用户: {args.user}")
            print(f"   会话: {args.session}")
            print(f"   模式: {args.mode}")
            
            results = indexer.search(
                args.search, 
                user_id=args.user,
                session_id=args.session if args.session != 'default' else None,
                mode=args.mode
            )
            
            if results.get("foreground"):
                print(f"\n📌 Foreground (当前会话) - {len(results['foreground'])} 条:")
                for i, r in enumerate(results['foreground'], 1):
                    print(f"  {i}. [{r['sourceType']}] {r['title']}")
                    print(f"     分数: {r['score']:.3f}")
                    print(f"     内容: {r['content'][:150]}...")
            
            if results.get("background"):
                print(f"\n📋 Background (其他会话) - {len(results['background'])} 条:")
                for i, r in enumerate(results['background'], 1):
                    print(f"  {i}. [{r['sourceType']}] {r['title']} (from: {r.get('sessionId', '?')})")
                    print(f"     分数: {r['score']:.3f}")
                    print(f"     内容: {r['content'][:150]}...")
        
        elif args.index:
            indexer.index_directory(Path(args.index), args.session)
        
        elif args.watch:
            watch_path = Path(args.watch)
            print(f"\n👁️  开始监视: {watch_path}")
            print("   按 Ctrl+C 停止\n")
            
            # 首先索引现有内容
            indexer.index_directory(watch_path, args.session)
            
            # 启动监视器
            event_handler = FileWatcher(indexer, watch_path)
            observer = Observer()
            observer.schedule(event_handler, str(watch_path), recursive=True)
            observer.start()
            
            try:
                while True:
                    time.sleep(1)
            except KeyboardInterrupt:
                observer.stop()
            observer.join()
        
        else:
            parser.print_help()
    
    finally:
        indexer.close()


if __name__ == "__main__":
    main()

