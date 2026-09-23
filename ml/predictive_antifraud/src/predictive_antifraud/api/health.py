from typing import Annotated

from fastapi import APIRouter, Depends

from ..models.registry import ModelName, ModelRegistry
from ..schemas.results import ModelStatus, ReadinessResponse
from .deps import get_registry

router = APIRouter(tags=["health"])


@router.get("/health")
def health() -> dict[str, str]:
    """Liveness: процесс жив."""
    return {"status": "ok"}


@router.get("/ready", response_model=ReadinessResponse)
def ready(registry: Annotated[ModelRegistry, Depends(get_registry)]) -> ReadinessResponse:
    """Какие модели загружены. Сервис без моделей работает, но отвечает ready=false."""
    statuses = []
    for name in ModelName:
        model = registry.get(name)
        statuses.append(ModelStatus(name=name.value, ready=model is not None, version=model.version if model else None))
    return ReadinessResponse(models=statuses)
