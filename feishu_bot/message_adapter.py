"""
消息格式转换：飞书 ↔ Silk

飞书消息格式（text/post/file 等）与 Silk Message JSON 之间的互转。
"""

import json
import logging
import time
import uuid

logger = logging.getLogger(__name__)


def feishu_text_to_silk_message(
    user_id: str,
    user_name: str,
    text: str,
    msg_type: str = "TEXT",
) -> str:
    """将飞书纯文本转为 Silk WebSocket Message JSON"""
    message = {
        "id": str(uuid.uuid4()),
        "userId": user_id,
        "userName": user_name,
        "content": text,
        "timestamp": int(time.time() * 1000),
        "type": msg_type,
        "isTransient": False,
        "isIncremental": False,
        "category": "NORMAL",
    }
    return json.dumps(message, ensure_ascii=False)


def build_markdown_card(title: str, content: str, template: str = "blue") -> dict:
    """构建飞书 Markdown 卡片"""
    # 限制卡片表格数量（飞书限制约 5 个）
    content = _limit_card_tables(content)
    return {
        "config": {"wide_screen_mode": True},
        "header": {
            "title": {"tag": "plain_text", "content": title},
            "template": template,
        },
        "elements": [
            {"tag": "markdown", "content": content},
        ],
    }


def build_streaming_card(content: str, is_final: bool = False) -> dict:
    """构建流式回复的飞书卡片

    进行中显示蓝色，完成后显示绿色。
    """
    content = _limit_card_tables(content)
    template = "green" if is_final else "blue"
    title = "Silk" if is_final else "Silk 思考中..."
    return {
        "config": {"wide_screen_mode": True},
        "header": {
            "title": {"tag": "plain_text", "content": title},
            "template": template,
        },
        "elements": [
            {"tag": "markdown", "content": content or "..."},
        ],
    }


def _scan_tables(text: str) -> list[tuple[int, int]]:
    """扫描 markdown 文本中代码块外的表格，返回 (start, end) 行号列表"""
    lines = text.split("\n")
    tables: list[tuple[int, int]] = []
    in_fence = False
    i = 0
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped.startswith("```"):
            in_fence = not in_fence
            i += 1
            continue
        if not in_fence and stripped.startswith("|") and stripped.endswith("|") and i + 1 < len(lines):
            sep = lines[i + 1].strip()
            if sep.startswith("|") and "---" in sep:
                start = i
                j = i + 2
                while j < len(lines) and lines[j].strip().startswith("|"):
                    j += 1
                tables.append((start, j))
                i = j
                continue
        i += 1
    return tables


def _limit_card_tables(text: str, max_tables: int = 5) -> str:
    """限制 markdown 表格数量，超出部分转为代码块"""
    tables = _scan_tables(text)
    if len(tables) <= max_tables:
        return text
    lines = text.split("\n")
    for start, end in reversed(tables[max_tables:]):
        table_lines = lines[start:end]
        lines[start:end] = ["```", *table_lines, "```"]
    return "\n".join(lines)
