"""
飞书事件处理器

基于 lark-oapi SDK 的 WebSocket 长连接，接收飞书消息事件，
将消息转发到 Silk 后端，并将 Silk 回复转发回飞书用户。
"""

import json
import logging
import threading
import time
from collections import OrderedDict
from typing import Optional

import lark_oapi as lark
from lark_oapi.api.im.v1 import (
    CreateMessageRequest,
    CreateMessageRequestBody,
    PatchMessageRequest,
    PatchMessageRequestBody,
)
from lark_oapi.event.callback.model.p2_card_action_trigger import (
    P2CardActionTrigger,
    P2CardActionTriggerResponse,
)

from user_binding import UserBindingManager, SilkBinding
from silk_client import SilkClient
from message_adapter import (
    feishu_text_to_silk_message,
    build_markdown_card,
    build_streaming_card,
)
from streaming import StreamingManager

logger = logging.getLogger(__name__)

# 消息去重
_DEDUP_MAX_SIZE = 500
_DEDUP_TTL = 300


class FeishuHandler:
    """飞书 Silk 机器人：接收飞书消息 → 转发 Silk → 回复飞书"""

    def __init__(self, config: dict):
        self._config = config
        self.app_id = config["app_id"]
        self.app_secret = config["app_secret"]

        # 命令关键字
        self._bind_cmd = config.get("bind_command", "绑定")
        self._unbind_cmd = config.get("unbind_command", "解绑")
        self._help_cmd = config.get("help_command", "帮助")

        # 飞书 SDK 客户端
        self.client = lark.Client.builder() \
            .app_id(self.app_id) \
            .app_secret(self.app_secret) \
            .log_level(lark.LogLevel.INFO) \
            .build()

        # Silk 客户端
        silk_host = config.get("silk_host", "localhost")
        silk_port = config.get("silk_port", 8003)
        self.silk = SilkClient(silk_host, silk_port)

        # 账号绑定管理
        self.bindings = UserBindingManager()

        # 流式回复管理
        self.streaming = StreamingManager(
            send_card_fn=self._send_card_get_id,
            patch_card_fn=self._patch_message,
            stream_interval=config.get("stream_interval", 0.5),
        )

        # 消息去重
        self._seen: OrderedDict[str, float] = OrderedDict()
        self._seen_lock = threading.Lock()

        # 飞书事件处理器
        self._event_handler = lark.EventDispatcherHandler.builder("", "") \
            .register_p2_im_message_receive_v1(self._on_raw_message) \
            .register_p2_card_action_trigger(self._on_raw_card_action) \
            .build()

    # ---- 飞书事件入口 ----

    def _on_raw_message(self, data) -> None:
        """收到飞书消息事件的入口"""
        try:
            self._handle_message(data)
        except Exception as e:
            logger.error("处理消息异常: %s", e, exc_info=True)

    def _on_raw_card_action(self, data: P2CardActionTrigger) -> P2CardActionTriggerResponse:
        """收到卡片动作事件（表单提交、按钮点击）"""
        try:
            # 使用 open_id 作为用户标识，与消息事件保持一致
            user_id = data.event.operator.open_id
            chat_id = data.event.context.open_chat_id
            action_value = data.event.action.value or {}
            if data.event.action.form_value:
                action_value["_form_value"] = data.event.action.form_value
            logger.info("卡片动作: user=%s, action=%s", user_id, action_value)
            self._handle_card_action(user_id, chat_id, action_value)
        except Exception as e:
            logger.error("处理卡片事件异常: %s", e, exc_info=True)
        return P2CardActionTriggerResponse()

    def _handle_message(self, data) -> None:
        """处理飞书消息"""
        message = data.event.message
        sender_id = data.event.sender.sender_id.open_id
        chat_id = message.chat_id
        message_id = message.message_id
        msg_type = message.message_type
        chat_type = getattr(message, "chat_type", None) or "p2p"

        # 去重
        if self._is_duplicate(message_id):
            return

        # 只处理私聊消息（1对1 机器人模式）
        if chat_type != "p2p":
            logger.debug("忽略非私聊消息: chat_type=%s", chat_type)
            return

        # 提取文本
        if msg_type == "file":
            self._handle_file(sender_id, chat_id, message_id, message)
            return

        try:
            content_dict = json.loads(message.content)
        except (json.JSONDecodeError, AttributeError):
            logger.warning("消息内容解析失败: message_id=%s", message_id)
            return

        text = self._extract_text(msg_type, content_dict)
        if not text:
            self._reply_text(chat_id, "当前仅支持文本消息，请直接输入文字。")
            return

        logger.info("收到飞书消息: user=%s, text=%s", sender_id[:12], text[:100])

        # 路由处理
        if text.startswith(self._bind_cmd):
            self._handle_bind(sender_id, chat_id, text)
        elif text == self._unbind_cmd:
            self._handle_unbind(sender_id, chat_id)
        elif text == self._help_cmd:
            self._send_help(chat_id)
        else:
            self._handle_chat(sender_id, chat_id, text)

    # ---- 命令处理 ----

    def _handle_bind(self, feishu_id: str, chat_id: str, text: str) -> None:
        """处理绑定命令：发送表单卡片或直接绑定（向后兼容）"""
        parts = text.split()
        if len(parts) == 3:
            # 老方式：绑定 用户名 密码（向后兼容）
            _, login_name, password = parts
            self._do_bind(feishu_id, chat_id, login_name, password)
        else:
            # 新方式：发送卡片表单
            existing = self.bindings.get(feishu_id)
            if existing:
                self._reply_text(
                    chat_id,
                    f"你已绑定 Silk 账号 {existing.silk_user_name}。\n"
                    f"如需更换，请先发送「{self._unbind_cmd}」解绑。"
                )
                return
            card = self._build_bind_card()
            self._send_card(chat_id, card)

    def _handle_card_action(self, feishu_id: str, chat_id: str, action_value: dict) -> None:
        """处理卡片动作（表单提交）"""
        action = action_value.get("action", "")

        if action == "bind_form_submit" or "_form_value" in action_value:
            form = action_value.get("_form_value", {})
            username = (form.get("silk_username") or "").strip()
            password = (form.get("silk_password") or "").strip()
            if not username or not password:
                self._reply_text(chat_id, "用户名和密码不能为空。")
                return
            self._do_bind(feishu_id, chat_id, username, password)

    def _do_bind(self, feishu_id: str, chat_id: str, login_name: str, password: str) -> None:
        """执行绑定：验证 Silk 账号并建立连接"""
        existing = self.bindings.get(feishu_id)
        if existing:
            self._reply_text(
                chat_id,
                f"你已绑定 Silk 账号 {existing.silk_user_name}。\n"
                f"如需更换，请先发送「{self._unbind_cmd}」解绑。"
            )
            return

        self._reply_text(chat_id, "正在验证 Silk 账号...")
        user = self.silk.login(login_name, password)
        if not user:
            self._reply_text(chat_id, "绑定失败：用户名或密码错误。")
            return

        silk_user_id = user["id"]
        silk_user_name = user["loginName"]
        silk_full_name = user.get("fullName", silk_user_name)

        group_id = self.silk.start_silk_private_chat(silk_user_id)
        if not group_id:
            self._reply_text(chat_id, "绑定失败：无法创建 Silk 对话空间，请稍后重试。")
            return

        binding = SilkBinding(
            silk_user_id=silk_user_id,
            silk_user_name=silk_user_name,
            silk_full_name=silk_full_name,
            group_id=group_id,
        )
        self.bindings.bind(feishu_id, binding)
        self._ensure_silk_connection(feishu_id, chat_id, binding)

        self._reply_text(
            chat_id,
            f"绑定成功！\n"
            f"Silk 账号: {silk_full_name} ({silk_user_name})\n"
            f"现在可以直接发消息与 Silk AI 对话了。"
        )

    def _build_bind_card(self) -> dict:
        """构建绑定表单卡片"""
        return {
            "config": {"wide_screen_mode": True},
            "header": {
                "title": {"tag": "plain_text", "content": "绑定 Silk 账号"},
                "template": "blue",
            },
            "elements": [
                {
                    "tag": "form",
                    "name": "bind_form",
                    "elements": [
                        {
                            "tag": "input",
                            "name": "silk_username",
                            "input_type": "text",
                            "width": "fill",
                            "label": {"tag": "plain_text", "content": "用户名"},
                            "placeholder": {"tag": "plain_text", "content": "输入 Silk 用户名"},
                        },
                        {
                            "tag": "input",
                            "name": "silk_password",
                            "input_type": "password",
                            "width": "fill",
                            "label": {"tag": "plain_text", "content": "密码"},
                            "placeholder": {"tag": "plain_text", "content": "输入 Silk 密码"},
                        },
                        {
                            "tag": "button",
                            "name": "submit_bind",
                            "text": {"tag": "plain_text", "content": "绑定"},
                            "type": "primary",
                            "form_action_type": "submit",
                            "value": {"action": "bind_form_submit"},
                        },
                    ],
                },
            ],
        }

    def _handle_unbind(self, feishu_id: str, chat_id: str) -> None:
        """处理解绑命令"""
        binding = self.bindings.get(feishu_id)
        if not binding:
            self._reply_text(chat_id, "你还没有绑定 Silk 账号。")
            return

        # 断开 Silk 连接
        self.silk.disconnect(binding.silk_user_id, binding.group_id)
        self.bindings.unbind(feishu_id)
        self._reply_text(chat_id, "已解绑 Silk 账号。")

    def _send_help(self, chat_id: str) -> None:
        """发送帮助信息"""
        help_text = (
            f"**Silk 飞书机器人**\n\n"
            f"**绑定账号**: 发送 `{self._bind_cmd}`，在弹出的表单中填写用户名和密码\n"
            f"**解绑账号**: 发送 `{self._unbind_cmd}`\n"
            f"**查看帮助**: 发送 `{self._help_cmd}`\n\n"
            f"绑定后，直接发送文字即可与 Silk AI 对话。\n"
            f"支持所有 Silk 功能，包括 `/cc` 进入 Claude Code 模式。"
        )
        card = build_markdown_card("Silk 帮助", help_text, template="blue")
        self._send_card(chat_id, card)

    # ---- 对话转发 ----

    def _handle_chat(self, feishu_id: str, chat_id: str, text: str) -> None:
        """将用户消息转发到 Silk"""
        binding = self.bindings.get(feishu_id)
        if not binding:
            self._reply_text(
                chat_id,
                f"请先绑定 Silk 账号：发送「{self._bind_cmd}」开始绑定\n"
                f"发送「{self._help_cmd}」查看更多。"
            )
            return

        # 确保连接存在
        conn = self._ensure_silk_connection(feishu_id, chat_id, binding)

        # 构造 Silk 消息并发送
        silk_msg = feishu_text_to_silk_message(
            user_id=binding.silk_user_id,
            user_name=binding.silk_full_name,
            text=text,
        )
        conn.send(silk_msg)
        logger.info("转发到 Silk: user=%s, text=%s", binding.silk_user_name, text[:80])

    def _handle_file(
        self, feishu_id: str, chat_id: str, message_id: str, message,
    ) -> None:
        """处理飞书文件消息"""
        binding = self.bindings.get(feishu_id)
        if not binding:
            self._reply_text(
                chat_id,
                f"请先绑定 Silk 账号：发送「{self._bind_cmd}」开始绑定"
            )
            return

        try:
            content_dict = json.loads(message.content)
            file_name = content_dict.get("file_name", "unknown")
            self._reply_text(chat_id, f"文件接收功能开发中，暂不支持发送文件「{file_name}」。")
        except Exception as e:
            logger.error("处理文件消息异常: %s", e)
            self._reply_text(chat_id, "文件处理失败，请稍后重试。")

    # ---- Silk 回调 ----

    def _on_silk_message(self, feishu_chat_id: str, silk_msg: dict) -> None:
        """收到 Silk 后端消息的回调"""
        sender_id = silk_msg.get("userId", "")
        msg_type = silk_msg.get("type", "TEXT")

        # 忽略 JOIN/LEAVE
        if msg_type in ("JOIN", "LEAVE"):
            return

        # 忽略非 AI 消息（用户自己的消息回显等）
        if sender_id != "silk_ai_agent":
            return

        logger.info("处理 AI 消息: type=%s, transient=%s, content=%s",
                     msg_type, silk_msg.get("isTransient"), silk_msg.get("content", "")[:80])

        # 尝试由流式管理器处理
        if self.streaming.handle_silk_message(feishu_chat_id, silk_msg):
            return

        # 非流式的完整 AI 回复（没有先行的 transient）
        content = silk_msg.get("content", "")
        if content:
            card = build_streaming_card(content, is_final=True)
            self._send_card(chat_id=feishu_chat_id, card=card)

    # ---- 辅助方法 ----

    def _ensure_silk_connection(
        self, feishu_id: str, feishu_chat_id: str, binding: SilkBinding,
    ):
        """确保用户有到 Silk 的 WebSocket 连接"""
        def on_msg(silk_msg: dict):
            self._on_silk_message(feishu_chat_id, silk_msg)

        return self.silk.connect(
            user_id=binding.silk_user_id,
            user_name=binding.silk_full_name,
            group_id=binding.group_id,
            on_message=on_msg,
        )

    def _is_duplicate(self, message_id: str) -> bool:
        with self._seen_lock:
            now = time.time()
            if message_id in self._seen:
                return True
            while self._seen:
                oldest_id, ts = next(iter(self._seen.items()))
                if now - ts > _DEDUP_TTL:
                    self._seen.pop(oldest_id)
                else:
                    break
            if len(self._seen) >= _DEDUP_MAX_SIZE:
                self._seen.popitem(last=False)
            self._seen[message_id] = now
            return False

    @staticmethod
    def _extract_text(msg_type: str, content_dict: dict) -> str:
        """从飞书消息中提取纯文本"""
        if msg_type == "text":
            return content_dict.get("text", "").strip()
        if msg_type == "post":
            paragraphs = content_dict.get("content")
            if isinstance(paragraphs, dict):
                for lang_content in paragraphs.values():
                    if isinstance(lang_content, dict):
                        paragraphs = lang_content.get("content", [])
                    else:
                        paragraphs = lang_content
                    break
            if not isinstance(paragraphs, list):
                return ""
            parts: list[str] = []
            for para in paragraphs:
                if not isinstance(para, list):
                    continue
                for elem in para:
                    if isinstance(elem, dict) and elem.get("tag") == "text":
                        t = elem.get("text", "").strip()
                        if t:
                            parts.append(t)
            return " ".join(parts)
        return ""

    # ---- 飞书消息发送 ----

    def _reply_text(self, chat_id: str, text: str) -> None:
        """发送文本消息到飞书"""
        self._send_message(chat_id, "text", json.dumps({"text": text}))

    def _send_card(self, chat_id: str, card: dict) -> None:
        """发送卡片消息到飞书（不关心 message_id）"""
        self._send_message(chat_id, "interactive", json.dumps(card, ensure_ascii=False))

    def _send_card_get_id(self, chat_id: str, card: dict) -> Optional[str]:
        """发送卡片消息并返回 message_id"""
        return self._send_message(
            chat_id, "interactive", json.dumps(card, ensure_ascii=False),
            return_id=True,
        )

    def _send_message(
        self, chat_id: str, msg_type: str, content: str,
        return_id: bool = False,
    ) -> Optional[str]:
        """发送飞书消息，return_id=True 时返回 message_id"""
        id_type = "open_id" if chat_id.startswith("ou_") else "chat_id"
        request = CreateMessageRequest.builder() \
            .receive_id_type(id_type) \
            .request_body(CreateMessageRequestBody.builder()
                .receive_id(chat_id)
                .msg_type(msg_type)
                .content(content)
                .build()) \
            .build()
        try:
            response = self.client.im.v1.message.create(request)
            if not response.success():
                logger.error("发送消息失败: code=%s, msg=%s", response.code, response.msg)
                return None
            if return_id:
                return response.data.message_id
            return None
        except Exception as e:
            logger.error("发送消息异常: %s", e)
            return None

    def _patch_message(self, message_id: str, content: str) -> bool:
        request = PatchMessageRequest.builder() \
            .message_id(message_id) \
            .request_body(PatchMessageRequestBody.builder()
                .content(content)
                .build()) \
            .build()
        try:
            response = self.client.im.v1.message.patch(request)
            if not response.success():
                logger.error("更新消息失败: code=%s, msg=%s", response.code, response.msg)
                return False
            return True
        except Exception as e:
            logger.error("更新消息异常: %s", e)
            return False

    # ---- 启动 ----

    def start(self) -> None:
        """启动飞书 WebSocket 长连接"""
        self._restore_connections()

        ws_client = lark.ws.Client(
            self.app_id, self.app_secret,
            event_handler=self._event_handler,
            log_level=lark.LogLevel.INFO,
        )
        logger.info("Silk 飞书机器人启动中...")
        ws_client.start()

    def _restore_connections(self) -> None:
        """启动时统计已绑定用户。连接将在用户首次发消息时按需建立。"""
        count = self.bindings.count()
        if count:
            logger.info("已加载 %d 个绑定用户，连接将在首次消息时建立", count)
