import logging

import httpx
import pytest

from fleet_service.agent.llm import (
    LLMCreditsExhausted,
    LLMError,
    LLMRetryable,
    LLMTimeout,
    OpenRouterClient,
    llm_scope,
)

from .conftest import test_settings


def client_with(handler) -> OpenRouterClient:
    return OpenRouterClient(test_settings(), httpx.AsyncClient(transport=httpx.MockTransport(handler)))


@pytest.mark.anyio
async def test_request_format():
    seen: list[httpx.Request] = []

    def ok(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": "Привет"}}]})

    tools = [{"type": "function", "function": {"name": "t", "description": "", "parameters": {"type": "object"}}}]
    message = await client_with(ok).complete([{"role": "user", "content": "?"}], tools)

    assert message["content"] == "Привет"
    [request] = seen
    assert str(request.url) == "http://fakes/openrouter/api/v1/chat/completions"
    assert request.headers["authorization"] == "Bearer test-key"
    body = httpx.Response(200, content=request.content).json()
    assert body["tools"] == tools and body["model"] == "fake/model"


@pytest.mark.anyio
@pytest.mark.parametrize("response, fragment", [
    (httpx.Response(429, json={"error": {"message": "Rate limit exceeded", "code": 429}}), "429: Rate limit exceeded"),
    (httpx.Response(502, text="<html>Bad gateway</html>"), "502: <html>Bad gateway</html>"),
    (httpx.Response(200, text="not json"), "не в формате JSON"),
    (httpx.Response(200, json={"error": {"message": "upstream failed"}}), "upstream failed"),
    (httpx.Response(200, json={"choices": []}), "пустой ответ"),
    (httpx.Response(200, json={"choices": [{"error": {"message": "cut"}, "message": {"content": ""}}]}), "cut"),
    (httpx.Response(200, json={"choices": [{"error": {"message": "overloaded"}, "finish_reason": "error"}]}),
     "Ошибка провайдера модели: overloaded"),
    (httpx.Response(200, json={"choices": [{"finish_reason": "error", "message": {"content": "Топливо: 3"}}]}),
     "генерация прервана"),
])
async def test_errors_are_llm_errors(response, fragment):
    with pytest.raises(LLMError) as e:
        await client_with(lambda request: response).complete([{"role": "user", "content": "?"}], [])
    assert fragment in str(e.value)


@pytest.mark.anyio
async def test_timeout_and_network_errors():
    def slow(request):
        raise httpx.ReadTimeout("timed out", request=request)

    def refused(request):
        raise httpx.ConnectError("refused", request=request)

    with pytest.raises(LLMTimeout):
        await client_with(slow).complete([], [])
    with pytest.raises(LLMError) as e:
        await client_with(refused).complete([], [])
    assert str(e.value) == "OpenRouter недоступен: ConnectError"


@pytest.mark.anyio
async def test_error_text_masks_secrets():
    def leaky(request):
        return httpx.Response(401, json={"error": {"message": "Invalid key sk-or-v1-abcdefabcdefabcdef"}})

    with pytest.raises(LLMError) as e:
        await client_with(leaky).complete([], [])
    assert "abcdef" not in str(e.value) and "sk-or-***" in str(e.value)


@pytest.mark.anyio
@pytest.mark.parametrize("response, error", [
    (httpx.Response(429, json={"error": {"message": "Rate limit", "code": 429}}, headers={"Retry-After": "7"}),
     LLMRetryable),
    (httpx.Response(408, json={"error": {"message": "Timeout", "code": 408}}), LLMRetryable),
    (httpx.Response(502, json={"error": {"message": "Model down", "code": 502}}), LLMRetryable),
    (httpx.Response(503, json={"error": {"message": "No provider", "code": 503}}), LLMRetryable),
    (httpx.Response(500, json={"error": {"message": "Internal", "code": 500}}), LLMRetryable),
    (httpx.Response(504, text="Gateway timeout"), LLMRetryable),
    # Ошибка провайдера в ответе 200 — по её коду.
    (httpx.Response(200, json={"error": {"message": "Insufficient credits", "code": 402}}), LLMCreditsExhausted),
    (httpx.Response(200, json={"error": {"message": "Rate limited", "code": 429}}), LLMRetryable),
    (httpx.Response(402, json={"error": {"message": "Insufficient credits", "code": 402}}), LLMCreditsExhausted),
    # LiteLLM отдаёт исходный код провайдера в error.code строкой при своём HTTP-коде.
    (httpx.Response(429, json={"error": {"message": "Insufficient credits", "code": "402"}}), LLMCreditsExhausted),
    (httpx.Response(400, json={"error": {"message": "Bad request", "code": 400}}), LLMError),
    (httpx.Response(401, json={"error": {"message": "No auth", "code": 401}}), LLMError),
    (httpx.Response(403, json={"error": {"message": "Moderation", "code": 403}}), LLMError),
])
async def test_error_classes(response, error):
    calls = []

    def handler(request):
        calls.append(request)
        return response

    with pytest.raises(LLMError) as e:
        await client_with(handler).complete([{"role": "user", "content": "?"}], [])
    assert type(e.value) is error
    # Стенд не повторяет сам: повторяет прокси (или никто при прямом обращении к OpenRouter).
    assert len(calls) == 1


@pytest.mark.anyio
async def test_retry_after_is_kept():
    def limited(request):
        return httpx.Response(429, json={"error": {"message": "Rate limit"}}, headers={"Retry-After": "7"})

    with pytest.raises(LLMRetryable) as e:
        await client_with(limited).complete([], [])
    assert e.value.retry_after == 7


@pytest.mark.anyio
async def test_credits_exhausted_is_logged_as_error(caplog):
    def no_credits(request):
        return httpx.Response(402, json={"error": {"message": "Insufficient credits", "code": 402}})

    with caplog.at_level(logging.ERROR), pytest.raises(LLMCreditsExhausted):
        await client_with(no_credits).complete([], [])
    assert any(r.levelno == logging.ERROR and "402" in r.getMessage() for r in caplog.records)


@pytest.mark.anyio
async def test_connect_errors_are_retryable_read_timeout_is_not():
    def connect_timeout(request):
        raise httpx.ConnectTimeout("connect", request=request)

    def read_timeout(request):
        raise httpx.ReadTimeout("read", request=request)

    with pytest.raises(LLMRetryable):
        await client_with(connect_timeout).complete([], [])
    with pytest.raises(LLMTimeout):
        await client_with(read_timeout).complete([], [])


@pytest.mark.anyio
async def test_call_is_not_started_after_deadline_and_timeout_is_cut():
    seen = []

    def ok(request):
        seen.append(request.extensions["timeout"]["read"])
        return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": "ок"}}]})

    client = client_with(ok)
    with llm_scope(0.5):
        with pytest.raises(LLMTimeout):
            await client.complete([], [])
    assert seen == []
    with llm_scope(10):
        await client.complete([], [])
    # Таймаут обращения — не дольше остатка срока чата (FS_LLM_TIMEOUT_SECONDS = 60).
    assert 9 < seen[0] <= 10


@pytest.mark.anyio
async def test_direct_request_has_fallback_models_and_provider_routing():
    seen = []

    def ok(request):
        seen.append(httpx.Response(200, content=request.content).json())
        return httpx.Response(200, json={"model": "x/fallback",
                                         "choices": [{"message": {"role": "assistant", "content": "ок"}}]})

    settings = test_settings(llm_fallback_models=" x/fallback, fake/model ,,y/second",
                             openrouter_provider_sort="latency", openrouter_allow_fallbacks=False)
    client = OpenRouterClient(settings, httpx.AsyncClient(transport=httpx.MockTransport(ok)))
    with llm_scope(None) as scope:
        await client.complete([], [])
    [body] = seen
    assert body["model"] == "fake/model"
    assert body["models"] == ["fake/model", "x/fallback", "y/second"]
    assert body["provider"] == {"require_parameters": True, "data_collection": "deny", "allow_fallbacks": False,
                                "sort": "latency"}
    assert scope.routes == ["fallback"]


@pytest.mark.anyio
async def test_without_fallback_models_request_is_as_before():
    seen = []

    def ok(request):
        seen.append(httpx.Response(200, content=request.content).json())
        return httpx.Response(200, json={"model": "fake/model-2026-01-01",
                                         "choices": [{"message": {"role": "assistant", "content": "ок"}}]})

    with llm_scope(None) as scope:
        await client_with(ok).complete([], [])
    assert "models" not in seen[0] and "sort" not in seen[0]["provider"]
    assert scope.routes == ["primary"]


@pytest.mark.anyio
async def test_proxy_request_uses_model_group_without_key():
    seen: list[httpx.Request] = []

    def ok(request):
        seen.append(request)
        return httpx.Response(200, json={"model": "fake/model", "choices": [{"message": {"content": "ок"}}]},
                              headers={"x-litellm-model-id": "openrouter"})

    settings = test_settings(llm_proxy_url="http://llm_proxy:4000/", llm_fallback_models="x/fallback")
    client = OpenRouterClient(settings, httpx.AsyncClient(transport=httpx.MockTransport(ok)))
    with llm_scope(None) as scope:
        await client.complete([{"role": "user", "content": "?"}], [])
    assert scope.routes == ["primary"]
    [request] = seen
    assert str(request.url) == "http://llm_proxy:4000/v1/chat/completions"
    assert "authorization" not in request.headers
    body = httpx.Response(200, content=request.content).json()
    # Запасные модели и маршрутизацию добавляет прокси из своей конфигурации.
    assert body["model"] == "assistant" and "models" not in body and "provider" not in body


@pytest.mark.anyio
async def test_proxy_fallback_is_seen_by_deployment_id():
    def ok(request):
        # Прокси подменяет model именем группы, если перехода не было, — верить можно только id копии.
        return httpx.Response(200, json={"model": "assistant", "choices": [{"message": {"content": "ок"}}]},
                              headers={"x-litellm-model-id": "fallback-1", "x-litellm-attempted-fallbacks": "1"})

    client = OpenRouterClient(test_settings(llm_proxy_url="http://llm_proxy:4000", llm_fallback_models="x/fallback"),
                              httpx.AsyncClient(transport=httpx.MockTransport(ok)))
    with llm_scope(None) as scope:
        await client.complete([], [])
    assert scope.routes == ["fallback"]


@pytest.mark.anyio
async def test_proxy_timeout_covers_retries_until_chat_deadline():
    """Через прокси FS_LLM_TIMEOUT_SECONDS — срок одной попытки прокси; стенд ждёт до срока чата, иначе повтор
    и переход к запасной модели не успели бы никогда."""
    seen = []

    def ok(request):
        seen.append(request.extensions["timeout"]["read"])
        return httpx.Response(200, json={"choices": [{"message": {"content": "ок"}}]})

    client = OpenRouterClient(test_settings(llm_proxy_url="http://llm_proxy:4000", llm_timeout_seconds=30),
                              httpx.AsyncClient(transport=httpx.MockTransport(ok)))
    await client.complete([], [])
    with llm_scope(80):
        await client.complete([], [])
    assert seen[0] == 100 and 79 < seen[1] <= 80


@pytest.mark.anyio
async def test_proxy_errors_name_the_proxy():
    def down(request):
        raise httpx.ConnectError("refused", request=request)

    client = OpenRouterClient(test_settings(llm_proxy_url="http://llm_proxy:4000"),
                              httpx.AsyncClient(transport=httpx.MockTransport(down)))
    with pytest.raises(LLMRetryable) as e:
        await client.complete([], [])
    assert str(e.value) == "Прокси модели недоступен: ConnectError"
