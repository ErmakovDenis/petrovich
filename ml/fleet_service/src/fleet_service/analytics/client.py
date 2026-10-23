"""Клиент сервиса predictive_antifraud: POST /v1/predictive/analyze и /v1/antifraud/check с VehicleTelemetry.

Сбой аналитики не ломает проверку по правилам: клиент не бросает исключений, а возвращает статус —
`ok` (модель отработала), `not_ready` (модель не загружена), `unavailable` (сервис не настроен, не отвечает,
ответил ошибкой или нет места в пределе FS_PREDICTIVE_CONCURRENCY за FS_PREDICTIVE_TIMEOUT_SECONDS). Ключ X-API-Key
в логи не пишется.
"""

import asyncio
import logging
import time
from collections.abc import Hashable
from typing import Literal

import httpx
from pydantic import Field, ValidationError

from ..breaker import BreakerOpen, CircuitBreaker, counts_as_failure
from ..cache import TtlCache
from ..config import Settings
from ..limits import UpstreamLimiter
from ..schemas.contract import Anomaly, CamelModel, DetectionResponse, VehicleTelemetry

log = logging.getLogger(__name__)

Kind = Literal["predictive", "antifraud"]
# Сколько ждать GET /ready (версии моделей для кэша): входит в FS_PREDICTIVE_TIMEOUT_SECONDS.
READY_TIMEOUT_SECONDS = 2.0


class _NotCached(Exception):
    """Неуспешный статус аналитики — возвращается, но в кэш не попадает."""

    def __init__(self, status: "AnalyticsStatus"):
        super().__init__(status.status)
        self.status = status

_PATHS: dict[str, str] = {"predictive": "/v1/predictive/analyze", "antifraud": "/v1/antifraud/check"}
_NAMES: dict[str, str] = {"predictive": "предиктивная аналитика", "antifraud": "антифрод"}


class AnalyticsStatus(CamelModel):
    status: Literal["ok", "not_ready", "unavailable"]
    model_version: str | None = None
    # Почему результата нет — текстом для пользователя и ассистента.
    detail: str | None = None
    anomalies_found: int = 0
    # Сами аномалии уходят в общий список проверки, в статусе не дублируются.
    anomalies: list[Anomaly] = Field(default_factory=list, exclude=True)


