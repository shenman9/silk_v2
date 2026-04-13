#!/usr/bin/env python3
"""CC Bridge Agent: connects to Silk backend via WebSocket, executes Claude CLI commands."""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import signal
from typing import Any

import websockets
import websockets.exceptions

from executor import Executor, CLAUDE_CODE_PATH
from session_manager import SessionManager

logger = logging.getLogger("cc_bridge")

# ---------------------------------------------------------------------------
# Globals
# ---------------------------------------------------------------------------

executor = Executor()
session_manager = SessionManager()


# ---------------------------------------------------------------------------
# WebSocket send helper
# ---------------------------------------------------------------------------

async def ws_send(ws: websockets.WebSocketClientProtocol, msg: dict[str, Any]) -> None:
    """Serialize *msg* to JSON and send over the WebSocket. Silently ignore closed connections."""
    try:
        await ws.send(json.dumps(msg, ensure_ascii=False))
    except websockets.exceptions.ConnectionClosed:
        logger.warning("[Bridge] WebSocket closed while sending, message dropped")
    except Exception as exc:
        logger.warning("[Bridge] Failed to send message: %s", exc)


# ---------------------------------------------------------------------------
# Command handlers
# ---------------------------------------------------------------------------

async def handle_execute(
    ws: websockets.WebSocketClientProtocol,
    msg: dict[str, Any],
    working_dir: str,
) -> None:
    """Handle ``execute`` and ``compact`` commands."""
    request_id = msg.get("requestId", "")
    prompt = msg.get("prompt", "")
    session_id = msg.get("sessionId", "")
    wd = msg.get("workingDir") or working_dir
    resume = msg.get("resume", False)

    async def send(payload: dict[str, Any]) -> None:
        await ws_send(ws, payload)

    def on_session_upsert(sid: str, wdir: str, title: str) -> None:
        session_manager.upsert_session(sid, wdir, title)

    await executor.execute_prompt(
        send=send,
        request_id=request_id,
        prompt=prompt,
        session_id=session_id,
        working_dir=wd,
        resume=resume,
        on_session_upsert=on_session_upsert,
    )


async def handle_cancel(
    ws: websockets.WebSocketClientProtocol,
    msg: dict[str, Any],
) -> None:
    """Handle ``cancel`` command."""
    request_id = msg.get("requestId", "")
    killed = await executor.cancel()
    if not killed:
        # If nothing was running, acknowledge immediately
        await ws_send(ws, {"type": "cancelled", "requestId": request_id})


async def handle_cd(
    ws: websockets.WebSocketClientProtocol,
    msg: dict[str, Any],
    working_dir_holder: list[str],
) -> None:
    """Handle ``cd`` command: validate directory and respond."""
    request_id = msg.get("requestId", "")
    path = msg.get("path", "")

    if not path:
        path = working_dir_holder[0]

    resolved = os.path.realpath(os.path.expanduser(path))
    if not os.path.isdir(resolved):
        await ws_send(ws, {
            "type": "cd_result",
            "requestId": request_id,
            "success": False,
            "error": f"\u76ee\u5f55\u4e0d\u5b58\u5728: {resolved}",
        })
        return

    working_dir_holder[0] = resolved
    await ws_send(ws, {
        "type": "cd_result",
        "requestId": request_id,
        "success": True,
        "path": resolved,
    })


async def handle_new_session(
    ws: websockets.WebSocketClientProtocol,
    msg: dict[str, Any],
) -> None:
    """Handle ``new_session`` command."""
    request_id = msg.get("requestId", "")
    new_id = session_manager.new_session()
    await ws_send(ws, {
        "type": "new_session",
        "requestId": request_id,
        "sessionId": new_id,
    })


async def handle_list_sessions(
    ws: websockets.WebSocketClientProtocol,
    msg: dict[str, Any],
) -> None:
    """Handle ``list_sessions`` command."""
    request_id = msg.get("requestId", "")
    sessions = session_manager.list_sessions()
    await ws_send(ws, {
        "type": "session_list",
        "requestId": request_id,
        "sessions": sessions,
    })


async def handle_resume_session(
    ws: websockets.WebSocketClientProtocol,
    msg: dict[str, Any],
) -> None:
    """Handle ``resume_session`` command."""
    request_id = msg.get("requestId", "")
    prefix = msg.get("sessionIdPrefix", "")
    record = session_manager.resume_session(prefix)
    if record is None:
        await ws_send(ws, {
            "type": "error",
            "requestId": request_id,
            "error": f'\u672a\u627e\u5230\u5339\u914d "{prefix}" \u7684\u4f1a\u8bdd',
        })
    else:
        await ws_send(ws, {
            "type": "session_resumed",
            "requestId": request_id,
            "sessionId": record["sessionId"],
            "workingDir": record.get("workingDir", ""),
        })


# ---------------------------------------------------------------------------
# Message dispatcher
# ---------------------------------------------------------------------------

