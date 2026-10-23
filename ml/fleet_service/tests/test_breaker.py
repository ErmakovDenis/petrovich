"""Circuit breaker: открытие после N сбоев, отказ без HTTP-запроса, один пробный запрос, что считается сбоем."""

import asyncio

import httpx
import pytest

from fleet_service.agent.llm import LLMError, LLMRetryable, LLMTimeout, OpenRouterClient, llm_scope
from fleet_service.analytics.client import AnalyticsClient
from fleet_service.autograph.client import AutoGraphClient
from fleet_service.autograph.errors import AutoGraphUnavailable, SessionInvalid
from fleet_service.breaker import BreakerOpen, CircuitBreaker
from fleet_service.limits import UpstreamLimiter

from .conftest import AUTH, ask, test_settings


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def _breaker(clock: Clock, failures: int = 3) -> CircuitBreaker:
    return CircuitBreaker("test", failures=failures, window_seconds=30, open_seconds=60, clock=clock)


def _fail(breaker: CircuitBreaker) -> None:
    with breaker.attempt() as a:
        a.fail()


def test_opens_after_n_failures_in_window_then_one_probe():
    clock = Clock()
    b = _breaker(clock)
    _fail(b)
    clock.now += 31  # первый сбой вышел из окна
    _fail(b)
    _fail(b)
    assert b.state == "closed"
    _fail(b)
    assert b.state == "open" and b.opened_total == 1
    with pytest.raises(BreakerOpen) as e:
        with b.attempt():
            pass
    assert e.value.retry_after == pytest.approx(60)
    clock.now += 60
    assert b.state == "half_open"
    # Один пробный запрос; пока он идёт, остальные получают отказ.
    with b.attempt():
        with pytest.raises(BreakerOpen):
            with b.attempt():
                pass
    assert b.state == "closed"


def test_failed_probe_reopens_for_full_period():
    clock = Clock()
    b = _breaker(clock, failures=1)
    _fail(b)
    clock.now += 60
    _fail(b)  # пробный не прошёл
    assert b.state == "open"
    clock.now += 59
    assert b.state == "open"
    clock.now += 1
    assert b.state == "half_open"


def test_exception_without_fail_is_not_a_failure_and_cancel_frees_the_probe():
    clock = Clock()
    b = _breaker(clock, failures=1)
    # Система ответила (например, 401) — исключение, но не сбой.
    with pytest.raises(SessionInvalid):
        with b.attempt():
            raise SessionInvalid()
    assert b.state == "closed"
    _fail(b)
    clock.now += 60
    with pytest.raises(asyncio.CancelledError):
        with b.attempt():
            raise asyncio.CancelledError()
    # Исход отменённого пробного неизвестен — следующий запрос снова пробный.
    assert b.state == "half_open"
    with b.attempt():
        pass
    assert b.state == "closed"


def test_late_results_of_requests_started_before_opening_do_not_change_state():
    clock = Clock()
    b = _breaker(clock, failures=1)
    with b.attempt() as slow:
        clock.now += 10
        _fail(b)  # пока «медленный» запрос шёл, breaker открылся
        clock.now += 10
        slow.fail()
    # Поздний сбой срок не продлевает: открыт с момента открытия, а не с последнего сбоя.
    assert b.status()["retryAfterSeconds"] == 50

    b2 = _breaker(clock, failures=1)
    with b2.attempt():
        _fail(b2)
    # Поздний успех запроса, начатого до открытия, breaker не закрывает — решает пробный запрос.
    assert b2.state == "open"