class AnalyticsClient:
    def __init__(self, settings: Settings, http: httpx.AsyncClient, limiter: UpstreamLimiter, breaker: CircuitBreaker):
        """[limiter] — общий на процесс предел одновременных запросов к аналитике (FS_PREDICTIVE_CONCURRENCY),
        [breaker] — её circuit breaker."""
        self._settings = settings
        self._http = http
        self._limiter = limiter
        self._breaker = breaker
        # Успешные результаты по (вид, версия модели, схема, машина, период) и версии моделей из GET /ready.
        self._results: TtlCache[AnalyticsStatus] = TtlCache(settings.telemetry_cache_ttl_seconds,
                                                            settings.analytics_cache_max_entries)
        self._versions: TtlCache[dict[str, str]] = TtlCache(settings.predictive_ready_ttl_seconds, 1)

    async def model_version(self, kind: Kind, timeout: float) -> str | None:
        """Версия загруженной модели вида [kind] по GET /ready (помнится FS_PREDICTIVE_READY_TTL_SECONDS, неудача —
        тоже: пустой список); None — модели нет или аналитика не ответила (тогда кэш не используется)."""
        s = self._settings

        async def load() -> dict[str, str]:
            try:
                resp = await self._http.get(s.predictive_url.rstrip("/") + "/ready", timeout=timeout)
                resp.raise_for_status()
                models = resp.json().get("models", [])
                return {m["name"]: m["version"] for m in models if m.get("ready") and m.get("version")}
            except (httpx.HTTPError, ValueError, KeyError, TypeError, AttributeError):
                return {}

        return (await self._versions.get("ready", load)).get(kind)

    async def run(self, kind: Kind, payload: dict, cache: tuple[Hashable, float] | None = None) -> AnalyticsStatus:
        """[cache] — ключ телеметрии проверки и срок жизни результата: успешный результат той же версии модели
        берётся из кэша без запроса к аналитике, одинаковые одновременные проверки ждут один запрос. Всё вместе —
        не дольше FS_PREDICTIVE_TIMEOUT_SECONDS."""
        s = self._settings
        started = time.monotonic()
        # Breaker не закрыт — аналитика сейчас не отвечает: ни /ready, ни кэша, сразу обычный путь (быстрый отказ).
        if cache is None or not s.predictive_url.strip() or self._breaker.state != "closed":
            return await self._run(kind, payload, s.predictive_timeout_seconds)
        version = await self.model_version(kind, min(READY_TIMEOUT_SECONDS, s.predictive_timeout_seconds))
        budget = max(0.1, s.predictive_timeout_seconds - (time.monotonic() - started))
        if version is None:
            return await self._run(kind, payload, budget)

        async def load() -> AnalyticsStatus:
            status = await self._run(kind, payload, budget)
            if status.status != "ok" or status.model_version != version:
                raise _NotCached(status)  # TtlCache не хранит ошибки — неуспешный статус не кэшируется
            return status

        try:
            return await self._results.get((kind, version, cache[0]), load, cache[1])
        except _NotCached as e:
            return e.status

    @staticmethod
    def payload(telemetry: VehicleTelemetry) -> dict:
        """Тело запроса — VehicleTelemetry в контракте; строится один раз на проверку для обоих сервисов."""
        return telemetry.model_dump(by_alias=True, mode="json")

    async def _run(self, kind: Kind, payload: dict, budget: float) -> AnalyticsStatus:
        """Один запрос к аналитике не дольше [budget] секунд вместе с ожиданием места."""
        s = self._settings
        name = _NAMES[kind]
        if not s.predictive_url.strip():
            return AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики не настроен на стенде")
        headers = {}
        if s.predictive_api_key is not None and s.predictive_api_key.get_secret_value():
            headers["X-API-Key"] = s.predictive_api_key.get_secret_value()
        overloaded = AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики перегружен")
        try:
            # Breaker проверяется до ожидания места: открыт (или идёт пробный запрос) — сразу unavailable.
            with self._breaker.attempt() as outcome:
                # Ожидание места в пределе входит в срок: правила не ждут аналитику дольше FS_PREDICTIVE_TIMEOUT_SECONDS.
                started = time.monotonic()
                try:
                    async with asyncio.timeout(budget):
                        slot = await self._limiter.acquire()
                except TimeoutError:
                    outcome.skip()  # до сервиса не дошли — ни успех, ни сбой
                    log.warning("аналитика %s: нет места за %.1f с (FS_PREDICTIVE_CONCURRENCY)", kind, budget)
                    return overloaded
                left = max(0.1, budget - (time.monotonic() - started))
                # Сколько стенд ещё ждёт: аналитика не держит запрос в своей очереди дольше (иначе считала бы впустую).
                headers["X-Client-Timeout"] = f"{left:.1f}"
                try:
                    resp = await self._http.post(
                        s.predictive_url.rstrip("/") + _PATHS[kind], json=payload, headers=headers, timeout=left,
                    )
                except httpx.TimeoutException:
                    # Таймаут, укороченный ожиданием места на стенде, — не признак недоступности сервиса.
                    if left >= s.predictive_timeout_seconds * 0.9:
                        outcome.fail()
                    else:
                        outcome.skip()
                    raise
                except httpx.HTTPError:
                    outcome.fail()
                    raise
                finally:
                    slot.release()
                # 503 — намеренный отказ по пределу нагрузки аналитики (PA_MAX_CONCURRENCY): сервис жив.
                if resp.status_code != 503 and counts_as_failure(resp.status_code):
                    outcome.fail()
        except BreakerOpen:
            return AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики временно отключён после сбоев")
        except httpx.HTTPError as e:
            log.warning("аналитика %s: сервис не ответил: %s", kind, type(e).__name__)
            return AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики не отвечает")
        if resp.status_code == 503:
            # Предел нагрузки аналитики (PA_MAX_CONCURRENCY): без повтора — правила уже посчитаны, повтор добавил бы
            # нагрузки перегруженному сервису.
            log.warning("аналитика %s: сервис перегружен (503)", kind)
            return AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики перегружен")
        if resp.status_code != 200:
            log.warning("аналитика %s: ответ %d", kind, resp.status_code)
            return AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики ответил ошибкой {resp.status_code}")
        try:
            result = DetectionResponse.model_validate_json(resp.content)
        except ValidationError:
            log.warning("аналитика %s: ответ не по контракту", kind)
            return AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики вернул ответ не по контракту")
        if not result.ready:
            return AnalyticsStatus(status="not_ready", detail=f"{name}: модель не загружена")
        return AnalyticsStatus(status="ok", model_version=result.model_version,
                               anomalies_found=len(result.anomalies), anomalies=result.anomalies)
