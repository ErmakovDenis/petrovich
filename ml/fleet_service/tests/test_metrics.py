"""GET /metrics: счётчики стенда после чата, отказа 429 и обращений к внешним системам; выключатель."""

import re

from .conftest import AUTH, FakeLLM, ask, test_settings, text


def _value(body: str, name: str, **labels: str) -> float:
    """Значение ряда метрики с заданными метками (порядок меток не важен)."""
    for line in body.splitlines():
        if not line.startswith(name + "{") and line.split(" ")[0] != name:
            continue
        found = dict(re.findall(r'(\w+)="([^"]*)"', line.split(" ")[0]))
        if all(found.get(k) == v for k, v in labels.items()):
            return float(line.rsplit(" ", 1)[1])
    raise AssertionError(f"нет ряда {name} {labels}")


def test_metrics_after_chat_rate_limit_and_upstream_calls(make_client):
    with make_client(test_settings(chat_burst=1, metrics_enabled=True)) as client:
        assert client.post("/v1/chat", json=ask(), headers=AUTH).status_code == 200
        assert client.post("/v1/chat", json=ask(), headers=AUTH).status_code == 429
        assert client.get("/v1/telemetry?vehicleId=veh-1&from=2026-09-16T00:00:00&to=2026-09-16T06:00:00"
                          "&utcOffsetMinutes=300", headers=AUTH).status_code == 200
        body = client.get("/metrics").text
    assert _value(body, "fleet_llm_responses_total", route="primary") >= 1
    assert _value(body, "fleet_upstream_requests_total", upstream="llm", outcome="200") >= 1
    assert _value(body, "fleet_upstream_requests_total", upstream="autograph", outcome="200") >= 1
    assert _value(body, "fleet_upstream_duration_seconds_count", upstream="autograph") >= 1
    assert _value(body, "fleet_rejections_total", reason="chat_rate_limit") == 1
    assert _value(body, "fleet_cache_requests_total", cache="telemetry", result="miss") >= 1
    assert _value(body, "fleet_breaker_state", upstream="autograph") == 0
    assert _value(body, "fleet_queue_depth", limiter="chat") == 0
    # HTTP-метрики стенда — с точным кодом (для доли 503) и шаблоном пути, без id.
    assert _value(body, "http_requests_total", handler="/v1/chat", status="429") == 1
    assert "valid-token" not in body and "schema-1" not in body and "veh-1" not in body


def test_open_breaker_in_metrics(make_client):
    with make_client(test_settings(metrics_enabled=True), llm=FakeLLM(text("ок"))) as client:
        breaker = client.app.state.breakers["predictive"]
        for _ in range(5):
            with breaker.attempt() as a:
                a.fail()
        body = client.get("/metrics").text
    assert _value(body, "fleet_breaker_state", upstream="predictive") == 2


def test_credits_exhausted_and_routes_are_counted():
    import httpx
    import pytest

    from fleet_service.agent.llm import LLMCreditsExhausted, ChatCompletionsClient
    from fleet_service.metrics import Metrics

    responses = iter([
        httpx.Response(402, json={"error": {"message": "Insufficient credits", "code": 402}}),
        httpx.Response(200, json={"model": "assistant", "choices": [{"message": {"content": "ок"}}]},
                       headers={"x-litellm-model-id": "reserve"}),
    ])
    metrics = Metrics()
    client = ChatCompletionsClient(test_settings(llm_proxy_url="http://llm_proxy:4000"),
                              httpx.AsyncClient(transport=httpx.MockTransport(lambda r: next(responses))),
                              metrics=metrics)

    async def run() -> None:
        with pytest.raises(LLMCreditsExhausted):
            await client.complete([], [])
        await client.complete([], [])

    import asyncio

    asyncio.run(run())
    assert metrics.llm_credits_exhausted.labels("openrouter")._value.get() == 1
    assert metrics.llm_credits_exhausted.labels("gigachat")._value.get() == 0
    assert metrics.llm_responses.labels("reserve")._value.get() == 1
    assert metrics.upstream_requests.labels("llm", "402")._value.get() == 1


def test_metrics_are_off_by_default(make_client):
    with make_client() as client:
        assert client.get("/metrics").status_code == 404


def test_series_exist_from_start_and_clients_cannot_create_new_ones(make_client):
    with make_client(test_settings(metrics_enabled=True)) as client:
        body = client.get("/metrics").text
        # С нуля: иначе первое событие после старта не видно increase()/rate() в алертах.
        assert _value(body, "fleet_llm_responses_total", route="reserve") == 0
        assert _value(body, "fleet_rejections_total", reason="model_not_configured") == 0
        assert _value(body, "fleet_http_503_total") == 0
        client.request("FOO1", "/random-path")
        client.request("FOO2", "/v1/vehicles", headers=AUTH)
        client.get("/random-path-2")
        body = client.get("/metrics").text
    assert "FOO" not in body and "random-path" not in body


def test_unconfigured_model_503_is_counted(make_client):
    with make_client(test_settings(metrics_enabled=True, llm_model="")) as client:
        assert client.post("/v1/chat", json=ask(), headers=AUTH).status_code == 503
        body = client.get("/metrics").text
    assert _value(body, "fleet_rejections_total", reason="model_not_configured") == 1
    assert _value(body, "fleet_http_503_total") == 1


