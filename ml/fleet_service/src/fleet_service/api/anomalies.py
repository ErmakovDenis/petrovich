from datetime import datetime
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..config import Settings, get_settings
from ..rules.check import AnomalyCheckService
from ..schemas.anomalies import CheckRequest, CheckResponse
from ..schemas.contract import Severity
from ..schemas.store import (
    AnomalyList,
    ImportRequest,
    ImportResponse,
    ResolveRequest,
    ScanRequest,
    ScanResponse,
    StoredAnomaly,
)
from ..store.repository import AnomalyFilter, AnomalyRepository, to_utc
from ..store.scan import ImportTooLarge, ScanService
from ..telemetry.service import PeriodInvalid, TelemetryService, VehicleNotFound
from .deps import (
    UserSession,
    get_check_service,
    get_scan_service,
    get_store,
    get_telemetry_service,
    require_session,
    user_name,
)
from .errors import UNPROCESSABLE

router = APIRouter(prefix="/v1/anomalies", tags=["anomalies"])

User = Annotated[UserSession, Depends(require_session)]
Store = Annotated[AnomalyRepository, Depends(get_store)]
Telemetry = Annotated[TelemetryService, Depends(get_telemetry_service)]
Scans = Annotated[ScanService, Depends(get_scan_service)]
UserName = Annotated[str | None, Depends(user_name)]
Offset = Annotated[int, Query(alias="utcOffsetMinutes", ge=-14 * 60, le=14 * 60)]


@router.post("/check", response_model=CheckResponse, response_model_by_alias=True)
async def check(
    request: CheckRequest,
    user: User,
    service: Annotated[AnomalyCheckService, Depends(get_check_service)],
) -> CheckResponse:
    """Аномалии машины за период: правила (как BaselineAnomalyDetector) и аналитика predictive_antifraud.
    Результат не сохраняется (для сохранения — /scan); статус аналитики — в `analytics` и `modelsReady`."""
    try:
        result = await service.check(
            user.session, user.schema_id, request.vehicle_id, request.from_, request.to, request.utc_offset_minutes
        )
    except PeriodInvalid as e:
        raise HTTPException(UNPROCESSABLE, str(e)) from None
    except VehicleNotFound as e:
        raise HTTPException(status.HTTP_404_NOT_FOUND, str(e)) from None
    return result.response


@router.get("", response_model=AnomalyList, response_model_by_alias=True)
async def list_anomalies(
    user: User,
    store: Store,
    telemetry: Telemetry,
    settings: Annotated[Settings, Depends(get_settings)],
    utc_offset_minutes: Offset,
    from_: Annotated[datetime | None, Query(alias="from", description="Местное время без пояса")] = None,
    to: Annotated[datetime | None, Query(description="Местное время без пояса")] = None,
    vehicle_id: Annotated[str | None, Query(alias="vehicleId")] = None,
    severity: Annotated[list[Severity] | None, Query()] = None,
    status_: Annotated[Literal["open", "resolved", "confirmed", "false_alarm"] | None, Query(alias="status")] = None,
    limit: Annotated[int, Query(ge=1)] = 200,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> AnomalyList:
    """Сохранённые аномалии машин пользователя, сначала новые. Период — по пересечению с эпизодом события;
    status: open — ждут решения, resolved — с решением, confirmed, false_alarm."""
    for t in (from_, to):
        if t is not None and t.tzinfo is not None:
            raise HTTPException(UNPROCESSABLE, "Время передаётся без часового пояса — как местное время пользователя")
    visible = [v.id for v in await telemetry.vehicles(user.session, user.schema_id)]
    f = AnomalyFilter(
        vehicle_ids=visible,
        from_utc=to_utc(from_, utc_offset_minutes) if from_ else None,
        to_utc=to_utc(to, utc_offset_minutes) if to else None,
        vehicle_id=vehicle_id,
        severities=[s.value for s in severity or []],
        status=status_,
    )
    total, records = await store.query(user.schema_id, f, min(limit, settings.anomalies_max_limit), offset)
    return AnomalyList(
        total=total,
        items=[r.to_api(utc_offset_minutes) for r in records],
        last_scan_at=await store.last_scan(user.schema_id),
    )


@router.post("/scan", response_model=ScanResponse, response_model_by_alias=True)
async def scan(request: ScanRequest, user: User, scans: Scans) -> ScanResponse:
    """Проверка с сохранением на канонической сетке: машины пользователя (или vehicleIds) за период. Ошибка одной
    машины — в её результате (ok=false), остальные проверяются."""
    try:
        return await scans.scan(
            user.session, user.schema_id, request.vehicle_ids, request.from_, request.to, request.utc_offset_minutes
        )
    except PeriodInvalid as e:
        raise HTTPException(UNPROCESSABLE, str(e)) from None


@router.post("/import", response_model=ImportResponse, response_model_by_alias=True)
async def import_decisions(request: ImportRequest, user: User, name: UserName, scans: Scans) -> ImportResponse:
    """Разовый перенос локальной истории и решений с устройства: стенд проверяет периоды вокруг событий
    и сопоставляет записи по машине, типу, параметру и времени с допуском. Несопоставленные решения — в unmatched."""
    try:
        return await scans.import_decisions(user.session, user.schema_id, name, request)
    except ImportTooLarge as e:
        raise HTTPException(UNPROCESSABLE, str(e)) from None


@router.post("/{anomaly_id:path}/resolve", response_model=StoredAnomaly, response_model_by_alias=True)
async def resolve(
    anomaly_id: str, request: ResolveRequest, user: User, name: UserName, store: Store, telemetry: Telemetry
) -> StoredAnomaly:
    """Решение по аномалии (resolution=null — вернуть в «ждут решения») для всех пользователей схемы; кто и когда —
    в записи и в истории решений."""
    visible = {v.id for v in await telemetry.vehicles(user.session, user.schema_id)}
    if await store.get(user.schema_id, anomaly_id, visible) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Аномалия не найдена или недоступна этому пользователю")
    outcome = await store.resolve(user.schema_id, anomaly_id, request.resolution, request.reason, name)
    if outcome is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Аномалия не найдена или недоступна этому пользователю")
    return outcome[0].to_api(request.utc_offset_minutes)
