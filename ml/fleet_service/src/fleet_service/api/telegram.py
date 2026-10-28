from datetime import UTC, datetime, timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response, status

from ..autograph.errors import AutoGraphUnavailable
from ..background.access import AccessRepository, user_key
from ..config import Settings, get_settings
from ..schemas.contract import Severity
from ..schemas.telegram import (
    ChatProblems,
    ConnectRequest,
    LinkRequest,
    LinkResponse,
    LinkView,
    MeResponse,
    Outbox,
    PairCodeRequest,
    PairCodeResponse,
    PairRequest,
    Subscription,
    TelegramChats,
)
from ..store.repository import AnomalyFilter, AnomalyRepository
from ..telegram.links import Link, TelegramLinks
from ..telegram.notify import TelegramNotifier
from ..telegram.sessions import NoAccess, TelegramSessions
from ..telemetry.service import TelemetryService
from .deps import (
    UserSession,
    get_access,
    get_store,
    get_telegram_links,
    get_telegram_sessions,
    get_telemetry_service,
    require_app,
    require_bot,
    require_chat,
    require_session,
    user_name,
)
from .errors import UNPROCESSABLE

router = APIRouter(prefix="/v1/telegram", tags=["telegram"])

Links = Annotated[TelegramLinks, Depends(get_telegram_links)]
Config = Annotated[Settings, Depends(get_settings)]
Chat = Annotated[Link, Depends(require_chat)]
App = Annotated[UserSession, Depends(require_app)]
Name = Annotated[str | None, Depends(user_name)]
Access = Annotated[AccessRepository, Depends(get_access)]
Telemetry = Annotated[TelemetryService, Depends(get_telemetry_service)]

UNLINKED_FROM_APP = "Бот отключён в приложении «Петрович». Чтобы подключиться снова, отправьте /start."
NO_BACKGROUND = ("Сначала включите «Фоновая проверка на стенде» в этом приложении: бот работает по её доступу к "
                 "AutoGRAPH")


def _notifier(request: Request) -> TelegramNotifier:
    return request.app.state.telegram_notifier


def _configured(settings: Settings) -> None:
    if not settings.telegram_configured:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Telegram-бот на стенде не настроен")


def _bot_url(settings: Settings) -> str:
    return f"https://t.me/{settings.telegram_bot_username.strip().lstrip('@')}"


def _login(name: str | None) -> str:
    if not name:
        raise HTTPException(UNPROCESSABLE, "Для бота нужен логин AutoGRAPH (X-User-Name)")
    return name


async def _check_access(access: AccessRepository, device_id: str, user: UserSession, name: str) -> None:
    """Бот работает по доступу фоновой проверки: он должен быть выдан этим устройством этого пользователя в этой схеме."""
    grant = await access.get(device_id)
    if grant is None or grant.schema_id != user.schema_id or \
            user_key(grant.user_name, grant.device_id) != user_key(name, ""):
        raise HTTPException(status.HTTP_409_CONFLICT, NO_BACKGROUND)


async def _own_chat(links: TelegramLinks, user: UserSession, name: str | None, chat_id: str) -> Link:
    link = await links.get(chat_id)
    if link is None or link.schema_id != user.schema_id or link.user_key != user_key(_login(name), ""):
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Этот чат не подключён к вашему «Петровичу»")
    return link


@router.post("/link", response_model=LinkResponse, response_model_by_alias=True)
async def link(request: LinkRequest, user: App, name: Name, settings: Config, links: Links, access: Access) \
        -> LinkResponse:
    """Ссылка подключения бота для приложения: одноразовый код на FS_TELEGRAM_LINK_TTL_MINUTES. Нужен доступ фоновой
    проверки этого устройства в этой схеме — по нему бот будет работать."""
    _configured(settings)
    name = _login(name)
    await _check_access(access, request.device_id, user, name)
    code, expires = await links.create_code(
        user.schema_id, request.schema_name.strip() or user.schema_id, name, request.utc_offset_minutes,
        settings.telegram_link_ttl_minutes * 60_000,
    )
    return LinkResponse(url=f"{_bot_url(settings)}?start={code}", expires_at=expires)


@router.post("/pair", response_model=LinkView, response_model_by_alias=True)
async def pair(request: PairRequest, http: Request, user: App, name: Name, settings: Config, links: Links,
               access: Access) -> LinkView:
    """Приложение подключает чат по коду, который бот показал в ответ на /start (Telegram — на другом устройстве)."""
    _configured(settings)
    name = _login(name)
    await _check_access(access, request.device_id, user, name)
    linked = await links.pair(request.code, user.schema_id, request.schema_name.strip() or user.schema_id, name,
                              request.utc_offset_minutes)
    if linked is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND,
                            "Код не подошёл: он устарел или набран с ошибкой. Отправьте боту /start и введите новый код")
    _notifier(http).linked(linked.chat_id)
    return linked.view()


@router.get("/chats", response_model=TelegramChats, response_model_by_alias=True)
async def chats(user: App, name: Name, settings: Config, links: Links, telemetry: Telemetry) -> TelegramChats:
    """Приложение: чаты пользователя в схеме с подписками и его машины — для экрана «Telegram»."""
    if not settings.telegram_configured:
        return TelegramChats(configured=False)
    vehicles, error = [], None
    try:
        vehicles = await telemetry.vehicles(user.session, user.schema_id)
    except AutoGraphUnavailable as e:
        error = str(e)
    return TelegramChats(
        configured=True, bot_url=_bot_url(settings),
        chats=[c.view() for c in await links.of_user(user.schema_id, _login(name))],
        vehicles=vehicles, vehicles_error=error,
    )


