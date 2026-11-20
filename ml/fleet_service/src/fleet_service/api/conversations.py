"""P4: персональные разговоры; legacy /v1/chat не меняет протокол хранения."""

import asyncio
import json
from dataclasses import dataclass, field
from typing import Annotated, Callable, Awaitable

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response

from ..agent.llm import llm_scope
from ..agent.mail_tools import EMAIL
from ..agent.prompt import build_messages
from ..agent.tools import ToolContext
from ..autograph.errors import SchemaForbidden
from ..identity.principal import IdentityRejected, Principal
from ..memory.aliases import ALIASES, alias_intent, memory_reply
from ..memory.conversations import ConversationInterrupted, ConversationRepository, clean_text
from ..memory.preferences import (
    PREFERENCES,
    PreferenceService,
    local_overrides,
    preference_intent,
    preference_reply,
)
from ..memory.turn_journal import TurnJournal
from ..schemas.chat import ChatRequest, ChatTurn
from ..schemas.conversations import (
    CreateConversation,
    ConversationView,
    DurableChatRequest,
    DurableChatResponse,
    DurableDraft,
    MessageView,
)
from ..schemas.emails import EmailContacts, EmailResult, EmailRecipientView
from ..mail.service import EmailRefused
from ..memory.repository import MemoryConflict, MemoryNotFound
from .chat import RESERVE_NOTICE
from .deps import _bearer, _unauthorized, require_bot
from .identity import get_identity, no_cache, require_principal
from .identity_telegram import _private, get_telegram_identity

router = APIRouter(prefix="/v2", tags=["conversations"])


@dataclass(frozen=True)
class Actor:
    principal: Principal
    session: str = field(repr=False)
    channel: str
    check: Callable[[], Awaitable[Principal]]

    @property
    def mail_owner(self):
        return "__principal__:" + self.principal.principal_id


async def actor(
    request: Request,
    response: Response,
    authorization: Annotated[str | None, Header()] = None,
    credential: Annotated[str | None, Header(alias="X-Identity-Credential", max_length=128)] = None,
    schema_id: Annotated[str | None, Header(alias="X-Schema-Id")] = None,
    chat_id: Annotated[str | None, Header(alias="X-Telegram-Chat-Id")] = None,
    user_id: Annotated[str | None, Header(alias="X-Telegram-User-Id")] = None,
    chat_type: Annotated[str | None, Header(alias="X-Telegram-Chat-Type")] = None,
) -> Actor:
    settings = request.app.state.settings
    if not settings.chat_v2_enabled:
        raise HTTPException(503, "Серверная история разговоров выключена")
    identity = get_identity(request, settings)
    no_cache(response)
    if chat_id is not None:
        require_bot(settings, authorization)
        peer = _private(chat_id, user_id, chat_type)
        service = get_telegram_identity(request, identity)
        try:
            bound = await service.authenticate(*peer)
        except IdentityRejected:
            raise _unauthorized("Подтвердите личность Telegram заново") from None
        except SchemaForbidden:
            raise HTTPException(403, "Схема AutoGRAPH недоступна этой учётной записи") from None

        async def check():
            fresh = await service.authenticate(*peer)
            if fresh.principal != bound.principal or fresh.session != bound.session:
                raise IdentityRejected()
            return fresh.principal

        return Actor(bound.principal, bound.session, "telegram", check)
    principal = await require_principal(identity, credential, authorization, schema_id, None)
    session = _bearer(authorization)

    async def check():
        fresh = await identity.authenticate(credential or "", session, principal.tenant_id)
        if fresh != principal:
            raise IdentityRejected()
        return fresh

    return Actor(principal, session, "android", check)


User = Annotated[Actor, Depends(actor)]


def repository(request):
    return ConversationRepository(request.app.state.store)


def translate(error):
    if isinstance(error, MemoryNotFound):
        return HTTPException(404, "Разговор не найден или недоступен")
    if isinstance(error, (IdentityRejected,)):
        return _unauthorized("Подтверждение личности истекло или отозвано — войдите повторно")
    if isinstance(error, (PermissionError, SchemaForbidden)):
        return HTTPException(403, "Права на данные разговора изменились; содержимое недоступно")
    if isinstance(error, (MemoryConflict, ConversationInterrupted)):
        return HTTPException(409, "Разговор уже обрабатывается или его версия изменилась; обновите историю")
    return error


