"""GET /v1/vehicles и /v1/telemetry, клиент AutoGRAPH (части, повторы, длина query, gzip) и доступ к машинам."""

import asyncio
import logging
from datetime import datetime, timedelta

import httpx
import pytest
from fastapi.testclient import TestClient

from fleet_service.autograph.client import chunk_ranges
from fleet_service.main import create_app

from .conftest import AUTH, test_settings
from .fakes import OTHER_TOKEN, VALID_TOKEN

OTHER = {**AUTH, "Authorization": f"Bearer {OTHER_TOKEN}"}


def period(hours: int = 24, start: str = "2026-09-15T18:00:00") -> dict:
    begin = datetime.fromisoformat(start)
    end = begin + timedelta(hours=hours)
    return {"from": begin.isoformat(), "to": end.isoformat(), "utcOffsetMinutes": 300}


def test_vehicles_of_user(make_client, fakes):
    with make_client() as client:
        r = client.get("/v1/vehicles", headers=AUTH)
        assert client.get("/v1/vehicles", headers=AUTH).status_code == 200
        other = client.get("/v1/vehicles", headers=OTHER).json()
    assert r.status_code == 200
    # Allowed=false не показывается; группа — из Groups; по имени.
    assert r.json() == [
        {"id": "veh-1", "name": "FAW №1", "group": "Колонна 1"},
        {"id": "veh-2", "name": "Урал NEXT А001АА", "group": None},
    ]
    assert [v["id"] for v in other] == ["veh-1"]
    # Список пользователя кэшируется по токену: второй запрос того же пользователя в AutoGRAPH не ходит.
    assert fakes.state.calls["EnumDevices"] == 2


def test_vehicles_require_session(make_client):
    with make_client() as client:
        assert client.get("/v1/vehicles").status_code == 401
        assert client.get("/v1/vehicles", headers={**AUTH, "Authorization": "Bearer expired"}).status_code == 401


def test_telemetry_contract_and_chunks(make_client, fakes):
    with make_client() as client:
        r = client.get("/v1/telemetry", headers=AUTH, params={"vehicleId": "veh-1", **period(24)})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["vehicle"] == {"id": "veh-1", "name": "FAW №1", "group": "Колонна 1"}
    assert body["from"] == "2026-09-15T18:00:00" and body["to"] == "2026-09-16T18:00:00"
    fuel = body["tables"]["FUEL"]
    # 24 ч → интервалы 2 мин, 721 отметка; время без пояса, с секундами.
    assert len(fuel["timestamps"]) == 721 and fuel["timestamps"][0] == "2026-09-15T18:00:00"
    assert fuel["columns"][0]["parameter"] == {"name": "TankMainFuelLevel", "caption": "Уровень топлива",
                                               "unit": "л", "category": "FUEL"}
    assert set(fuel["columns"][0]["values"]) == {250.0}
    # Обороты всегда 0 — датчик без данных, раздела «Двигатель» с ними нет; температура ОЖ есть.
    assert [c["parameter"]["name"] for c in body["tables"]["ENGINE"]["columns"]] == ["TemperatureCOOL"]
    # Части по 6 ч, формат yyyyMMdd-HHmm, только выбранные параметры (без интервальных).
    requests = fakes.state.trip_requests
    assert [(q["SD"], q["ED"]) for q in requests] == [
        ("20260915-1800", "20260916-0000"), ("20260916-0000", "20260916-0600"),
        ("20260916-0600", "20260916-1200"), ("20260916-1200", "20260916-1800"),
    ]
    assert requests[0]["onlineParams"] == "TankMainFuelLevel,Power,DIgnition,Rotation,TemperatureCOOL,PressureOIL,Speed"
    assert requests[0]["tripSplitterIndex"] == "-1" and requests[0]["IDs"] == "veh-1"


