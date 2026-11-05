"""Шлюз GigaChat на строгом подставном сервере (tests/fakes.py): никаких обращений к настоящему API."""

import asyncio
import datetime as dt
import ipaddress
import json
import logging
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import httpx
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from fleet_service.agent.eval_tools import evaluate, load_cases
from fleet_service.config import Settings
from fleet_service.gigachat.app import create_gateway, tls_context
from fleet_service.gigachat.convert import MIN_TEMPERATURE, convert_messages, from_gigachat, function_result, to_gigachat
from fleet_service.main import create_app

from .conftest import AUTH, ask, test_settings
from .fakes import FAKE_GIGACHAT_KEY

MODEL = "GigaChat-2-Pro"
USER = [{"role": "user", "content": "Привет"}]


def settings_for(**overrides):
    values = {
        "llm_provider": "gigachat",
        "gigachat_credentials": FAKE_GIGACHAT_KEY,
        "gigachat_base_url": "http://fakes/gigachat/v1",
        "gigachat_auth_url": "http://fakes/gigachat/oauth",
        "gigachat_gateway_url": "http://gigachat_gateway/v1",
    }
    return test_settings(**(values | overrides))


def gateway_for(fakes, clock=time.time, **overrides):
    http = httpx.AsyncClient(transport=httpx.ASGITransport(app=fakes))
    return create_gateway(settings_for(**overrides), http=http, clock=clock)


def post(gateway, **body):
    with TestClient(gateway, raise_server_exceptions=False) as client:
        return client.post("/v1/chat/completions", json={"model": MODEL, "messages": USER, **body})


class Hosts(httpx.AsyncBaseTransport):
    """Запросы к шлюзу — в его приложение, остальные — в подставные внешние системы."""

    def __init__(self, gateway, fakes):
        self._gateway = httpx.ASGITransport(app=gateway)
        self._fakes = httpx.ASGITransport(app=fakes)

    async def handle_async_request(self, request):
        target = self._gateway if request.url.host == "gigachat_gateway" else self._fakes
        return await target.handle_async_request(request)


@pytest.mark.parametrize("change, status", [
    ({"messages": [{"role": "user", "content": "?"}, {"role": "system", "content": "s"}]}, 422),
    ({"messages": [{"role": "system", "content": "a"}, {"role": "system", "content": "b"}]}, 422),
    ({"messages": [{"role": "function", "content": "[1]"}]}, 422),
    ({"messages": [{"role": "function", "content": "не json"}]}, 422),
    ({"messages": [{"role": "function", "content": "{}", "name": "a"}]}, 422),
    ({"messages": [{"role": "tool", "content": "{}"}]}, 422),
    ({"messages": [{"role": "assistant", "content": None}]}, 422),
    ({"messages": [{"role": "assistant", "content": "", "function_call": {"name": "a", "arguments": {}}}]}, 422),
    ({"messages": [{"role": "assistant", "content": "", "functions_state_id": "чужой",
                    "function_call": {"name": "a", "arguments": {}}}, {"role": "function", "content": "{}"}]}, 422),
    ({"messages": [{"role": "assistant", "content": "", "function_call": {"name": "a", "arguments": "{}"}},
                   {"role": "function", "content": "{}"}]}, 422),
    ({"tools": []}, 422),
    ({"temperature": 0}, 422),
    ({}, 200),
])
def test_fake_gigachat_is_really_strict(fakes, change, status):
    """Подставной сервер сам отвергает неверные запросы: иначе тесты шлюза ничего не доказывают."""
    fakes.state.gigachat_tokens.add("t")
    with TestClient(fakes) as client:
        r = client.post("/gigachat/v1/chat/completions", json={"model": MODEL, "messages": USER, **change},
                        headers={"Authorization": "Bearer t", "User-Agent": "test"})
    assert r.status_code == status, r.text


def test_fake_gigachat_requires_user_agent_and_live_token(fakes):
    fakes.state.gigachat_tokens.add("t")
    with TestClient(fakes) as client:
        url, body = "/gigachat/v1/chat/completions", {"model": MODEL, "messages": USER}
        assert client.post(url, json=body, headers={"Authorization": "Bearer t", "User-Agent": ""}).status_code == 403
        expired = client.post(url, json=body, headers={"Authorization": "Bearer old", "User-Agent": "test"})
        assert expired.status_code == 401 and expired.json() == {"status": 401, "message": "Token has expired"}


