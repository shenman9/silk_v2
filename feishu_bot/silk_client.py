"""
Silk 后端客户端

通过 WebSocket 和 HTTP 与 Silk 后端交互：
- WebSocket: 实时收发聊天消息
- HTTP: 登录认证、创建群组、文件上传
"""

import asyncio
import json
import logging
import queue
import threading
from typing import Callable, Optional

import requests
import websockets
import websockets.exceptions

logger = logging.getLogger(__name__)

# WebSocket 重连参数
_RECONNECT_DELAY = 3  # 秒
_MAX_RECONNECT_DELAY = 60  # 最大重连间隔


class SilkConnection:
    """单个用户到 Silk 后端的 WebSocket 连接

    架构：
    - 一个独立 daemon 线程运行 asyncio 事件循环
    - 使用 threading.Event 做跨线程同步
    - 使用 thread-safe queue.Queue 做发送队列（不依赖 asyncio.Queue）
    - _on_message 回调在独立线程中执行，不阻塞 asyncio 事件循环
    """

    def __init__(
        self,
        ws_url: str,
        user_id: str,
        user_name: str,
        group_id: str,
        on_message: Callable[[dict], None],
    ):
        self.ws_url = ws_url
        self.user_id = user_id
        self.user_name = user_name
        self.group_id = group_id
        self._on_message = on_message

        self._ws: Optional[websockets.WebSocketClientProtocol] = None
        self._thread: Optional[threading.Thread] = None
        self._running = False

        # thread-safe 发送队列，不依赖 asyncio
        self._send_queue: queue.Queue[str] = queue.Queue()

        # 回调队列：保证消息按序处理，不会并发执行回调
        self._callback_queue: queue.Queue[dict] = queue.Queue()

        # 连接就绪信号：start() 阻塞等待直到 WebSocket 连接建立
        self._connected_event = threading.Event()

        # 历史消息过滤：Silk 连接建立时会推送最近 50 条历史消息。
        # 我们记录发出的第一条消息的 ID，收到该 ID 的回显后才开始转发。
        self._ready = False
        self._first_msg_id: Optional[str] = None

    @property
    def url(self) -> str:
        return (
            f"{self.ws_url}"
            f"?userId={self.user_id}"
            f"&userName={self.user_name}"
            f"&groupId={self.group_id}"
        )

    def start(self) -> None:
        """启动 WebSocket 连接线程，阻塞直到连接建立或超时"""
        if self._running:
            return
        self._running = True
        self._connected_event.clear()
        self._thread = threading.Thread(
            target=self._run_loop, daemon=True,
            name=f"silk-ws-{self.user_id[:8]}",
        )
        self._thread.start()
        # 启动回调消费线程（单线程顺序处理，避免并发竞态）
        threading.Thread(
            target=self._callback_consumer, daemon=True,
            name=f"silk-cb-{self.user_id[:8]}",
        ).start()
        # 阻塞等待连接建立，最多 10 秒
        self._connected_event.wait(timeout=10)

    def stop(self) -> None:
        """停止连接"""
        self._running = False

    def send(self, message_json: str) -> None:
        """线程安全地发送消息（任何线程均可调用）"""
        # 记录第一条消息的 ID，用于识别回显并开始接收后续消息
        if self._first_msg_id is None:
            try:
                self._first_msg_id = json.loads(message_json).get("id")
            except (json.JSONDecodeError, AttributeError):
                pass
        self._send_queue.put(message_json)

    def _run_loop(self) -> None:
        """运行 asyncio 事件循环"""
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            loop.run_until_complete(self._connect_loop())
        except Exception as e:
            logger.error("Silk WebSocket 事件循环异常: %s", e)
        finally:
            loop.close()

    async def _connect_loop(self) -> None:
        """持续连接，断线自动重连"""
        delay = _RECONNECT_DELAY
        while self._running:
            try:
                logger.info("正在连接 Silk 后端: %s", self.url)
                async with websockets.connect(
                    self.url,
                    ping_interval=30,
                    ping_timeout=120,
                    max_size=None,
                ) as ws:
                    self._ws = ws
                    self._ready = False  # 新连接，跳过历史消息
                    self._first_msg_id = None  # 等下一条发送的消息设置
                    # 清空上一次连接残留的回调队列
                    while not self._callback_queue.empty():
                        try:
                            self._callback_queue.get_nowait()
                        except queue.Empty:
                            break
                    delay = _RECONNECT_DELAY
                    logger.info("Silk WebSocket 已连接: user=%s, group=%s",
                                self.user_id, self.group_id)
                    # 通知 start() 连接已建立
                    self._connected_event.set()
                    # 同时运行收发循环
                    await asyncio.gather(
                        self._recv_loop(ws),
                        self._send_loop(ws),
                    )
            except websockets.exceptions.ConnectionClosed as e:
                logger.warning("Silk WebSocket 断开: %s", e)
            except Exception as e:
                logger.error("Silk WebSocket 连接失败: %s", e)
            finally:
                self._ws = None

            if not self._running:
                break
            logger.info("将在 %d 秒后重连...", delay)
            await asyncio.sleep(delay)
            delay = min(delay * 2, _MAX_RECONNECT_DELAY)

    async def _recv_loop(self, ws: websockets.WebSocketClientProtocol) -> None:
        """接收 Silk 消息"""
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                logger.warning("Silk 消息解析失败: %s", raw[:200])
                continue

            # 历史消息过滤：等收到自己发的第一条消息的回显后才开始转发
            if not self._ready:
                msg_id = msg.get("id", "")
                if self._first_msg_id and msg_id == self._first_msg_id:
                    self._ready = True
                    logger.info("收到首条消息回显 (id=%s)，开始接收后续消息", msg_id)
                continue  # 回显本身和之前的历史消息都跳过

            logger.info("收到 Silk 消息: %s", str(raw)[:200])
            self._callback_queue.put(msg)
        logger.warning("_recv_loop 已退出（WebSocket 关闭）")

    def _callback_consumer(self) -> None:
        """单线程顺序消费回调队列，保证消息按序处理（避免并发竞态）"""
        while self._running:
            try:
                msg = self._callback_queue.get(timeout=1.0)
            except queue.Empty:
                continue
            try:
                self._on_message(msg)
            except Exception as e:
                logger.error("Silk 消息回调异常: %s", e, exc_info=True)

    async def _send_loop(self, ws: websockets.WebSocketClientProtocol) -> None:
        """从队列中取消息发送到 Silk

        使用 thread-safe queue.Queue，通过短暂 sleep 轮询避免阻塞事件循环。
        """
        while self._running:
            try:
                message_json = self._send_queue.get_nowait()
            except queue.Empty:
                await asyncio.sleep(0.05)  # 50ms 轮询间隔
                continue

            try:
                await ws.send(message_json)
                logger.info("消息已发送到 Silk: %s", message_json[:80])
            except websockets.exceptions.ConnectionClosed:
                # 连接断了，消息放回队列等重连后重发
                self._send_queue.put(message_json)
                break
        logger.warning("_send_loop 已退出")


