"""Кэш по смыслу данных: закрытый период телеметрии живёт дольше открытого, повторный вопрос о прошлой неделе не
идёт в AutoGRAPH, результат аналитики кэшируется по версии модели."""

import json
import logging
from datetime import datetime
from types import SimpleNamespace

import pytest

from fleet_service import cache as cache_module
from fleet_service.cache import TtlCache

from .conftest import AUTH, FakeLLM, ask, test_settings, text, tool_call

# «Сейчас» для стенда: 2026-09-20 00:00 UTC, у пользователя UTC+5 — 05:00.
NOW = datetime(2026, 9, 20, 0, 0)
CLOSED = "from=2026-09-16T00:00:00&to=2026-09-17T00:00:00&utcOffsetMinutes=300&vehicleId=veh-1"
# Конец — 04:50 местного: позже «сейчас − FS_TELEMETRY_SETTLE_MINUTES (30)», период ещё открыт.
OPEN = "from=2026-09-19T05:00:00&to=2026-09-20T04:50:00&utcOffsetMinutes=300&vehicleId=veh-1"


class Monotonic:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def monotonic(monkeypatch) -> Monotonic:
    clock = Monotonic()
    # Подменяем time только в модуле кэша — не в asyncio.
    monkeypatch.setattr(cache_module, "time", SimpleNamespace(monotonic=clock))
    return clock


@pytest.mark.anyio
async def test_entry_ttl(monotonic):
    cache: TtlCache[str] = TtlCache(ttl_seconds=10, max_entries=10)

    async def load() -> str:
        return "v"

    await cache.get("short", load)
    await cache.get("long", load, ttl=100)
    monotonic.now += 50
    assert cache.peek("long") == "v" and cache.peek("short") is None


def test_closed_period_lives_longer(make_client, fakes, monotonic):
    with make_client() as client:
        client.app.state.telemetry_service.clock = lambda: NOW
        for query in (CLOSED, OPEN):
            assert client.get(f"/v1/telemetry?{query}", headers=AUTH).status_code == 200
        loaded = fakes.state.calls["GetTripTables"]
        # Через 200 с (больше FS_TELEMETRY_CACHE_TTL_SECONDS = 120): закрытый период — из кэша, открытый — заново.
        monotonic.now += 200
        client.get(f"/v1/telemetry?{CLOSED}", headers=AUTH)
        assert fakes.state.calls["GetTripTables"] == loaded
        client.get(f"/v1/telemetry?{OPEN}", headers=AUTH)
        assert fakes.state.calls["GetTripTables"] > loaded
        # Закрытый — до FS_TELEMETRY_CLOSED_CACHE_TTL_SECONDS (6 ч).
        reloaded = fakes.state.calls["GetTripTables"]
        monotonic.now += 21600
        client.get(f"/v1/telemetry?{CLOSED}", headers=AUTH)
        assert fakes.state.calls["GetTripTables"] > reloaded


def test_settle_minutes_decide_what_is_closed(make_client):
    with make_client(test_settings(telemetry_settle_minutes=30)) as client:
        service = client.app.state.telemetry_service
        service.clock = lambda: NOW  # 05:00 местного при UTC+5
        assert service.is_closed(datetime(2026, 9, 20, 4, 30), 300)
        assert not service.is_closed(datetime(2026, 9, 20, 4, 31), 300)
        # Другой пояс — другое «сейчас»: при UTC+4 у пользователя 04:00, и 04:30 ещё впереди.
        assert not service.is_closed(datetime(2026, 9, 20, 4, 30), 240)


def test_repeated_question_about_last_week_does_not_call_autograph(make_client, fakes, caplog):
    week = json.dumps({"vehicle_id": "veh-1", "from": "2026-09-07T00:00", "to": "2026-09-14T00:00"})
    llm = FakeLLM(
        tool_call("c1", "get_vehicle_summary", week), text("Сводка за прошлую неделю."),
        tool_call("c2", "get_vehicle_summary", week), text("Сводка за прошлую неделю."),
    )
    with make_client(llm=llm) as client, caplog.at_level(logging.INFO):
        assert client.post("/v1/chat", json=ask("Сколько топлива было на прошлой неделе?"), headers=AUTH).status_code == 200
        loaded = fakes.state.calls["GetTripTables"]
        assert loaded > 0
        assert client.post("/v1/chat", json=ask("А ещё раз за прошлую неделю?"), headers=AUTH).status_code == 200
    assert fakes.state.calls["GetTripTables"] == loaded
    assert any("из кэша (закрытый период)" in r.getMessage() for r in caplog.records)


