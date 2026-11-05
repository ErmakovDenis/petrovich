"""Метрики Prometheus стенда (GET /metrics при FS_METRICS_ENABLED): запросы к внешним системам по коду и
длительность, ответы модели по маршруту (основная, запасная, резерв), 402 модели, отказы по причине, попадания
в кэши, состояние breaker'ов, глубина очередей. HTTP-метрики самого стенда — prometheus-fastapi-instrumentator.

Метки — только из короткого известного набора (система, код, причина): ни id машин, ни схем, ни логинов — иначе
число рядов не ограничено, а в метрики попали бы данные пользователей. Свой реестр на приложение: в тестах их много.
"""

import time
from collections.abc import Iterator
from contextlib import contextmanager

from prometheus_client import CollectorRegistry, Counter, Histogram
from prometheus_client.core import GaugeMetricFamily
from prometheus_client.registry import Collector

# Метки известны заранее: ряды создаются сразу с нулём, иначе первое событие после старта не видно increase()/rate().
LLM_ROUTES = ("primary", "fallback", "reserve")
REJECTION_REASONS = (
    "chat_rate_limit", "chat_queue_full", "model_not_configured", "llm_credits", "llm_unavailable", "breaker_llm",
    "breaker_autograph", "autograph_no_slot", "autograph_unavailable", "predictive_no_slot", "breaker_predictive",
    "gigachat_no_slot",
)
# Метод запроса приходит от клиента: всё необычное — одной меткой, иначе любой без токена плодит ряды без предела.
HTTP_METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}
HTTP_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300)
UPSTREAM_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 30, 60, 120)
BREAKER_STATES = {"closed": 0, "half_open": 1, "open": 2}


class Metrics:
    def __init__(self) -> None:
        r = self.registry = CollectorRegistry()
        self.upstream_requests = Counter(
            "fleet_upstream_requests", "Запросы стенда к внешним системам по исходу (HTTP-код, error, timeout)",
            ["upstream", "outcome"], registry=r)
        self.upstream_duration = Histogram(
            "fleet_upstream_duration_seconds", "Длительность запросов к внешним системам", ["upstream"],
            buckets=UPSTREAM_BUCKETS, registry=r)
        self.llm_responses = Counter(
            "fleet_llm_responses", "Ответы модели по маршруту: primary, fallback (запасная модель), reserve",
            ["route"], registry=r)
        self.llm_credits_exhausted = Counter(
            "fleet_llm_credits_exhausted", "Ответы 402 модели: кончились кредиты, токены или лимит ключа", registry=r)
        self.gigachat_blacklist = Counter(
            "fleet_gigachat_blacklist", "Ответы GigaChat с тематическим ограничением (finish_reason=blacklist)", registry=r)
        self.rejections = Counter(
            "fleet_rejections", "Отказы стенда без обработки по причине (лимиты, очереди, breaker'ы)", ["reason"],
            registry=r)
        for route in LLM_ROUTES:
            self.llm_responses.labels(route)
        for reason in REJECTION_REASONS:
            self.rejections.labels(reason)
        self.http_requests = Counter(
            "http_requests", "Ответы стенда по шаблону пути, методу и коду", ["handler", "method", "status"], registry=r)
        self.http_duration = Histogram(
            "http_request_duration_seconds", "Длительность ответов стенда", ["handler", "method"], buckets=HTTP_BUCKETS,
            registry=r)
        self.http_503 = Counter(
            "fleet_http_503", "Ответы стенда с кодом 503 (ряд с нуля — первый такой ответ виден алерту)", registry=r)
        self.cache_requests = Counter(
            "fleet_cache_requests", "Обращения к кэшам: hit — из кэша, miss — загрузка", ["cache", "result"],
            registry=r)

    @contextmanager
    def upstream(self, name: str) -> Iterator["UpstreamCall"]:
        """Запрос к внешней системе: длительность и исход (call.outcome = код или error/timeout)."""
        call = UpstreamCall(self.upstream_duration.labels(name))
        try:
            yield call
        finally:
            if not call.deferred:
                call.finish()
            self.upstream_requests.labels(name, call.outcome).inc()

    def http_response(self, info) -> None:
        """Запись prometheus-fastapi-instrumentator: шаблон пути (без id) и точный код — для доли 503 в алертах."""
        method = info.method if info.method in HTTP_METHODS else "other"
        self.http_requests.labels(info.modified_handler, method, info.modified_status).inc()
        self.http_duration.labels(info.modified_handler, method).observe(info.modified_duration)
        if info.response is not None and info.response.status_code == 503:
            self.http_503.inc()

    def cache(self, name: str, hit: bool) -> None:
        self.cache_requests.labels(name, "hit" if hit else "miss").inc()

    def watch(self, breakers: dict, limits: object) -> None:
        """Состояние breaker'ов и очередей — читается при каждом scrape."""
        self.registry.register(_StateCollector(breakers, limits))


class UpstreamCall:
    def __init__(self, duration) -> None:
        self.outcome = "error"
        self.deferred = False
        self._duration = duration
        self._started = time.monotonic()

    def status(self, code: int) -> None:
        self.outcome = str(code)

    def defer(self) -> None:
        """Потоковый ответ: длительность запишет [finish] после чтения тела."""
        self.deferred = True

    def finish(self) -> None:
        self._duration.observe(time.monotonic() - self._started)


class _StateCollector(Collector):
    def __init__(self, breakers: dict, limits: object):
        self._breakers = breakers
        self._limits = limits

    def collect(self):
        state = GaugeMetricFamily("fleet_breaker_state", "Состояние breaker'а: 0 — закрыт, 1 — пробный, 2 — открыт",
                                  labels=["upstream"])
        for name, b in self._breakers.items():
            state.add_metric([name], BREAKER_STATES[b.state])
        yield state
        since = GaugeMetricFamily("fleet_breaker_seconds_since_failure", "Секунд с последнего сбоя внешней системы",
                                  labels=["upstream"])
        for name, b in self._breakers.items():
            if b.seconds_since_failure is not None:
                since.add_metric([name], b.seconds_since_failure)
        yield since
        waiting = GaugeMetricFamily("fleet_queue_depth", "Ожидают места в пределе", labels=["limiter"])
        active = GaugeMetricFamily("fleet_inflight", "Занято мест в пределе", labels=["limiter"])
        for name in ("chat", "autograph", "predictive"):
            limiter = getattr(self._limits, name)
            waiting.add_metric([name], limiter.waiting)
            active.add_metric([name], limiter.active)
        yield waiting
        yield active
