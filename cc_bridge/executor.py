#!/usr/bin/env python3
"""Executor: spawn claude CLI subprocess, parse stream-json output, relay events."""

from __future__ import annotations

import asyncio
import glob
import json
import logging
import os
import platform
import shutil
import time
from typing import Any, Callable, Coroutine

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configuration (env vars with defaults)
# ---------------------------------------------------------------------------

def _detect_claude_path() -> str:
    """Auto-detect the claude CLI executable path.

    Search order:
    1. CLAUDE_CODE_PATH env var (user override)
    2. ``claude`` on PATH (Linux/macOS default, Windows checks .cmd/.exe/.ps1)
    3. Common npm global install locations (platform-specific)
    """
    system = platform.system()

    # 1. User explicitly set the path
    env_path = os.environ.get("CLAUDE_CODE_PATH")
    if env_path:
        if os.path.isfile(env_path) or shutil.which(env_path):
            return env_path
        _die_claude_not_found(system, f"CLAUDE_CODE_PATH={env_path} but file not found")

    # 2. Try ``claude`` directly on PATH
    found = shutil.which("claude")
    if found:
        return found

    # 3. Probe well-known npm global install locations
    candidates: list[str] = []

    if system == "Windows":
        appdata = os.environ.get("APPDATA", "")
        if appdata:
            candidates.append(os.path.join(appdata, "npm", "claude.cmd"))
        home = os.path.expanduser("~")
        candidates.append(os.path.join(home, "AppData", "Roaming", "npm", "claude.cmd"))
        localappdata = os.environ.get("LOCALAPPDATA", "")
        if localappdata:
            candidates.append(os.path.join(localappdata, "fnm_multishells", "**", "claude.cmd"))
            candidates.append(os.path.join(localappdata, "pnpm", "claude.cmd"))
    elif system == "Darwin":
        home = os.path.expanduser("~")
        candidates += [
            "/usr/local/bin/claude",
            "/opt/homebrew/bin/claude",
            os.path.join(home, ".nvm", "versions", "node", "**", "bin", "claude"),
            os.path.join(home, ".volta", "bin", "claude"),
            os.path.join(home, ".local", "bin", "claude"),
        ]
    else:  # Linux
        home = os.path.expanduser("~")
        candidates += [
            "/usr/local/bin/claude",
            os.path.join(home, ".nvm", "versions", "node", "**", "bin", "claude"),
            os.path.join(home, ".volta", "bin", "claude"),
            os.path.join(home, ".local", "bin", "claude"),
            os.path.join(home, ".npm-global", "bin", "claude"),
        ]

    for pattern in candidates:
        if "**" in pattern:
            matches = glob.glob(pattern, recursive=True)
            if matches:
                return matches[0]
        elif os.path.isfile(pattern):
            return pattern

    _die_claude_not_found(system)
    return ""  # unreachable, for type checker


def _die_claude_not_found(system: str, extra: str = "") -> None:
    """Print a helpful error message and exit."""
    msg = [
        "",
        "=" * 60,
        "  ERROR: Claude Code CLI not found!",
        "=" * 60,
    ]
    if extra:
        msg.append(f"  {extra}")
        msg.append("")
    msg.append("  Please try one of the following:")
    msg.append("")
    msg.append("  1. Install Claude Code:")
    msg.append("     npm install -g @anthropic-ai/claude-code")
    msg.append("")
    msg.append("  2. If already installed, find its path:")
    if system == "Windows":
        msg.append("     where claude.cmd")
        msg.append("     # or in PowerShell:")
        msg.append("     Get-Command claude")
    elif system == "Darwin":
        msg.append("     which claude")
    else:
        msg.append("     which claude")
    msg.append("")
    msg.append("  3. Set the path manually:")
    if system == "Windows":
        msg.append('     set CLAUDE_CODE_PATH=C:\\path\\to\\claude.cmd')
        msg.append("     python bridge.py --server ... --token ...")
    else:
        msg.append("     CLAUDE_CODE_PATH=/path/to/claude python bridge.py --server ... --token ...")
    msg.append("")
    msg.append("=" * 60)
    print("\n".join(msg), flush=True)
    raise SystemExit(1)


