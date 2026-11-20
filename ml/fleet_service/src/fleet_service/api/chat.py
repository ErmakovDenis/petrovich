import asyncio
import logging
import time
from typing import Annotated

from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response, status

from ..agent.llm import llm_scope
from ..agent.loop import Agent
from ..agent.prompt import build_messages
from ..agent.tools import ToolContext
from ..memory.aliases import ALIASES, alias_intent, memory_reply
from ..memory.preferences import (PREFERENCES, PreferenceService, preference_intent, preference_reply)
from ..memory.repository import MemoryRepository
from ..identity.principal import IdentityRejected
from ..autograph.errors import SchemaForbidden
from ..config import Settings, get_settings
from ..autograph.session import session_key
from ..limits import Limits, Overloaded
from ..schemas.chat import ChatRequest, ChatResponse
from ..store.repository import AnomalyRepository
from ..telemetry.service import TelemetryService
from ..agent.mail_tools import EMAIL
from ..mail.service import EmailRefused
from .deps import (
    UserSession,
    get_agent,
    get_limits,
    get_store,
    get_system_prompt,
    get_telemetry_service,
    require_session,
    acting_user_name,
    _unauthorized,
)
from .errors import UNPROCESSABLE
from .identity import get_identity, no_cache, require_principal

log = logging.getLogger(__name__)

router = APIRouter(prefix="/v1", tags=["chat"])

RESERVE_NOTICE = ("Ассистент сейчас работает в упрощённом режиме: основная модель недоступна. Цифры взяты из данных "
                  "стенда, но ответ может быть менее точным в формулировках.")


