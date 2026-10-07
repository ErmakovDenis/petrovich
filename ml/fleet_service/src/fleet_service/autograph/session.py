"""Проверка токена сессии AutoGRAPH, который приложение передаёт стенду.

Дешёвый запрос `EnumSchemas?session=<токен>`: токен действителен, если AutoGRAPH вернул список схем, и схема
доступна пользователю, если её id есть в этом списке. Итог кэшируется на FS_AUTH_CACHE_TTL_SECONDS по хэшу
токена (сам токен в памяти кэша не хранится). Недоступность AutoGRAPH не кэшируется и не превращается в 401.
"""

import asyncio
import hashlib
import logging
import time
from collections import OrderedDict
from enum import Enum

import httpx

from ..config import Settings

log = logging.getLogger(__name__)


class SessionInvalid(Exception):
    pass


class SchemaForbidden(Exception):
    pass


class AutoGraphUnavailable(Exception):
    pass


class _Outcome(Enum):
    OK = "ok"
    INVALID = "invalid"
    FORBIDDEN = "forbidden"


class AutoGraphSessionChecker:
    def __init__(self, settings: Settings, http: httpx.AsyncClient):
        self._settings = settings
        self._http = http
        self._cache: OrderedDict[str, tuple[float, _Outcome]] = OrderedDict()
        self._lock = asyncio.Lock()

    async def verify(self, session: str, schema_id: str) -> None:
        """Ничего не возвращает при успехе; иначе SessionInvalid / SchemaForbidden / AutoGraphUnavailable."""
        key = hashlib.sha256(f"{session}\n{schema_id}".encode()).hexdigest()
        outcome = await self._cached(key)
        if outcome is None:
            outcome = await self._check(session, schema_id)
            await self._store(key, outcome)
        if outcome is _Outcome.INVALID:
            raise SessionInvalid()
        if outcome is _Outcome.FORBIDDEN:
            raise SchemaForbidden()

    async def _cached(self, key: str) -> _Outcome | None:
        async with self._lock:
            entry = self._cache.get(key)
            if entry is None:
                return None
            expires, outcome = entry
            if expires <= time.monotonic():
                del self._cache[key]
                return None
            return outcome

    async def _store(self, key: str, outcome: _Outcome) -> None:
        ttl = self._settings.auth_cache_ttl_seconds
        if ttl <= 0:
            return
        async with self._lock:
            self._cache[key] = (time.monotonic() + ttl, outcome)
            self._cache.move_to_end(key)
            while len(self._cache) > self._settings.auth_cache_max_entries:
                self._cache.popitem(last=False)

    async def _check(self, session: str, schema_id: str) -> _Outcome:
        url = self._settings.autograph_base_url.rstrip("/") + "/EnumSchemas"
        try:
            resp = await self._http.get(
                url, params={"session": session}, timeout=self._settings.autograph_timeout_seconds
            )
        except httpx.HTTPError as e:
            log.warning("AutoGRAPH: проверка сессии не удалась: %s", type(e).__name__)
            raise AutoGraphUnavailable() from e
        if resp.status_code in (401, 403):
            return _Outcome.INVALID
        if resp.status_code != 200:
            log.warning("AutoGRAPH: EnumSchemas ответил %d", resp.status_code)
            raise AutoGraphUnavailable()
        try:
            schemas = resp.json()
        except ValueError:
            schemas = None
        # С недействительным токеном AutoGRAPH может ответить 200 не со списком — это тоже отказ.
        if not isinstance(schemas, list):
            return _Outcome.INVALID
        ids = {str(s.get("ID")) for s in schemas if isinstance(s, dict)}
        return _Outcome.OK if schema_id in ids else _Outcome.FORBIDDEN
