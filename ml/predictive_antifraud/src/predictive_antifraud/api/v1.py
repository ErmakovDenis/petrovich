from typing import Annotated

from fastapi import APIRouter, Depends

from ..schemas.results import DetectionResponse
from ..schemas.telemetry import VehicleTelemetry
from ..services.antifraud import AntifraudService
from ..services.predictive import PredictiveService
from .deps import get_antifraud, get_predictive, require_api_key

router = APIRouter(prefix="/v1", dependencies=[Depends(require_api_key)])


@router.post("/predictive/analyze", tags=["predictive"], response_model=DetectionResponse)
def analyze(
    telemetry: VehicleTelemetry,
    service: Annotated[PredictiveService, Depends(get_predictive)],
) -> DetectionResponse:
    """Предиктивная аналитика по телеметрии одной машины за период."""
    return service.analyze(telemetry)


@router.post("/antifraud/check", tags=["antifraud"], response_model=DetectionResponse)
def check(
    telemetry: VehicleTelemetry,
    service: Annotated[AntifraudService, Depends(get_antifraud)],
) -> DetectionResponse:
    """Проверка телеметрии одной машины за период на признаки мошенничества."""
    return service.check(telemetry)
