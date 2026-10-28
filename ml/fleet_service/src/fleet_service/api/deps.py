import hmac
from dataclasses import dataclass
from typing import Annotated
from urllib.parse import unquote

from fastapi import Depends, Header, HTTPException, Request, status

from ..agent.loop import Agent
from ..autograph.client import AutoGraphClient
from ..autograph.session import AutoGraphSessionChecker, AutoGraphUnavailable, SchemaForbidden, SessionInvalid
from ..background.access import AccessRepository
from ..config import Settings, get_settings
from ..limits import Limits
from ..rules.check import AnomalyCheckService
from ..store.repository import AnomalyRepository
from ..store.scan import ScanService
from ..telegram.links import Link, TelegramLinks
from ..telegram.sessions import NoAccess, TelegramSessions
from ..telemetry.service import TelemetryService


@dataclass(frozen=True)
class UserSession:
    """Пользователь стенда: токен сессии AutoGRAPH и выбранная схема из заголовков запроса. Запрос Telegram-бота —
    [telegram] подключение чата: токен — из доступа фоновой проверки пользователя, логин — из подключения."""

    session: str
    schema_id: str
    telegram: Link | None = None


def get_session_checker(request: Request) -> AutoGraphSessionChecker:
    return request.app.state.session_checker


def get_agent(request: Request) -> Agent:
    return request.app.state.agent


def get_telemetry_service(request: Request) -> TelemetryService:
    return request.app.state.telemetry_service


def get_check_service(request: Request) -> AnomalyCheckService:
    return request.app.state.check_service


def get_system_prompt(request: Request) -> str:
    return request.app.state.system_prompt


def get_store(request: Request) -> AnomalyRepository:
    return request.app.state.store


def get_scan_service(request: Request) -> ScanService:
    return request.app.state.scan_service


def get_access(request: Request) -> AccessRepository:
    return request.app.state.access


def get_autograph_client(request: Request) -> AutoGraphClient:
    return request.app.state.autograph_client


def get_limits(request: Request) -> Limits:
    return request.app.state.limits


def get_telegram_links(request: Request) -> TelegramLinks:
    return request.app.state.telegram_links


def get_telegram_sessions(request: Request) -> TelegramSessions:
    return request.app.state.telegram_sessions


def user_name(x_user_name: Annotated[str | None, Header()] = None) -> str | None:
    """Логин AutoGRAPH из `X-User-Name` (UTF-8 в URL-кодировке) — для записи «кто принял решение».
    Стенд его не проверяет: доступ даёт только токен сессии. Не передан — None."""
    name = unquote(x_user_name or "").strip()[:200]
    return name or None


def _unauthorized(detail: str) -> HTTPException:
    return HTTPException(status.HTTP_401_UNAUTHORIZED, detail, headers={"WWW-Authenticate": "Bearer"})


def _bearer(authorization: str | None) -> str:
    scheme, _, token = (authorization or "").partition(" ")
    return token.strip() if scheme.lower() == "bearer" else ""


def require_bot(
    settings: Annotated[Settings, Depends(get_settings)],
    authorization: Annotated[str | None, Header()] = None,
) -> None:
    """Запрос Telegram-бота: `Authorization: Bearer <FS_TELEGRAM_SERVICE_TOKEN>`. Бот не настроен — 503."""
    if not settings.telegram_configured:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Telegram-бот на стенде не настроен")
    expected = settings.telegram_service_token.get_secret_value().encode()
    if not hmac.compare_digest(_bearer(authorization).encode(), expected):
        raise _unauthorized("Неверный ключ Telegram-бота")


NOT_LINKED = "Чат не подключён к «Петровичу»: отправьте /start и введите код в приложении (Настройки → Telegram)"


async def require_chat(
    _: Annotated[None, Depends(require_bot)],
    links: Annotated[TelegramLinks, Depends(get_telegram_links)],
    x_telegram_chat_id: Annotated[str | None, Header()] = None,
) -> Link:
    """Подключённый чат из `X-Telegram-Chat-Id` запроса бота; не подключён — 401."""
    link = await links.get((x_telegram_chat_id or "").strip())
    if link is None:
        raise _unauthorized(NOT_LINKED)
    return link


async def require_session(
    request: Request,
    checker: Annotated[AutoGraphSessionChecker, Depends(get_session_checker)],
    authorization: Annotated[str | None, Header()] = None,
    x_schema_id: Annotated[str | None, Header()] = None,
    x_telegram_chat_id: Annotated[str | None, Header()] = None,
) -> UserSession:
    """`Authorization: Bearer <токен сессии AutoGRAPH>` и `X-Schema-Id: <id схемы>`; логин и пароль стенду не нужны.

    Telegram-бот вместо них передаёт свой ключ и `X-Telegram-Chat-Id`: схема и пользователь — из подключения чата,
    токен — из доступа фоновой проверки этого пользователя (telegram/sessions.py)."""
    if x_telegram_chat_id is not None:
        settings: Settings = request.app.state.settings
        require_bot(settings, authorization)
        link = await require_chat(None, request.app.state.telegram_links, x_telegram_chat_id)
        try:
            session = await request.app.state.telegram_sessions.session(link)
        except NoAccess as e:
            raise _unauthorized(str(e)) from None
        except AutoGraphUnavailable:
            raise HTTPException(
                status.HTTP_503_SERVICE_UNAVAILABLE, "AutoGRAPH недоступен: не удалось проверить сессию"
            ) from None
        return UserSession(session, link.schema_id, link)
    token = _bearer(authorization)
    if not token:
        raise _unauthorized("Нет токена сессии AutoGRAPH")
    schema_id = (x_schema_id or "").strip()
    if not schema_id:
        raise _unauthorized("Не указана схема AutoGRAPH (X-Schema-Id)")
    try:
        await checker.verify(token, schema_id)
    except SessionInvalid:
        raise _unauthorized("Сессия AutoGRAPH недействительна или истекла") from None
    except SchemaForbidden:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Схема AutoGRAPH недоступна этому пользователю") from None
    except AutoGraphUnavailable:
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE, "AutoGRAPH недоступен: не удалось проверить сессию"
        ) from None
    return UserSession(token, schema_id)


def acting_user_name(
    user: Annotated[UserSession, Depends(require_session)],
    header: Annotated[str | None, Depends(user_name)],
) -> str | None:
    """Кто действует — автор решения, владелец черновика письма: у Telegram-бота — логин из подключения чата,
    у приложения — `X-User-Name`."""
    return user.telegram.user_name if user.telegram is not None else header


def require_app(user: Annotated[UserSession, Depends(require_session)]) -> UserSession:
    """Только приложение: выдача доступа фоновой проверки и ссылки на бота боту недоступны."""
    if user.telegram is not None:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Недоступно Telegram-боту")
    return user
