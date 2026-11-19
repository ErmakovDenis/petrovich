"""P0: явное подтверждение личности; Telegram-подключения не дают персональных полномочий."""

import math
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response

from ..autograph.errors import LoginRejected, SchemaForbidden
from ..config import Settings, get_settings
from ..identity.principal import IdentityRejected, Principal
from ..identity.service import IdentityService
from ..limits import RateLimited
from ..schemas.identity import BindIdentityRequest, BindIdentityResponse, IdentityStatus
from .deps import _bearer, _unauthorized

router = APIRouter(prefix="/v2/identity", tags=["identity"])


def get_identity(request: Request, settings: Annotated[Settings, Depends(get_settings)]) -> IdentityService:
    if not settings.identity_enabled:
        raise HTTPException(503, "Подтверждение личности на стенде выключено")
    if settings.identity_account_lifecycle != "managed":
        raise HTTPException(503, "Для персонального доступа не подтверждён регламент жизненного цикла аккаунтов")
    return request.app.state.identity


Identity = Annotated[IdentityService, Depends(get_identity)]
Credential = Annotated[str, Header(alias="X-Identity-Credential", min_length=1, max_length=128)]


def no_cache(response: Response) -> None:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"


async def require_principal(
    identity: Identity,
    credential: Annotated[str | None, Header(alias="X-Identity-Credential", max_length=128)] = None,
    authorization: Annotated[str | None, Header()] = None,
    x_schema_id: Annotated[str | None, Header()] = None,
    x_telegram_chat_id: Annotated[str | None, Header()] = None,
) -> Principal:
    if x_telegram_chat_id is not None:
        raise HTTPException(403, "Личность Telegram пока не подтверждается")
    try:
        return await identity.authenticate(credential or "", _bearer(authorization), (x_schema_id or "").strip())
    except IdentityRejected:
        raise _unauthorized("Подтверждение личности истекло или отозвано — войдите повторно") from None
    except SchemaForbidden:
        raise HTTPException(403, "Схема AutoGRAPH недоступна этой учётной записи") from None


@router.post("/bind", response_model=BindIdentityResponse, response_model_by_alias=True)
async def bind(body: BindIdentityRequest, request: Request, response: Response, identity: Identity):
    # До Login, по реальному адресу соединения; клиентские forwarded-заголовки здесь не доверенные.
    try:
        request.app.state.identity_rate.take(request.client.host if request.client else "unknown")
    except RateLimited as error:
        request.app.state.metrics.rejections.labels("identity_rate_limit").inc()
        raise HTTPException(
            429, "Слишком много попыток входа, повторите позже",
            headers={"Retry-After": str(math.ceil(error.retry_after))},
        ) from None
    if "x-telegram-chat-id" in request.headers:
        raise HTTPException(403, "Личность Telegram пока не подтверждается")
    try:
        enrollment = await identity.bind(
            body.user_name, body.password.get_secret_value(), body.schema_id, body.utc_offset_minutes,
        )
    except (LoginRejected, IdentityRejected):
        raise _unauthorized("Не удалось подтвердить учётную запись") from None
    except SchemaForbidden:
        raise HTTPException(403, "Схема AutoGRAPH недоступна этой учётной записи") from None
    no_cache(response)
    bound = enrollment.identity
    return BindIdentityResponse(
        principal_id=bound.principal.principal_id, tenant_id=bound.principal.tenant_id,
        credential=bound.credential, autograph_session=enrollment.session, expires_at=bound.expires_at,
    )


@router.get("/me", response_model=IdentityStatus, response_model_by_alias=True)
async def me(response: Response, principal: Annotated[Principal, Depends(require_principal)]):
    no_cache(response)
    return IdentityStatus(principal_id=principal.principal_id, tenant_id=principal.tenant_id)


@router.post("/revoke", status_code=204)
async def revoke(
    response: Response, identity: Identity, credential: Credential,
    authorization: Annotated[str | None, Header()] = None,
    x_schema_id: Annotated[str | None, Header()] = None,
    x_telegram_chat_id: Annotated[str | None, Header()] = None,
):
    if x_telegram_chat_id is not None:
        raise HTTPException(403, "Личность Telegram пока не подтверждается")
    session, schema_id = _bearer(authorization), (x_schema_id or "").strip()
    if not session or not schema_id:
        raise _unauthorized("Для отзыва нужна исходная пара credential и сессии AutoGRAPH со схемой")
    # Повторный отзыв безопасен; действующая сессия AutoGRAPH не нужна для прекращения доступа.
    await identity.revoke_bound(credential, session, schema_id)
    no_cache(response)
