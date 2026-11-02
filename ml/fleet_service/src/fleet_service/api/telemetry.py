from datetime import date, datetime, timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..schemas.contract import Vehicle, VehicleTelemetry
from ..schemas.widgets import WidgetStatsResponse
from ..store.repository import AnomalyRepository
from ..telemetry.service import PeriodInvalid, TelemetryService, VehicleNotFound
from .deps import UserSession, get_store, get_telemetry_service, require_session
from .errors import UNPROCESSABLE

router = APIRouter(prefix="/v1", tags=["telemetry"])

Service = Annotated[TelemetryService, Depends(get_telemetry_service)]
User = Annotated[UserSession, Depends(require_session)]
Store = Annotated[AnomalyRepository, Depends(get_store)]


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


@router.get("/widget-stats", response_model=WidgetStatsResponse, response_model_by_alias=True)
async def widget_stats(
    user: User,
    service: Service,
    store: Store,
    from_: Annotated[date, Query(alias="from")],
    to: Annotated[date, Query()],
) -> WidgetStatsResponse:
    """Готовые суточные суммы для главной; AutoGRAPH здесь не вызывается."""
    if to < from_ or (to - from_).days > 62:
        raise HTTPException(UNPROCESSABLE, "Период виджетов должен быть от 0 до 62 дней")
    visible = {v.id for v in await service.vehicles(user.session, user.schema_id)}
    items = [item for item in await store.widget_stats(user.schema_id, from_.isoformat(), to.isoformat())
             if item.vehicle.id in visible]
    return WidgetStatsResponse(from_=from_, to=to, items=items)