def test_telemetry_is_cached_per_schema_vehicle_and_period(make_client, fakes):
    with make_client() as client:
        for headers in (AUTH, AUTH, OTHER):
            assert client.get("/v1/telemetry", headers=headers,
                              params={"vehicleId": "veh-1", **period(6)}).status_code == 200
        # Другой пояс — другой ключ: AutoGRAPH трактует время в поясе токена.
        client.get("/v1/telemetry", headers=AUTH, params={"vehicleId": "veh-1", **period(6), "utcOffsetMinutes": 180})
    # Пользователь другого токена той же схемы получил данные из кэша.
    assert fakes.state.calls["GetTripTables"] == 2
    assert fakes.state.calls["EnumParameters"] == 1


def test_foreign_vehicle_is_404_even_if_cached(make_client, fakes):
    with make_client() as client:
        assert client.get("/v1/telemetry", headers=AUTH,
                          params={"vehicleId": "veh-2", **period(6)}).status_code == 200
        calls = fakes.state.calls["GetTripTables"]
        # veh-2 нет в EnumDevices у other-token: кэш схемы не выдаёт её чужому пользователю.
        r = client.get("/v1/telemetry", headers=OTHER, params={"vehicleId": "veh-2", **period(6)})
        assert r.status_code == 404 and r.json() == {"detail": "Машина не найдена или недоступна этому пользователю"}
        # Allowed=false и несуществующая машина — тоже 404, в AutoGRAPH за данными не ходим.
        for vid in ("veh-3", "nope"):
            assert client.get("/v1/telemetry", headers=AUTH, params={"vehicleId": vid, **period(6)}).status_code == 404
    assert fakes.state.calls["GetTripTables"] == calls


@pytest.mark.parametrize("params, fragment", [
    ({"from": "2026-09-16T10:00:00", "to": "2026-09-16T10:00:00"}, "раньше конца"),
    ({"from": "2026-09-01T00:00:00", "to": "2026-09-16T00:00:00"}, "длиннее 168 ч"),
    ({"from": "2026-09-16T10:00:00+05:00", "to": "2026-09-16T12:00:00+05:00"}, "без часового пояса"),
    ({"from": "вчера", "to": "2026-09-16T12:00:00"}, "Неверный запрос"),
])
def test_bad_period_is_422(make_client, params, fragment):
    with make_client() as client:
        r = client.get("/v1/telemetry", headers=AUTH, params={"vehicleId": "veh-1", "utcOffsetMinutes": 0, **params})
    assert r.status_code == 422 and fragment in r.json()["detail"]


def test_long_parameter_list_is_split_by_query_length(make_client, fakes):
    fakes.state.parameters["veh-1"] = [
        {"Name": f"VoltageSensor{i:02d}", "Caption": f"Напряжение {i}", "ReturnType": 4} for i in range(40)
    ]
    with make_client(test_settings(autograph_max_query_chars=400)) as client:
        r = client.get("/v1/telemetry", headers=AUTH, params={"vehicleId": "veh-1", **period(6)})
    assert r.status_code == 200
    requests = fakes.state.trip_requests
    assert len(requests) > 1 and all(q["urlLength"] <= 400 for q in requests)
    names = [n for q in requests for n in q["onlineParams"].split(",")]
    assert names == [f"VoltageSensor{i:02d}" for i in range(40)]


