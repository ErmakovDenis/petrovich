"""Клиент OpenRouter: POST {base}/chat/completions в формате OpenAI chat completions."""

from typing import Any, Protocol

import httpx

from ..config import Settings
from ..log_masking import mask_secrets

Message = dict[str, Any]


class LLMError(Exception):
    """Модель недоступна или ответила ошибкой. Текст безопасно показывать пользователю."""


class LLMTimeout(LLMError):
    pass


class LLMClient(Protocol):
    async def complete(self, messages: list[Message], tools: list[dict]) -> Message:
        """Возвращает сообщение ассистента: `content` и, возможно, `tool_calls`."""
        ...


def _short(text: str, limit: int = 200) -> str:
    text = mask_secrets(" ".join(text.split()))
    return text if len(text) <= limit else text[:limit] + "…"


class OpenRouterClient:
    def __init__(self, settings: Settings, http: httpx.AsyncClient):
        self._settings = settings
        self._http = http

    async def complete(self, messages: list[Message], tools: list[dict]) -> Message:
        s = self._settings
        body: dict[str, Any] = {
            "model": s.llm_model,
            "messages": messages,
            "temperature": s.llm_temperature,
            "max_tokens": s.llm_max_tokens,
            "provider": {
                "require_parameters": s.openrouter_require_parameters,
                "data_collection": s.openrouter_data_collection,
            },
        }
        # Пустой список tools часть провайдеров отвергает — без tools ключ не передаём.
        if tools:
            body["tools"] = tools
        key = s.openrouter_api_key.get_secret_value() if s.openrouter_api_key else ""
        try:
            resp = await self._http.post(
                s.openrouter_base_url.rstrip("/") + "/chat/completions",
                json=body,
                headers={"Authorization": f"Bearer {key}"},
                timeout=s.llm_timeout_seconds,
            )
        except httpx.TimeoutException as e:
            raise LLMTimeout(f"Модель не ответила за {s.llm_timeout_seconds:g} с") from e
        except httpx.HTTPError as e:
            raise LLMError(f"OpenRouter недоступен: {type(e).__name__}") from e

        try:
            data = resp.json()
        except ValueError:
            data = None
        if resp.status_code != 200:
            raise LLMError(f"OpenRouter ответил {resp.status_code}: {_short(self._error_text(data) or resp.text)}")
        if not isinstance(data, dict):
            raise LLMError("OpenRouter вернул ответ не в формате JSON")
        # OpenRouter может вернуть 200 с ошибкой провайдера в теле.
        if data.get("error"):
            raise LLMError(f"Ошибка провайдера модели: {_short(self._error_text(data))}")
        choices = data.get("choices") or []
        choice = choices[0] if choices and isinstance(choices[0], dict) else {}
        # Ошибка провайдера посреди генерации: частичный текст пользователю не отдаём.
        if choice.get("error") or choice.get("finish_reason") == "error":
            raise LLMError(f"Ошибка провайдера модели: {_short(self._error_text(choice) or 'генерация прервана')}")
        message = choice.get("message")
        if not isinstance(message, dict):
            raise LLMError("Модель вернула пустой ответ")
        return message

    @staticmethod
    def _error_text(data: Any) -> str:
        if isinstance(data, dict) and isinstance(data.get("error"), dict):
            return str(data["error"].get("message") or data["error"])
        if isinstance(data, dict) and data.get("error"):
            return str(data["error"])
        return ""
