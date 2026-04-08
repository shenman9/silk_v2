"""
飞书用户 ↔ Silk 账号 绑定管理

持久化存储到 data/user_bindings.json，支持绑定/解绑/查询。
"""

import json
import logging
import os
import tempfile
from dataclasses import dataclass, asdict
from pathlib import Path
from threading import Lock

logger = logging.getLogger(__name__)

_DATA_DIR = Path(__file__).parent / "data"
_BINDINGS_FILE = _DATA_DIR / "user_bindings.json"


@dataclass
class SilkBinding:
    silk_user_id: str
    silk_user_name: str
    silk_full_name: str
    group_id: str


class UserBindingManager:
    """管理飞书 open_id → Silk 账号的映射"""

    def __init__(self):
        self._bindings: dict[str, SilkBinding] = {}
        self._lock = Lock()
        self._load()

    def get(self, feishu_open_id: str) -> SilkBinding | None:
        with self._lock:
            return self._bindings.get(feishu_open_id)

    def bind(self, feishu_open_id: str, binding: SilkBinding) -> None:
        with self._lock:
            self._bindings[feishu_open_id] = binding
            self._save()
        logger.info("账号绑定: feishu=%s → silk=%s (%s)",
                     feishu_open_id, binding.silk_user_id, binding.silk_user_name)

    def unbind(self, feishu_open_id: str) -> bool:
        with self._lock:
            if feishu_open_id in self._bindings:
                del self._bindings[feishu_open_id]
                self._save()
                logger.info("账号解绑: feishu=%s", feishu_open_id)
                return True
            return False

    def count(self) -> int:
        """返回已绑定用户数量"""
        with self._lock:
            return len(self._bindings)

    def _load(self) -> None:
        if not _BINDINGS_FILE.exists():
            return
        try:
            with open(_BINDINGS_FILE) as f:
                data = json.load(f)
            for feishu_id, info in data.items():
                self._bindings[feishu_id] = SilkBinding(**info)
            logger.info("已加载 %d 个账号绑定", len(self._bindings))
        except Exception as e:
            logger.error("加载绑定数据失败: %s", e)

    def _save(self) -> None:
        _DATA_DIR.mkdir(parents=True, exist_ok=True)
        data = {k: asdict(v) for k, v in self._bindings.items()}
        # 原子写入：先写临时文件再重命名
        fd, tmp_path = tempfile.mkstemp(dir=_DATA_DIR, suffix=".tmp")
        try:
            with os.fdopen(fd, "w") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            os.replace(tmp_path, _BINDINGS_FILE)
        except Exception:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass
            raise
