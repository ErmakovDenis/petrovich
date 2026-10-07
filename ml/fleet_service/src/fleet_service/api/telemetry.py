from datetime import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..schemas.contract import Vehicle, VehicleTelemetry
from ..telemetry.service import PeriodInvalid, TelemetryService, VehicleNotFound
from .deps import UserSession, get_telemetry_service, require_session
from .errors import UNPROCESSABLE

router = APIRouter(prefix="/v1", tags=["telemetry"])

Service = Annotated[TelemetryService, Depends(get_telemetry_service)]
User = Annotated[UserSession, Depends(require_session)]


@router.get("/vehicles", response_model=list[Vehicle], response_model_by_alias=True)
async def vehicles(user: User, service: Service) -> list[Vehicle]:
    """Машины выбранной схемы, доступные пользователю (EnumDevices), по имени."""
    return await service.vehicles(user.session, user.schema_id)


@router.get("/telemetry", response_model=VehicleTelemetry, response_model_by_alias=True)
async def telemetry(
    user: User,
    service: Service,
    vehicle_id: Annotated[str, Query(alias="vehicleId", min_length=1)],
    from_: Annotated[datetime, Query(alias="from", description="Местное время пользователя без пояса")],
    to: Annotated[datetime, Query(description="Местное время пользователя без пояса")],
    utc_offset_minutes: Annotated[int, Query(alias="utcOffsetMinutes", ge=-14 * 60, le=14 * 60)],
) -> VehicleTelemetry:
    """VehicleTelemetry машины за период: интервалы и свёртка — как в приложении (TripTablesMapper)."""
    try:
        result = await service.telemetry(user.session, user.schema_id, vehicle_id, from_, to, utc_offset_minutes)
    except PeriodInvalid as e:
        raise HTTPException(UNPROCESSABLE, str(e)) from None
    except VehicleNotFound as e:
        raise HTTPException(status.HTTP_404_NOT_FOUND, str(e)) from None
    return result.telemetry
