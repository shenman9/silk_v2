#!/usr/bin/env python3
"""Session manager: persist CC session metadata to ~/.silk/cc_sessions.json."""

from __future__ import annotations

import json
import logging
import os
import platform
import threading
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

def _get_session_file() -> Path:
    """Return platform-appropriate session file path."""
    if platform.system() == "Windows":
        appdata = os.environ.get("APPDATA", "")
        if appdata:
            return Path(appdata) / "Silk" / "cc_sessions.json"
    return Path.home() / ".silk" / "cc_sessions.json"


SESSION_FILE = _get_session_file()
EXPIRE_DAYS = 7


def _now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


class SessionManager:
    """Thread-safe CC session persistence.

    Storage layout::

        {
          "sessions": [
            {
              "sessionId": "...",
              "workingDir": "...",
              "title": "...",
              "createdAt": "ISO",
              "lastActivity": "ISO"
            }
          ]
        }
    """

    def __init__(self, path: Path | None = None) -> None:
        self._path = path or SESSION_FILE
        self._lock = threading.Lock()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def new_session(self) -> str:
        """Generate a new session UUID and return it (not yet persisted)."""
        return str(uuid.uuid4())

    def list_sessions(self) -> list[dict[str, Any]]:
        """Return non-expired sessions sorted by lastActivity desc."""
        with self._lock:
            sessions = self._load()

        cutoff = (datetime.now(timezone.utc) - timedelta(days=EXPIRE_DAYS)).isoformat()
        active = [s for s in sessions if s.get("lastActivity", "") >= cutoff]
        active.sort(key=lambda s: s.get("lastActivity", ""), reverse=True)
        return active

    def resume_session(self, prefix: str) -> dict[str, Any] | None:
        """Find a session whose ID starts with *prefix*. Return the full record or None."""
        sessions = self.list_sessions()
        for s in sessions:
            if s.get("sessionId", "").startswith(prefix):
                return s
        return None

    def upsert_session(
        self,
        session_id: str,
        working_dir: str,
        title: str,
    ) -> None:
        """Create or update a session record, then persist to disk."""
        now = _now_iso()
        with self._lock:
            sessions = self._load()

            existing = next(
                (s for s in sessions if s.get("sessionId") == session_id),
                None,
            )
            if existing is not None:
                existing["lastActivity"] = now
                existing["title"] = title
            else:
                sessions.insert(0, {
                    "sessionId": session_id,
                    "workingDir": working_dir,
                    "title": title,
                    "createdAt": now,
                    "lastActivity": now,
                })

            # Sort by lastActivity desc, remove expired
            sessions.sort(key=lambda s: s.get("lastActivity", ""), reverse=True)
            cutoff = (
                datetime.now(timezone.utc) - timedelta(days=EXPIRE_DAYS)
            ).isoformat()
            sessions = [s for s in sessions if s.get("lastActivity", "") >= cutoff]

            self._save(sessions)

    # ------------------------------------------------------------------
    # Internal I/O
    # ------------------------------------------------------------------

    def _load(self) -> list[dict[str, Any]]:
        """Read session list from disk. Returns empty list on any error."""
        if not self._path.exists():
            return []
        try:
            data = json.loads(self._path.read_text(encoding="utf-8"))
            return data.get("sessions", [])
        except Exception as exc:
            logger.warning("Failed to read session file %s: %s", self._path, exc)
            return []

    def _save(self, sessions: list[dict[str, Any]]) -> None:
        """Atomically write session list to disk."""
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self._path.with_suffix(".tmp")
            tmp.write_text(
                json.dumps({"sessions": sessions}, indent=2, ensure_ascii=False),
                encoding="utf-8",
            )
            os.replace(str(tmp), str(self._path))
        except Exception as exc:
            logger.error("Failed to write session file %s: %s", self._path, exc)