def test_full_cycle_with_tools_and_state_id_through_gateway(fakes):
    settings = settings_for()
    gateway = gateway_for(fakes)
    app = create_app(settings, http=httpx.AsyncClient(transport=Hosts(gateway, fakes)))
    with TestClient(app, raise_server_exceptions=False) as client:
        r = client.post("/v1/chat", json=ask(), headers=AUTH)
    assert r.status_code == 200, r.text
    assert "средний уровень топлива 250.0 л" in r.json()["reply"]
    requests = fakes.state.gigachat_requests
    # list_vehicles → get_vehicle_summary → ответ: за каждый вызов функции — отдельное обращение.
    assert len(requests) == 3
    calls = [m for m in requests[-1]["messages"] if m.get("function_call")]
    assert [c["function_call"]["name"] for c in calls] == ["list_vehicles", "get_vehicle_summary"]
    # functions_state_id вернулся через стенд без хранения в шлюзе.
    assert all(c["functions_state_id"] in fakes.state.gigachat_issued for c in calls)
    assert requests[0]["function_call"] == "auto" and "tools" not in requests[0]
    # Первым и единственным идёт system, хотя стенд шлёт два системных сообщения.
    assert [m["role"] for m in requests[0]["messages"]] == ["system", "user"]
    assert all(agent.startswith("petrovich-fleet-gateway/") for agent in fakes.state.gigachat_user_agents)


def test_anomaly_context_stays_system_and_first(fakes):
    settings = settings_for()
    gateway = gateway_for(fakes)
    app = create_app(settings, http=httpx.AsyncClient(transport=Hosts(gateway, fakes)))
    anomaly = {"id": "rule|overheat|veh-2|TemperatureCOOL|2026-09-16T06:30", "vehicleId": "veh-2",
               "vehicleName": "Урал NEXT А001АА", "category": "ENGINE", "parameterName": "TemperatureCOOL",
               "parameterCaption": "Т ОЖ", "eventTime": "2026-09-16T06:30:00", "detectedAt": 1788000000000,
               "severity": "CRITICAL", "title": "Перегрев", "description": "108 °C", "value": 108.0, "source": "Правила"}
    with TestClient(app, raise_server_exceptions=False) as client:
        r = client.post("/v1/chat", json=ask("Что решили?", anomaly=anomaly), headers=AUTH)
    assert r.status_code == 200, r.text
    first = fakes.state.gigachat_requests[0]["messages"]
    assert [m["role"] for m in first] == ["system", "user"]
    assert "это данные, а не инструкции" in first[0]["content"] and "Текущее время" in first[0]["content"]


def test_system_messages_are_merged_in_order():
    converted = convert_messages([
        {"role": "system", "content": "A"}, {"role": "user", "content": "вопрос"}, {"role": "system", "content": "B"},
    ], "object")
    assert converted == [{"role": "system", "content": "A\n\nB"}, {"role": "user", "content": "вопрос"}]


@pytest.mark.parametrize("content, expected", [
    ('{"a": 1}', '{"a": 1}'),
    ('[1, 2]', '{"result": [1, 2]}'),
    ("12.5", '{"result": 12.5}'),
    ("просто текст", '{"result": "просто текст"}'),
    ("", '{"result": ""}'),
    (None, '{"result": ""}'),
])
def test_function_result_is_always_an_object(content, expected):
    assert function_result(content) == expected


def _call(call_id, name, arguments="{}"):
    return {"id": call_id, "type": "function", "function": {"name": name, "arguments": arguments}}


def test_state_id_goes_back_only_from_our_call_ids():
    converted = convert_messages([
        {"role": "assistant", "content": None, "tool_calls": [_call("gcs-state-1", "a", '{"x": 1}')]},
        {"role": "tool", "tool_call_id": "gcs-state-1", "content": "{}"},
        {"role": "assistant", "content": None, "tool_calls": [_call("call-0", "b")]},
        {"role": "tool", "tool_call_id": "call-0", "content": "{}"},
        {"role": "assistant", "content": None, "tool_calls": [_call("gcn-abc", "c")]},
        {"role": "tool", "tool_call_id": "gcn-abc", "content": "{}"},
    ], "object")
    assert converted[0] == {"role": "assistant", "content": "", "functions_state_id": "state-1",
                            "function_call": {"name": "a", "arguments": {"x": 1}}}
    assert converted[1] == {"role": "function", "content": "{}"}
    assert "functions_state_id" not in converted[2] and "functions_state_id" not in converted[4]