@router.post("/chats/{chat_id}/subscription", response_model=LinkView, response_model_by_alias=True)
async def set_chat_subscription(chat_id: str, subscription: Subscription, user: App, name: Name, links: Links) \
        -> LinkView:
    """Приложение: что и по каким машинам присылать в чат — то же, что /settings в боте."""
    await _own_chat(links, user, name, chat_id)
    updated = await links.set_subscription(chat_id, subscription)
    if updated is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Этот чат не подключён к вашему «Петровичу»")
    return updated.view()


@router.post("/chats/{chat_id}/unlink", response_model=TelegramChats, response_model_by_alias=True)
async def unlink_chat(chat_id: str, http: Request, user: App, name: Name, settings: Config, links: Links,
                      telemetry: Telemetry) -> TelegramChats:
    """Приложение: отключить чат. Бот сообщит об этом в чате; ответ — оставшиеся чаты."""
    await _own_chat(links, user, name, chat_id)
    if await links.delete(chat_id):
        _notifier(http).say(chat_id, UNLINKED_FROM_APP)
    return await chats(user, name, settings, links, telemetry)


@router.post("/bot/connect", response_model=LinkView, response_model_by_alias=True,
             dependencies=[Depends(require_bot)])
async def connect(request: ConnectRequest, links: Links) -> LinkView:
    """Бот: подключить чат по коду из ссылки /start. Код гасится."""
    linked = await links.redeem(request.code, request.chat_id, request.title)
    if linked is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND,
                            "Ссылка устарела или уже использована — возьмите новую в приложении")
    return linked.view()


@router.post("/bot/pair-code", response_model=PairCodeResponse, response_model_by_alias=True,
             dependencies=[Depends(require_bot)])
async def pair_code(request: PairCodeRequest, settings: Config, links: Links) -> PairCodeResponse:
    """Бот: код для чата, чтобы пользователь ввёл его в приложении (Настройки → Telegram). Срок — как у ссылки."""
    code, expires = await links.create_pair_code(request.chat_id, request.title,
                                                 settings.telegram_link_ttl_minutes * 60_000)
    return PairCodeResponse(code=code, expires_at=expires)


@router.post("/bot/outbox", response_model=Outbox, response_model_by_alias=True, dependencies=[Depends(require_bot)])
async def outbox(request: Request) -> Outbox:
    """Бот: что отправить в чаты сейчас. Выданное отмечается — повторно не выдаётся."""
    return await _notifier(request).pull()


@router.get("/me", response_model=MeResponse, response_model_by_alias=True)
async def me(chat: Chat, sessions: Annotated[TelegramSessions, Depends(get_telegram_sessions)], telemetry: Telemetry) \
        -> MeResponse:
    """Бот: подключение чата, подписка и машины пользователя (без машин, если AutoGRAPH или доступ недоступны)."""
    vehicles, error = [], None
    try:
        vehicles = await telemetry.vehicles(await sessions.session(chat), chat.schema_id)
    except (NoAccess, AutoGraphUnavailable) as e:
        error = str(e)
    return MeResponse(**chat.view().model_dump(), vehicles=vehicles, vehicles_error=error)


# Не ниже выбранной важности: INFO — любые.
_AT_LEAST = {
    Severity.INFO: (),
    Severity.WARNING: (Severity.CRITICAL.value, Severity.WARNING.value),
    Severity.CRITICAL: (Severity.CRITICAL.value,),
}


@router.get("/me/problems", response_model=ChatProblems, response_model_by_alias=True)
async def problems(
    user: Annotated[UserSession, Depends(require_session)],
    store: Annotated[AnomalyRepository, Depends(get_store)], telemetry: Telemetry,
    limit: Annotated[int | None, Query(ge=1, le=500)] = None, offset: Annotated[int, Query(ge=0)] = 0,
) -> ChatProblems:
    """Бот: события, ждущие решения, по настройкам чата: без выключенных событий (mutedKinds) и по настройкам списков
    (только выбранные машины, важность, период, порядок). Без [limit] — сколько строк выбрал пользователь."""
    chat = user.telegram
    if chat is None:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Только для Telegram-бота")
    sub = chat.subscription
    lists = sub.lists
    vehicles = [v.id for v in await telemetry.vehicles(user.session, user.schema_id)]
    if lists.subscribed_only and sub.vehicle_ids is not None:
        vehicles = [v for v in vehicles if v in sub.vehicle_ids]
    since = None
    if lists.period_hours is not None:
        since = datetime.now(UTC).replace(tzinfo=None) - timedelta(hours=lists.period_hours)
    total, records = await store.query(
        user.schema_id,
        AnomalyFilter(vehicle_ids=vehicles, status="open", from_utc=since, severities=_AT_LEAST[lists.min_severity],
                      exclude_kinds=sub.muted_kinds),
        limit or lists.page_size, offset, critical_first=lists.order == "severity",
    )
    return ChatProblems(total=total, items=[r.to_api(chat.utc_offset_minutes) for r in records], lists=lists)


@router.put("/me/subscription", response_model=LinkView, response_model_by_alias=True)
async def set_subscription(subscription: Subscription, chat: Chat, links: Links) -> LinkView:
    """Бот: что и по каким машинам присылать в чат."""
    updated = await links.set_subscription(chat.chat_id, subscription)
    if updated is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Чат не подключён")
    return updated.view()


@router.delete("/me", status_code=status.HTTP_204_NO_CONTENT)
async def unlink(chat: Chat, links: Links) -> Response:
    """Бот: отключить чат (/settings → «Отключить бота» или пользователь заблокировал бота)."""
    await links.delete(chat.chat_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
