"""Пределы нагрузки стенда: rate limit на пользователя, допуск к чату с ограниченной очередью и общие пределы
одновременных вызовов внешних систем (AutoGRAPH, predictive_antifraud) на процесс.

Неограниченная очередь не защищает: при загрузке около 100 % задержка растёт без предела. Поэтому у чата есть
и предел одновременных ответов, и предел ожидающих — при полной очереди сразу отказ. Пределы внешних вызовов общие
для всех путей (чат, проверки, scan, фон), поэтому суммарная нагрузка на AutoGRAPH и аналитику не зависит от
числа пришедших запросов. Фоновая проверка уступает интерактивным запросам.

Всё в памяти одного процесса: при нескольких репликах лимиты нужно переносить в общее хранилище (DEPLOY.md).
"""

import asyncio
import math
import time
from collections import OrderedDict, deque
from collections.abc import AsyncIterator, Callable, Iterator
from contextlib import asynccontextmanager, contextmanager
from contextvars import ContextVar
from dataclasses import dataclass


class RateLimited(Exception):
    """Пользователь превысил частоту запросов. [retry_after] — через сколько секунд появится место."""

    def __init__(self, retry_after: float):
        super().__init__(f"Слишком много вопросов подряд, повторите через {math.ceil(retry_after)} с")
        self.retry_after = retry_after


class Overloaded(Exception):
    """Очередь заполнена — быстрый отказ вместо ожидания без предела."""

    def __init__(self, name: str):
        super().__init__(f"очередь «{name}» заполнена")
        self.name = name


class TokenBuckets:
    """Token bucket на ключ: [burst] запросов подряд, дальше — [rate_per_minute] в минуту. Ключей не больше
    [max_keys] (давно не обращавшиеся вытесняются: их ведро, скорее всего, уже снова полное)."""

    def __init__(self, rate_per_minute: float, burst: int, max_keys: int = 10_000,
                 clock: Callable[[], float] = time.monotonic):
        self._rate = rate_per_minute / 60
        self._burst = burst
        self._max_keys = max_keys
        self._clock = clock
        self._buckets: OrderedDict[str, tuple[float, float]] = OrderedDict()

    def take(self, key: str) -> None:
        """Забирает один запрос из ведра ключа или бросает RateLimited."""
        now = self._clock()
        tokens, at = self._buckets.get(key, (float(self._burst), now))
        tokens = min(float(self._burst), tokens + (now - at) * self._rate)
        if tokens < 1:
            self._buckets[key] = (tokens, now)
            self._buckets.move_to_end(key)
            raise RateLimited((1 - tokens) / self._rate if self._rate > 0 else 60.0)
        self._buckets[key] = (tokens - 1, now)
        self._buckets.move_to_end(key)
        while len(self._buckets) > self._max_keys:
            self._buckets.popitem(last=False)

    def refund(self, key: str) -> None:
        """Возвращает запрос, который стенд не обслужил по своей причине (очередь полна): он не должен тратить
        лимит пользователя."""
        entry = self._buckets.get(key)
        if entry is not None:
            self._buckets[key] = (min(float(self._burst), entry[0] + 1), entry[1])


class AdmissionLimiter:
    """Не больше [concurrency] задач одновременно и [queue_max] ожидающих; очередь полна — сразу Overloaded.
    Освободившееся место передаётся ждущему сразу — счётчики не отстают от передачи."""

    def __init__(self, name: str, concurrency: int, queue_max: int):
        self.name = name
        self._concurrency = concurrency
        self._queue_max = queue_max
        self.active = 0
        self._waiters: deque[asyncio.Future[None]] = deque()

    @property
    def waiting(self) -> int:
        return len(self._waiters)

    @asynccontextmanager
    async def slot(self) -> AsyncIterator[None]:
        if self.active < self._concurrency and not self._waiters:
            self.active += 1
        elif len(self._waiters) >= self._queue_max:
            raise Overloaded(self.name)
        else:
            future: asyncio.Future[None] = asyncio.get_running_loop().create_future()
            self._waiters.append(future)
            try:
                await future
            except asyncio.CancelledError:
                if future.done() and not future.cancelled():
                    self._release()
                elif future in self._waiters:
                    self._waiters.remove(future)
                raise
        try:
            yield
        finally:
            self._release()

    def _release(self) -> None:
        # Место переходит первому живому ждущему, active не меняется; ждущих нет — место свободно.
        while self._waiters:
            future = self._waiters.popleft()
            if not future.done():
                future.set_result(None)
                return
        self.active -= 1


