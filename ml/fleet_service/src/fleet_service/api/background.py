from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, status

from ..autograph.client import AutoGraphClient
from ..autograph.errors import LoginRejected
from ..background.access import AccessRepository, Grant, PasswordAction, user_key
from ..config import Settings, get_settings
from ..schemas.background import AccessRequest, AccessStatus, ClaimRequest, ClaimResponse, DeviceRequest
from ..store.repository import AnomalyRepository
from .deps import UserSession, get_access, get_autograph_client, get_store, require_session, user_name
from .errors import UNPROCESSABLE

router = APIRouter(prefix="/v1", tags=["background"])

User = Annotated[UserSession, Depends(require_session)]
UserName = Annotated[str | None, Depends(user_name)]
Access = Annotated[AccessRepository, Depends(get_access)]
Config = Annotated[Settings, Depends(get_settings)]


async def _status(settings: Settings, store: AnomalyRepository, user: UserSession, grant: Grant | None) -> AccessStatus:
    mine = grant is not None and grant.schema_id == user.schema_id
    return AccessStatus(
        enabled=settings.background_configured,
        registered=mine,
        token_active=mine and grant.token is not None,
        token_since=grant.token_since if mine else None,
        password_stored=mine and grant.password is not None,
        last_ok_at=grant.last_ok_at if mine else None,
        last_error=grant.last_error if mine else None,
        last_scan_at=await store.last_scan(user.schema_id),
        interval_minutes=settings.background_interval_minutes,
        window_hours=settings.background_window_hours,
    )


@router.post("/background/access", response_model=AccessStatus, response_model_by_alias=True)
async def grant_access(
    request: AccessRequest, user: User, name: UserName, access: Access, settings: Config,
    store: Annotated[AnomalyRepository, Depends(get_store)],
    client: Annotated[AutoGraphClient, Depends(get_autograph_client)],
) -> AccessStatus:
    """Выдать или обновить доступ стенда для фоновой проверки: текущий токен сессии (из Authorization) и пояс.
    Приложение вызывает это при каждом фоновом обновлении — стенд получает свежий токен. Пароль — только при
    savePassword=true: стенд проверяет его входом в AutoGRAPH и хранит зашифрованным."""
    if not settings.background_configured or not access.can_store:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Фоновая проверка на стенде не включена")
    password = request.password.get_secret_value() if request.password else ""
    action = PasswordAction.CLEAR
    if request.save_password and password:
        if not name:
            raise HTTPException(UNPROCESSABLE, "Для входа стенда по паролю нужен логин AutoGRAPH (X-User-Name)")
        try:
            token = await client.login(name, password, request.utc_offset_minutes)
        except LoginRejected:
            raise HTTPException(UNPROCESSABLE, "AutoGRAPH не принял логин или пароль — пароль на стенде не сохранён") \
                from None
        if user.schema_id not in await client.schema_ids(token):
            raise HTTPException(status.HTTP_403_FORBIDDEN, "Схема AutoGRAPH недоступна этой учётной записи")
        action = PasswordAction.SET
    elif request.save_password:
        action = PasswordAction.KEEP
    grant = await access.upsert(
        request.device_id, user.schema_id, name, request.utc_offset_minutes, user.session, action, password or None
    )
    return await _status(settings, store, user, grant)


@router.get("/background/access", response_model=AccessStatus, response_model_by_alias=True)
async def access_status(
    user: User, access: Access, settings: Config, store: Annotated[AnomalyRepository, Depends(get_store)],
    device_id: Annotated[str, Query(alias="deviceId", min_length=16, max_length=64)],
) -> AccessStatus:
    """Состояние доступа устройства и последняя проверка схемы."""
    return await _status(settings, store, user, await access.get(device_id))


@router.post("/background/access/revoke", response_model=AccessStatus, response_model_by_alias=True)
async def revoke_access(
    request: DeviceRequest, user: User, name: UserName, access: Access, settings: Config,
    store: Annotated[AnomalyRepository, Depends(get_store)],
) -> AccessStatus:
    """Отзыв доступа (выключили фоновую проверку, сменили адрес стенда или источник данных): токен и пароль
    устройства удаляются со стенда, фоновые проверки от его имени прекращаются. Отозвать можно доступ своей схемы
    или своего логина (устройство могло выдать его в прежней схеме); чужой — ответ тот же, запись не трогается."""
    grant = await access.get(request.device_id)
    if grant is not None and (
        grant.schema_id == user.schema_id or user_key(grant.user_name, grant.device_id) == user_key(name, grant.device_id)
    ):
        await access.delete(request.device_id)
    return await _status(settings, store, user, None)


@router.post("/notifications/claim", response_model=ClaimResponse, response_model_by_alias=True)
async def claim_notifications(
    request: ClaimRequest, user: User, name: UserName, access: Access, settings: Config,
) -> ClaimResponse:
    """Перед уведомлением: какие из аномалий пользователь ещё не видел в уведомлении ни на одном устройстве.
    Возвращённые отмечаются показанными — второе устройство того же пользователя их уже не получит."""
    if len(request.ids) > settings.notification_claim_max_ids:
        raise HTTPException(UNPROCESSABLE, f"За один раз — не больше {settings.notification_claim_max_ids} аномалий")
    fresh = await access.claim(user.schema_id, user_key(name, request.device_id), request.device_id, request.ids)
    return ClaimResponse(ids=fresh)