CLAUDE_CODE_PATH: str = _detect_claude_path()
CLAUDE_CODE_MAX_TURNS: int = int(os.environ.get("CLAUDE_CODE_MAX_TURNS", "100"))
CLAUDE_CODE_TIMEOUT: int = int(os.environ.get("CLAUDE_CODE_TIMEOUT", "36000"))
CLAUDE_CODE_MAX_OUTPUT_CHARS: int = int(
    os.environ.get("CLAUDE_CODE_MAX_OUTPUT_CHARS", "30000")
)

# Streaming throttle
STREAM_MIN_INTERVAL_S: float = 0.5
STREAM_MIN_CHARS: int = 50

# Idle status refresh
IDLE_REFRESH_S: float = 2.0

# ---------------------------------------------------------------------------
# Tool icons for stream-json parsing
# ---------------------------------------------------------------------------

TOOL_ICONS: dict[str, str] = {
    "Read": "\U0001f4d6",       # 📖
    "Write": "\u270d\ufe0f",    # ✍️
    "Edit": "\U0001f4dd",       # 📝
    "NotebookEdit": "\U0001f4d3",  # 📓
    "Bash": "\U0001f4bb",       # 💻
    "Glob": "\U0001f50d",       # 🔍
    "Grep": "\U0001f50d",       # 🔍
    "Task": "\U0001f916",       # 🤖
    "WebFetch": "\U0001f310",   # 🌐
    "Agent": "\U0001f916",      # 🤖
    "TodoWrite": "\U0001f4dd",  # 📝
}

PARAM_MAX: int = 60

# Type alias for the WebSocket send callback
SendFn = Callable[[dict[str, Any]], Coroutine[Any, Any, None]]


# ---------------------------------------------------------------------------
# Stream-json parser (stateless, per-line)
# ---------------------------------------------------------------------------

class ParsedLine:
    """Result of parsing a single JSON line from claude CLI output."""

    __slots__ = ("text_chunk", "tool_logs", "tool_results", "meta")

    def __init__(
        self,
        text_chunk: str = "",
        tool_logs: list[dict[str, Any]] | None = None,
        tool_results: list[dict[str, Any]] | None = None,
        meta: dict[str, Any] | None = None,
    ) -> None:
        self.text_chunk = text_chunk
        self.tool_logs = tool_logs or []
        self.tool_results = tool_results or []
        self.meta = meta


def format_tool_call(tool_name: str, input_obj: dict[str, Any]) -> str:
    """Format a tool call for display: icon + name + primary param."""
    icon = TOOL_ICONS.get(tool_name, "\U0001f527")  # 🔧

    # Pick the most descriptive parameter
    param = ""
    for key in (
        "file_path", "notebook_path", "command", "pattern",
        "path", "description", "url",
    ):
        val = input_obj.get(key)
        if val is not None and isinstance(val, str):
            param = val
            break
    if not param:
        # Fall back to first string value
        for v in input_obj.values():
            if isinstance(v, str):
                param = v
                break

    if len(param) > PARAM_MAX:
        display = param[: PARAM_MAX - 3] + "..."
    else:
        display = param

    if display:
        return f"{icon} {tool_name} `{display}`"
    return f"{icon} {tool_name}"


def parse_line(json_line: str) -> ParsedLine:
    """Parse a single JSON line from the claude CLI stream-json output."""
    try:
        data: dict[str, Any] = json.loads(json_line)
    except (json.JSONDecodeError, ValueError):
        return ParsedLine()

    event_type = data.get("type")
    if event_type == "assistant":
        return _parse_assistant(data)
    if event_type == "user":
        return _parse_user(data)
    if event_type == "result":
        return _parse_result(data)
    if event_type == "system":
        return _parse_system(data)
    return ParsedLine()