async def dispatch(
    ws: websockets.WebSocketClientProtocol,
    raw: str,
    working_dir_holder: list[str],
) -> None:
    """Route an incoming message by its ``type`` field."""
    try:
        msg: dict[str, Any] = json.loads(raw)
    except (json.JSONDecodeError, ValueError):
        logger.warning("[Bridge] Received non-JSON message, ignoring")
        return

    msg_type = msg.get("type", "")
    logger.debug("[Bridge] Received message type=%s", msg_type)

    if msg_type == "ping":
        await ws_send(ws, {"type": "pong"})

    elif msg_type == "execute":
        await handle_execute(ws, msg, working_dir_holder[0])

    elif msg_type == "compact":
        # Compact is just execute with prompt="/compact" and resume=True
        msg.setdefault("prompt", "/compact")
        msg["resume"] = True
        await handle_execute(ws, msg, working_dir_holder[0])

    elif msg_type == "cancel":
        await handle_cancel(ws, msg)

    elif msg_type == "cd":
        await handle_cd(ws, msg, working_dir_holder)

    elif msg_type == "new_session":
        await handle_new_session(ws, msg)

    elif msg_type == "list_sessions":
        await handle_list_sessions(ws, msg)

    elif msg_type == "resume_session":
        await handle_resume_session(ws, msg)

    else:
        logger.debug("[Bridge] Unknown message type: %s", msg_type)


# ---------------------------------------------------------------------------
# Connection loop with auto-reconnect
# ---------------------------------------------------------------------------


def _log_task_exception(task: asyncio.Task) -> None:
    """Log exceptions from fire-and-forget tasks."""
    if task.cancelled():
        return
    exc = task.exception()
    if exc is not None:
        logger.error("[Bridge] Dispatch task failed: %s", exc, exc_info=True)


async def run(server: str, token: str, working_dir: str) -> None:
    """Main loop: connect, dispatch messages, auto-reconnect on failure."""
    # Build WebSocket URL from plain host:port
    host = server.rstrip("/")
    # Strip protocol prefix if user accidentally included it
    for prefix in ("ws://", "wss://", "http://", "https://"):
        if host.lower().startswith(prefix):
            host = host[len(prefix):]
            break
    ws_url = f"ws://{host}/cc-bridge?token={token}"
    working_dir_holder = [os.path.realpath(working_dir)]

    delay = 1.0  # exponential backoff start
    max_delay = 60.0

    while True:
        try:
            logger.info("[Bridge] Connecting to %s ...", ws_url)
            async with websockets.connect(
                ws_url,
                ping_interval=30,
                ping_timeout=10,
                max_size=10 * 1024 * 1024,  # 10 MB
            ) as ws:
                # Reset backoff on successful connect
                delay = 1.0
                logger.info("[Bridge] Connected successfully")

                # Send hello
                await ws_send(ws, {
                    "type": "hello",
                    "defaultDir": working_dir_holder[0],
                })

                # Message loop
                async for raw in ws:
                    if isinstance(raw, bytes):
                        raw = raw.decode("utf-8", errors="replace")
                    # Dispatch in a task so we don't block reads
                    task = asyncio.create_task(dispatch(ws, raw, working_dir_holder))
                    task.add_done_callback(_log_task_exception)

            # Clean disconnect
            logger.info("[Bridge] WebSocket closed cleanly")

        except websockets.exceptions.ConnectionClosed as exc:
            logger.warning("[Bridge] Connection closed: %s", exc)
        except (ConnectionRefusedError, OSError) as exc:
            logger.warning("[Bridge] Connection failed: %s", exc)
        except Exception as exc:
            logger.error("[Bridge] Unexpected error: %s", exc, exc_info=True)

        logger.info("[Bridge] Reconnecting in %.0fs ...", delay)
        await asyncio.sleep(delay)
        delay = min(delay * 2, max_delay)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="CC Bridge Agent: WebSocket bridge for Claude CLI execution",
    )
    parser.add_argument(
        "--server",
        required=True,
        help="Silk backend address, e.g. localhost:8006",
    )
    parser.add_argument(
        "--token",
        required=True,
        help="Authentication token for the bridge connection",
    )
    parser.add_argument(
        "--working-dir",
        default=os.getcwd(),
        help="Default working directory for claude CLI (default: cwd)",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Log level (default: INFO)",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s [%(name)s] %(levelname)s %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    logger.info("[Bridge] Claude CLI detected: %s", CLAUDE_CODE_PATH)

    # Graceful shutdown
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    shutdown_event = asyncio.Event()

    def _signal_handler() -> None:
        logger.info("[Bridge] Received shutdown signal")
        shutdown_event.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _signal_handler)
        except NotImplementedError:
            # Windows doesn't support add_signal_handler
            signal.signal(sig, lambda *_: _signal_handler())

    async def _run_with_shutdown() -> None:
        main_task = asyncio.create_task(
            run(args.server, args.token, args.working_dir)
        )
        shutdown_task = asyncio.create_task(shutdown_event.wait())

        done, pending = await asyncio.wait(
            {main_task, shutdown_task},
            return_when=asyncio.FIRST_COMPLETED,
        )

        for task in pending:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

        # Cancel the executor subprocess if running
        await executor.cancel()
        logger.info("[Bridge] Shutdown complete")

    try:
        loop.run_until_complete(_run_with_shutdown())
    except KeyboardInterrupt:
        pass
    finally:
        loop.close()


if __name__ == "__main__":
    main()
