import httpx
import pytest

from fleet_service.agent.llm import LLMError, LLMTimeout, OpenRouterClient

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
