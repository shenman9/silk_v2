"""
Silk 飞书机器人启动入口
"""

import atexit
import logging
import signal
import sys

from config import load_config
from feishu_handler import FeishuHandler


def main():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    )

    cfg = load_config()
    handler = FeishuHandler(cfg)

    def shutdown():
        logging.getLogger(__name__).info("正在关闭 Silk 连接...")
        handler.silk.disconnect_all()

    atexit.register(shutdown)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))

    handler.start()


if __name__ == "__main__":
    main()
