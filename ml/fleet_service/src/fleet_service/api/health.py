from typing import Annotated

from fastapi import APIRouter, Depends

from ..config import Settings, get_settings

router = APIRouter(tags=["health"])


@router.get("/health")
def health(settings: Annotated[Settings, Depends(get_settings)]) -> dict[str, str | bool]:
    """Liveness. llmConfigured=false — не заданы FS_OPENROUTER_API_KEY или FS_LLM_MODEL; backgroundConfigured=false —
    фоновая проверка выключена (FS_BACKGROUND_ENABLED) или не задан FS_ACCESS_ENCRYPTION_KEY; emailConfigured=false —
    письма не настроены (FS_SMTP_HOST, FS_SMTP_FROM, FS_EMAIL_RECIPIENTS_PATH)."""
    return {"status": "ok", "llmConfigured": settings.llm_configured,
            "backgroundConfigured": settings.background_configured, "emailConfigured": settings.email_configured}
