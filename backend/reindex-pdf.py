#!/usr/bin/env python3
"""
重新索引 PDF 文件脚本
读取 PDF 文件内容，清理中文空格，然后索引到 Weaviate
"""

import os
import re
import json
import requests
from datetime import datetime

try:
    import pdfplumber
except ImportError:
    print("正在安装 pdfplumber...")
    os.system("pip3 install pdfplumber -q")
    import pdfplumber

try:
    import jieba
except ImportError:
    print("正在安装 jieba (中文分词)...")
    os.system("pip3 install jieba -q")
    import jieba

WEAVIATE_URL = "http://localhost:8008"
SESSION_ID = "group_4fe989d0-c127-45a2-8630-9dcbb92149a0"
UPLOADS_DIR = "/root/Silk/chat_history/4fe989d0-c127-45a2-8630-9dcbb92149a0/uploads"

def clean_chinese_text(text):
    """
    清理并分词中文文本，优化 BM25 搜索
    使用 jieba 分词，在中文词之间插入空格
    """
    result = text
    
    # 1. 移除原有的中文字符之间的空格
    prev = ""
    while prev != result:
        prev = result
        result = re.sub(r'([\u4e00-\u9fa5])\s+([\u4e00-\u9fa5])', r'\1\2', result)
    
    # 2. 使用 jieba 分词，在词之间插入空格
    # 这样 Weaviate BM25 就能正确识别中文词
    words = jieba.cut(result, cut_all=False)
    result = ' '.join(words)
    
    # 3. 清理多余空格
    result = re.sub(r'\s+', ' ', result)
    
    return result.strip()

def extract_pdf_text(pdf_path):
    """从 PDF 提取文本"""
    text = ""
    try:
        with pdfplumber.open(pdf_path) as pdf:
            for page in pdf.pages:
                page_text = page.extract_text()
                if page_text:
                    text += page_text + "\n"
    except Exception as e:
        print(f"  ❌ PDF 提取失败: {e}")
    return text

def index_document(title, content, file_path):
    """索引文档到 Weaviate"""
    cleaned_content = clean_chinese_text(content)
    cleaned_title = clean_chinese_text(title)
    
    # 生成关键词
    words = re.findall(r'[\u4e00-\u9fa5]+|[a-zA-Z]+', cleaned_content)
    keywords = list(set([w for w in words if len(w) >= 2]))[:20]
    
    # 生成摘要
    summary = f"文件: {cleaned_title} (类型: PDF) | 关键词: {', '.join(keywords[:7])} | {cleaned_content[:100]}..."
    
    data = {
        "class": "SilkContext",
        "properties": {
            "content": cleaned_content[:50000],  # 限制长度
            "title": cleaned_title,
            "summary": summary,
            "sourceType": "FILE",
            "fileType": "PDF",
            "sessionId": SESSION_ID,
            "filePath": file_path,
            "timestamp": datetime.now().isoformat() + "Z",
            "tags": keywords,
            "chunkIndex": 0,
            "totalChunks": 1,
            "importance": 0.5
        }
    }
    
    try:
        response = requests.post(
            f"{WEAVIATE_URL}/v1/objects",
            json=data,
            headers={"Content-Type": "application/json"}
        )
        if response.status_code in [200, 201]:
            print(f"  ✅ 索引成功: {cleaned_title}")
            return True
        else:
            print(f"  ❌ 索引失败: {response.status_code} - {response.text[:200]}")
            return False
    except Exception as e:
        print(f"  ❌ 请求失败: {e}")
        return False

def main():
    print("🔄 重新索引 PDF 文件...")
    print(f"   目录: {UPLOADS_DIR}")
    print(f"   Session: {SESSION_ID}")
    print()
    
    if not os.path.exists(UPLOADS_DIR):
        print(f"❌ 目录不存在: {UPLOADS_DIR}")
        return
    
    pdf_files = [f for f in os.listdir(UPLOADS_DIR) if f.lower().endswith('.pdf')]
    
    if not pdf_files:
        print("❌ 没有找到 PDF 文件")
        return
    
    print(f"📁 找到 {len(pdf_files)} 个 PDF 文件:")
    for f in pdf_files:
        print(f"   - {f}")
    print()
    
    success = 0
    for pdf_file in pdf_files:
        pdf_path = os.path.join(UPLOADS_DIR, pdf_file)
        print(f"📄 处理: {pdf_file}")
        
        # 提取文本
        text = extract_pdf_text(pdf_path)
        if not text:
            print(f"  ⚠️ 无法提取文本")
            continue
        
        print(f"   提取了 {len(text)} 字符")
        
        # 索引
        if index_document(pdf_file, text, pdf_path):
            success += 1
    
    print()
    print(f"✅ 完成！成功索引 {success}/{len(pdf_files)} 个文件")
    print()
    print("💡 现在可以搜索 '望远镜金额' 了！")

if __name__ == "__main__":
    main()
