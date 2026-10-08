"""POST /v1/anomalies/check: правила + predictive_antifraud, статус аналитики, доступ к машинам; tool check_vehicle
и три исхода ответа ассистента (найдены / не найдены / проверка недоступна) на подставной модели."""

import json
import re

import httpx
import pytest
from fastapi.testclient import TestClient

from fleet_service.agent.fleet_tools import build_fleet_tools
from fleet_service.agent.tools import ToolContext, ToolRegistry
from fleet_service.main import create_app
from fleet_service.rules.thresholds import RuleThresholds

from .conftest import AUTH, ask, test_settings
from .fakes import FAKE_PA_KEY, OTHER_TOKEN, SCHEMA_ID, VALID_TOKEN

PERIOD = {"from": "2026-09-16T06:00:00", "to": "2026-09-16T12:00:00", "utcOffsetMinutes": 300}


def check(client: TestClient, vehicle_id: str = "veh-2", headers: dict = AUTH, **extra) -> httpx.Response:
    return client.post("/v1/anomalies/check", headers=headers, json={"vehicleId": vehicle_id, **PERIOD, **extra})


def test_rules_find_overheat_and_analytics_not_ready(make_client, fakes):
    with make_client() as client:
        r = check(client)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["vehicleId"] == "veh-2" and body["modelsReady"] is False
    assert body["analytics"]["predictive"] == {"status": "not_ready", "modelVersion": None,
                                               "detail": "предиктивная аналитика: модель не загружена",
                                               "anomaliesFound": 0}
    assert body["analytics"]["antifraud"]["status"] == "not_ready"
    # Перегрев 108 °C с 30-й по 39-ю минуту каждого часа: 6 эпизодов, id как у BaselineAnomalyDetector.
    ids = [a["id"] for a in body["anomalies"]]
    assert ids == [f"rule|overheat|veh-2|TemperatureCOOL|2026-09-16T{h:02d}:30" for h in range(6, 12)]
    assert body["rulesAnomalies"] == 6
    first = body["anomalies"][0]
    assert (first["severity"], first["title"], first["value"], first["source"]) == \
        ("CRITICAL", "Перегрев двигателя", 108.0, "Базовые правила")
    assert first["vehicleName"] == "Урал NEXT А001АА" and first["category"] == "ENGINE"
    assert first["description"] == "Температура охлаждающей жидкости 108 °C (норма до 100 °C)."


def test_analytics_gets_telemetry_and_api_key(make_client, fakes):
    fakes.state.analytics = "ready"
    with make_client() as client:
        body = check(client, "veh-1").json()
        telemetry = client.get("/v1/telemetry", headers=AUTH, params={"vehicleId": "veh-1", **PERIOD}).json()
    assert body["modelsReady"] is True and body["anomalies"] == []
    assert body["analytics"]["predictive"]["modelVersion"] == "fake-1"
    # Обе модели получили VehicleTelemetry в контракте — тот же JSON, что отдаёт /v1/telemetry.
    assert [s for s, _ in fakes.state.analytics_requests] == ["predictive", "antifraud"]
    assert all(payload == telemetry for _, payload in fakes.state.analytics_requests)


def test_ml_anomalies_come_first_without_duplicates(make_client, fakes):
    fakes.state.analytics = "found"
    with make_client() as client:
        body = check(client).json()
    assert body["anomalies"][0]["id"].startswith("ml|fraud|veh-2|")
    assert body["analytics"]["antifraud"]["anomaliesFound"] == 1
    assert len(body["anomalies"]) == 7 and len({a["id"] for a in body["anomalies"]}) == 7


@pytest.mark.parametrize("setup, detail", [
    ({"mode": "down"}, "ответил ошибкой 500"),
    ({"settings": {"predictive_api_key": "wrong"}}, "ответил ошибкой 401"),
    ({"settings": {"predictive_url": ""}}, "не настроен"),
])
def test_analytics_failure_does_not_break_rules(make_client, fakes, setup, detail):
    fakes.state.analytics = setup.get("mode", "ready")
    with make_client(test_settings(**setup.get("settings", {}))) as client:
        r = check(client)
    assert r.status_code == 200
    body = r.json()
    assert body["rulesAnomalies"] == 6 and len(body["anomalies"]) == 6
    assert body["modelsReady"] is False
    assert body["analytics"]["predictive"]["status"] == "unavailable"
    assert detail in body["analytics"]["predictive"]["detail"]