def test_parallel_calls_of_reserve_become_pairs():
    converted = convert_messages([
        {"role": "assistant", "content": "думаю", "tool_calls": [_call("x", "a"), _call("y", "b")]},
        {"role": "tool", "tool_call_id": "y", "content": '{"r": "y"}'},
        {"role": "tool", "tool_call_id": "x", "content": '{"r": "x"}'},
    ], "object")
    assert [(m["role"], m["content"]) for m in converted] == [
        ("assistant", "думаю"), ("function", '{"r": "x"}'), ("assistant", ""), ("function", '{"r": "y"}'),
    ]


def test_repeated_call_ids_keep_their_own_results():
    converted = convert_messages([
        {"role": "assistant", "content": None, "tool_calls": [_call("call_0", "a")]},
        {"role": "tool", "tool_call_id": "call_0", "content": '{"r": "первый"}'},
        {"role": "assistant", "content": None, "tool_calls": [_call("call_0", "b")]},
        {"role": "tool", "tool_call_id": "call_0", "content": '{"r": "второй"}'},
    ], "object")
    assert [m["content"] for m in converted if m["role"] == "function"] == ['{"r": "первый"}', '{"r": "второй"}']


def test_result_without_call_is_passed_once():
    converted = convert_messages([{"role": "tool", "tool_call_id": "x", "content": "{}"}], "object")
    assert converted == [{"role": "function", "content": "{}"}]


@pytest.mark.parametrize("content, expected", [
    ('{"avg": NaN}', '{"result": "{\\"avg\\": NaN}"}'),
    ("Infinity", '{"result": "Infinity"}'),
])
def test_non_standard_json_is_wrapped_as_text(content, expected):
    assert function_result(content) == expected
    json.loads(expected, parse_constant=pytest.fail)


@pytest.mark.parametrize("fmt, expected", [("object", {"x": 1}), ("string", '{"x": 1}')])
def test_arguments_format_in_request(fmt, expected):
    [message, _] = convert_messages([
        {"role": "assistant", "content": None, "tool_calls": [_call("gcs-1", "a", '{"x": 1}')]},
        {"role": "tool", "tool_call_id": "gcs-1", "content": "{}"},
    ], fmt)
    assert message["function_call"]["arguments"] == expected


@pytest.mark.parametrize("arguments", [{"x": 1}, '{"x": 1}'])
def test_response_arguments_of_both_kinds_become_a_string(arguments):
    data = {"choices": [{"finish_reason": "function_call", "message": {
        "role": "assistant", "content": "", "functions_state_id": "st",
        "function_call": {"name": "a", "arguments": arguments}}}]}
    result = from_gigachat(data, MODEL)
    choice = result["choices"][0]
    assert choice["finish_reason"] == "tool_calls" and choice["message"]["content"] is None
    [call] = choice["message"]["tool_calls"]
    assert call["id"] == "gcs-st" and json.loads(call["function"]["arguments"]) == {"x": 1}


def test_call_without_state_gets_id_without_state():
    data = {"choices": [{"finish_reason": "function_call", "message": {"function_call": {"name": "a"}}}]}
    [call] = from_gigachat(data, MODEL)["choices"][0]["message"]["tool_calls"]
    assert call["id"].startswith("gcn-") and call["function"]["arguments"] == "{}"


def test_arguments_string_mode_passes_strict_server(fakes):
    fakes.state.gigachat_arguments = "string"
    settings = settings_for(gigachat_arguments_format="string")
    gateway = gateway_for(fakes, gigachat_arguments_format="string")
    app = create_app(settings, http=httpx.AsyncClient(transport=Hosts(gateway, fakes)))
    with TestClient(app, raise_server_exceptions=False) as client:
        assert client.post("/v1/chat", json=ask(), headers=AUTH).status_code == 200


