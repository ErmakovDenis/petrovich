"""Пределы нагрузки через API: очередь чата, rate limit, общий предел запросов к AutoGRAPH и аналитике."""

import asyncio

import httpx
import pytest

from fleet_service.analytics.client import AnalyticsClient
from fleet_service.autograph import client as autograph_client
from fleet_service.autograph.errors import AutoGraphUnavailable
from fleet_service.autograph.session import AutoGraphSessionChecker
from fleet_service.limits import UpstreamLimiter
from fleet_service.main import create_app

from .conftest import AUTH, FakeLLM, ask, test_settings, text
from .fakes import create_fakes


class BlockingLLM:
    """Модель, которая отвечает, только когда тест откроет [gate]."""

    def __init__(self) -> None:
        self.gate = asyncio.Event()
        self.started = 0

    async def complete(self, messages, tools):
        self.started += 1
        await self.gate.wait()
        return text("Готово")


async def _post_chat(client: httpx.AsyncClient) -> httpx.Response:
    return await client.post("/v1/chat", json=ask(), headers=AUTH)


@pytest.mark.anyio
async def test_third_concurrent_chat_is_rejected_at_once():
    fakes = create_fakes()
    llm = BlockingLLM()
    app = create_app(test_settings(chat_concurrency=1, chat_queue_max=1),
                     http=httpx.AsyncClient(transport=httpx.ASGITransport(app=fakes)), llm=llm, background_loop=False)
    async with app.router.lifespan_context(app), httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://stand"
    ) as client:
        first = asyncio.create_task(_post_chat(client))
        second = asyncio.create_task(_post_chat(client))
        for _ in range(100):
            if llm.started == 1 and app.state.limits.chat.waiting == 1:
                break
            await asyncio.sleep(0.01)
        # Один отвечает, один ждёт в очереди — третий получает отказ сразу, не дожидаясь первых.
        third = await asyncio.wait_for(_post_chat(client), timeout=5)
        assert third.status_code == 503 and third.headers["retry-after"] == "60"
        assert third.json() == {"detail": "Высокая нагрузка на ассистента, повторите через минуту"}
        llm.gate.set()
        assert [r.status_code for r in await asyncio.gather(first, second)] == [200, 200]
        # Отказ 503 не потратил лимит пользователя: FS_CHAT_BURST=3, два ответа — третий вопрос проходит.
        assert (await _post_chat(client)).status_code == 200


def test_chat_rate_limit_per_user(make_client):
    llm = FakeLLM(*[text("ок")] * 3)
    with make_client(test_settings(chat_burst=2, chat_rate_per_minute=6), llm=llm) as client:
        assert [client.post("/v1/chat", json=ask(), headers=AUTH).status_code for _ in range(2)] == [200, 200]
        r = client.post("/v1/chat", json=ask(), headers=AUTH)
        assert r.status_code == 429 and r.headers["retry-after"] == "10"
        assert r.json() == {"detail": "Слишком много вопросов подряд, повторите через 10 с"}
        # У другого пользователя (другая сессия) — своё ведро.
        other = {"Authorization": "Bearer other-token", "X-Schema-Id": "schema-1"}
        assert client.post("/v1/chat", json=ask(), headers=other).status_code == 200


def test_invalid_request_does_not_spend_the_limit(make_client):
    llm = FakeLLM(text("ок"))
    with make_client(test_settings(chat_burst=1), llm=llm) as client:
        bad = {"messages": [{"role": "assistant", "content": "?"}], "utcOffsetMinutes": 300}
        assert client.post("/v1/chat", json=bad, headers=AUTH).status_code == 422
        assert client.post("/v1/chat", json=ask(), headers=AUTH).status_code == 200