CHECK = {"vehicleId": "veh-2", "from": "2026-09-16T06:00:00", "to": "2026-09-16T12:00:00", "utcOffsetMinutes": 300}


def test_analytics_result_is_cached_by_model_version(make_client, fakes):
    fakes.state.analytics = "found"
    with make_client() as client:
        first = client.post("/v1/anomalies/check", json=CHECK, headers=AUTH).json()
        assert len(fakes.state.analytics_requests) == 2
        second = client.post("/v1/anomalies/check", json=CHECK, headers=AUTH).json()
        # Закрытый период, та же версия модели — аналитика не вызывается, результат тот же.
        assert len(fakes.state.analytics_requests) == 2
        ml = [[a for a in r["anomalies"] if a["id"].startswith("ml|")] for r in (first, second)]
        assert ml[0] and ml[0] == ml[1] and second["analytics"] == first["analytics"]
        # Новая модель (другая версия в /ready) — старые результаты не используются.
        fakes.state.analytics_version = "fake-2"
        client.app.state.check_service._analytics._versions = TtlCache(60, 1)  # не ждём FS_PREDICTIVE_READY_TTL_SECONDS
        third = client.post("/v1/anomalies/check", json=CHECK, headers=AUTH).json()
    assert len(fakes.state.analytics_requests) == 4
    assert third["analytics"]["antifraud"]["modelVersion"] == "fake-2"


def test_analytics_on_data_loaded_open_lives_short_even_if_period_closed_since(make_client, fakes, monotonic):
    fakes.state.analytics = "found"
    check = {"vehicleId": "veh-2", "from": "2026-09-20T03:00:00", "to": "2026-09-20T04:50:00", "utcOffsetMinutes": 300}
    with make_client() as client:
        service = client.app.state.telemetry_service
        service.clock = lambda: NOW  # 05:00 местного: период до 04:50 ещё открыт
        client.post("/v1/anomalies/check", json=check, headers=AUTH)
        assert len(fakes.state.analytics_requests) == 2
        # Прошло 30 мин — период закрыт, но телеметрия в кэше загружена открытой (поздних точек могло не быть).
        service.clock = lambda: datetime(2026, 9, 20, 0, 30)
        monotonic.now += 60
        client.post("/v1/anomalies/check", json=check, headers=AUTH)
        assert len(fakes.state.analytics_requests) == 2
        # Через FS_TELEMETRY_CACHE_TTL_SECONDS (а не 6 ч) — заново: и телеметрия, и аналитика.
        monotonic.now += 120
        client.post("/v1/anomalies/check", json=check, headers=AUTH)
    assert len(fakes.state.analytics_requests) == 4


@pytest.mark.anyio
async def test_concurrent_identical_checks_share_one_analytics_request(fakes):
    import asyncio

    import httpx

    from fleet_service.main import create_app

    fakes.state.analytics = "ready"
    app = create_app(test_settings(), http=httpx.AsyncClient(transport=httpx.ASGITransport(app=fakes)),
                     background_loop=False)
    async with app.router.lifespan_context(app), httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://stand"
    ) as client:
        responses = await asyncio.gather(*(client.post("/v1/anomalies/check", json=CHECK, headers=AUTH)
                                           for _ in range(3)))
    assert all(r.status_code == 200 for r in responses)
    assert len(fakes.state.analytics_requests) == 2


def test_open_analytics_breaker_skips_ready_probe(make_client, fakes):
    fakes.state.analytics = "ready"
    with make_client() as client:
        breaker = client.app.state.breakers["predictive"]
        for _ in range(5):
            with breaker.attempt() as a:
                a.fail()
        body = client.post("/v1/anomalies/check", json=CHECK, headers=AUTH).json()
    assert body["analytics"]["antifraud"]["detail"] == "антифрод: сервис аналитики временно отключён после сбоев"
    assert fakes.state.calls["ready"] == 0 and fakes.state.analytics_requests == []


def test_not_ready_and_failed_analytics_are_not_cached(make_client, fakes):
    with make_client() as client:
        for _ in range(2):
            client.post("/v1/anomalies/check", json=CHECK, headers=AUTH)
    # not_ready (моделей нет) — каждый раз спрашиваем заново.
    assert len(fakes.state.analytics_requests) == 4
