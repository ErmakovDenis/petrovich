"""Circuit breaker на внешнюю систему (AutoGRAPH, predictive_antifraud, модель): после FS_BREAKER_FAILURES сбоев за
FS_BREAKER_WINDOW_SECONDS система считается недоступной на FS_BREAKER_OPEN_SECONDS — запросы сразу получают
отказ, не ожидая таймаута. Затем проходит один пробный запрос (half-open): успех закрывает breaker, сбой открывает
снова.

Сбой — то, что говорит о недоступности системы: сеть, таймаут, 5xx, 408, 429. Ответ 4xx на неверный запрос или
неверный ключ — система работает, breaker его не учитывает. Состояние — в памяти процесса (/health, /v1/status).
"""

import time
from collections import deque
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from typing import Literal

State = Literal["closed", "open", "half_open"]

# Retry-After для запросов, пришедших во время пробного: его исход известен через секунды.
PROBE_RETRY_SECONDS = 5.0


def counts_as_failure(status_code: int) -> bool:
    """HTTP-ответ, который говорит о недоступности системы: 5xx, 408, 429."""
    return status_code >= 500 or status_code in (408, 429)


class BreakerOpen(Exception):
    """Breaker открыт: запрос не отправлялся. [retry_after] — через сколько секунд будет пробный запрос."""

    def __init__(self, name: str, retry_after: float):
        super().__init__(f"{name}: временно недоступен, повтор через {max(1, round(retry_after))} с")
        self.name = name
        self.retry_after = retry_after


class Attempt:
    """Исход одного обращения: вызывающий отмечает сбой (fail) — иначе обращение считается успешным."""

    def __init__(self) -> None:
        self.failed = False
        self.neutral = False

    def fail(self) -> None:
        self.failed = True

    def skip(self) -> None:
        """Обращения к системе не было (например, отказ ещё до запроса) — не успех и не сбой."""
        self.neutral = True


class CircuitBreaker:
    def __init__(self, name: str, failures: int, window_seconds: float, open_seconds: float,
                 clock: Callable[[], float] = time.monotonic):
        self.name = name
        self._failures = failures
        self._window = window_seconds
        self._open_seconds = open_seconds
        self._clock = clock
        self._recent: deque[float] = deque()
        self._opened_at: float | None = None
        self._probing = False
        self.opened_total = 0

    @property
    def state(self) -> State:
        if self._opened_at is None:
            return "closed"
        if self._probing or self._clock() - self._opened_at >= self._open_seconds:
            return "half_open"
        return "open"

    def status(self) -> dict:
        state = self.state
        self._prune(self._clock())
        info: dict = {"state": state, "recentFailures": len(self._recent), "openedTotal": self.opened_total}
        if state == "open" and self._opened_at is not None:
            info["retryAfterSeconds"] = round(self._open_seconds - (self._clock() - self._opened_at), 1)
        return info

    @contextmanager
    def attempt(self) -> Iterator[Attempt]:
        """Обращение под breaker'ом. Открыт — сразу BreakerOpen. Исключение без отметки fail() — не сбой системы
        (она ответила), кроме отмены: тогда исход неизвестен, пробное место просто освобождается."""
        probe = self._admit()
        outcome = Attempt()
        try:
            yield outcome
        except BaseException as e:
            if outcome.failed:
                self._failure(probe)
            elif isinstance(e, Exception) and not outcome.neutral:
                self._success(probe)
            elif probe:
                self._probing = False
            raise
        if outcome.failed:
            self._failure(probe)
        elif outcome.neutral:
            if probe:
                self._probing = False
        else:
            self._success(probe)

    def _admit(self) -> bool:
        """True — это пробный запрос half-open."""
        if self._opened_at is None:
            return False
        elapsed = self._clock() - self._opened_at
        if elapsed < self._open_seconds:
            raise BreakerOpen(self.name, self._open_seconds - elapsed)
        if self._probing:
            # Идёт пробный запрос — его исход станет известен скоро.
            raise BreakerOpen(self.name, PROBE_RETRY_SECONDS)
        # Half-open: пропускаем один пробный запрос, остальные ждут его исхода.
        self._probing = True
        return True

    def _success(self, probe: bool) -> None:
        # Исход запросов, начатых до открытия, открытое состояние не меняет: решает пробный запрос.
        if probe:
            self._probing = False
            self._opened_at = None
            self._recent.clear()

    def _failure(self, probe: bool) -> None:
        now = self._clock()
        if probe:
            # Пробный запрос не прошёл — снова открыт на полный срок.
            self._probing = False
            self._opened_at = now
            self.opened_total += 1
            return
        if self._opened_at is not None:
            return
        self._recent.append(now)
        self._prune(now)
        if len(self._recent) >= self._failures:
            self._opened_at = now
            self._recent.clear()
            self.opened_total += 1

    def _prune(self, now: float) -> None:
        while self._recent and now - self._recent[0] > self._window:
            self._recent.popleft()