def test_temperature_zero_is_raised_to_minimum():
    request = to_gigachat({"messages": USER, "temperature": 0, "max_tokens": 10}, MODEL, "object")
    assert request == {"model": MODEL, "messages": USER, "temperature": MIN_TEMPERATURE, "max_tokens": 10}
    assert to_gigachat({"messages": USER, "temperature": 0.2}, MODEL, "object")["temperature"] == 0.2


def test_only_known_fields_reach_gigachat(fakes):
    r = post(gateway_for(fakes), user="u", n=1, tool_choice="auto", temperature=0.2)
    assert r.status_code == 200, r.text
    assert set(fakes.state.gigachat_requests[0]) == {"model", "messages", "temperature"}


def test_bad_requests_are_400_without_call(fakes):
    gateway = gateway_for(fakes)
    with TestClient(gateway) as client:
        assert client.post("/v1/chat/completions", json={"messages": USER, "tools": ["x"]}).status_code == 400
        wrong_calls = [{"role": "assistant", "tool_calls": ["x"]}]
        assert client.post("/v1/chat/completions", json={"messages": wrong_calls}).status_code == 400
        assert client.post("/v1/chat/completions", content="не json").status_code == 400
        assert client.post("/v1/chat/completions", json=[]).status_code == 400
        assert client.post("/v1/chat/completions", json={"messages": []}).status_code == 400
        assert client.post("/v1/chat/completions", json={"messages": USER, "stream": True}).status_code == 400
    assert fakes.state.gigachat_oauth == []


@pytest.mark.parametrize("unit", ["ms", "s"])
def test_token_expiry_in_both_units_and_refresh_60_seconds_before(fakes, unit):
    fakes.state.gigachat_expires_unit = unit
    fakes.state.gigachat_token_lifetime = 1800
    now = [time.time()]
    gateway = gateway_for(fakes, clock=lambda: now[0])
    with TestClient(gateway) as client:
        def ask_once():
            return client.post("/v1/chat/completions", json={"messages": USER}).status_code

        assert ask_once() == 200 and len(fakes.state.gigachat_oauth) == 1
        now[0] += 1700
        assert ask_once() == 200 and len(fakes.state.gigachat_oauth) == 1
        # За 60 с до срока — новый токен, хотя старый ещё действует.
        now[0] += 45
        assert ask_once() == 200 and len(fakes.state.gigachat_oauth) == 2


def test_token_without_expiry_uses_default_lifetime():
    from fleet_service.gigachat.token import DEFAULT_LIFETIME_SECONDS, expires_at_seconds

    assert expires_at_seconds(None, 100.0) == 100.0 + DEFAULT_LIFETIME_SECONDS
    assert expires_at_seconds(1679471442, 0) == 1679471442
    assert expires_at_seconds(1739784663483, 0) == pytest.approx(1739784663.483)


@pytest.mark.anyio
async def test_one_token_load_for_concurrent_requests(fakes):
    gateway = gateway_for(fakes, gigachat_concurrency=4)
    fakes.state.gigachat_max_streams = 4
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=gateway), base_url="http://gw") as client:
        replies = await asyncio.gather(*[
            client.post("/v1/chat/completions", json={"messages": USER}) for _ in range(4)
        ])
    assert [r.status_code for r in replies] == [200] * 4
    assert len(fakes.state.gigachat_oauth) == 1


def test_expired_token_is_refreshed_once_and_request_repeated(fakes):
    gateway = gateway_for(fakes)
    with TestClient(gateway) as client:
        assert client.post("/v1/chat/completions", json={"messages": USER}).status_code == 200
        fakes.state.gigachat_tokens.clear()
        r = client.post("/v1/chat/completions", json={"messages": USER})
    assert r.status_code == 200, r.text
    assert len(fakes.state.gigachat_oauth) == 2


