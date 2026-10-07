from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, Header, HTTPException, Request, status

from ..agent.loop import Agent
from ..autograph.session import AutoGraphSessionChecker, AutoGraphUnavailable, SchemaForbidden, SessionInvalid
from ..rules.check import AnomalyCheckService
from ..telemetry.service import TelemetryService


@dataclass(frozen=True)
class UserSession:
    """Пользователь стенда: токен сессии AutoGRAPH и выбранная схема из заголовков запроса."""

    session: str
    schema_id: str


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


def _unauthorized(detail: str) -> HTTPException:
    return HTTPException(status.HTTP_401_UNAUTHORIZED, detail, headers={"WWW-Authenticate": "Bearer"})


async def require_session(
    checker: Annotated[AutoGraphSessionChecker, Depends(get_session_checker)],
    authorization: Annotated[str | None, Header()] = None,
    x_schema_id: Annotated[str | None, Header()] = None,
) -> UserSession:
    """`Authorization: Bearer <токен сессии AutoGRAPH>` и `X-Schema-Id: <id схемы>`; логин и пароль стенду не нужны."""
    scheme, _, token = (authorization or "").partition(" ")
    token = token.strip()
    if scheme.lower() != "bearer" or not token:
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
