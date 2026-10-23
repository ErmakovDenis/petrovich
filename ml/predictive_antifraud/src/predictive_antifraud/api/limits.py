"""Предел нагрузки на тяжёлые эндпоинты (/v1/*): не больше PA_MAX_CONCURRENCY запросов в работе и PA_QUEUE_MAX
в очереди; очередь полна — сразу 503 с Retry-After. Запрос, простоявший в очереди дольше
PA_REQUEST_DEADLINE_SECONDS или дольше, чем клиент готов ждать (заголовок X-Client-Timeout — остаток срока стенда),
не считается: клиент его уже не ждёт.

Без предела обработчики `def` копятся в пуле потоков Starlette (40 потоков), задержка растёт без границы, а расчёт
продолжается после того, как клиент ушёл. /health и /ready под предел не попадают; запросы без верного X-API-Key —
тоже: им ответит 401 сам роутер, место в очереди они не занимают.
"""

import asyncio
import logging
import secrets
from collections import deque

from starlette.datastructures import Headers
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

log = logging.getLogger(__name__)

LIMITED_PREFIX = "/v1/"
RETRY_AFTER_SECONDS = 5
CLIENT_TIMEOUT_HEADER = "x-client-timeout"


class Overloaded(Exception):
    pass


class RequestLimiter:
    """Предел с ограниченной очередью: место передаётся ждущему сразу, счётчики не отстают от передачи."""

    def __init__(self, max_concurrency: int, queue_max: int):
        self._max = max_concurrency
        self._queue_max = queue_max
        self.active = 0
        self._waiters: deque[asyncio.Future[None]] = deque()

    @property
    def waiting(self) -> int:
        return len(self._waiters)

    async def acquire(self, deadline: float) -> bool:
        """True — место получено; False — не дождались за [deadline] с. Очередь полна — Overloaded."""
        if self.active < self._max and not self._waiters:
            self.active += 1
            return True
        if len(self._waiters) >= self._queue_max:
            raise Overloaded()
        future: asyncio.Future[None] = asyncio.get_running_loop().create_future()
        self._waiters.append(future)
        try:
            await asyncio.wait_for(asyncio.shield(future), deadline)
            return True
        except (TimeoutError, asyncio.CancelledError) as e:
            if future.done() and not future.cancelled():
                # Место передали в момент истечения срока — запрос всё равно не считаем, место отдаём дальше.
                self.release()
            elif future in self._waiters:
                self._waiters.remove(future)
            future.cancel()
            if isinstance(e, asyncio.CancelledError):
                raise
            return False

    def release(self) -> None:
        # Место переходит первому живому ждущему, active не меняется; ждущих нет — место свободно.
        while self._waiters:
            future = self._waiters.popleft()
            if not future.done():
                future.set_result(None)
                return
        self.active -= 1


class ConcurrencyLimitMiddleware:
    def __init__(self, app: ASGIApp, limiter: RequestLimiter, deadline_seconds: float, api_key: str | None):
        self.app = app
        self._limiter = limiter
        self._deadline = deadline_seconds
        self._api_key = api_key

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not _path(scope).startswith(LIMITED_PREFIX):
            await self.app(scope, receive, send)
            return
        headers = Headers(scope=scope)
        if self._api_key and not secrets.compare_digest(headers.get("x-api-key", ""), self._api_key):
            await self.app(scope, receive, send)
            return
        deadline = _deadline(headers, self._deadline)
        try:
            admitted = await self._limiter.acquire(deadline)
        except Overloaded:
            log.warning("перегрузка: в работе %d, в очереди %d — отказ", self._limiter.active, self._limiter.waiting)
            await _reject("Сервис аналитики перегружен: очередь заполнена, повторите позже")(scope, receive, send)
            return
        if not admitted:
            log.warning("запрос простоял в очереди дольше %g с — не считается", deadline)
            await _reject(f"Запрос простоял в очереди дольше {deadline:g} с и не обработан")(scope, receive, send)
            return
        try:
            await self.app(scope, receive, send)
        finally:
            self._limiter.release()


def _path(scope: Scope) -> str:
    """Путь без root_path: за прокси с префиксом (/predictive/v1/...) предел тоже действует."""
    path, root = scope["path"], scope.get("root_path", "")
    return path[len(root):] if root and path.startswith(root) else path


def _deadline(headers: Headers, limit: float) -> float:
    """Сколько ждать в очереди: PA_REQUEST_DEADLINE_SECONDS, но не дольше, чем клиент готов ждать ответ."""
    try:
        client = float(headers.get(CLIENT_TIMEOUT_HEADER, ""))
    except ValueError:
        return limit
    return min(limit, client) if client > 0 else limit


def _reject(detail: str) -> JSONResponse:
    return JSONResponse({"detail": detail}, status_code=503, headers={"Retry-After": str(RETRY_AFTER_SECONDS)})