def test_autograph_unavailable_on_session_check_is_counted(make_client):
    from .fakes import OTHER_TOKEN

    with make_client(test_settings(metrics_enabled=True)) as client:
        client.app.state.session_checker.verify = _unavailable
        r = client.get("/v1/vehicles", headers={"Authorization": f"Bearer {OTHER_TOKEN}", "X-Schema-Id": "schema-1"})
        assert r.status_code == 503
        body = client.get("/metrics").text
    assert _value(body, "fleet_rejections_total", reason="autograph_no_slot") == 1


async def _unavailable(*_args) -> None:
    from fleet_service.autograph.errors import AutoGraphUnavailable

    raise AutoGraphUnavailable(reason="autograph_no_slot")


def test_llm_error_in_200_body_and_reserve_402_are_told_apart():
    import asyncio

    import httpx
    import pytest

    from fleet_service.agent.llm import LLMCreditsExhausted, LLMError, ChatCompletionsClient
    from fleet_service.metrics import Metrics

    responses = iter([
        httpx.Response(200, json={"error": {"message": "provider down", "code": 500}}),
        httpx.Response(402, json={"error": {"message": "reserve credits", "code": 402}}),
    ])
    metrics = Metrics()
    client = ChatCompletionsClient(
        test_settings(llm_proxy_url="http://llm_proxy:4000", llm_reserve_model="r", llm_reserve_base_url="http://r/v1"),
        httpx.AsyncClient(transport=httpx.MockTransport(lambda r: next(responses))), metrics=metrics)

    async def run() -> None:
        with pytest.raises(LLMError):
            await client.complete([], [])
        with pytest.raises(LLMCreditsExhausted):
            await client.complete([], [])

    asyncio.run(run())
    assert metrics.upstream_requests.labels("llm", "error_in_body")._value.get() == 1
    # 402 резерва — не про основного провайдера: критичный алерт «пополните баланс» не срабатывает.
    assert metrics.llm_credits_exhausted.labels("openrouter")._value.get() == 0


def test_broken_stream_is_a_separate_outcome():
    import asyncio

    import httpx
    import pytest

    from fleet_service.autograph.client import AutoGraphClient
    from fleet_service.autograph.errors import AutoGraphUnavailable
    from fleet_service.breaker import CircuitBreaker
    from fleet_service.limits import UpstreamLimiter
    from fleet_service.metrics import Metrics

    metrics = Metrics()
    settings = test_settings(autograph_retries=1)
    client = AutoGraphClient(
        settings, httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200, content=b"{}"))),
        UpstreamLimiter("autograph", 1), CircuitBreaker("AutoGRAPH", 5, 60, 60), metrics)

    async def run() -> None:
        with pytest.raises(AutoGraphUnavailable):
            async with client._request("GetTripTables", {}, 5, stream=True):
                raise AutoGraphUnavailable("AutoGRAPH оборвал передачу данных")

    asyncio.run(run())
    assert metrics.upstream_requests.labels("autograph", "200")._value.get() == 1
    assert metrics.upstream_requests.labels("autograph", "stream_error")._value.get() == 1
    # Длительность потокового ответа записана один раз — после чтения тела.
    assert metrics.upstream_duration.labels("autograph")._sum.get() >= 0
    assert [s.value for m in metrics.registry.collect() if m.name == "fleet_upstream_duration_seconds"
            for s in m.samples if s.name.endswith("_count")] == [1]


def test_provider_info_and_credits_label_follow_active_provider(make_client):
    with make_client(test_settings(metrics_enabled=True)) as client:
        body = client.get("/metrics").text
    assert _value(body, "fleet_llm_provider_info", provider="openrouter") == 1
    assert 'fleet_llm_provider_info{provider="gigachat"}' not in body
    # Ряд с нулём есть сразу: первое событие видно алерту.
    assert _value(body, "fleet_llm_credits_exhausted_total", provider="gigachat") == 0
    settings = test_settings(metrics_enabled=True, llm_provider="gigachat", gigachat_credentials="fake-key")
    with make_client(settings) as client:
        assert _value(client.get("/metrics").text, "fleet_llm_provider_info", provider="gigachat") == 1


def test_gigachat_402_is_counted_with_provider():
    import asyncio

    import httpx
    import pytest

    from fleet_service.agent.llm import ChatCompletionsClient, LLMCreditsExhausted
    from fleet_service.metrics import Metrics

    def no_tokens(_):
        return httpx.Response(402, json={"error": {"message": "Payment required", "code": 402}})

    metrics = Metrics()
    client = ChatCompletionsClient(test_settings(llm_provider="gigachat", gigachat_credentials="fake-key"),
                                   httpx.AsyncClient(transport=httpx.MockTransport(no_tokens)), metrics=metrics)

    async def run() -> None:
        with pytest.raises(LLMCreditsExhausted):
            await client.complete([], [])

    asyncio.run(run())
    assert metrics.llm_credits_exhausted.labels("gigachat")._value.get() == 1
    assert metrics.llm_credits_exhausted.labels("openrouter")._value.get() == 0