_background: ContextVar[bool] = ContextVar("background_priority", default=False)


@contextmanager
def background_priority() -> Iterator[None]:
    """Вызовы внешних систем в этом контексте — фоновые: уступают интерактивным (UpstreamLimiter)."""
    token = _background.set(True)
    try:
        yield
    finally:
        _background.reset(token)


class Slot:
    """Занятое место UpstreamLimiter; release() можно вызывать повторно."""

    def __init__(self, limiter: "UpstreamLimiter", background: bool):
        self._limiter = limiter
        self._background = background
        self._released = False

    def release(self) -> None:
        if not self._released:
            self._released = True
            self._limiter._release(self._background)


class UpstreamLimiter:
    """Общий на процесс предел одновременных вызовов внешней системы. Интерактивные запросы идут в порядке
    очереди; фоновые (background_priority) получают место, только если интерактивных ожидающих нет, и занимают
    не больше половины мест (⌈C/2⌉) — чтобы запрос пользователя не стоял за всей фоновой проверкой. При C = 1 фоновой
    достаётся единственное место (иначе она не шла бы никогда), и пользователь ждёт конца её запроса."""

    def __init__(self, name: str, concurrency: int):
        self.name = name
        self.concurrency = concurrency
        self._background_max = max(1, math.ceil(concurrency / 2))
        self.active = 0
        self.background_active = 0
        self._waiters: deque[asyncio.Future[None]] = deque()
        self._background_waiters: deque[asyncio.Future[None]] = deque()

    @property
    def waiting(self) -> int:
        return len(self._waiters) + len(self._background_waiters)

    def _can_take(self, background: bool) -> bool:
        if self.active >= self.concurrency:
            return False
        if background:
            return not self._waiters and self.background_active < self._background_max
        return True

    def _take(self, background: bool) -> None:
        self.active += 1
        if background:
            self.background_active += 1

    async def acquire(self) -> Slot:
        background = _background.get()
        queue = self._background_waiters if background else self._waiters
        if not queue and self._can_take(background):
            self._take(background)
            return Slot(self, background)
        future: asyncio.Future[None] = asyncio.get_running_loop().create_future()
        queue.append(future)
        try:
            await future
        except asyncio.CancelledError:
            if future.done() and not future.cancelled():
                # Место уже выдано, но ждавший отменён — вернуть.
                self._release(background)
            else:
                # Отменённый future мог уже вынуть из очереди _wake() — тогда удалять нечего.
                if future in queue:
                    queue.remove(future)
                self._wake()
            raise
        return Slot(self, background)

    @asynccontextmanager
    async def slot(self) -> AsyncIterator[None]:
        held = await self.acquire()
        try:
            yield
        finally:
            held.release()

    def _release(self, background: bool) -> None:
        self.active -= 1
        if background:
            self.background_active -= 1
        self._wake()

    def _wake(self) -> None:
        """Раздаёт освободившиеся места: сначала интерактивным по очереди, потом фоновым."""
        while self._waiters and self._can_take(False):
            future = self._waiters.popleft()
            if not future.done():
                self._take(False)
                future.set_result(None)
        while self._background_waiters and self._can_take(True):
            future = self._background_waiters.popleft()
            if not future.done():
                self._take(True)
                future.set_result(None)


@dataclass(frozen=True)
class Limits:
    """Пределы процесса (app.state.limits)."""

    chat_rate: TokenBuckets
    chat: AdmissionLimiter
    autograph: UpstreamLimiter
    predictive: UpstreamLimiter