class SilkClient:
    """Silk 后端客户端，管理多用户连接"""

    def __init__(self, silk_host: str, silk_port: int):
        self.silk_host = silk_host
        self.silk_port = silk_port
        self.api_base = f"http://{silk_host}:{silk_port}"
        self.ws_url = f"ws://{silk_host}:{silk_port}/chat"
        self._connections: dict[str, SilkConnection] = {}
        self._lock = threading.Lock()

    def login(self, login_name: str, password: str) -> dict | None:
        """调用 Silk /auth/login 验证账号"""
        try:
            resp = requests.post(
                f"{self.api_base}/auth/login",
                json={"loginName": login_name, "password": password},
                timeout=10,
            )
            data = resp.json()
            if data.get("success") and data.get("user"):
                logger.info("Silk 登录成功: %s", login_name)
                return data["user"]
            logger.warning("Silk 登录失败: %s", data.get("message"))
            return None
        except Exception as e:
            logger.error("Silk 登录请求异常: %s", e)
            return None

    def start_silk_private_chat(self, user_id: str) -> str | None:
        """调用 Silk 专属助手 API，返回私聊 group_id"""
        try:
            resp = requests.post(
                f"{self.api_base}/api/silk-private-chat",
                json={"userId": user_id},
                timeout=10,
            )
            data = resp.json()
            if data.get("success") and data.get("group"):
                group_id = data["group"]["id"]
                group_name = data["group"].get("name", "")
                is_new = data.get("isNew", False)
                logger.info("Silk 专属对话就绪: %s (id=%s, new=%s)", group_name, group_id, is_new)
                return group_id
            logger.warning("Silk 专属对话创建失败: %s", data.get("message"))
            return None
        except Exception as e:
            logger.error("Silk 专属对话请求异常: %s", e)
            return None

    def connect(
        self,
        user_id: str,
        user_name: str,
        group_id: str,
        on_message: Callable[[dict], None],
    ) -> SilkConnection:
        """为用户创建或获取到 Silk 的 WebSocket 连接"""
        key = f"{user_id}:{group_id}"
        with self._lock:
            existing = self._connections.get(key)
            if existing and existing._running:
                return existing

            conn = SilkConnection(
                ws_url=self.ws_url,
                user_id=user_id,
                user_name=user_name,
                group_id=group_id,
                on_message=on_message,
            )
            self._connections[key] = conn

        # start() 可能阻塞最多 10s 等连接建立，不要在持锁时调用
        conn.start()
        return conn

    def disconnect(self, user_id: str, group_id: str) -> None:
        """断开指定用户的连接"""
        key = f"{user_id}:{group_id}"
        with self._lock:
            conn = self._connections.pop(key, None)
            if conn:
                conn.stop()

    def disconnect_all(self) -> None:
        """断开所有连接"""
        with self._lock:
            for conn in self._connections.values():
                conn.stop()
            self._connections.clear()
