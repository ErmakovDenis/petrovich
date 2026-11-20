"""Личный профиль Telegram подтверждается только отдельным кодом в проверенной сессии приложения."""

import math
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response

from ..autograph.errors import SchemaForbidden
from ..identity.principal import IdentityRejected, Principal
from ..identity.telegram import TelegramIdentity, TelegramIdentityService
from ..limits import RateLimited
from ..schemas.identity import (
    IdentityStatus, TelegramIdentityCode, TelegramIdentityLink, TelegramIdentityLinks,
    TelegramIdentityPair, TelegramIdentityPeer,
)
from .deps import _bearer, _unauthorized, require_bot
from .identity import Credential, get_identity, no_cache, require_principal

router = APIRouter(prefix="/v2/identity", tags=["identity"])


def get_telegram_identity(request: Request, _: Annotated[object, Depends(get_identity)]) -> TelegramIdentityService:
    service = request.app.state.telegram_identity
    if not service.configured:
        raise HTTPException(503, "Для личного профиля Telegram нужно настроить шифрование на стенде")
    return service


Service = Annotated[TelegramIdentityService, Depends(get_telegram_identity)]
AppPrincipal = Annotated[Principal, Depends(require_principal)]


def _private(chat_id: str | None, user_id: str | None, chat_type: str | None) -> tuple[str, str]:
    if not chat_id or not user_id or chat_type != "private" or chat_id != user_id or \
            not chat_id.isascii() or not chat_id.isdigit() or len(chat_id) > 19 or chat_id.startswith("0"):
        raise HTTPException(403, "Персональная привязка доступна только в личном чате с ботом")
    return chat_id, user_id


async def require_peer(
    _: Annotated[None, Depends(require_bot)],
    x_telegram_chat_id: Annotated[str | None, Header()] = None,
    x_telegram_user_id: Annotated[str | None, Header()] = None,
    x_telegram_chat_type: Annotated[str | None, Header()] = None,
) -> tuple[str, str]:
    return _private(x_telegram_chat_id, x_telegram_user_id, x_telegram_chat_type)


Peer = Annotated[tuple[str, str], Depends(require_peer)]


def _rate(request: Request, key: str) -> None:
    try:
        request.app.state.identity_rate.take(key)
    except RateLimited as error:
        raise HTTPException(429, "Слишком много попыток привязки, повторите позже",
                            headers={"Retry-After": str(math.ceil(error.retry_after))}) from None


@router.post("/telegram/pair-code", response_model=TelegramIdentityCode, response_model_by_alias=True)
async def create_code(body: TelegramIdentityPeer, request: Request, response: Response, service: Service,
                      _: Annotated[None, Depends(require_bot)]):
    chat_id, user_id = _private(body.chat_id, body.telegram_user_id, body.chat_type)
    _rate(request, "identity-code:" + chat_id)
    code, expires = await service.create_code(chat_id, user_id)
    no_cache(response)
    return TelegramIdentityCode(code=code, expires_at=expires)


@router.post("/telegram/pair", response_model=TelegramIdentityLink, response_model_by_alias=True)
async def pair(body: TelegramIdentityPair, request: Request, response: Response, service: Service,
               principal: AppPrincipal, credential: Credential,
               authorization: Annotated[str | None, Header()] = None):
    _rate(request, "identity-pair:" + principal.principal_id)
    try:
        linked = await service.pair(body.code, principal, credential, _bearer(authorization))
    except IdentityRejected:
        raise HTTPException(404, "Персональный код устарел или уже использован; получите новый в личном чате") from None
    no_cache(response)
    return TelegramIdentityLink(**linked)


@router.get("/telegram", response_model=TelegramIdentityLinks, response_model_by_alias=True)
async def list_links(response: Response, service: Service, principal: AppPrincipal):
    no_cache(response)
    return TelegramIdentityLinks(links=[TelegramIdentityLink(**r) for r in await service.list_links(principal)])


@router.delete("/telegram/links/{chat_id}", status_code=204)
async def unlink_from_app(chat_id: str, response: Response, service: Service, principal: AppPrincipal):
    await service.unlink(chat_id, principal=principal)
    no_cache(response)


async def require_telegram_principal(service: Service, peer: Peer) -> TelegramIdentity:
    try:
        return await service.authenticate(*peer)
    except IdentityRejected:
        raise _unauthorized("Личный профиль Telegram не подтверждён или истёк; подключите его в приложении") from None
    except SchemaForbidden:
        raise HTTPException(403, "Схема AutoGRAPH недоступна этой учётной записи") from None


@router.get("/telegram/me", response_model=IdentityStatus, response_model_by_alias=True)
async def me(response: Response, identity: Annotated[TelegramIdentity, Depends(require_telegram_principal)]):
    no_cache(response)
    return IdentityStatus(principal_id=identity.principal.principal_id, tenant_id=identity.principal.tenant_id)


@router.delete("/telegram/me", status_code=204)
async def unlink_from_bot(response: Response, service: Service, peer: Peer):
    await service.unlink(peer[0], user_id=peer[1])
    no_cache(response)
