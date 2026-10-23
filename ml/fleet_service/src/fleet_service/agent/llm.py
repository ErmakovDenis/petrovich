"""Клиент модели: POST {base}/chat/completions в формате OpenAI chat completions.

Два режима:
- через прокси LiteLLM (FS_LLM_PROXY_URL, сервис llm_proxy): модель — группа `assistant`; повторы, запасные модели
  OpenRouter и маршрутизацию провайдеров добавляет прокси (его конфигурация — llm_proxy.py);
- напрямую в OpenRouter (прокси не задан): без повторов; запасные модели и маршрутизация — в теле запроса.

Ошибки делятся на классы: LLMRetryable — повторить имело смысл (408, 429, 500, 502, 503, 504, сеть; через прокси
повторы уже исчерпаны), LLMCreditsExhausted — 402 (кончились кредиты или лимит ключа), LLMError — остальное (400,
401, 403: ошибка конфигурации или запроса). Код берётся и из HTTP-ответа, и из error.code тела (ошибка провайдера
в ответе 200, исходный код провайдера в ответе прокси).
"""

import logging
import time
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any, Protocol

import httpx

from ..config import Settings
from ..log_masking import mask_secrets

log = logging.getLogger(__name__)

Message = dict[str, Any]

# Группа моделей в конфигурации прокси (llm_proxy.py) и id копий: ответ прокси несёт id в x-litellm-model-id.
PROXY_MODEL = "assistant"
PRIMARY_ID = "openrouter"
FALLBACK_ID = "fallback-{n}"
RETRYABLE_STATUSES = frozenset({408, 429, 500, 502, 503, 504})
# Меньше этого до срока ответа чата — новое обращение к модели не начинается: ответ всё равно не успеет.
MIN_CALL_SECONDS = 1.0


class LLMError(Exception):
    """Модель недоступна или ответила ошибкой. Текст безопасно показывать пользователю."""


class LLMTimeout(LLMError):
    pass


class LLMRetryable(LLMError):
    """Временный сбой (408, 429, 5xx, сеть). [retry_after] — сколько секунд советует подождать провайдер."""

    def __init__(self, message: str, retry_after: float | None = None):
        super().__init__(message)
        self.retry_after = retry_after


class LLMCreditsExhausted(LLMError):
    """402: кончились кредиты OpenRouter или лимит ключа. Повторять бесполезно, нужен человек."""


@dataclass
class LLMScope:
    """Обращения к модели в рамках одного ответа чата: срок ответа (time.monotonic) и кто отвечал."""

    deadline: float | None = None
    # По обращению: primary — основная модель, fallback — запасная модель OpenRouter.
    routes: list[str] = field(default_factory=list)


_scope: ContextVar[LLMScope | None] = ContextVar("llm_scope", default=None)


@contextmanager
def llm_scope(timeout_seconds: float | None) -> Iterator[LLMScope]:
    """Срок на все обращения к модели в этом контексте: позже него обращение не начинается."""
    scope = LLMScope(None if timeout_seconds is None else time.monotonic() + timeout_seconds)
    token = _scope.set(scope)
    try:
        yield scope
    finally:
        _scope.reset(token)


class LLMClient(Protocol):
    async def complete(self, messages: list[Message], tools: list[dict]) -> Message:
        """Возвращает сообщение ассистента: `content` и, возможно, `tool_calls`."""
        ...


def _short(text: str, limit: int = 200) -> str:
    text = mask_secrets(" ".join(text.split()))
    return text if len(text) <= limit else text[:limit] + "…"


def _retry_after(resp: httpx.Response) -> float | None:
    value = resp.headers.get("retry-after", "").strip()
    try:
        seconds = float(value)
    except ValueError:
        return None
    return seconds if seconds >= 0 else None


def _error_code(data: Any) -> int | None:
    """Код ошибки из тела: OpenRouter и LiteLLM кладут исходный HTTP-код провайдера в error.code (LiteLLM — строкой)."""
    error = data.get("error") if isinstance(data, dict) else None
    code = error.get("code") if isinstance(error, dict) else None
    try:
        return int(code) if code is not None else None
    except (TypeError, ValueError):
        return None