@router.post("/conversations", response_model=ConversationView)
async def create(body: CreateConversation, request: Request, user: User):
    if body.channel != user.channel:
        raise HTTPException(422, "Канал разговора не соответствует подтверждённому клиенту")
    repo = repository(request)
    cid = await repo.create_conversation(user.principal, user.channel)
    return ConversationView.model_validate(await repo.metadata(user.principal, cid))


@router.get("/conversations")
async def conversations(
    request: Request,
    user: User,
    limit: Annotated[int, Query(ge=1, le=100)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
):
    rows = await repository(request).conversations(user.principal, limit=limit, offset=offset)
    return {"conversations": [ConversationView.model_validate(row).model_dump(by_alias=True) for row in rows]}


@router.get("/conversations/{cid}", response_model=ConversationView)
async def conversation(cid: str, request: Request, user: User):
    try:
        return ConversationView.model_validate(await repository(request).metadata(user.principal, cid))
    except (MemoryNotFound, IdentityRejected) as error:
        raise translate(error) from None


@router.get("/conversations/{cid}/messages")
async def messages(
    cid: str,
    request: Request,
    user: User,
    limit: Annotated[int, Query(ge=1, le=100)] = 50,
    offset: Annotated[int, Query(ge=0)] = 0,
):
    try:
        visible = {
            v.id
            for v in await request.app.state.telemetry_service.fresh_vehicles(
                user.session, user.principal.tenant_id
            )
        }
        rows = await repository(request).page(user.principal, cid, visible, limit, offset)
        for row in rows:
            row["drafts"], row["unavailable_draft_ids"] = await drafts(request, user, row.pop("draft_ids"))
            if row["drafts"] or row["unavailable_draft_ids"]:
                row["content"] = draft_status(row["drafts"], row["unavailable_draft_ids"], row["content"])
        await user.check()
        return {"messages": [MessageView.model_validate(row).model_dump(by_alias=True) for row in rows]}
    except (MemoryNotFound, IdentityRejected, SchemaForbidden) as error:
        raise translate(error) from None


@router.delete("/conversations/{cid}", status_code=204)
async def delete(cid: str, request: Request, user: User):
    try:
        await repository(request).delete_conversation(user.principal, cid)
    except (MemoryNotFound, IdentityRejected) as error:
        raise translate(error) from None


async def drafts(request, user, identifiers):
    service = request.app.state.emails
    result, unavailable = [], []
    for identifier in identifiers:
        try:
            if service is None:
                raise EmailRefused(503, "Письма не настроены")
            draft = await service._owned(identifier, user.principal.tenant_id, user.mail_owner)
            result.append(
                DurableDraft(
                    id=draft.id,
                    recipients=[
                        EmailRecipientView(id=r.id, name=r.name, role=r.role, email=r.email)
                        for r in draft.recipients
                    ],
                    subject=draft.subject,
                    body=draft.body,
                    expires_at=draft.expires_at,
                    status=(
                        "expired"
                        if draft.status == "draft" and draft.expires_at <= service.clock()
                        else draft.status
                    ),
                    sent_at=draft.sent_at,
                )
            )
        except EmailRefused:
            unavailable.append(identifier)
    return result, unavailable


def draft_status(items, unavailable, original):
    labels = {
        "draft": "ждёт подтверждения",
        "sent": "отправлено",
        "cancelled": "отменено",
        "expired": "срок истёк",
        "sending": "отправка выполняется",
        "failed": "отправка не подтверждена",
    }
    states = list(dict.fromkeys(labels.get(item.status, "статус недоступен") for item in items))
    if unavailable:
        states.append("черновик больше недоступен")
    text = "Письма: " + "; ".join(states) + "."
    if original.endswith(RESERVE_NOTICE):
        text += "\n\n" + RESERVE_NOTICE
    return text


async def answer_view(request, user, repo, turn, response):
    state = turn["state"]
    result = DurableChatResponse(
        conversation_id=turn["conversation_id"],
        client_message_id=turn["client_message_id"],
        user_message_id=turn["user_message_id"],
        assistant_message_id=turn.get("assistant_message_id"),
        version=turn["base_version"] + (1 if state == "completed" else 0),
        state=(
            "completed" if state == "completed" else "interrupted" if state == "interrupted" else "processing"
        ),
    )
    if state == "completed":
        # Не читаем весь разговор ради одного ответа.
        result.reply = await repo.reply(user.principal, turn["id"])
        result.drafts, result.unavailable_draft_ids = await drafts(
            request, user, json.loads(turn["draft_ids_json"])
        )
        if result.drafts or result.unavailable_draft_ids:
            result.reply = draft_status(result.drafts, result.unavailable_draft_ids, result.reply)
    elif state == "interrupted":
        response.status_code = 409
        result.drafts, result.unavailable_draft_ids = await drafts(
            request, user, json.loads(turn["draft_ids_json"])
        )
        result.reply = "Запрос прерван: результат начатого действия неизвестен. Автоматический повтор отключён; проверьте результат перед новым запросом."
    else:
        response.status_code = 202
        response.headers["Retry-After"] = "2"
    await user.check()
    return result


@router.post("/chat", response_model=DurableChatResponse)
async def chat(body: DurableChatRequest, request: Request, response: Response, user: User):
    state = request.app.state
    settings, telemetry = state.settings, state.telemetry_service
    if not settings.llm_configured:
        raise HTTPException(503, "Ассистент на стенде не настроен")
    safe_content = clean_text(body.content)
    if not safe_content.strip() or len(safe_content) > min(
        settings.chat_max_message_chars, settings.chat_history_max_chars
    ):
        raise HTTPException(422, "Пустой вопрос или превышен допустимый размер сообщения")
    repo, turn, acquired = repository(request), None, False
    try:
        with llm_scope(settings.chat_timeout_seconds) as scope:
            async with asyncio.timeout(settings.chat_timeout_seconds):
                visible = {
                    v.id for v in await telemetry.fresh_vehicles(user.session, user.principal.tenant_id)
                }
                turn, acquired = await repo.claim(
                    user.principal,
                    body,
                    user.channel,
                    visible,
                    int((settings.chat_timeout_seconds + 30) * 1000),
                )
                if not acquired:
                    return await answer_view(request, user, repo, turn, response)
                # Идентификатор известен клиенту даже при ошибке генерации.
                response.headers["X-Conversation-Id"] = turn["conversation_id"]
                response.headers["X-Client-Message-Id"] = body.client_message_id
                features = frozenset({ALIASES} if settings.memory_aliases_enabled else set())
                if settings.memory_preferences_enabled:
                    features |= {PREFERENCES}
                if body.allow_email and settings.email_configured:
                    features |= {EMAIL}
                calls = await repo.calls(user.principal, turn["id"])
                journal = TurnJournal(repo, user, turn, telemetry)
                ctx = ToolContext(
                    user.session,
                    user.principal.tenant_id,
                    body.utc_offset_minutes,
                    user_name=user.mail_owner,
                    features=features,
                    principal=user.principal,
                    check_principal=user.check,
                    journal=journal,
                    read_only_recovery=bool(calls),
                    alias_intent=alias_intent(body.content) if settings.memory_aliases_enabled else None,
                    preference_intent=(
                        preference_intent(body.content) if settings.memory_preferences_enabled else None
                    ),
                )
                existing_drafts, _ = await drafts(request, user, json.loads(turn["draft_ids_json"]))
                ctx.drafts.extend(existing_drafts)
                history, overrides, draft_refs = await repo.context(
                    user.principal,
                    turn["conversation_id"],
                    visible,
                    settings.chat_max_messages - 1,
                    max(1, (settings.chat_history_max_chars - len(safe_content)) // (2 if calls else 1)),
                )
                for message, identifiers in zip(history, draft_refs):
                    if identifiers:
                        items, unavailable = await drafts(request, user, identifiers)
                        message.content = draft_status(items, unavailable, message.content)
                while (
                    history
                    and sum(len(m.content) for m in history) + len(safe_content)
                    > settings.chat_history_max_chars
                ):
                    history.pop(0)
                current = ChatTurn(role="user", content=safe_content)
                chat_request = ChatRequest(
                    messages=[*history, current],
                    utc_offset_minutes=body.utc_offset_minutes,
                    anomaly_id=body.anomaly_id,
                    allow_email=body.allow_email,
                )
                preferences = (
                    await PreferenceService(repo).load(ctx) if settings.memory_preferences_enabled else None
                )
                stored = None
                if body.anomaly_id:
                    record = await state.store.get(user.principal.tenant_id, body.anomaly_id, visible)
                    if record:
                        stored = record.to_api(body.utc_offset_minutes).model_dump(by_alias=True, mode="json")
                if body.email_contacts is not None and state.emails is not None:
                    await state.emails.sync_contacts(
                        user.principal.tenant_id, user.mail_owner, body.email_contacts
                    )
                prompt = build_messages(
                    state.system_prompt,
                    chat_request,
                    stored_anomaly=stored,
                    preferences=preferences,
                    conversation_overrides=overrides if settings.memory_preferences_enabled else None,
                    server_history=True,
                )
                prompt.extend(
                    await journal.resume(
                        ctx,
                        max(
                            0,
                            settings.chat_history_max_chars
                            - len(safe_content)
                            - sum(len(m.content) for m in history),
                        ),
                    )
                )
                key = "conversation:" + user.principal.principal_id + ":" + user.principal.tenant_id
                state.limits.chat_rate.take(key)
                async with state.limits.chat.slot():
                    result = await state.agent.run(prompt, ctx)
                reply = preference_reply(ctx, memory_reply(ctx, result.reply))
                if ctx.drafts:
                    reply = "Черновики писем подготовлены и ждут отдельного подтверждения пользователя."
                if settings.llm_reserve_degraded and "reserve" in scope.routes:
                    reply += "\n\n" + RESERVE_NOTICE
                reply = clean_text(reply)
                if len(reply) > 32000:
                    raise HTTPException(502, "Ответ модели слишком длинный и не сохранён")
                await user.check()
                fresh = {v.id for v in await telemetry.fresh_vehicles(user.session, user.principal.tenant_id)}
                if not visible.issubset(fresh):
                    raise PermissionError()
                local, _ = local_overrides([current])
                overrides.update(local)
                await repo.complete(
                    user.principal,
                    turn["id"],
                    turn["lease_token"],
                    reply,
                    [d.id for d in ctx.drafts],
                    ctx.alias_results,
                    ctx.preference_results,
                    overrides,
                )
                return await answer_view(
                    request, user, repo, await repo.get_turn(user.principal, turn["id"]), response
                )
    except (
        MemoryNotFound,
        MemoryConflict,
        IdentityRejected,
        PermissionError,
        SchemaForbidden,
        ConversationInterrupted,
    ) as error:
        raise translate(error) from None
    except TimeoutError:
        raise HTTPException(504, "Ответ не завершён; повторите тот же clientMessageId") from None
    finally:
        if acquired and turn is not None:
            # Завершённый/deleted turn или новая аренда не затрагиваются. Отмена не теряет durable состояние.
            try:
                await asyncio.shield(repo.release(user.principal, turn["id"], turn["lease_token"]))
            except (MemoryNotFound, IdentityRejected):
                pass


@router.post("/emails/{identifier}/{action}", response_model=EmailResult)
async def email_action(
    identifier: str, action: str, request: Request, user: User, body: EmailContacts | None = None
):
    if action not in {"confirm", "cancel"}:
        raise HTTPException(404, "Действие не найдено")
    service = request.app.state.emails
    if service is None:
        raise HTTPException(503, "Письма не настроены")
    try:
        if body is not None and body.email_contacts is not None:
            await service.sync_contacts(user.principal.tenant_id, user.mail_owner, body.email_contacts)
        result = await (service.confirm if action == "confirm" else service.cancel)(
            identifier, user.principal.tenant_id, user.mail_owner
        )
        return EmailResult(
            id=result.id, status="sent" if action == "confirm" else "cancelled", sent_at=result.sent_at
        )
    except EmailRefused as error:
        raise HTTPException(error.status, str(error)) from None