def test_second_401_is_not_retried_further():
    calls = {"api": 0, "oauth": 0}

    def upstream(request):
        if request.url.path.endswith("/oauth"):
            calls["oauth"] += 1
            return httpx.Response(200, json={"access_token": f"t{calls['oauth']}", "expires_at": 9_999_999_999})
        calls["api"] += 1
        return httpx.Response(401, json={"status": 401, "message": "Unauthorized"})

    gateway = create_gateway(settings_for(), http=httpx.AsyncClient(transport=httpx.MockTransport(upstream)))
    with TestClient(gateway) as client:
        r = client.post("/v1/chat/completions", json={"messages": USER})
    assert r.status_code == 401 and r.json()["error"]["code"] == 401
    assert calls == {"api": 2, "oauth": 2}


def test_wrong_key_is_401_without_key_in_text(fakes):
    r = post(gateway_for(fakes, gigachat_credentials="wrong-key-value"))
    assert r.status_code == 401
    assert "wrong-key-value" not in r.text and fakes.state.gigachat_requests == []


@pytest.mark.parametrize("upstream, expected", [(402, 402), (429, 429), (500, 500), (503, 503), (422, 400), (404, 404)])
def test_status_mapping_has_error_code(fakes, upstream, expected):
    fakes.state.gigachat_failures = [{"status": upstream, "message": "сбой", "retryAfter": 7}]
    r = post(gateway_for(fakes))
    assert r.status_code == expected
    error = r.json()["error"]
    assert error["code"] == expected and "сбой" in error["message"]
    if expected in (402, 429, 500, 503):
        assert r.headers["retry-after"] == "7"
    # Без повторов на стороне шлюза: их делает прокси.
    assert len(fakes.state.gigachat_requests) == 1


def test_blacklist_returns_model_text_with_warning_and_counter(fakes, caplog):
    fakes.state.gigachat_failures = [{"finishReason": "blacklist", "content": "Не могу обсуждать эту тему."}]
    gateway = gateway_for(fakes)
    with caplog.at_level(logging.WARNING):
        r = post(gateway)
    assert r.status_code == 200
    choice = r.json()["choices"][0]
    assert choice["message"]["content"] == "Не могу обсуждать эту тему." and choice["finish_reason"] == "stop"
    assert gateway.state.metrics.gigachat_blacklist._value.get() == 1
    assert "blacklist" in caplog.text


def test_finish_reason_error_is_an_error(fakes):
    fakes.state.gigachat_failures = [{"finishReason": "error"}]
    r = post(gateway_for(fakes))
    assert r.status_code == 502 and "невалидные аргументы" in r.json()["error"]["message"]


@pytest.mark.parametrize("payload", [{"choices": []}, {"choices": ["x"]}, {}, {"choices": [{"message": "text"}]},
                                     {"choices": [{"finish_reason": "stop"}]}])
def test_response_without_choices_is_502(payload):
    def upstream(request):
        if request.url.path.endswith("/oauth"):
            return httpx.Response(200, json={"access_token": "t", "expires_at": 9_999_999_999})
        return httpx.Response(200, json=payload)

    gateway = create_gateway(settings_for(), http=httpx.AsyncClient(transport=httpx.MockTransport(upstream)))
    r = post(gateway)
    assert r.status_code == 502 and r.json()["error"]["code"] == 502


def test_usage_of_wrong_shape_does_not_break_the_answer():
    data = {"choices": [{"finish_reason": "stop", "message": {"content": "ок"}}], "usage": [1]}
    assert from_gigachat(data, MODEL)["usage"] == {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}


@pytest.mark.parametrize("payload", [{"access_token": None}, {"access_token": ""}, {"access_token": 5}, {}, ["x"]])
def test_bad_token_response_is_502_and_never_cached(payload):
    calls = []

    def upstream(request):
        calls.append(request.url.path)
        return httpx.Response(200, json=payload)

    gateway = create_gateway(settings_for(), http=httpx.AsyncClient(transport=httpx.MockTransport(upstream)))
    r = post(gateway)
    assert r.status_code == 502 and calls == ["/gigachat/oauth"]


@pytest.mark.anyio
async def test_requests_wait_for_the_only_stream(fakes):
    fakes.state.gigachat_delay = 0.1
    gateway = gateway_for(fakes)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=gateway), base_url="http://gw") as client:
        replies = await asyncio.gather(*[
            client.post("/v1/chat/completions", json={"messages": USER}) for _ in range(3)
        ])
    assert [r.status_code for r in replies] == [200] * 3
    assert fakes.state.gigachat_peak == 1 and fakes.state.gigachat_rejected == 0