def _parse_assistant(data: dict[str, Any]) -> ParsedLine:
    blocks = (data.get("message") or {}).get("content") or []
    text_parts: list[str] = []
    tool_logs: list[dict[str, Any]] = []

    for block in blocks:
        if isinstance(block, str):
            text_parts.append(block)
            continue
        if not isinstance(block, dict):
            continue

        block_type = block.get("type")
        if block_type == "text":
            text_parts.append(block.get("text", ""))
        elif block_type == "thinking":
            tool_logs.append({
                "line": "\U0001f4ad \u601d\u8003...",   # 💭 思考...
                "toolUseId": None,
                "toolName": "thinking",
            })
        elif block_type == "tool_use":
            name = block.get("name", "Unknown")
            input_obj = block.get("input") or {}
            tool_id = block.get("id", "")
            tool_logs.append({
                "line": format_tool_call(name, input_obj),
                "toolUseId": tool_id,
                "toolName": name,
            })

    text = "\n\n".join(p for p in text_parts if p)
    return ParsedLine(text_chunk=text, tool_logs=tool_logs)


def _parse_user(data: dict[str, Any]) -> ParsedLine:
    blocks = (data.get("message") or {}).get("content") or []
    results: list[dict[str, Any]] = []

    for block in blocks:
        if not isinstance(block, dict):
            continue
        if block.get("type") != "tool_result":
            continue

        tool_use_id = block.get("tool_use_id", "")
        is_error = block.get("is_error", False)

        raw_content = block.get("content")
        if isinstance(raw_content, str):
            content_str = raw_content
        elif isinstance(raw_content, list):
            parts = []
            for el in raw_content:
                if isinstance(el, dict) and el.get("type") == "text":
                    parts.append(el.get("text", ""))
            content_str = "\n".join(parts)
        else:
            content_str = ""

        summary = ""
        if is_error and content_str:
            first_line = content_str.strip().split("\n", 1)[0]
            summary = first_line[:PARAM_MAX]

        results.append({
            "toolUseId": tool_use_id,
            "isError": is_error,
            "summary": summary,
        })

    return ParsedLine(tool_results=results)


def _parse_result(data: dict[str, Any]) -> ParsedLine:
    meta = {
        "costUsd": data.get("cost_usd", 0.0),
        "durationMs": data.get("duration_ms", 0),
        "numTurns": data.get("num_turns", 0),
        "sessionId": data.get("session_id", ""),
    }
    result_text = data.get("result", "")
    if not isinstance(result_text, str):
        result_text = ""
    return ParsedLine(text_chunk=result_text, meta=meta)


def _parse_system(data: dict[str, Any]) -> ParsedLine:
    subtype = data.get("subtype", "")
    if subtype == "compact_boundary":
        pre_tokens = (data.get("compact_metadata") or {}).get("pre_tokens", 0)
        if pre_tokens > 0:
            text = f"\u4e0a\u4e0b\u6587\u5df2\u538b\u7f29\uff08\u538b\u7f29\u524d {pre_tokens:,} tokens\uff09"
        else:
            text = "\u4e0a\u4e0b\u6587\u5df2\u538b\u7f29"
        return ParsedLine(text_chunk=text)
    return ParsedLine()


# ---------------------------------------------------------------------------
# Executor: manage subprocess + streaming
# ---------------------------------------------------------------------------