def test_analytics_unreachable(fakes):
    asgi = httpx.ASGITransport(app=fakes)

    async def route(request: httpx.Request) -> httpx.Response:
        if request.url.path.startswith("/predictive"):
            raise httpx.ConnectError("connection refused", request=request)
        return await asgi.handle_async_request(request)

    with TestClient(create_app(test_settings(), http=httpx.AsyncClient(transport=httpx.MockTransport(route)))) as c:
        body = check(c).json()
    assert body["analytics"]["antifraud"] == {"status": "unavailable", "modelVersion": None, "anomaliesFound": 0,
                                              "detail": "антифрод: сервис аналитики не отвечает"}
    assert body["rulesAnomalies"] == 6


def test_thresholds_from_settings(make_client):
    with make_client(test_settings(rules=RuleThresholds(overheat_celsius=110))) as client:
        assert check(client).json()["anomalies"] == []


def test_access_and_validation(make_client):
    with make_client() as client:
        assert check(client, headers={}).status_code == 401
        assert check(client, "veh-2", headers={**AUTH, "Authorization": f"Bearer {OTHER_TOKEN}"}).status_code == 404
        assert check(client, "veh-3").status_code == 404
        r = client.post("/v1/anomalies/check", headers=AUTH,
                        json={"vehicleId": "veh-1", "from": "2026-09-16T12:00:00", "to": "2026-09-16T06:00:00",
                              "utcOffsetMinutes": 0})
        assert r.status_code == 422 and "раньше конца" in r.json()["detail"]


def test_api_key_is_not_logged(make_client, caplog):
    with make_client() as client:
        check(client)
    assert FAKE_PA_KEY not in caplog.text and VALID_TOKEN not in caplog.text


# --- tool check_vehicle и ответы ассистента ---

def run_check(client: TestClient, args: dict) -> dict:
    settings = client.app.state.settings
    registry = ToolRegistry(max_result_chars=settings.tool_max_result_chars)
    for tool in build_fleet_tools(settings, client.app.state.telemetry_service, client.app.state.check_service):
        registry.register(tool)
    return json.loads(client.portal.call(registry.execute, "check_vehicle", json.dumps(args),
                                         ToolContext(VALID_TOKEN, SCHEMA_ID, 300)))


def test_tool_outcomes(make_client, fakes):
    args = {"from": "2026-09-16T06:00", "to": "2026-09-16T12:00"}
    with make_client(test_settings(tool_max_anomalies=2)) as client:
        found = run_check(client, {"vehicle_id": "veh-2", **args})
        unavailable = run_check(client, {"vehicle_id": "veh-1", **args})
        fakes.state.analytics = "ready"
        none = run_check(client, {"vehicle_id": "veh-1", **args})
    assert found["outcome"] == "anomalies_found" and found["total"] == 6 and found["truncated"] is True
    assert [a["type"] for a in found["anomalies"]] == ["overheat", "overheat"]
    assert found["predictiveCheckAvailable"] is False and "только по правилам" in found["message"]
    assert unavailable["outcome"] == "analytics_unavailable" and unavailable["total"] == 0
    assert unavailable["predictiveCheckAvailable"] is False
    assert "модель не загружена" in unavailable["message"]
    assert "не означает, что нарушений нет" in unavailable["message"]
    assert none["outcome"] == "no_anomalies" and none["predictiveCheckAvailable"] is True and "message" not in none


def test_anomaly_kind_tolerates_foreign_id_format():
    from fleet_service.agent.fleet_tools import _kind

    assert _kind("rule|overheat|42|TemperatureCOOL|2026-09-16T10:30") == "overheat"
    assert _kind("3f1c2a9e-uuid-без-разделителей") == ""


# Период по умолчанию — последние сутки от текущего времени: число часовых эпизодов перегрева veh-2 (24 или 25)
# зависит от минуты запуска теста.
@pytest.mark.parametrize("mode, question, reply", [
    ("not_ready", "Есть ли аномалии у Урал за сутки?",
     r"Урал NEXT А001АА: найдено аномалий — 2[45] \(Перегрев двигателя\)\. Предиктивная проверка недоступна\."),
    ("ready", "Проверь FAW на нарушения", r"FAW №1: правила и аналитика нарушений не нашли\."),
    ("not_ready", "Проверь FAW на нарушения", r"FAW №1: по правилам нарушений нет, но предиктивная проверка недоступна\."),
])
def test_assistant_distinguishes_three_outcomes(make_client, fakes, mode, question, reply):
    fakes.state.analytics = mode
    with make_client() as client:
        r = client.post("/v1/chat", json=ask(question), headers=AUTH)
    assert r.status_code == 200
    assert re.fullmatch(reply, r.json()["reply"]), r.json()["reply"]
    called = [c["function"]["name"] for m in fakes.state.llm_requests[-1]["messages"] for c in m.get("tool_calls") or []]
    assert called == ["list_vehicles", "check_vehicle"]