@pytest.mark.anyio
async def test_no_slot_in_time_is_429_with_retry_after(fakes):
    gateway = gateway_for(fakes, gigachat_timeout_seconds=0.1)
    slots = gateway.state.gateway.slots
    await slots.acquire()
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=gateway), base_url="http://gw") as client:
        busy = await client.post("/v1/chat/completions", json={"messages": USER})
        assert busy.status_code == 429
        assert busy.headers["retry-after"] == "5" and busy.json()["error"]["code"] == 429
        assert gateway.state.metrics.rejections.labels("gigachat_no_slot")._value.get() == 1
        assert fakes.state.gigachat_requests == []
        # Место освободилось: следующий запрос проходит.
        slots.release()
        assert (await client.post("/v1/chat/completions", json={"messages": USER})).status_code == 200


@pytest.mark.anyio
async def test_slot_at_the_very_end_of_the_term_is_not_used(fakes):
    gateway = gateway_for(fakes, gigachat_timeout_seconds=0.2)
    slots = gateway.state.gateway.slots
    await slots.acquire()
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=gateway), base_url="http://gw") as client:
        waiting = asyncio.create_task(client.post("/v1/chat/completions", json={"messages": USER}))
        # Место освобождается, когда до срока ожидания остаётся меньше MIN_CALL_SECONDS (или срок уже прошёл).
        await asyncio.sleep(0.18)
        slots.release()
        r = await waiting
        assert r.status_code == 429 and fakes.state.gigachat_requests == []
        # Место не потеряно: следующий запрос проходит.
        assert (await client.post("/v1/chat/completions", json={"messages": USER})).status_code == 200


def _call_abandoned_after(gateway, seconds):
    """Вызов приложения шлюза напрямую по ASGI: клиент разрывает связь через [seconds] секунд."""
    payload = json.dumps({"messages": USER}).encode()
    sent = []
    delivered = False
    gone_at = time.monotonic() + seconds

    async def receive():
        nonlocal delivered
        if not delivered:
            delivered = True
            return {"type": "http.request", "body": payload, "more_body": False}
        # Как у uvicorn: разрыв виден сразу, а пока связь жива, receive ждёт (Request.is_disconnected его отменяет).
        if time.monotonic() >= gone_at:
            return {"type": "http.disconnect"}
        await asyncio.Event().wait()

    async def send(message):
        sent.append(message)

    scope = {"type": "http", "asgi": {"version": "3.0"}, "http_version": "1.1", "method": "POST", "scheme": "http",
             "path": "/v1/chat/completions", "raw_path": b"/v1/chat/completions", "query_string": b"",
             "headers": [(b"content-type", b"application/json")], "client": ("t", 1), "server": ("t", 80)}
    return gateway(scope, receive, send), sent


@pytest.mark.anyio
async def test_abandoned_request_frees_the_only_stream(fakes, monkeypatch):
    monkeypatch.setattr("fleet_service.gigachat.app.DISCONNECT_POLL_SECONDS", 0.01)
    fakes.state.gigachat_delay = 30
    gateway = gateway_for(fakes)
    abandoned, sent = _call_abandoned_after(gateway, 0.05)
    started = time.monotonic()
    await asyncio.wait_for(abandoned, 5)
    assert time.monotonic() - started < 5
    assert sent[0]["status"] == 499
    # Поток свободен сразу: следующий запрос не ждёт 30 с и срока шлюза.
    fakes.state.gigachat_delay = 0
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=gateway), base_url="http://gw") as client:
        r = await asyncio.wait_for(client.post("/v1/chat/completions", json={"messages": USER}), 5)
    assert r.status_code == 200


@pytest.mark.anyio
async def test_queued_abandoned_request_never_reaches_gigachat(fakes, monkeypatch):
    monkeypatch.setattr("fleet_service.gigachat.app.DISCONNECT_POLL_SECONDS", 0.01)
    fakes.state.gigachat_delay = 0.3
    gateway = gateway_for(fakes)
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=gateway), base_url="http://gw") as client:
        first = asyncio.create_task(client.post("/v1/chat/completions", json={"messages": USER}))
        await asyncio.sleep(0.05)
        queued, _ = _call_abandoned_after(gateway, 0.05)
        await asyncio.wait_for(queued, 5)
        assert (await first).status_code == 200
    assert len(fakes.state.gigachat_requests) == 1


