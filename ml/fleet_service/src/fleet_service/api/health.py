from typing import Annotated

from fastapi import APIRouter, Depends, Request

from ..config import Settings, get_settings

router = APIRouter(tags=["health"])


@router.get("/health")
def health(request: Request, settings: Annotated[Settings, Depends(get_settings)]) -> dict[str, object]:
    """Liveness. llmProvider — активный провайдер модели (FS_LLM_PROVIDER); llmConfigured=false — не заданы ключ или
    модель активного провайдера (FS_OPENROUTER_API_KEY и FS_LLM_MODEL либо FS_GIGACHAT_CREDENTIALS и FS_GIGACHAT_MODEL); backgroundConfigured=false —
    фоновая проверка выключена (FS_BACKGROUND_ENABLED) или не задан FS_ACCESS_ENCRYPTION_KEY; emailConfigured=false —
    письма не настроены (FS_SMTP_HOST, FS_SMTP_FROM, FS_EMAIL_RECIPIENTS_PATH); reserveConfigured — задана резервная
    модель (FS_LLM_RESERVE_*); telegramConfigured — включён Telegram-бот (FS_TELEGRAM_*). breakers — состояние circuit breaker'ов (closed / open / half_open): открытый breaker
    не делает стенд нездоровым — status остаётся ok, подробности — в /v1/status."""
    breakers = getattr(request.app.state, "breakers", {})
    return {"status": "ok", "llmProvider": settings.llm_provider, "llmConfigured": settings.llm_configured,
            "backgroundConfigured": settings.background_configured, "emailConfigured": settings.email_configured,
            "reserveConfigured": settings.reserve_configured, "telegramConfigured": settings.telegram_configured,
            "breakers": {name: b.state for name, b in breakers.items()}}
