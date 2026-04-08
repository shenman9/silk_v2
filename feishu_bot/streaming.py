"""
Silk 流式回复 → 飞书卡片实时更新

跟踪 Silk AI 的流式回复（transient + incremental 消息），
转换为飞书卡片的创建和 patch 更新，实现打字机效果。

Silk 消息序列:
1. SYSTEM transient: "🤖 正在处理您的问题..."  (状态提示)
2. SYSTEM transient: "🤔 思考中..."            (状态提示)
3. TEXT transient+incremental: "第一段..."      (流式内容片段)
4. TEXT transient+incremental: "第二段..."      (流式内容片段)
5. TEXT non-transient: "完整内容..."            (最终完整消息)
"""

import json
import logging
import time
import threading
from typing import Optional, Callable

from message_adapter import build_streaming_card

logger = logging.getLogger(__name__)

_SILK_AI_USER_ID = "silk_ai_agent"


_SESSION_TIMEOUT = 300  # 流式会话超时（秒），防止 AI 断连时会话永远不被清理


class StreamingSession:
    """跟踪一次 Silk AI 流式回复"""

    def __init__(self):
        self.feishu_message_id: Optional[str] = None
        self.last_patch_time: float = 0
        self.created_at: float = time.time()
        self.active: bool = False
        # 状态文本（SYSTEM 消息，如"思考中..."）
        self.status: str = ""
        # AI 正文内容（TEXT 消息的累积）
        self.text_buffer: str = ""


class StreamingManager:
    """管理所有用户的流式回复会话"""

    def __init__(
        self,
        send_card_fn: Callable[[str, dict], Optional[str]],
        patch_card_fn: Callable[[str, str], bool],
        stream_interval: float = 0.5,
    ):
        self._send_card = send_card_fn
        self._patch_card = patch_card_fn
        self._interval = stream_interval
        self._sessions: dict[str, StreamingSession] = {}
        self._lock = threading.Lock()

    def handle_silk_message(self, feishu_chat_id: str, silk_msg: dict) -> bool:
        """处理一条 Silk 消息，判断是否为 AI 流式回复并处理"""
        sender_id = silk_msg.get("userId", "")
        is_transient = silk_msg.get("isTransient", False)
        is_incremental = silk_msg.get("isIncremental", False)
        content = silk_msg.get("content", "")
        msg_type = silk_msg.get("type", "TEXT")

        if sender_id != _SILK_AI_USER_ID:
            return False

        # 清理超时的流式会话（防止 AI 断连导致会话永远不被关闭）
        self._cleanup_stale_sessions()

        # 在锁内更新状态，决定要执行什么操作
        action = None  # "send_new", "patch", "finalize_patch", "finalize_send"
        card_content = None
        message_id = None

        with self._lock:
            session = self._sessions.get(feishu_chat_id)

            if is_transient:
                if session is None:
                    session = StreamingSession()
                    session.active = True
                    self._sessions[feishu_chat_id] = session

                if msg_type == "SYSTEM":
                    # SYSTEM 消息只更新状态，不追加到正文
                    session.status = content
                    # 还没有正文时，用状态消息创建/更新卡片
                    if not session.text_buffer:
                        action, card_content, message_id = self._plan_update(
                            session, session.status,
                        )
                    return True

                # TEXT 类型：流式正文内容
                if is_incremental:
                    session.text_buffer += content
                else:
                    session.text_buffer = content

                action, card_content, message_id = self._plan_update(
                    session, session.text_buffer,
                )

            else:
                # 非 transient = 最终完整消息
                if session and session.active:
                    session.active = False
                    final_content = content if content else session.text_buffer
                    message_id = session.feishu_message_id
                    card_content = final_content
                    action = "finalize_patch" if message_id else "finalize_send"
                    del self._sessions[feishu_chat_id]
                else:
                    return False

        # 锁外执行飞书 API 调用
        self._execute_action(action, feishu_chat_id, card_content, message_id)
        return True

    def _plan_update(self, session: StreamingSession, display_content: str):
        """在锁内决定要执行什么操作"""
        now = time.time()
        if session.feishu_message_id is None:
            return "send_new", display_content, None
        elif (now - session.last_patch_time) >= self._interval:
            return "patch", display_content, session.feishu_message_id
        return None, None, None

    def _execute_action(
        self, action, feishu_chat_id: str, card_content: str, message_id: str,
    ):
        """在锁外执行飞书 API 调用"""
        if action is None:
            return

        if action == "send_new":
            card = build_streaming_card(card_content, is_final=False)
            msg_id = self._send_card(feishu_chat_id, card)
            if msg_id:
                with self._lock:
                    s = self._sessions.get(feishu_chat_id)
                    if s:
                        s.feishu_message_id = msg_id
                        s.last_patch_time = time.time()
        elif action == "patch":
            card = build_streaming_card(card_content, is_final=False)
            card_json = json.dumps(card, ensure_ascii=False)
            self._patch_card(message_id, card_json)
            with self._lock:
                s = self._sessions.get(feishu_chat_id)
                if s:
                    s.last_patch_time = time.time()
        elif action == "finalize_patch":
            card = build_streaming_card(card_content, is_final=True)
            card_json = json.dumps(card, ensure_ascii=False)
            self._patch_card(message_id, card_json)
        elif action == "finalize_send":
            card = build_streaming_card(card_content, is_final=True)
            self._send_card(feishu_chat_id, card)

    def _cleanup_stale_sessions(self) -> None:
        """清理超时的流式会话"""
        now = time.time()
        with self._lock:
            stale = [
                cid for cid, s in self._sessions.items()
                if now - s.created_at > _SESSION_TIMEOUT
            ]
            for cid in stale:
                logger.warning("清理超时流式会话: %s", cid)
                del self._sessions[cid]