def _flaky_client(fakes, failures: int) -> tuple[TestClient, dict]:
    """Стенд, у которого первые [failures] запросов GetTripTables обрываются сетевой ошибкой."""
    asgi = httpx.ASGITransport(app=fakes)
    state = {"left": failures, "attempts": 0}

    async def route(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/GetTripTables"):
            state["attempts"] += 1
            if state["left"] > 0:
                state["left"] -= 1
                raise httpx.ConnectError("connection reset", request=request)
        return await asgi.handle_async_request(request)

    http = httpx.AsyncClient(transport=httpx.MockTransport(route))
    settings = test_settings(autograph_retry_delay_seconds=0)
    return TestClient(create_app(settings, http=http), raise_server_exceptions=False), state


def test_network_errors_are_retried(fakes):
    client, state = _flaky_client(fakes, failures=2)
    with client:
        r = client.get("/v1/telemetry", headers=AUTH, params={"vehicleId": "veh-1", **period(6)})
    assert r.status_code == 200 and state["attempts"] == 3


def test_autograph_down_after_retries_is_503_and_not_cached(fakes, caplog):
    client, state = _flaky_client(fakes, failures=3)
    with caplog.at_level(logging.INFO), client:
        r = client.get("/v1/telemetry", headers=AUTH, params={"vehicleId": "veh-1", **period(6)})
        assert r.status_code == 503
        assert r.json() == {"detail": "AutoGRAPH недоступен: нет ответа после 3 попыток"}
        assert state["attempts"] == 3
        # Ошибка не кэшируется: следующий запрос снова идёт в AutoGRAPH и получает данные.
        assert client.get("/v1/telemetry", headers=AUTH,
                          params={"vehicleId": "veh-1", **period(6)}).status_code == 200
    assert VALID_TOKEN not in caplog.text


def test_expired_session_during_load_is_401(make_client, fakes):
    """Токен истёк между проверкой сессии (кэш) и загрузкой — приложение должно войти заново: 401, а не 503."""
    with make_client() as client:
        assert client.get("/v1/vehicles", headers=AUTH).status_code == 200
        from . import fakes as fakes_module

        devices = fakes_module.DEVICES.pop(VALID_TOKEN)
        try:
            r = client.get("/v1/telemetry", headers=AUTH, params={"vehicleId": "veh-1", **period(6)})
        finally:
            fakes_module.DEVICES[VALID_TOKEN] = devices
    assert r.status_code == 401 and r.headers["www-authenticate"] == "Bearer"


def test_concurrent_requests_share_one_load(make_client, fakes):
    from fleet_service.telemetry.service import TelemetryService

    with make_client() as client:
        service: TelemetryService = client.app.state.telemetry_service

        async def both():
            args = (VALID_TOKEN, "schema-1", "veh-1", datetime(2026, 9, 16, 6), datetime(2026, 9, 16, 12), 300)
            return await asyncio.gather(service.telemetry(*args), service.telemetry(*args))

        a, b = client.portal.call(both)
    assert a is b
    assert fakes.state.calls["GetTripTables"] == 1


def test_shared_load_with_expired_foreign_token_does_not_fail_valid_user(make_client, fakes):
    """Общая загрузка схемы шла с токеном A, который истёк: пользователь B с живым токеном получает данные,
    а A — 401 (войдёт заново)."""
    from fleet_service.autograph.errors import SessionInvalid
    from fleet_service.telemetry.service import TelemetryService

    from . import fakes as fakes_module

    with make_client() as client:
        service: TelemetryService = client.app.state.telemetry_service
        # Список машин A уже в кэше (сессию проверили недавно), а сам токен A в AutoGRAPH уже не действует.
        client.portal.call(service.vehicles, OTHER_TOKEN, "schema-1")
        devices = fakes_module.DEVICES.pop(OTHER_TOKEN)
        try:
            async def both():
                args = ("schema-1", "veh-1", datetime(2026, 9, 16, 6), datetime(2026, 9, 16, 12), 300)
                return await asyncio.gather(service.telemetry(OTHER_TOKEN, *args), service.telemetry(VALID_TOKEN, *args),
                                            return_exceptions=True)

            a, b = client.portal.call(both)
        finally:
            fakes_module.DEVICES[OTHER_TOKEN] = devices
    assert isinstance(a, SessionInvalid)
    assert b.telemetry.vehicle.id == "veh-1"


def test_chunk_ranges():
    start = datetime(2026, 9, 16, 10, 0, 30)
    assert chunk_ranges(start, start, timedelta(hours=6)) == []
    ranges = chunk_ranges(start, datetime(2026, 9, 16, 22, 0, 0), timedelta(hours=6))
    assert ranges == [
        (start, datetime(2026, 9, 16, 16, 0, 30)),
        (datetime(2026, 9, 16, 16, 0, 30), datetime(2026, 9, 16, 22, 0, 0)),
    ]
