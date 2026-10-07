"""Tools list_vehicles и get_vehicle_summary: числа из кода, размер ответа не зависит от периода, «нет данных» ≠ 0,
сбой AutoGRAPH уходит модели как ошибка, а пользователь получает честный ответ."""

import asyncio
import json
import logging

import httpx
import pytest
from fastapi.testclient import TestClient

from fleet_service.agent.fleet_tools import build_fleet_tools
from fleet_service.agent.tools import Tool, ToolContext, ToolRegistry
from fleet_service.main import create_app

from .conftest import AUTH, ask, test_settings
from .fakes import OTHER_TOKEN, SCHEMA_ID, VALID_TOKEN

CTX = ToolContext(VALID_TOKEN, SCHEMA_ID, 300)


def run_tool(client: TestClient, name: str, args: dict, ctx: ToolContext = CTX) -> dict:
    """Вызов tool как из цикла агента: через реестр стенда, результат — JSON-строка для модели."""
    settings = client.app.state.settings
    registry = ToolRegistry(max_result_chars=settings.tool_max_result_chars)
    for tool in build_fleet_tools(settings, client.app.state.telemetry_service):
        registry.register(tool)
    return json.loads(client.portal.call(registry.execute, name, json.dumps(args), ctx))


@pytest.fixture
def client(make_client):
    with make_client() as c:
        yield c


def test_list_vehicles_with_query_and_limit(make_client):
    with make_client(test_settings(tool_max_vehicles=1)) as c:
        everything = run_tool(c, "list_vehicles", {})
        ural = run_tool(c, "list_vehicles", {"query": "урал"})
        other = run_tool(c, "list_vehicles", {}, ToolContext(OTHER_TOKEN, SCHEMA_ID, 300))
    assert everything == {"total": 2, "truncated": True,
                          "vehicles": [{"id": "veh-1", "name": "FAW №1", "group": "Колонна 1"}]}
    assert ural["vehicles"] == [{"id": "veh-2", "name": "Урал NEXT А001АА", "group": None}]
    assert other["total"] == 1


def test_summary_has_aggregates_units_period_and_no_data(client):
    s = run_tool(client, "get_vehicle_summary",
                 {"vehicle_id": "veh-1", "from": "2026-09-16T00:00", "to": "2026-09-16T06:00"})
    assert s["vehicle"] == {"id": "veh-1", "name": "FAW №1", "group": "Колонна 1"}
    assert s["period"] == {"from": "2026-09-16T00:00", "to": "2026-09-16T06:00", "bucketMinutes": 1, "intervals": 361}
    by_name = {p["name"]: p for p in s["parameters"]}
    assert by_name["TankMainFuelLevel"] == {
        "name": "TankMainFuelLevel", "caption": "Уровень топлива", "unit": "л", "category": "FUEL",
        "min": 250.0, "max": 250.0, "mean": 250.0, "last": 250.0, "lastTime": "2026-09-16T06:00", "coverage": 1.0,
    }
    speed = by_name["Speed"]
    assert (speed["min"], speed["max"], speed["unit"]) == (0.0, 60.0, "км/ч")
    # Давление масла не приходило — «нет данных»; обороты весь период 0 — отдельно, не как «нет данных».
    assert "Rotation" not in by_name and "PressureOIL" not in by_name
    assert s["noDataParameters"] == ["Давление масла"]
    assert s["allZeroParameters"] == ["Обороты"]


def test_summary_size_does_not_depend_on_period(client):
    day = run_tool(client, "get_vehicle_summary",
                   {"vehicle_id": "veh-1", "from": "2026-09-15T12:00", "to": "2026-09-15T18:00"})
    week = run_tool(client, "get_vehicle_summary",
                    {"vehicle_id": "veh-1", "from": "2026-09-08T18:00", "to": "2026-09-15T18:00"})
    assert week["period"]["intervals"] == 673 and day["period"]["intervals"] == 361
    a, b = len(json.dumps(day, ensure_ascii=False)), len(json.dumps(week, ensure_ascii=False))
    assert abs(a - b) < 50 and b < 2000


def test_summary_accepts_unique_name_and_defaults_to_last_24h(client):
    s = run_tool(client, "get_vehicle_summary", {"vehicle_id": "faw №1"})
    assert s["vehicle"]["id"] == "veh-1"
    assert s["period"]["bucketMinutes"] == 2 and s["period"]["intervals"] == 721


@pytest.mark.parametrize("args, fragment", [
    ({"vehicle_id": "veh-3"}, "не найдена"),
    ({"vehicle_id": "veh-1", "from": "2026-09-01T00:00", "to": "2026-09-16T00:00"}, "длиннее 168 ч"),
    ({"vehicle_id": "veh-1", "from": "вчера"}, "ожидается время"),
    ({"vehicle_id": "veh-1", "to": "2026-09-16T00:00+05:00"}, "без часового пояса"),
    ({}, "не указан vehicle_id"),
])
def test_summary_errors_go_back_to_model(client, args, fragment):
    result = run_tool(client, "get_vehicle_summary", args)
    assert fragment in result["error"]


def test_foreign_vehicle_is_not_available_to_tool(client):
    run_tool(client, "get_vehicle_summary", {"vehicle_id": "veh-2"})  # в кэше схемы
    result = run_tool(client, "get_vehicle_summary", {"vehicle_id": "veh-2"}, ToolContext(OTHER_TOKEN, SCHEMA_ID, 300))
    assert "не найдена" in result["error"]


def test_result_size_guard():
    async def huge(args, ctx):
        return {"data": "x" * 2000}

    registry = ToolRegistry(max_result_chars=1000)
    registry.register(Tool("huge", "большой ответ", {"type": "object", "properties": {}}, huge))
    result = json.loads(asyncio.run(registry.execute("huge", "{}", CTX)))
    assert result == {"error": "huge: ответ слишком большой, уточните запрос"}


def test_question_about_fuel_is_answered_with_number_from_tool(make_client, fakes, caplog):
    with caplog.at_level(logging.INFO):
        with make_client() as c:
            r = c.post("/v1/chat", json=ask("Что с топливом у FAW №1 за сутки?"), headers=AUTH)
    assert r.status_code == 200
    reply = r.json()["reply"]
    assert reply.startswith("FAW №1: средний уровень топлива 250.0 л")
    # Модель получила сводку от tool, а не ряды точек.
    tool_messages = [m for m in fakes.state.llm_requests[-1]["messages"] if m["role"] == "tool"]
    summary = json.loads(tool_messages[-1]["content"])
    assert "timestamps" not in tool_messages[-1]["content"] and summary["parameters"]
    # Вызов tool виден в логе (с аргументами, без токена).
    assert "модель вызывает tool get_vehicle_summary({\"vehicle_id\": \"veh-1\"})" in caplog.text
    assert "tool get_vehicle_summary: выполнен" in caplog.text
    assert VALID_TOKEN not in caplog.text


def test_autograph_down_gives_honest_answer_not_500(fakes):
    asgi = httpx.ASGITransport(app=fakes)

    async def route(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/GetTripTables"):
            raise httpx.ConnectError("connection refused", request=request)
        return await asgi.handle_async_request(request)

    http = httpx.AsyncClient(transport=httpx.MockTransport(route))
    with TestClient(create_app(test_settings(autograph_retry_delay_seconds=0), http=http)) as c:
        r = c.post("/v1/chat", json=ask(), headers=AUTH)
    assert r.status_code == 200
    assert r.json()["reply"] == ("Данных об этом у меня нет: get_vehicle_summary не выполнен: "
                                 "AutoGRAPH недоступен: нет ответа после 3 попыток")
