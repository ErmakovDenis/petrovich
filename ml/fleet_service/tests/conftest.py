from collections.abc import Callable
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from fleet_service.agent.llm import Message
from fleet_service.config import Settings
from fleet_service.main import create_app

from .fakes import FAKE_LLM_KEY, FAKE_PA_KEY, SCHEMA_ID, VALID_TOKEN, create_fakes

AUTH = {"Authorization": f"Bearer {VALID_TOKEN}", "X-Schema-Id": SCHEMA_ID}


def test_settings(**overrides: Any) -> Settings:
    """Настройки стенда, направленные на подставные серверы; .env и переменные окружения не читаются."""
    values: dict[str, Any] = {
        "openrouter_api_key": FAKE_LLM_KEY,
        "openrouter_base_url": "http://fakes/openrouter/api/v1",
        "llm_model": "fake/model",
        "autograph_base_url": "http://fakes/autograph/ServiceJSON/",
        "predictive_url": "http://fakes/predictive",
        "predictive_api_key": FAKE_PA_KEY,
    }
    values.update(overrides)
    return Settings(_env_file=None, **values)


test_settings.__test__ = False  # не тест, несмотря на имя


def ask(text: str = "Сколько топлива у машин?", **extra: Any) -> dict:
    return {"messages": [{"role": "user", "content": text}], "utcOffsetMinutes": 300, **extra}


class FakeLLM:
    """Подставная модель для цикла агента: отдаёт заготовленные ответы по очереди и запоминает запросы."""

    def __init__(self, *replies: Message | Callable[[list[Message]], Message] | Exception):
        self.replies = list(replies)
        self.calls: list[tuple[list[Message], list[dict]]] = []

    async def complete(self, messages: list[Message], tools: list[dict]) -> Message:
        self.calls.append(([dict(m) for m in messages], tools))
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply(messages) if callable(reply) else reply


def text(content: str) -> Message:
    return {"role": "assistant", "content": content}


def tool_call(call_id: str, name: str, arguments: str) -> Message:
    return {"role": "assistant", "content": None,
            "tool_calls": [{"id": call_id, "type": "function", "function": {"name": name, "arguments": arguments}}]}


@pytest.fixture
def fakes():
    return create_fakes()


@pytest.fixture
def make_client(fakes):
    """Клиент стенда; внешние HTTP-запросы стенда уходят в подставное приложение fakes."""

    def make(settings: Settings | None = None, **kwargs: Any) -> TestClient:
        http = httpx.AsyncClient(transport=httpx.ASGITransport(app=fakes))
        app = create_app(settings or test_settings(), http=http, **kwargs)
        return TestClient(app, raise_server_exceptions=False)

    return make