@router.post("/chat", response_model=ChatResponse)
async def chat(
    request: ChatRequest,
    http: Request,
    response: Response,
    user: Annotated[UserSession, Depends(require_session)],
    settings: Annotated[Settings, Depends(get_settings)],
    agent: Annotated[Agent, Depends(get_agent)],
    system_prompt: Annotated[str, Depends(get_system_prompt)],
    store: Annotated[AnomalyRepository, Depends(get_store)],
    telemetry: Annotated[TelemetryService, Depends(get_telemetry_service)],
    name: Annotated[str | None, Depends(acting_user_name)],
    limits: Annotated[Limits, Depends(get_limits)],
    identity_credential: Annotated[str | None, Header(alias="X-Identity-Credential", max_length=128)] = None,
) -> ChatResponse:
    """Ответ ассистента на последнее сообщение пользователя с учётом истории и контекста аномалии."""
    if not settings.llm_configured:
        http.app.state.metrics.rejections.labels("model_not_configured").inc()
        raise HTTPException(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "Ассистент на стенде не настроен: не заданы ключ или модель",
        )
    if request.messages[-1].role != "user":
        raise HTTPException(UNPROCESSABLE, "Последнее сообщение должно быть от пользователя")
    limit = settings.chat_max_message_chars
    if len(request.messages[-1].content) > limit:
        raise HTTPException(UNPROCESSABLE, f"Сообщение длиннее {limit} символов")
    # Старые сообщения сверх лимита отбрасываем, длинные из истории обрезаем: отвергнутый вопрос остаётся
    # в истории приложения и не должен ломать следующие запросы. Последнее сообщение (вопрос) остаётся всегда.
    history = [
        t if len(t.content) <= limit else t.model_copy(update={"content": t.content[:limit] + "…"})
        for t in request.messages[-settings.chat_max_messages:]
    ]
    trimmed = request.model_copy(update={"messages": history})
    # Лимит на пользователя: ключ — сессия и схема (хэш), логин из X-User-Name не проверен и не годится.
    rate_key = session_key(user.session, user.schema_id)
    limits.chat_rate.take(rate_key)

    started = time.monotonic()
    features = frozenset({EMAIL}) if request.allow_email and settings.email_configured else frozenset()
    principal = None
    check_principal = None
    if (settings.memory_aliases_enabled or settings.memory_preferences_enabled) and identity_credential is not None:
        identity = get_identity(http, settings)
        principal = await require_principal(identity, identity_credential, "Bearer " + user.session,
                                            user.schema_id, http.headers.get("x-telegram-chat-id"))
        async def check_principal():
            return await identity.authenticate(identity_credential, user.session, user.schema_id)
        if settings.memory_aliases_enabled:
            features = features | {ALIASES}
        if settings.memory_preferences_enabled:
            features = features | {PREFERENCES}
        no_cache(response)
    ctx = ToolContext(user.session, user.schema_id, request.utc_offset_minutes, user_name=name, features=features,
                      principal=principal, check_principal=check_principal,
                      alias_intent=alias_intent(request.messages[-1].content) if settings.memory_aliases_enabled else None,
                      preference_intent=preference_intent(request.messages[-1].content) if settings.memory_preferences_enabled else None)
    try:
        # Срок общий для asyncio.timeout и клиента модели: обращение, которое не успеет, не начинается. Ожидание
        # места среди FS_CHAT_CONCURRENCY ответов входит в срок; очередь полна — сразу Overloaded (503).
        with llm_scope(settings.chat_timeout_seconds) as scope:
            async with asyncio.timeout(settings.chat_timeout_seconds):
                preferences = None
                if settings.memory_preferences_enabled:
                    try:
                        preferences = await PreferenceService(MemoryRepository(store)).load(ctx)
                    except IdentityRejected:
                        raise _unauthorized("Подтверждение личности истекло или отозвано — войдите повторно") from None
                    except SchemaForbidden:
                        raise HTTPException(403, "Схема AutoGRAPH недоступна этой учётной записи") from None
                if request.email_contacts is not None and http.app.state.emails is not None:
                    try:
                        await http.app.state.emails.sync_contacts(user.schema_id, name, request.email_contacts)
                    except EmailRefused as e:
                        raise HTTPException(e.status, str(e)) from None
                stored = None
                if request.anomaly_id:
                    # Чат из карточки при хранилище на стенде: приложение передаёт только id, данные и решение —
                    # отсюда.
                    vehicles = await (telemetry.fresh_vehicles(user.session, user.schema_id) if principal else
                                      telemetry.vehicles(user.session, user.schema_id))
                    visible = {v.id for v in vehicles}
                    record = await store.get(user.schema_id, request.anomaly_id, visible)
                    if record is not None:
                        stored = record.to_api(request.utc_offset_minutes).model_dump(by_alias=True, mode="json")
                async with limits.chat.slot():
                    result = await agent.run(build_messages(system_prompt, trimmed, stored_anomaly=stored, preferences=preferences), ctx)
    except Overloaded:
        # Отказ по вине стенда не тратит лимит пользователя.
        limits.chat_rate.refund(rate_key)
        raise
    except TimeoutError:
        raise HTTPException(
            status.HTTP_504_GATEWAY_TIMEOUT,
            f"Ассистент не успел ответить за {settings.chat_timeout_seconds:g} с",
        ) from None
    log.info(
        "чат: схема %s, сообщений %d, обращений к модели %d (%s), tools %s, черновиков писем %d, %.1f с",
        user.schema_id, len(trimmed.messages), result.iterations, ", ".join(scope.routes) or "—",
        result.tool_calls or "—", len(ctx.drafts), time.monotonic() - started,
    )
    reply = preference_reply(ctx, memory_reply(ctx, result.reply))
    if settings.llm_reserve_degraded and "reserve" in scope.routes:
        # Резерв хуже основной модели (FS_LLM_RESERVE_DEGRADED): цифры те же — их считают tools, — но формулировки и
        # выбор инструментов могут быть проще. Пользователь должен это знать.
        reply = f"{reply}\n\n{RESERVE_NOTICE}"
    return ChatResponse(reply=reply, drafts=ctx.drafts)
