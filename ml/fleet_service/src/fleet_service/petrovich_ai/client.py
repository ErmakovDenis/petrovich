"""Клиент ленты событий API Антифрод «Petrovich AI» (`GET /api/v1/anomaly-events`): готовые аномалии парка
за даты, страницы по 200 (потолок 2000 на проверку). Авторизация — HTTP Basic; креды только в FS_PETROVICH_AI_*.

Сбой не ломает проверку: любая ошибка (сеть, таймаут, не 2xx, ответ не по контракту) — events() вернёт None,
и вызывающий выбирает кэш API или общие правила по fallbackRules. Креды в логи не пишутся.
"""

import logging
from datetime import date
from typing import Any

import httpx

from ..config import Settings

log = logging.getLogger(__name__)

EVENTS_PAGE = 200
# Потолок на случай огромной ленты: проверка не должна завалить стенд тысячами записей.
EVENTS_MAX = 2000


class PetrovichAiClient:
    def __init__(self, settings: Settings, http: httpx.AsyncClient):
        self._settings = settings
        self._http = http

    @property
    def enabled(self) -> bool:
        s = self._settings
        return bool(
            s.petrovich_ai_url.strip() and s.petrovich_ai_user.strip()
            and s.petrovich_ai_password is not None and s.petrovich_ai_password.get_secret_value()
        )

    async def events(self, date_from: date, date_to: date) -> list[dict[str, Any]] | None:
        """Все события ленты за даты; None — сервис недоступен или ответ не по контракту (вызывающий уходит
        в обычный путь)."""
        if not self.enabled:
            return None
        s = self._settings
        auth = (s.petrovich_ai_user.strip(), s.petrovich_ai_password.get_secret_value())
        items: list[dict[str, Any]] = []
        offset = 0
        try:
            while True:
                resp = await self._http.get(
                    s.petrovich_ai_url.rstrip("/") + "/api/v1/anomaly-events",
                    params={"date_from": date_from.isoformat(), "date_to": date_to.isoformat(),
                            "limit": EVENTS_PAGE, "offset": offset},
                    auth=auth,
                    timeout=s.petrovich_ai_timeout_seconds,
                )
                if resp.status_code != 200:
                    log.warning("petrovich ai: лента событий ответила %d", resp.status_code)
                    return None
                data = resp.json()
                page, total = data.get("items"), data.get("total")
                if not isinstance(page, list) or not isinstance(total, int):
                    log.warning("petrovich ai: ответ ленты не по контракту")
                    return None
                items += page
                offset += len(page)
                if offset >= total or not page or offset >= EVENTS_MAX:
                    return items
        except (httpx.HTTPError, ValueError):
            log.warning("petrovich ai: лента событий не ответила", exc_info=True)
            return None
