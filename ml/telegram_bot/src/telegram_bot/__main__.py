"""Запуск: python -m telegram_bot (настройки — TB_* в окружении или .env)."""

import asyncio
import logging

from .bot import run
from .config import Settings


def main() -> None:
    settings = Settings()
    logging.basicConfig(level=settings.log_level, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    # Токен бота входит в адрес запросов к Telegram: журнал запросов httpx/aiohttp не пишем.
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("aiohttp.access").setLevel(logging.WARNING)
    asyncio.run(run(settings))


if __name__ == "__main__":
    main()
