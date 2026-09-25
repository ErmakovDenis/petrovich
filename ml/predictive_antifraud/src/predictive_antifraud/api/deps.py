import secrets
from typing import Annotated

from fastapi import Depends, Header, HTTPException, Request, status

from ..config import Settings, get_settings
from ..models.registry import ModelRegistry
from ..services.antifraud import AntifraudService
from ..services.predictive import PredictiveService


def get_registry(request: Request) -> ModelRegistry:
    return request.app.state.registry


def get_predictive(request: Request) -> PredictiveService:
    return request.app.state.predictive


def get_antifraud(request: Request) -> AntifraudService:
    return request.app.state.antifraud


def require_api_key(
    settings: Annotated[Settings, Depends(get_settings)],
    x_api_key: Annotated[str | None, Header()] = None,
) -> None:
    if settings.api_key and not (x_api_key and secrets.compare_digest(x_api_key, settings.api_key)):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Неверный или отсутствующий X-API-Key")
