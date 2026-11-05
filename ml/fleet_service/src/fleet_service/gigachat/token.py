"""Токен доступа GigaChat: кэш в памяти, обновление за 60 с до срока, одна загрузка на все запросы."""

import asyncio
import logging
import time
import uuid
from collections.abc import Callable

import httpx

from ..config import Settings
from ..metrics import Metrics
from .errors import USER_AGENT, GatewayError, gigachat_message

log = logging.getLogger(__name__)

REFRESH_BEFORE_SECONDS = 60
# Из справки: токен живёт 30 минут. Берётся, если в ответе нет срока.
DEFAULT_LIFETIME_SECONDS = 1800
# Больше этого числа expires_at — миллисекунды (10¹¹ секунд — это 5138 год): схема спецификации говорит о миллисекундах,
# её же пример — о секундах.
MILLISECONDS_FROM = 100_000_000_000


def expires_at_seconds(value: object, now: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        log.warning("GigaChat: в ответе нет срока токена, считаем %d с", DEFAULT_LIFETIME_SECONDS)
        return now + DEFAULT_LIFETIME_SECONDS
    return value / 1000 if value > MILLISECONDS_FROM else float(value)


class TokenSource:
    def __init__(self, settings: Settings, http: httpx.AsyncClient, metrics: Metrics,
                 clock: Callable[[], float] = time.time):
        self._settings = settings
        self._http = http
        self._metrics = metrics
        self._clock = clock
        self._lock = asyncio.Lock()
        self._token: str | None = None
        self._expires_at = 0.0

    async def get(self, rejected: str | None = None) -> str:
        """Действующий токен. [rejected] — токен, который API только что отверг: его больше не отдаём."""
        if self._usable(rejected):
            return self._token  # type: ignore[return-value]
        async with self._lock:
            # Пока ждали замок, токен мог загрузить другой запрос.
            if not self._usable(rejected):
                await self._load()
            return self._token  # type: ignore[return-value]

    def _usable(self, rejected: str | None) -> bool:
        return (self._token is not None and self._token != rejected
                and self._clock() < self._expires_at - REFRESH_BEFORE_SECONDS)

    async def _load(self) -> None:
        s = self._settings
        key = s.gigachat_credentials.get_secret_value() if s.gigachat_credentials else ""
        headers = {
            "Authorization": f"Basic {key}",
            "RqUID": str(uuid.uuid4()),
            "Accept": "application/json",
            "User-Agent": USER_AGENT,
        }
        try:
            with self._metrics.upstream("gigachat_oauth") as call:
                try:
                    resp = await self._http.post(s.gigachat_auth_url, headers=headers,
                                                 data={"scope": s.gigachat_scope}, timeout=s.gigachat_timeout_seconds)
                except (httpx.TimeoutException, asyncio.CancelledError):
                    call.outcome = "timeout"
                    raise
                call.status(resp.status_code)
        except httpx.HTTPError as e:
            # Причина (например, CERTIFICATE_VERIFY_FAILED при неверном сертификате) — только в лог.
            log.error("GigaChat: не удалось получить токен: %s: %s", type(e).__name__, e)
            raise GatewayError(503, f"GigaChat: не удалось получить токен: {type(e).__name__}") from e
        if resp.status_code in (400, 401, 403):
            # Ключ или scope неверны: повторы не помогут, нужен владелец стенда.
            text = f"GigaChat отверг ключ авторизации: {gigachat_message(resp)}"
            log.error("%s (ответ %d) — проверьте FS_GIGACHAT_CREDENTIALS и FS_GIGACHAT_SCOPE", text, resp.status_code)
            raise GatewayError(401, text)
        if resp.status_code != 200:
            log.warning("GigaChat: получение токена, ответ %d", resp.status_code)
            raise GatewayError(resp.status_code if resp.status_code == 429 or resp.status_code >= 500 else 502,
                               f"GigaChat: получение токена, ответ {resp.status_code}")
        try:
            data = resp.json()
            token = data["access_token"]
        except (ValueError, KeyError, TypeError):
            token = None
        if not isinstance(token, str) or not token:
            log.warning("GigaChat: ответ с токеном не удалось разобрать")
            raise GatewayError(502, "GigaChat: ответ с токеном не удалось разобрать")
        self._token = token
        self._expires_at = expires_at_seconds(data.get("expires_at"), self._clock())
        log.info("GigaChat: получен токен доступа, осталось %.0f с", self._expires_at - self._clock())
