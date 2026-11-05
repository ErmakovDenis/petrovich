"""Ошибки шлюза в формате OpenAI. error.code — исходный код: его разбирает клиент модели на стенде и прокси LiteLLM."""

import httpx
from fastapi.responses import JSONResponse

from .. import __version__
from ..log_masking import mask_secrets

# По справке, без User-Agent основной адрес API отвечает 403.
USER_AGENT = f"petrovich-fleet-gateway/{__version__}"


class GatewayError(Exception):
    def __init__(self, status: int, message: str, retry_after: str | None = None):
        super().__init__(message)
        self.status = status
        self.retry_after = retry_after

    def response(self) -> JSONResponse:
        headers = {"Retry-After": self.retry_after} if self.retry_after else None
        return JSONResponse({"error": {"message": str(self), "type": "gigachat_gateway", "code": self.status}},
                            status_code=self.status, headers=headers)


def gigachat_message(resp: httpx.Response, limit: int = 300) -> str:
    """Текст ошибки GigaChat (поле message), без секретов и короткий: он попадает в лог и в ответ прокси."""
    try:
        data = resp.json()
    except ValueError:
        data = None
    text = str(data.get("message") or "") if isinstance(data, dict) else ""
    text = mask_secrets(" ".join((text or resp.text).split()))
    return text if len(text) <= limit else text[:limit] + "…"
