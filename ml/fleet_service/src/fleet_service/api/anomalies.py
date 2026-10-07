from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status

from ..rules.check import AnomalyCheckService
from ..schemas.anomalies import CheckRequest, CheckResponse
from ..telemetry.service import PeriodInvalid, VehicleNotFound
from .deps import UserSession, get_check_service, require_session
from .errors import UNPROCESSABLE

router = APIRouter(prefix="/v1/anomalies", tags=["anomalies"])


@router.post("/check", response_model=CheckResponse, response_model_by_alias=True)
async def check(
    request: CheckRequest,
    user: Annotated[UserSession, Depends(require_session)],
    service: Annotated[AnomalyCheckService, Depends(get_check_service)],
) -> CheckResponse:
    """Аномалии машины за период: правила (как BaselineAnomalyDetector) и аналитика predictive_antifraud.
    Результат не сохраняется; статус аналитики — в `analytics` и `modelsReady`."""
    try:
        result = await service.check(
            user.session, user.schema_id, request.vehicle_id, request.from_, request.to, request.utc_offset_minutes
        )
    except PeriodInvalid as e:
        raise HTTPException(UNPROCESSABLE, str(e)) from None
    except VehicleNotFound as e:
        raise HTTPException(status.HTTP_404_NOT_FOUND, str(e)) from None
    return result.response
