import httpx
import pytest

from fleet_service.agent.eval_tools import load_cases, evaluate, report, score

from .conftest import test_settings
from .fakes import FAKE_LLM_KEY


def _call(name: str, arguments: str) -> dict:
    return {"role": "assistant", "content": None,
            "tool_calls": [{"id": "c1", "type": "function", "function": {"name": name, "arguments": arguments}}]}


def test_cases_are_valid():
    cases = load_cases()
    assert 20 <= len(cases) <= 30
    assert len({c["id"] for c in cases}) == len(cases)
    tools = {"list_vehicles", "get_vehicle_summary", "check_vehicle", "list_anomalies", "get_anomaly", None}
    for c in cases:
        expected = c["expect"]["tool"]
        assert set(expected if isinstance(expected, list) else [expected]) <= tools, c["id"]


def test_score():
    case = {"id": "x", "expect": {"tool": "get_vehicle_summary", "args": {"vehicle_id": "veh-2", "from": "^2026-09-16"}}}
    good = score(case, _call("get_vehicle_summary", '{"vehicle_id": "veh-2", "from": "2026-09-16T00:00"}'))
    assert good.tool_ok and good.args_ok
    wrong_args = score(case, _call("get_vehicle_summary", '{"vehicle_id": "veh-1"}'))
    assert wrong_args.tool_ok and not wrong_args.args_ok
    broken = score(case, _call("get_vehicle_summary", "{не json"))
    assert broken.tool_ok and not broken.args_ok and "не JSON" in broken.got
    text = score({"id": "t", "expect": {"tool": None}}, {"role": "assistant", "content": "Привет"})
    assert text.tool_ok and text.args_ok and text.got == "текст"
    severity = {"id": "s", "expect": {"tool": "list_anomalies", "args": {"severity": ["CRITICAL"]}}}
    assert score(severity, _call("list_anomalies", '{"severity": ["CRITICAL"]}')).args_ok


@pytest.mark.anyio
async def test_evaluate_against_fake_model(fakes):
    """Прогон набора на подставной модели: сценарий fakes вызывает list_vehicles, list_anomalies по дате, сводку
    по найденной машине."""
    http = httpx.AsyncClient(transport=httpx.ASGITransport(app=fakes))
    outcomes = await evaluate(http, "http://fakes/openrouter/api/v1", "fake/model", FAKE_LLM_KEY, load_cases(),
                              test_settings())
    by_id = {o.case_id: o for o in outcomes}
    assert by_id["fleet-list"].args_ok
    assert by_id["feed-date"].args_ok
    assert by_id["fuel-ural"].args_ok
    # Подставная модель всегда зовёт tool — вопрос без инструментов она проваливает.
    assert not by_id["no-tool-weather"].tool_ok
    text = report("fake/model", outcomes)
    assert text.startswith("## fake/model") and "Верный tool:" in text
    # Модель получает tools ассистента без писем и ту же маршрутизацию провайдеров, что у стенда.
    body = fakes.state.llm_requests[0]
    assert {t["function"]["name"] for t in body["tools"]} == {
        "list_vehicles", "get_vehicle_summary", "check_vehicle", "list_anomalies", "get_anomaly"}
    assert body["provider"] == {"require_parameters": True, "data_collection": "deny"}