@pytest.mark.anyio
async def test_concurrent_scans_respect_autograph_limit():
    fakes = create_fakes()
    asgi = httpx.ASGITransport(app=fakes)
    inflight = {"now": 0, "max": 0}

    async def counting(request: httpx.Request) -> httpx.Response:
        if not request.url.path.startswith("/autograph"):
            return await asgi.handle_async_request(request)
        inflight["now"] += 1
        inflight["max"] = max(inflight["max"], inflight["now"])
        try:
            await asyncio.sleep(0.02)
            return await asgi.handle_async_request(request)
        finally:
            inflight["now"] -= 1

    settings = test_settings(autograph_concurrency=2, scan_concurrency=4, auth_cache_ttl_seconds=0)
    app = create_app(settings, http=httpx.AsyncClient(transport=httpx.MockTransport(counting)), background_loop=False)
    scan = {"vehicleIds": ["veh-1", "veh-2"], "from": "2026-09-16T00:00:00", "to": "2026-09-16T12:00:00",
            "utcOffsetMinutes": 300}
    async with app.router.lifespan_context(app), httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://stand"
    ) as client:
        responses = await asyncio.gather(*(client.post("/v1/anomalies/scan", json=scan, headers=AUTH) for _ in range(4)))
    assert all(r.status_code == 200 and all(v["ok"] for v in r.json()["results"]) for r in responses)
    # 4 scan × 4 машины параллельно — а к AutoGRAPH одновременно не больше FS_AUTOGRAPH_CONCURRENCY.
    assert inflight["max"] == 2
    assert app.state.limits.autograph.active == 0


@pytest.mark.anyio
async def test_autograph_retry_delay_is_exponential_with_jitter(monkeypatch):
    sleeps: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        sleeps.append(seconds)

    monkeypatch.setattr(autograph_client.asyncio, "sleep", fake_sleep)
    monkeypatch.setattr(autograph_client.random, "uniform", lambda a, b: b)

    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    settings = test_settings(autograph_retries=5, autograph_retry_delay_seconds=1, autograph_retry_max_seconds=5)
    client = autograph_client.AutoGraphClient(settings, httpx.AsyncClient(transport=httpx.MockTransport(down)),
                                              UpstreamLimiter("autograph", 8))
    with pytest.raises(autograph_client.AutoGraphUnavailable):
        await client.enum_devices("valid-token", "schema-1")
    assert sleeps == [1, 2, 4, 5]
    # Jitter: пауза — от половины до полной.
    assert autograph_client.retry_delay(settings, 3) == 4
    monkeypatch.setattr(autograph_client.random, "uniform", lambda a, b: a)
    assert autograph_client.retry_delay(settings, 3) == 2


@pytest.mark.anyio
async def test_session_check_does_not_wait_for_a_slot_forever():
    limiter = UpstreamLimiter("autograph", concurrency=1)
    held = await limiter.acquire()
    checker = AutoGraphSessionChecker(test_settings(autograph_timeout_seconds=0.05),
                                      httpx.AsyncClient(transport=httpx.ASGITransport(app=create_fakes())), limiter)
    with pytest.raises(AutoGraphUnavailable):
        await checker.verify("valid-token", "schema-1")
    held.release()
    await checker.verify("valid-token", "schema-1")
    assert limiter.active == 0


@pytest.mark.anyio
async def test_analytics_gets_the_stand_deadline():
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers["x-client-timeout"])
        return httpx.Response(200, json={"ready": False, "modelVersion": None, "anomalies": []})

    analytics = AnalyticsClient(test_settings(predictive_timeout_seconds=30),
                                httpx.AsyncClient(transport=httpx.MockTransport(handler)), UpstreamLimiter("p", 2))
    assert (await analytics.run("predictive", {})).status == "not_ready"
    # Остаток срока стенда: аналитика не держит запрос в очереди дольше.
    assert 29 <= float(seen[0]) <= 30


@pytest.mark.anyio
async def test_analytics_waiting_for_a_slot_counts_into_timeout():
    fakes = create_fakes()
    limiter = UpstreamLimiter("predictive", concurrency=1)
    held = await limiter.acquire()
    settings = test_settings(predictive_timeout_seconds=0.05)
    analytics = AnalyticsClient(settings, httpx.AsyncClient(transport=httpx.ASGITransport(app=fakes)), limiter)
    status = await analytics.run("antifraud", {"vehicle": {"id": "veh-1"}})
    assert status.status == "unavailable" and status.detail == "антифрод: сервис аналитики перегружен"
    assert fakes.state.analytics_requests == []
    held.release()
    assert limiter.active == 0