def test_timeout_is_counted_as_timeout():
    async def hang(_):
        await asyncio.sleep(30)

    def upstream_factory():
        class Slow(httpx.AsyncBaseTransport):
            async def handle_async_request(self, request):
                if request.url.path.endswith("/oauth"):
                    return httpx.Response(200, json={"access_token": "t", "expires_at": 9_999_999_999})
                await hang(request)

        return Slow()

    gateway = create_gateway(settings_for(gigachat_timeout_seconds=0.1), http=httpx.AsyncClient(transport=upstream_factory()))
    assert post(gateway).status_code == 504
    assert gateway.state.metrics.upstream_requests.labels("gigachat", "timeout")._value.get() == 1


def test_oauth_problems_are_in_gateway_log(fakes, caplog):
    with caplog.at_level(logging.WARNING):
        assert post(gateway_for(fakes, gigachat_credentials="wrong-key-value")).status_code == 401
    assert "FS_GIGACHAT_CREDENTIALS" in caplog.text and "wrong-key-value" not in caplog.text


def test_without_key_health_is_ok_and_chat_is_503(fakes):
    gateway = create_gateway(settings_for(gigachat_credentials=None))
    with TestClient(gateway) as client:
        assert client.get("/health").json() == {"status": "ok", "configured": False}
        r = client.post("/v1/chat/completions", json={"messages": USER})
    assert r.status_code == 503 and r.json()["error"]["code"] == 503


def test_network_failure_is_503():
    def down(request):
        raise httpx.ConnectError("нет связи")

    gateway = create_gateway(settings_for(), http=httpx.AsyncClient(transport=httpx.MockTransport(down)))
    r = post(gateway)
    assert r.status_code == 503 and r.json()["error"]["code"] == 503


def test_eval_tools_works_through_gateway(fakes):
    settings = settings_for()
    gateway = gateway_for(fakes)

    async def run():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=gateway)) as http:
            return await evaluate(http, "http://gigachat_gateway/v1", MODEL, "", load_cases(), settings)

    outcomes = asyncio.run(run())
    assert outcomes and not [o for o in outcomes if o.got.startswith("ошибка")]


def test_metrics_of_gateway(fakes):
    gateway = gateway_for(fakes, metrics_enabled=True)
    with TestClient(gateway) as client:
        client.post("/v1/chat/completions", json={"messages": USER})
        body = client.get("/metrics").text
    assert 'fleet_upstream_requests_total{outcome="200",upstream="gigachat"} 1.0' in body
    assert 'upstream="gigachat_oauth"' in body and "fleet_gigachat_blacklist_total 0.0" in body
    assert FAKE_GIGACHAT_KEY not in body and "giga-token" not in body


def test_no_secrets_in_logs(fakes, caplog):
    gateway = gateway_for(fakes)
    with caplog.at_level(logging.DEBUG):
        with TestClient(gateway) as client:
            assert client.post("/v1/chat/completions", json={"messages": USER}).status_code == 200
            fakes.state.gigachat_tokens.clear()
            assert client.post("/v1/chat/completions", json={"messages": USER}).status_code == 200
    assert "получен токен" in caplog.text
    assert FAKE_GIGACHAT_KEY not in caplog.text and "giga-token" not in caplog.text


def test_upstream_error_text_is_masked(fakes):
    fakes.state.gigachat_failures = [{"status": 500, "message": f"Authorization: Basic {FAKE_GIGACHAT_KEY}"}]
    r = post(gateway_for(fakes))
    assert r.status_code == 500 and FAKE_GIGACHAT_KEY not in r.text


# --- TLS: настоящий HTTPS на локальном адресе, сертификаты создаются в тесте ---