class OpenRouterClient:
    """OpenAI-совместимый клиент: OpenRouter напрямую или прокси LiteLLM (FS_LLM_PROXY_URL)."""

    def __init__(self, settings: Settings, http: httpx.AsyncClient):
        self._settings = settings
        self._http = http

    @property
    def via_proxy(self) -> bool:
        return bool(self._settings.llm_proxy_url.strip())

    def _request(self, messages: list[Message], tools: list[dict]) -> tuple[str, dict[str, Any], dict[str, str]]:
        s = self._settings
        body: dict[str, Any] = {
            "messages": messages,
            "temperature": s.llm_temperature,
            "max_tokens": s.llm_max_tokens,
        }
        # Пустой список tools часть провайдеров отвергает — без tools ключ не передаём.
        if tools:
            body["tools"] = tools
        if self.via_proxy:
            # Ключ OpenRouter, маршрутизацию и запасные модели прокси берёт из своей конфигурации.
            body["model"] = PROXY_MODEL
            return s.llm_proxy_url.rstrip("/") + "/v1/chat/completions", body, {}
        body["model"] = s.llm_model
        if s.fallback_models:
            body["models"] = [s.llm_model, *s.fallback_models]
        body["provider"] = provider_preferences(s)
        key = s.openrouter_api_key.get_secret_value() if s.openrouter_api_key else ""
        return s.openrouter_base_url.rstrip("/") + "/chat/completions", body, {"Authorization": f"Bearer {key}"}

    def _timeout(self) -> float:
        """Таймаут обращения: FS_LLM_TIMEOUT_SECONDS, но не дольше остатка срока ответа чата. Через прокси —
        весь остаток срока: FS_LLM_TIMEOUT_SECONDS — срок одной попытки прокси, а повторам и переходу к запасной
        модели нужно больше."""
        s = self._settings
        timeout = s.chat_timeout_seconds if self.via_proxy else s.llm_timeout_seconds
        scope = _scope.get()
        if scope is not None and scope.deadline is not None:
            left = scope.deadline - time.monotonic()
            if left < MIN_CALL_SECONDS:
                raise LLMTimeout("Срок ответа ассистента истёк до обращения к модели")
            timeout = min(timeout, left)
        return timeout

    async def complete(self, messages: list[Message], tools: list[dict]) -> Message:
        url, body, headers = self._request(messages, tools)
        timeout = self._timeout()
        try:
            resp = await self._http.post(url, json=body, headers=headers, timeout=timeout)
        except httpx.ConnectTimeout as e:
            raise LLMRetryable(f"{self._name} недоступен: {type(e).__name__}") from e
        except httpx.TimeoutException as e:
            raise LLMTimeout(f"Модель не ответила за {timeout:.0f} с") from e
        except httpx.HTTPError as e:
            raise LLMRetryable(f"{self._name} недоступен: {type(e).__name__}") from e

        try:
            data = resp.json()
        except ValueError:
            data = None
        if resp.status_code != 200:
            raise self._status_error(resp, data)
        if not isinstance(data, dict):
            raise LLMError(f"{self._name} вернул ответ не в формате JSON")
        # OpenRouter может вернуть 200 с ошибкой провайдера в теле — класс по её коду.
        if data.get("error"):
            raise self._classify(f"Ошибка провайдера модели: {_short(self._error_text(data))}", _error_code(data), None)
        choices = data.get("choices") or []
        choice = choices[0] if choices and isinstance(choices[0], dict) else {}
        # Ошибка провайдера посреди генерации: частичный текст пользователю не отдаём.
        if choice.get("error") or choice.get("finish_reason") == "error":
            raise LLMError(f"Ошибка провайдера модели: {_short(self._error_text(choice) or 'генерация прервана')}")
        message = choice.get("message")
        if not isinstance(message, dict):
            raise LLMError("Модель вернула пустой ответ")
        self._note_route(resp, data)
        return message

    @property
    def _name(self) -> str:
        return "Прокси модели" if self.via_proxy else "OpenRouter"

    def _status_error(self, resp: httpx.Response, data: Any) -> LLMError:
        code = resp.status_code
        text = f"{self._name} ответил {code}: {_short(self._error_text(data) or resp.text)}"
        original = _error_code(data)
        # Код провайдера в теле важнее кода прокси: LiteLLM может отдать 402 провайдера под своим кодом.
        return self._classify(text, original if original is not None else code, _retry_after(resp), code)

    @staticmethod
    def _classify(text: str, code: int | None, retry_after: float | None, http_code: int | None = None) -> LLMError:
        if 402 in (code, http_code):
            log.error("модель: кончились кредиты или лимит ключа OpenRouter (402) — нужен владелец стенда: %s", text)
            return LLMCreditsExhausted(text)
        if code in RETRYABLE_STATUSES or http_code in RETRYABLE_STATUSES:
            return LLMRetryable(text, retry_after)
        return LLMError(text)

    def _note_route(self, resp: httpx.Response, data: dict[str, Any]) -> None:
        """Какая модель и провайдер фактически ответили — в лог и в LLMScope (доля ответов запасных моделей)."""
        s = self._settings
        model = str(data.get("model") or "—")
        if self.via_proxy:
            # Прокси отдаёт модель и провайдера не всегда (без перехода — имя группы), копию — всегда.
            deployment = resp.headers.get("x-litellm-model-id", "")
            route = "fallback" if deployment.startswith(FALLBACK_ID.format(n="")) else "primary"
            extra = (f", копия прокси {deployment or '—'}, повторов {resp.headers.get('x-litellm-attempted-retries', '0')}, "
                     f"переходов {resp.headers.get('x-litellm-attempted-fallbacks', '0')}")
        else:
            # Основную модель провайдер может назвать иначе (с датой версии) — запасной считаем только узнанную.
            route = "fallback" if any(_same_model(model, m) for m in s.fallback_models) else "primary"
            extra = f", провайдер {data.get('provider') or '—'}"
        scope = _scope.get()
        if scope is not None:
            scope.routes.append(route)
        log.info("модель: ответила %s (%s)%s", model, route, extra)

    @staticmethod
    def _error_text(data: Any) -> str:
        if isinstance(data, dict) and isinstance(data.get("error"), dict):
            return str(data["error"].get("message") or data["error"])
        if isinstance(data, dict) and data.get("error"):
            return str(data["error"])
        return ""


def provider_preferences(s: Settings) -> dict[str, Any]:
    """Поле provider запроса OpenRouter (и extra_body прокси): require_parameters остаётся true — иначе запасной
    провайдер может молча игнорировать tools, и ассистент начнёт отвечать без данных."""
    provider: dict[str, Any] = {
        "require_parameters": s.openrouter_require_parameters,
        "data_collection": s.openrouter_data_collection,
    }
    # У OpenRouter allow_fallbacks по умолчанию true: с настройками по умолчанию запрос — как раньше.
    if not s.openrouter_allow_fallbacks:
        provider["allow_fallbacks"] = False
    if s.openrouter_provider_sort:
        provider["sort"] = s.openrouter_provider_sort
    return provider


def _same_model(answered: str, configured: str) -> bool:
    """Модель ответа — это [configured]. Прокси может добавить префикс провайдера (openrouter/...)."""
    return answered == configured or answered.endswith("/" + configured)
