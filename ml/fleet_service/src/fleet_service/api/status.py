"""GET /v1/status — состояние стенда для администратора: circuit breaker'ы, очереди и пределы, размеры кэшей.

Доступ — `Authorization: Bearer <FS_ADMIN_TOKEN>`; без FS_ADMIN_TOKEN эндпоинт выключен (404). Сессия AutoGRAPH
здесь не нужна: администратор стенда — не пользователь схемы. Данных пользователей в ответе нет.
"""

import secrets
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Request, status

from ..config import Settings, get_settings

router = APIRouter(prefix="/v1", tags=["status"])


def require_admin(
    settings: Annotated[Settings, Depends(get_settings)],
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    token = settings.admin_token.get_secret_value() if settings.admin_token else ""
    if not token:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Not Found")
    scheme, _, given = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not secrets.compare_digest(given.strip().encode(), token.encode()):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Нужен ключ администратора стенда",
                            headers={"WWW-Authenticate": "Bearer"})


@router.get("/status", dependencies=[Depends(require_admin)])
def stand_status(request: Request, settings: Annotated[Settings, Depends(get_settings)]) -> dict[str, object]:
    state = request.app.state
    limits = state.limits
    return {
        "breakers": {name: b.status() for name, b in state.breakers.items()},
        "limits": {
            "chat": {"active": limits.chat.active, "waiting": limits.chat.waiting,
                     "concurrency": settings.chat_concurrency, "queueMax": settings.chat_queue_max},
            "autograph": {"active": limits.autograph.active, "background": limits.autograph.background_active,
                          "waiting": limits.autograph.waiting, "concurrency": settings.autograph_concurrency},
            "predictive": {"active": limits.predictive.active, "background": limits.predictive.background_active,
                           "waiting": limits.predictive.waiting, "concurrency": settings.predictive_concurrency},
        },
        "caches": state.telemetry_service.cache_sizes(),
        "llm": {"viaProxy": bool(settings.llm_proxy_url.strip()), "fallbackModels": settings.fallback_models,
                "reserveConfigured": settings.reserve_configured},
    }