def _certificates(directory, name):
    """Корневой сертификат и сертификат сервера на 127.0.0.1; пишет корневой и пару сервера в [directory]."""
    now = dt.datetime.now(dt.timezone.utc)
    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME, f"Тестовый корневой {name}")])
    ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name).public_key(ca_key.public_key())
          .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(days=1))
          .not_valid_after(now + dt.timedelta(days=1))
          .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
          .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), critical=True)
          .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
          .sign(ca_key, hashes.SHA256()))
    key = ec.generate_private_key(ec.SECP256R1())
    server = (x509.CertificateBuilder()
              .subject_name(x509.Name([x509.NameAttribute(x509.NameOID.COMMON_NAME, "127.0.0.1")]))
              .issuer_name(ca_name).public_key(key.public_key()).serial_number(x509.random_serial_number())
              .not_valid_before(now - dt.timedelta(days=1)).not_valid_after(now + dt.timedelta(days=1))
              .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
              .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
              .add_extension(x509.KeyUsage(True, False, False, False, False, False, False, False, False), critical=True)
              .add_extension(x509.ExtendedKeyUsage([x509.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
              .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
              .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
              .sign(ca_key, hashes.SHA256()))
    ca_file = directory / f"{name}-ca.pem"
    ca_file.write_bytes(ca.public_bytes(serialization.Encoding.PEM))
    pair = directory / f"{name}-server.pem"
    pair.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                       serialization.NoEncryption()) + server.public_bytes(serialization.Encoding.PEM))
    return ca_file, pair


@pytest.fixture
def https_gigachat(tmp_path):
    """Простой HTTPS-сервер в потоке: токен и ответ модели. Возвращает адрес и файлы своего и чужого корневых."""
    ca_file, pair = _certificates(tmp_path, "good")
    other_ca, _ = _certificates(tmp_path, "other")

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            self.rfile.read(int(self.headers.get("content-length", 0)))
            if self.path.endswith("/oauth"):
                payload = {"access_token": "tls-token", "expires_at": 9_999_999_999}
            else:
                payload = {"choices": [{"finish_reason": "stop", "message": {"content": "через TLS"}}]}
            data = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(pair)
    # Отказ в рукопожатии с шлюзом, не доверяющим сертификату, не должен останавливать поток сервера.
    server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
    thread.start()
    yield f"https://127.0.0.1:{server.server_address[1]}", ca_file, other_ca
    server.shutdown()
    server.server_close()


def test_tls_with_right_root_works(https_gigachat):
    base, ca_file, _ = https_gigachat
    gateway = create_gateway(settings_for(gigachat_base_url=f"{base}/v1", gigachat_auth_url=f"{base}/oauth",
                                          gigachat_ca_bundle=ca_file))
    r = post(gateway)
    assert r.status_code == 200, r.text
    assert r.json()["choices"][0]["message"]["content"] == "через TLS"


def test_tls_with_other_root_never_sends_the_key(https_gigachat, caplog):
    base, _, other_ca = https_gigachat
    gateway = create_gateway(settings_for(gigachat_base_url=f"{base}/v1", gigachat_auth_url=f"{base}/oauth",
                                          gigachat_ca_bundle=other_ca))
    r = post(gateway)
    # Сертификат сервера не подтверждён: рукопожатие не прошло, до заголовка с ключом дело не дошло.
    assert r.status_code == 503 and "ConnectError" in r.json()["error"]["message"]
    assert "CERTIFICATE_VERIFY_FAILED" in caplog.text


def test_key_without_root_certificate_fails_at_start():
    with pytest.raises(ValueError, match="FS_GIGACHAT_CA_BUNDLE"):
        create_gateway(settings_for())


def test_wrong_certificate_file_fails_at_start(tmp_path):
    missing = tmp_path / "нет.pem"
    with pytest.raises(ValueError, match="FS_GIGACHAT_CA_BUNDLE"):
        create_gateway(settings_for(gigachat_ca_bundle=missing))
    broken = tmp_path / "broken.pem"
    broken.write_text("это не сертификат")
    with pytest.raises(ValueError, match="FS_GIGACHAT_CA_BUNDLE"):
        create_gateway(settings_for(gigachat_ca_bundle=broken))


def test_verification_cannot_be_switched_off(tmp_path):
    ca_file, _ = _certificates(tmp_path, "own")
    context = tls_context(settings_for(gigachat_ca_bundle=ca_file))
    assert context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname is True
    assert not [name for name in Settings.model_fields if "verify" in name or "insecure" in name]