@pytest.mark.anyio
async def test_autograph_open_breaker_makes_no_request_and_probe_after_open_seconds():
    calls = []

    def down(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(502)

    clock = Clock()
    breaker = CircuitBreaker("autograph", failures=2, window_seconds=30, open_seconds=60, clock=clock)
    client = AutoGraphClient(test_settings(), httpx.AsyncClient(transport=httpx.MockTransport(down)),
                             UpstreamLimiter("autograph", 8), breaker)
    for _ in range(2):
        with pytest.raises(AutoGraphUnavailable):
            await client.enum_devices("valid-token", "schema-1")
    assert breaker.state == "open" and len(calls) == 2
    with pytest.raises(AutoGraphUnavailable) as e:
        await client.enum_devices("valid-token", "schema-1")
    assert "много сбоев подряд" in str(e.value) and len(calls) == 2
    clock.now += 60
    with pytest.raises(AutoGraphUnavailable):
        await client.enum_devices("valid-token", "schema-1")
    assert len(calls) == 3 and breaker.state == "open"


@pytest.mark.anyio
async def test_autograph_client_errors_do_not_open_breaker():
    def not_found(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404)

    def expired(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401)

    breaker = CircuitBreaker("autograph", failures=1, window_seconds=30, open_seconds=60)
    for handler, error in ((not_found, AutoGraphUnavailable), (expired, SessionInvalid)):
        client = AutoGraphClient(test_settings(), httpx.AsyncClient(transport=httpx.MockTransport(handler)),
                                 UpstreamLimiter("autograph", 8), breaker)
        with pytest.raises(error):
            await client.enum_devices("valid-token", "schema-1")
    assert breaker.state == "closed"


@pytest.mark.anyio
async def test_llm_breaker_fails_fast_without_waiting_for_timeout():
    calls = []

    def down(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(503, json={"error": {"message": "No provider", "code": 503}})

    breaker = CircuitBreaker("llm", failures=2, window_seconds=30, open_seconds=60)
    client = OpenRouterClient(test_settings(), httpx.AsyncClient(transport=httpx.MockTransport(down)), breaker)
    for _ in range(3):
        with pytest.raises(LLMRetryable) as e:
            await client.complete([], [])
    assert len(calls) == 2
    assert "временно недоступен" in str(e.value) and e.value.retry_after == pytest.approx(60, abs=1)


@pytest.mark.anyio
async def test_llm_request_errors_do_not_open_breaker():
    def bad(request: httpx.Request) -> httpx.Response:
        return httpx.Response(400, json={"error": {"message": "Bad request", "code": 400}})

    breaker = CircuitBreaker("llm", failures=1, window_seconds=30, open_seconds=60)
    client = OpenRouterClient(test_settings(), httpx.AsyncClient(transport=httpx.MockTransport(bad)), breaker)
    for _ in range(3):
        with pytest.raises(Exception):
            await client.complete([], [])
    assert breaker.state == "closed"


@pytest.mark.anyio
async def test_analytics_open_breaker_is_unavailable_without_request():
    calls = []

    def down(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(500)

    breaker = CircuitBreaker("predictive", failures=1, window_seconds=30, open_seconds=60)
    analytics = AnalyticsClient(test_settings(), httpx.AsyncClient(transport=httpx.MockTransport(down)),
                                UpstreamLimiter("predictive", 2), breaker)
    assert (await analytics.run("antifraud", {})).detail == "антифрод: сервис аналитики ответил ошибкой 500"
    status = await analytics.run("antifraud", {})
    assert status.status == "unavailable" and status.detail == "антифрод: сервис аналитики временно отключён после сбоев"
    assert len(calls) == 1


def test_probe_in_flight_gives_short_retry_after_and_reopen_is_counted():
    clock = Clock()
    b = _breaker(clock, failures=1)
    _fail(b)
    clock.now += 60
    with b.attempt() as probe:
        with pytest.raises(BreakerOpen) as e:
            with b.attempt():
                pass
        assert e.value.retry_after == 5
        probe.fail()
    assert b.opened_total == 2


@pytest.mark.anyio
async def test_autograph_retries_inside_one_request_are_one_outcome():
    """Сбой для breaker'а — запрос целиком: сетевые ошибки, после которых повтор удался, его не открывают."""
    state = {"n": 0}

    def flaky(request: httpx.Request) -> httpx.Response:
        state["n"] += 1
        if state["n"] % 3:
            raise httpx.ConnectError("blip", request=request)
        return httpx.Response(200, json={"Groups": [], "Items": []})

    breaker = CircuitBreaker("autograph", failures=2, window_seconds=30, open_seconds=60)
    client = AutoGraphClient(test_settings(autograph_retry_delay_seconds=0), httpx.AsyncClient(
        transport=httpx.MockTransport(flaky)), UpstreamLimiter("autograph", 8), breaker)
    for _ in range(3):
        assert await client.enum_devices("valid-token", "schema-1") == []
    assert breaker.state == "closed" and state["n"] == 9


@pytest.mark.anyio
async def test_autograph_stream_broken_mid_body_is_a_failure():
    class Broken(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield b'{"veh-1": '
            raise httpx.RemoteProtocolError("connection dropped")

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, stream=Broken())

    breaker = CircuitBreaker("autograph", failures=1, window_seconds=30, open_seconds=60)
    client = AutoGraphClient(test_settings(), httpx.AsyncClient(transport=httpx.MockTransport(handler)),
                             UpstreamLimiter("autograph", 8), breaker)
    # trip_tables превращает обрыв в AutoGraphUnavailable; здесь — сам поток.
    with pytest.raises(httpx.TransportError):
        async with client._request("GetTripTables", {}, 5, stream=True) as resp:
            async for _ in resp.aiter_bytes():
                pass
    assert breaker.state == "open"


@pytest.mark.anyio
async def test_analytics_load_shedding_503_does_not_open_breaker():
    def overloaded(request: httpx.Request) -> httpx.Response:
        return httpx.Response(503, json={"detail": "перегружен"})

    breaker = CircuitBreaker("predictive", failures=1, window_seconds=30, open_seconds=60)
    analytics = AnalyticsClient(test_settings(), httpx.AsyncClient(transport=httpx.MockTransport(overloaded)),
                                UpstreamLimiter("predictive", 2), breaker)
    for _ in range(3):
        assert (await analytics.run("antifraud", {})).detail == "антифрод: сервис аналитики перегружен"
    assert breaker.state == "closed"


@pytest.mark.anyio
async def test_llm_timeout_cut_by_chat_deadline_is_not_a_failure():
    def slow(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    breaker = CircuitBreaker("llm", failures=1, window_seconds=30, open_seconds=60)
    client = OpenRouterClient(test_settings(), httpx.AsyncClient(transport=httpx.MockTransport(slow)), breaker)
    with llm_scope(5):  # осталось 5 с из срока чата — таймаут короче FS_LLM_TIMEOUT_SECONDS
        with pytest.raises(LLMTimeout):
            await client.complete([], [])
    assert breaker.state == "closed"
    with pytest.raises(LLMTimeout):  # полный таймаут без ответа — сбой
        await client.complete([], [])
    assert breaker.state == "open"


@pytest.mark.anyio
async def test_proxy_error_with_reserve_counts_even_if_4xx():
    def reserve_misconfigured(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": {"message": "Unauthorized", "code": "401"}})

    breaker = CircuitBreaker("llm", failures=1, window_seconds=30, open_seconds=60)
    settings = test_settings(llm_proxy_url="http://llm_proxy:4000", llm_reserve_base_url="http://reserve/v1",
                             llm_reserve_model="m")
    client = OpenRouterClient(settings, httpx.AsyncClient(transport=httpx.MockTransport(reserve_misconfigured)),
                              breaker)
    with pytest.raises(LLMError):
        await client.complete([], [])
    assert breaker.state == "open"


def _reserve_route(request: httpx.Request) -> httpx.Response:
    return httpx.Response(200, json={"model": "fake/reserve", "choices": [{"message": {"content": "Топлива 250 л."}}]},
                          headers={"x-litellm-model-id": "reserve", "x-litellm-attempted-fallbacks": "1"})


def _stand_with_llm(handler, **settings):
    from fleet_service.main import create_app

    from .fakes import create_fakes

    asgi = httpx.ASGITransport(app=create_fakes())

    async def route(request: httpx.Request) -> httpx.Response:
        if request.url.host == "llm_proxy":
            return handler(request)
        return await asgi.handle_async_request(request)

    from fastapi.testclient import TestClient

    app = create_app(test_settings(llm_proxy_url="http://llm_proxy:4000", **settings),
                     http=httpx.AsyncClient(transport=httpx.MockTransport(route)))
    return TestClient(app, raise_server_exceptions=False)


def test_reply_through_reserve_has_notice_and_warning(caplog):
    with _stand_with_llm(_reserve_route) as client:
        r = client.post("/v1/chat", json=ask(), headers=AUTH)
    assert r.status_code == 200
    reply = r.json()["reply"]
    assert reply.startswith("Топлива 250 л.") and "упрощённом режиме" in reply
    assert any(rec.levelname == "WARNING" and "(reserve)" in rec.getMessage() for rec in caplog.records)


@pytest.mark.anyio
async def test_open_breaker_goes_straight_to_reserve_group():
    """Через прокси с резервом: ответы резерва — сбои OpenRouter; открыт — сразу группа резерва, без OpenRouter."""
    groups: list[str] = []

    def proxy(request: httpx.Request) -> httpx.Response:
        group = httpx.Response(200, content=request.content).json()["model"]
        groups.append(group)
        return _reserve_route(request)

    clock = Clock()
    breaker = CircuitBreaker("llm", failures=2, window_seconds=30, open_seconds=60, clock=clock)
    settings = test_settings(llm_proxy_url="http://llm_proxy:4000", llm_reserve_base_url="http://reserve/v1",
                             llm_reserve_model="fake/reserve")
    client = OpenRouterClient(settings, httpx.AsyncClient(transport=httpx.MockTransport(proxy)), breaker)
    for _ in range(3):
        await client.complete([], [])
    assert groups == ["assistant", "assistant", "assistant-reserve"] and breaker.state == "open"
    clock.now += 60
    await client.complete([], [])
    # Пробный запрос — снова к основной группе (через OpenRouter).
    assert groups[-1] == "assistant"


@pytest.mark.anyio
async def test_open_breaker_without_reserve_fails_fast():
    calls = []

    def reserve_only(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(503, json={"error": {"message": "down", "code": "503"}})

    breaker = CircuitBreaker("llm", failures=1, window_seconds=30, open_seconds=60)
    client = OpenRouterClient(test_settings(llm_proxy_url="http://llm_proxy:4000"),
                              httpx.AsyncClient(transport=httpx.MockTransport(reserve_only)), breaker)
    with pytest.raises(LLMRetryable):
        await client.complete([], [])
    with pytest.raises(LLMRetryable) as e:
        await client.complete([], [])
    assert len(calls) == 1 and e.value.retry_after == pytest.approx(60, abs=1)


def test_reserve_notice_can_be_turned_off():
    with _stand_with_llm(_reserve_route, llm_reserve_degraded=False) as client:
        r = client.post("/v1/chat", json=ask(), headers=AUTH)
    assert r.json()["reply"] == "Топлива 250 л."


def test_status_needs_admin_token(make_client):
    with make_client() as client:
        assert client.get("/v1/status").status_code == 404
    with make_client(test_settings(admin_token="admin-secret")) as client:
        assert client.get("/v1/status").status_code == 401
        assert client.get("/v1/status", headers={"Authorization": "Bearer wrong"}).status_code == 401
        # Токен сессии AutoGRAPH — не ключ администратора.
        assert client.get("/v1/status", headers=AUTH).status_code == 401
        r = client.get("/v1/status", headers={"Authorization": "Bearer admin-secret"})
    assert r.status_code == 200
    body = r.json()
    assert body["breakers"]["autograph"]["state"] == "closed"
    assert body["limits"]["chat"] == {"active": 0, "waiting": 0, "concurrency": 8, "queueMax": 16}
    assert set(body["caches"]) == {"devices", "parameters", "telemetry"}


def test_health_shows_open_breaker_but_stays_ok(make_client):
    with make_client() as client:
        breaker = client.app.state.breakers["predictive"]
        for _ in range(5):
            _fail(breaker)
        body = client.get("/health").json()
    assert body["status"] == "ok" and body["breakers"]["predictive"] == "open"