class Executor:
    """Manage a single claude CLI subprocess at a time."""

    def __init__(self) -> None:
        self._process: asyncio.subprocess.Process | None = None
        self._cancel_requested: bool = False

    # ------------------------------------------------------------------
    # Cancel
    # ------------------------------------------------------------------

    async def cancel(self) -> bool:
        """Kill the running subprocess. Return True if a process was killed."""
        self._cancel_requested = True
        proc = self._process
        if proc is not None and proc.returncode is None:
            try:
                proc.kill()
            except ProcessLookupError:
                pass
            logger.info("[Executor] Subprocess killed by cancel request")
            return True
        return False

    # ------------------------------------------------------------------
    # Execute
    # ------------------------------------------------------------------

    async def execute_prompt(
        self,
        send: SendFn,
        request_id: str,
        prompt: str,
        session_id: str,
        working_dir: str,
        resume: bool = False,
        on_session_upsert: Callable[[str, str, str], None] | None = None,
    ) -> None:
        """Spawn claude CLI and stream parsed events to *send*.

        Parameters
        ----------
        send:
            Async callback to send a JSON dict over the WebSocket.
        request_id:
            Unique ID for this request, echoed in every event.
        prompt:
            The user prompt (or "/compact").
        session_id:
            The CC session UUID.
        working_dir:
            Working directory for the subprocess.
        resume:
            If True, use ``--resume`` instead of ``--session-id``.
        on_session_upsert:
            Optional callback ``(session_id, working_dir, title)`` to
            persist session metadata when a result meta arrives.
        """
        self._cancel_requested = False

        # Persist session eagerly
        if on_session_upsert is not None:
            title = prompt[:50] + "\u2026" if len(prompt) > 50 else prompt
            on_session_upsert(session_id, working_dir, title)

        cmd = self._build_command(prompt, session_id, resume)
        logger.info(
            "[Executor] Spawning subprocess: cmd=%s, cwd=%s",
            " ".join(f'"{c}"' for c in cmd),
            working_dir,
        )

        # When using ``script`` PTY wrapper (Linux/macOS), claude's stderr goes
        # through the PTY and won't be captured by stderr=PIPE.  Merge stderr
        # into stdout so we can still see error messages in the output stream.
        use_pty = platform.system() != "Windows"
        try:
            process = await asyncio.create_subprocess_exec(
                *cmd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.STDOUT if use_pty else asyncio.subprocess.PIPE,
                cwd=working_dir,
            )
        except Exception as exc:
            logger.error("[Executor] Failed to start claude CLI: %s", exc)
            await send({
                "type": "error",
                "requestId": request_id,
                "error": f"\u542f\u52a8 Claude Code \u5931\u8d25: {exc}",
                "exitCode": -1,
                "stderr": str(exc),
            })
            return

        self._process = process

        # -- Timeout watchdog --
        timeout_task = asyncio.create_task(self._timeout_watchdog(process))

        # -- State for stream processing --
        accumulated_text = ""
        last_meta: dict[str, Any] | None = None
        active_tool_ids: dict[str, str] = {}  # tool_use_id → log line
        last_push_time = time.monotonic()
        last_push_len = 0

        # Phase-aware status
        model_thinking = True
        phase_start_time = time.monotonic()

        should_break = False

        try:
            assert process.stdout is not None
            line_count = 0

            # Read stdout line-by-line with idle timeout
            while not should_break:
                try:
                    raw_bytes = await asyncio.wait_for(
                        process.stdout.readline(),
                        timeout=IDLE_REFRESH_S,
                    )
                except asyncio.TimeoutError:
                    # No data within IDLE_REFRESH_S
                    if process.returncode is not None:
                        break
                    elapsed = int(time.monotonic() - phase_start_time)
                    if model_thinking:
                        status = f"\U0001f4ad \u601d\u8003\u4e2d... (\u5df2\u7b49\u5f85 {elapsed}s)"
                    else:
                        status = f"\u23f3 \u6b63\u5728\u5904\u7406... (\u5df2\u7b49\u5f85 {elapsed}s)"
                    await send({
                        "type": "status_update",
                        "requestId": request_id,
                        "status": status,
                    })
                    continue

                if not raw_bytes:
                    # EOF
                    break

                line_count += 1
                line = raw_bytes.decode("utf-8", errors="replace").strip().replace("\r", "")
                if not line:
                    continue
                if not line.startswith("{"):
                    logger.info("[Executor] Non-JSON output: %s", line[:200])
                    continue

                logger.debug(
                    "[Executor] Line %d (%d chars): %s",
                    line_count, len(line), line[:80],
                )
                parsed = parse_line(line)

                # ---- Phase state transitions ----
                has_tool_results = bool(parsed.tool_results)
                has_assistant_output = bool(parsed.tool_logs) or bool(parsed.text_chunk)
                prev_thinking = model_thinking
                prev_phase_start = phase_start_time

                if has_tool_results:
                    model_thinking = True
                if has_assistant_output:
                    model_thinking = False
                if model_thinking != prev_thinking:
                    phase_start_time = time.monotonic()

                # Thinking completed -> update the thinking log with duration
                if has_assistant_output and prev_thinking:
                    thinking_duration = int(time.monotonic() - prev_phase_start)
                    for tool_log in parsed.tool_logs:
                        if tool_log.get("toolName") == "thinking":
                            updated = (
                                f"\U0001f4ad \u601d\u8003\u5b8c\u6210 "
                                f"(\u7528\u65f6 {thinking_duration}s)"
                            )
                            await send({
                                "type": "tool_log",
                                "requestId": request_id,
                                "log": updated,
                                "stableId": "cc_thinking",
                            })

                # ---- Tool logs ----
                for tool_log in parsed.tool_logs:
                    tool_use_id = tool_log.get("toolUseId")
                    if tool_use_id:
                        active_tool_ids[tool_use_id] = tool_log["line"]
                    # Thinking already handled above
                    if tool_log.get("toolName") == "thinking":
                        continue
                    await send({
                        "type": "tool_log",
                        "requestId": request_id,
                        "log": tool_log["line"],
                        "stableId": tool_use_id,
                    })

                # ---- Tool results (append checkmark/cross) ----
                for result in parsed.tool_results:
                    tool_use_id = result.get("toolUseId", "")
                    original_line = active_tool_ids.pop(tool_use_id, None)
                    if original_line is not None:
                        if result.get("isError"):
                            summary = result.get("summary", "")
                            suffix = f" \u2192 \u274c {summary}" if summary else " \u2192 \u274c"
                        else:
                            suffix = " \u2192 \u2705"
                        await send({
                            "type": "tool_log",
                            "requestId": request_id,
                            "log": f"{original_line}{suffix}",
                            "stableId": tool_use_id,
                        })

                # ---- Text accumulation ----
                should_append = (
                    parsed.text_chunk
                    and (parsed.meta is None or not accumulated_text)
                )
                if should_append:
                    accumulated_text += parsed.text_chunk

                    # Truncation protection
                    if len(accumulated_text) > CLAUDE_CODE_MAX_OUTPUT_CHARS:
                        accumulated_text = accumulated_text[:CLAUDE_CODE_MAX_OUTPUT_CHARS]
                        logger.warning(
                            "[Executor] Output exceeded %d chars, killing process",
                            CLAUDE_CODE_MAX_OUTPUT_CHARS,
                        )
                        try:
                            process.kill()
                        except ProcessLookupError:
                            pass
                        await send({
                            "type": "stream_text",
                            "requestId": request_id,
                            "text": accumulated_text,
                        })
                        await send({
                            "type": "tool_log",
                            "requestId": request_id,
                            "log": (
                                f"\u26a0\ufe0f \u8f93\u51fa\u5df2\u622a\u65ad"
                                f"\uff08\u8d85\u8fc7 {CLAUDE_CODE_MAX_OUTPUT_CHARS}"
                                f" \u5b57\u7b26\u4e0a\u9650\uff09"
                            ),
                            "stableId": None,
                        })
                        should_break = True
                        continue

                    # Throttled push
                    now = time.monotonic()
                    new_chars = len(accumulated_text) - last_push_len
                    if (
                        now - last_push_time >= STREAM_MIN_INTERVAL_S
                        or new_chars >= STREAM_MIN_CHARS
                    ):
                        await send({
                            "type": "stream_text",
                            "requestId": request_id,
                            "text": accumulated_text,
                        })
                        last_push_time = now
                        last_push_len = len(accumulated_text)

                # ---- Meta ----
                if parsed.meta is not None:
                    last_meta = parsed.meta
                    # Persist session from meta
                    if on_session_upsert and parsed.meta.get("sessionId"):
                        title = prompt[:50] + "\u2026" if len(prompt) > 50 else prompt
                        on_session_upsert(
                            parsed.meta["sessionId"],
                            working_dir,
                            title,
                        )

            # Push remaining text
            if len(accumulated_text) > last_push_len:
                await send({
                    "type": "stream_text",
                    "requestId": request_id,
                    "text": accumulated_text,
                })

            # Wait for process to finish
            logger.info(
                "[Executor] stdout finished (%d lines), waiting for process exit...",
                line_count,
            )
            try:
                await asyncio.wait_for(process.wait(), timeout=10.0)
            except asyncio.TimeoutError:
                logger.warning("[Executor] Process did not exit in time, killing")
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
                await process.wait()

            timeout_task.cancel()
            exit_code = process.returncode or 0

            # Read stderr
            stderr_text = ""
            if process.stderr:
                try:
                    stderr_bytes = await process.stderr.read()
                    stderr_text = stderr_bytes.decode("utf-8", errors="replace").strip()
                except Exception:
                    pass

            if exit_code != 0:
                logger.warning(
                    "[Executor] Exit code=%d, stderr=%s",
                    exit_code, stderr_text[:500] if stderr_text else "(empty)",
                )

            if self._cancel_requested:
                await send({"type": "cancelled", "requestId": request_id})
            elif exit_code != 0 and not accumulated_text:
                error_msg = stderr_text or (
                    f"Claude Code \u8fdb\u7a0b\u5f02\u5e38\u9000\u51fa (code={exit_code})"
                )
                await send({
                    "type": "error",
                    "requestId": request_id,
                    "error": error_msg,
                    "exitCode": exit_code,
                    "stderr": stderr_text,
                })
            else:
                await send({
                    "type": "complete",
                    "requestId": request_id,
                    "text": accumulated_text,
                    "meta": last_meta,
                })

        except asyncio.CancelledError:
            timeout_task.cancel()
            if process.returncode is None:
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
            raise
        except Exception as exc:
            timeout_task.cancel()
            if process.returncode is None:
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
            logger.error("[Executor] Error processing subprocess output: %s", exc)
            await send({
                "type": "error",
                "requestId": request_id,
                "error": f"\u5904\u7406 Claude Code \u8f93\u51fa\u5f02\u5e38: {exc}",
                "exitCode": -1,
                "stderr": str(exc),
            })
        finally:
            self._process = None

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _build_command(
        self, prompt: str, session_id: str, resume: bool
    ) -> list[str]:
        """Build the full command line including ``script`` PTY wrapper."""
        claude_args: list[str] = [
            CLAUDE_CODE_PATH,
            "-p", prompt,
            "--output-format", "stream-json",
        ]
        if resume:
            claude_args.extend(["--resume", session_id])
        else:
            claude_args.extend(["--session-id", session_id])

        claude_args.extend([
            "--verbose",
            "--permission-mode", "bypassPermissions",
            "--max-turns", str(CLAUDE_CODE_MAX_TURNS),
        ])

        # Windows: run claude CLI directly (no PTY needed)
        # Linux:  script -q -c "cmd" /dev/null
        # macOS:  script -q /dev/null cmd args...
        system = platform.system()
        if system == "Windows":
            return claude_args
        if system == "Darwin":
            return ["script", "-q", "/dev/null"] + claude_args
        # Linux and others
        shell_cmd = " ".join(_shell_quote(a) for a in claude_args)
        return ["script", "-q", "-c", shell_cmd, "/dev/null"]

    async def _timeout_watchdog(self, process: asyncio.subprocess.Process) -> None:
        """Kill the subprocess after CLAUDE_CODE_TIMEOUT seconds."""
        try:
            await asyncio.sleep(CLAUDE_CODE_TIMEOUT)
            if process.returncode is None:
                logger.warning(
                    "[Executor] Subprocess timed out (%ds), killing",
                    CLAUDE_CODE_TIMEOUT,
                )
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
        except asyncio.CancelledError:
            pass


def _shell_quote(arg: str) -> str:
    """Quote a shell argument with single quotes, escaping embedded single quotes."""
    if any(c in arg for c in (" ", '"', "'", "\n", "\t", "\\", "$", "`", "!", "#")):
        return "'" + arg.replace("'", "'\\''") + "'"
    return arg
