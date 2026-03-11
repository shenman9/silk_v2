#!/usr/bin/env python3
"""
重新索引所有上传文件到 Weaviate
修复 sessionId 前缀问题，并正确提取文本内容
"""

import os
import json
import uuid
import requests
from datetime import datetime
from pathlib import Path

WEAVIATE_URL = "http://localhost:8008"
CHAT_HISTORY_DIR = "/Users/mac/Documents/Silk/backend/chat_history"

def get_file_content(file_path: str) -> str:
    """读取文件内容"""
    ext = Path(file_path).suffix.lower()
    
    # 文本文件扩展名
    text_extensions = {'.txt', '.md', '.markdown', '.json', '.xml', '.html', '.htm',
                       '.css', '.js', '.kt', '.java', '.py', '.yaml', '.yml', '.csv'}
    
    if ext in text_extensions:
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                content = f.read()
                print(f"   📝 读取文本文件: {len(content)} 字符")
                return content[:50000]  # 限制长度
        except Exception as e:
            print(f"   ⚠️ 读取失败: {e}")
            return f"[无法读取文件: {os.path.basename(file_path)}]"
    
    elif ext == '.pdf':
        try:
            import subprocess
            # 使用 pdftotext 提取（如果有的话）
            result = subprocess.run(['pdftotext', '-layout', file_path, '-'],
                                    capture_output=True, text=True, timeout=30)
            if result.returncode == 0 and result.stdout.strip():
                content = result.stdout.strip()
                print(f"   📄 PDF 提取: {len(content)} 字符")
                return content[:50000]
        except:
            pass
        return f"[PDF 文件: {os.path.basename(file_path)}]"
    
    else:
        return f"[二进制文件: {os.path.basename(file_path)}, 大小: {os.path.getsize(file_path)} bytes]"

def delete_old_file_records():
    """删除旧的 FILE 类型记录"""
    query = """
    {
        Get {
            SilkContext(where: { path: ["sourceType"], operator: Equal, valueText: "FILE" }, limit: 100) {
                _additional { id }
            }
        }
    }
    """
    
    try:
        response = requests.post(f"{WEAVIATE_URL}/v1/graphql",
                                json={"query": query})
        data = response.json()
        
        objects = data.get("data", {}).get("Get", {}).get("SilkContext", [])
        
        for obj in objects:
            obj_id = obj.get("_additional", {}).get("id")
            if obj_id:
                requests.delete(f"{WEAVIATE_URL}/v1/objects/SilkContext/{obj_id}")
                print(f"   🗑️ 删除旧记录: {obj_id[:8]}...")
        
        print(f"✅ 删除了 {len(objects)} 条旧记录")
        return True
    except Exception as e:
        print(f"❌ 删除失败: {e}")
        return False

def index_file(file_path: str, session_id: str, user_id: str = "reindex-user"):
    """索引单个文件到 Weaviate"""
    filename = os.path.basename(file_path)
    content = get_file_content(file_path)
    
    # 确保 sessionId 有 group_ 前缀
    normalized_session_id = session_id if session_id.startswith("group_") else f"group_{session_id}"
    
    # 创建 Weaviate 对象
    obj = {
        "class": "SilkContext",
        "id": str(uuid.uuid4()),
        "properties": {
            "content": content,
            "title": filename,
            "sourceType": "FILE",
            "fileType": Path(file_path).suffix.upper().replace(".", ""),
            "sessionId": normalized_session_id,
            "authorId": user_id,
            "filePath": file_path,
            "timestamp": datetime.now().isoformat() + "Z",
            "importance": 0.7
        }
    }
    
    try:
        response = requests.post(f"{WEAVIATE_URL}/v1/objects",
                                json=obj,
                                headers={"Content-Type": "application/json"})
        if response.status_code in [200, 201]:
            print(f"   ✅ 索引成功: {filename} -> {normalized_session_id}")
            return True
        else:
            print(f"   ❌ 索引失败: {response.text}")
            return False
    except Exception as e:
        print(f"   ❌ 索引异常: {e}")
        return False

def main():
    print("🔄 重新索引所有上传文件")
    print("=" * 50)
    
    # 检查 Weaviate
    try:
        response = requests.get(f"{WEAVIATE_URL}/v1/.well-known/ready", timeout=5)
        if not response.ok:
            print("❌ Weaviate 未就绪")
            return
    except:
        print("❌ Weaviate 未运行")
        return
    
    print("✅ Weaviate 已就绪")
    
    # 删除旧的文件记录
    print("\n🗑️ 清理旧的文件索引...")
    delete_old_file_records()
    
    # 遍历所有会话文件夹
    print("\n📁 重新索引文件...")
    total_indexed = 0
    
    for session_dir in Path(CHAT_HISTORY_DIR).iterdir():
        if not session_dir.is_dir():
            continue
            
        uploads_dir = session_dir / "uploads"
        if not uploads_dir.exists():
            continue
        
        session_id = session_dir.name
        print(f"\n📂 会话: {session_id}")
        
        # 尝试获取用户 ID（从 session.json）
        user_id = "unknown-user"
        session_file = session_dir / "session.json"
        if session_file.exists():
            try:
                with open(session_file, 'r') as f:
                    session_data = json.load(f)
                    messages = session_data.get("messages", [])
                    if messages:
                        # 获取第一个非 AI 用户的 ID
                        for msg in messages:
                            sender_id = msg.get("senderId", "")
                            if sender_id and "silk" not in sender_id.lower() and "ai" not in sender_id.lower():
                                user_id = sender_id
                                break
            except:
                pass
        
        for file_path in uploads_dir.iterdir():
            if file_path.is_file():
                if index_file(str(file_path), session_id, user_id):
                    total_indexed += 1
    
    print("\n" + "=" * 50)
    print(f"✅ 完成！共索引 {total_indexed} 个文件")

if __name__ == "__main__":
    main()

