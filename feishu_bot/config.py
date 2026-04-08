"""
配置加载模块

从 config/config.yaml 读取飞书应用凭证和 Silk 后端地址。
"""

from pathlib import Path

import yaml

_CONFIG_DIR = Path(__file__).parent / "config"


def load_config() -> dict:
    """加载配置 (config/config.yaml)"""
    path = _CONFIG_DIR / "config.yaml"
    if not path.exists():
        raise FileNotFoundError(
            f"配置文件不存在: {path}\n"
            "请复制 config/config.yaml.example 为 config/config.yaml 并填入实际值。"
        )

    with open(path) as f:
        config = yaml.safe_load(f) or {}

    if not config.get("app_id") or not config.get("app_secret"):
        raise ValueError("config/config.yaml 中 app_id 和 app_secret 不能为空")

    # 设置默认值
    config.setdefault("silk_host", "localhost")
    config.setdefault("silk_port", 8003)
    config.setdefault("bind_command", "绑定")
    config.setdefault("unbind_command", "解绑")
    config.setdefault("help_command", "帮助")
    config.setdefault("stream_interval", 0.5)

    return config
