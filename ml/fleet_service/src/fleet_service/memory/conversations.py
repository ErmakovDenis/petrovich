"""Короткие транзакции P4; сеть и LLM всегда вне транзакции SQLite."""

import hashlib
import json
import re
from uuid import NAMESPACE_URL, uuid4, uuid5

from sqlalchemy import delete, insert, select, update

from ..log_masking import mask_secrets
from ..schemas.chat import ChatTurn
from ..store.db import (
    conversations as C,
    conversation_messages as M,
    conversation_turns as T,
    conversation_tool_calls as J,
    conversation_summaries as S,
    memory_outbox as O,
)
from .preference_values import PreferenceValues
from .repository import MemoryConflict, MemoryNotFound, MemoryRepository, owner


class ConversationInterrupted(Exception):
    """Есть начатый tool без подтверждённого результата: автоматически повторять его нельзя."""


def clean_text(value: str) -> str:
    value = mask_secrets(value)
    return re.sub(r"(?i)(пароль|токен|api[_ -]?key)\s*[:=]\s*\S+", r"\1=***", value)


def fingerprint(value) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


class ConversationRepository(MemoryRepository):
    def _owned_turn(self, conn, principal, turn_id):
        row = (
            conn.execute(
                select(T).join(C).where(T.c.id == turn_id, owner(C, principal), C.c.deleted_at.is_(None))
            )
            .mappings()
            .first()
        )
        if row is None:
            raise MemoryNotFound()
        return row

    def _fenced(self, conn, principal, turn_id, token):
        row = self._owned_turn(conn, principal, turn_id)
        if row["state"] != "pending" or row["lease_token"] != token or row["lease_until"] <= self._clock():
            raise MemoryConflict()
        return row

    async def claim(self, principal, request, channel, vehicle_ids: set[str], lease_ms: int):
        request_hash = fingerprint(request.model_dump(exclude={"conversation_id", "expected_version"}))
        cid = request.conversation_id or str(
            uuid5(
                NAMESPACE_URL,
                json.dumps([principal.tenant_id, principal.principal_id, channel, request.client_message_id]),
            )
        )

        def work(conn):
            at = self._clock()
            conversation = (
                conn.execute(select(C).where(owner(C, principal), C.c.id == cid)).mappings().first()
            )
            if conversation is None:
                if request.conversation_id is not None:
                    raise MemoryNotFound()
                conn.execute(
                    insert(C).values(
                        id=cid,
                        tenant_id=principal.tenant_id,
                        principal_id=principal.principal_id,
                        channel=channel,
                        created_at=at,
                        updated_at=at,
                        version=0,
                        style_overrides="{}",
                    )
                )
                conversation = self._conversation(conn, principal, cid)
                self._audit(conn, principal, "create", "conversation", cid, at)
            if conversation["deleted_at"] is not None:
                raise MemoryNotFound()
            if conversation["channel"] != channel:
                raise MemoryConflict()
            prior = (
                conn.execute(
                    select(T).where(
                        T.c.conversation_id == cid, T.c.client_message_id == request.client_message_id
                    )
                )
                .mappings()
                .first()
            )
            if prior:
                if prior["request_hash"] != request_hash:
                    raise MemoryConflict()
                if not set(json.loads(prior["vehicle_ids_json"])).issubset(vehicle_ids):
                    raise PermissionError()
                if prior["state"] in {"completed", "interrupted"}:
                    return dict(prior), False
                if prior["state"] == "pending" and prior["lease_until"] > at:
                    return dict(prior), False
                # Нельзя обойти незавершённый вызов, у которого мог уже появиться побочный эффект.
                uncertain = conn.execute(
                    select(J.c.id).where(J.c.turn_id == prior["id"], J.c.state == "started")
                ).first()
                if uncertain:
                    conn.execute(
                        update(T)
                        .where(T.c.id == prior["id"])
                        .values(state="interrupted", lease_token=None, lease_until=0)
                    )
                    conn.execute(update(M).where(M.c.id == prior["user_message_id"]).values(state="failed"))
                    return {**dict(prior), "state": "interrupted"}, False
                if conversation["version"] != prior["base_version"]:
                    raise MemoryConflict()
                active = conn.execute(
                    select(T.c.id).where(
                        T.c.conversation_id == cid, T.c.state == "pending", T.c.id != prior["id"]
                    )
                ).first()
                if active:
                    raise MemoryConflict()
                token = str(uuid4())
                conn.execute(
                    update(T)
                    .where(T.c.id == prior["id"])
                    .values(state="pending", lease_token=token, lease_until=at + lease_ms, updated_at=at)
                )
                conn.execute(update(M).where(M.c.id == prior["user_message_id"]).values(state="pending"))
                return {
                    **dict(prior),
                    "state": "pending",
                    "lease_token": token,
                    "lease_until": at + lease_ms,
                }, True
            if (
                conversation["version"] != request.expected_version
                or conn.execute(
                    select(T.c.id).where(T.c.conversation_id == cid, T.c.state == "pending")
                ).first()
            ):
                raise MemoryConflict()
            # Не смешиваем протоколы записи P1 и P4 в одном разговоре.
            if conn.execute(
                select(M.c.id).where(
                    M.c.conversation_id == cid, M.c.client_message_id == request.client_message_id
                )
            ).first():
                raise MemoryConflict()
            turn_id, mid, token = str(uuid4()), str(uuid4()), str(uuid4())
            stamp = max(at, conversation["updated_at"] + 1)
            conn.execute(
                insert(M).values(
                    id=mid,
                    conversation_id=cid,
                    client_message_id=request.client_message_id,
                    role="user",
                    content=clean_text(request.content),
                    state="pending",
                    created_at=stamp,
                    turn_id=turn_id,
                )
            )
            conn.execute(update(C).where(C.c.id == cid).values(updated_at=stamp))
            values = dict(
                id=turn_id,
                conversation_id=cid,
                client_message_id=request.client_message_id,
                request_hash=request_hash,
                base_version=conversation["version"],
                state="pending",
                lease_token=token,
                lease_until=at + lease_ms,
                user_message_id=mid,
                vehicle_ids_json=json.dumps(sorted(vehicle_ids)),
                draft_ids_json="[]",
                alias_results_json="[]",
                preference_results_json="[]",
                created_at=stamp,
                updated_at=at,
            )
            conn.execute(insert(T).values(**values))
            return values, True

        return await self._run(principal, work, write=True)

    async def get_turn(self, principal, turn_id):
        return await self._run(principal, lambda conn: dict(self._owned_turn(conn, principal, turn_id)))

    async def reply(self, principal, turn_id):
        def work(conn):
            row = self._owned_turn(conn, principal, turn_id)
            return conn.execute(
                select(M.c.content).where(M.c.id == row["assistant_message_id"], M.c.state == "committed")
            ).scalar_one()

        return await self._run(principal, work)

    async def release(self, principal, turn_id, token):
        def work(conn):
            row = self._owned_turn(conn, principal, turn_id)
            if row["state"] == "pending" and row["lease_token"] == token:
                conn.execute(
                    update(T).where(T.c.id == turn_id).values(state="failed", lease_token=None, lease_until=0)
                )
                conn.execute(update(M).where(M.c.id == row["user_message_id"]).values(state="failed"))

        await self._run(principal, work, write=True)

    async def metadata(self, principal, cid):
        return await self._run(principal, lambda conn: dict(self._conversation(conn, principal, cid)))

    async def page(self, principal, cid, visible, limit=50, offset=0, *, committed_only=False):
        self._page(limit, offset)

        def work(conn):
            self._conversation(conn, principal, cid)
            query = (
                select(M, T.c.vehicle_ids_json, T.c.draft_ids_json)
                .outerjoin(T, M.c.turn_id == T.c.id)
                .where(M.c.conversation_id == cid)
            )
            query = query.where(M.c.state == "committed" if committed_only else M.c.state != "deleted")
            rows = conn.execute(
                query.order_by(M.c.created_at, M.c.turn_id, M.c.role.desc(), M.c.id)
                .limit(limit)
                .offset(offset)
            ).mappings()
            result = []
            for row in rows:
                allowed = row["vehicle_ids_json"] is not None and set(
                    json.loads(row["vehicle_ids_json"])
                ).issubset(visible)
                result.append(
                    {
                        **{column.name: row[column.name] for column in M.columns},
                        "content": (
                            row["content"]
                            if allowed
                            else "Содержимое сообщения больше недоступно по текущим правам"
                        ),
                        "redacted": not allowed,
                        "draft_ids": (
                            json.loads(row["draft_ids_json"] or "[]")
                            if allowed and row["role"] == "assistant"
                            else []
                        ),
                    }
                )
            return result

        return await self._run(principal, work)

    async def context(self, principal, cid, visible, max_messages, max_chars):
        def work(conn):
            conversation = self._conversation(conn, principal, cid)
            # Читаем только ограниченное окно. И пользователь, и assistant одного turn имеют один stamp.
            rows = (
                conn.execute(
                    select(M, T.c.vehicle_ids_json, T.c.draft_ids_json)
                    .outerjoin(T, M.c.turn_id == T.c.id)
                    .where(M.c.conversation_id == cid, M.c.state == "committed")
                    .order_by(M.c.created_at.desc(), M.c.turn_id.desc(), M.c.role)
                    .limit(max_messages)
                )
                .mappings()
                .all()
            )
            selected, selected_ids, draft_refs, used = [], [], [], 0
            for row in rows:
                if row["vehicle_ids_json"] is None or not set(json.loads(row["vehicle_ids_json"])).issubset(
                    visible
                ):
                    continue
                content = row["content"]
                if used + len(content) > max_chars:
                    break
                used += len(content)
                selected.append(ChatTurn(role=row["role"], content=content))
                selected_ids.append(row["id"])
                draft_refs.append(
                    json.loads(row["draft_ids_json"] or "[]") if row["role"] == "assistant" else []
                )
            selected.reverse()
            draft_refs.reverse()
            # Структурная сводка не копирует старые факты/инструкции в новый system prompt.
            source = {
                "version": conversation["version"],
                "retainedMessages": len(selected),
                "startMessageId": selected_ids[-1] if selected_ids else None,
                "endMessageId": selected_ids[0] if selected_ids else None,
            }
            conn.execute(delete(S).where(S.c.conversation_id == cid))
            conn.execute(
                insert(S).values(
                    conversation_id=cid,
                    version=conversation["version"],
                    summary="Контекст ограничен последними доступными сообщениями; текущие факты получать заново из tools.",
                    source_range=json.dumps(source),
                    updated_at=self._clock(),
                )
            )
            try:
                overrides = PreferenceValues.model_validate(
                    json.loads(conversation["style_overrides"])
                ).model_dump(exclude_unset=True)
            except ValueError:
                overrides = {}
            return selected, overrides, draft_refs

        return await self._run(principal, work, write=True)

    async def complete(
        self, principal, turn_id, token, reply, drafts, alias_results, preference_results, new_overrides
    ):
        def work(conn):
            row = self._fenced(conn, principal, turn_id, token)
            conversation = self._conversation(conn, principal, row["conversation_id"])
            if conversation["version"] != row["base_version"]:
                raise MemoryConflict()
            mid = str(uuid4())
            conn.execute(
                insert(M).values(
                    id=mid,
                    conversation_id=row["conversation_id"],
                    client_message_id=row["client_message_id"],
                    role="assistant",
                    content=clean_text(reply),
                    state="committed",
                    created_at=row["created_at"],
                    turn_id=turn_id,
                )
            )
            conn.execute(update(M).where(M.c.id == row["user_message_id"]).values(state="committed"))
            conn.execute(
                update(T)
                .where(T.c.id == turn_id)
                .values(
                    state="completed",
                    assistant_message_id=mid,
                    lease_token=None,
                    lease_until=0,
                    draft_ids_json=json.dumps(drafts),
                    alias_results_json=json.dumps(alias_results, ensure_ascii=False),
                    preference_results_json=json.dumps(preference_results, ensure_ascii=False),
                    updated_at=self._clock(),
                )
            )
            conn.execute(
                insert(O).values(
                    id=str(uuid4()),
                    tenant_id=principal.tenant_id,
                    principal_id=principal.principal_id,
                    event_type="turn_committed",
                    aggregate_id=row["conversation_id"],
                    payload_ref=row["user_message_id"],
                    state="cancelled" if drafts else "pending",
                    attempts=0,
                    next_run_at=self._clock(),
                )
            )
            conn.execute(
                update(C)
                .where(C.c.id == row["conversation_id"])
                .values(
                    version=row["base_version"] + 1,
                    updated_at=max(self._clock(), row["created_at"]),
                    style_overrides=json.dumps(new_overrides),
                )
            )
            return mid

        return await self._run(principal, work, write=True)

    async def calls(self, principal, turn_id):
        def work(conn):
            self._owned_turn(conn, principal, turn_id)
            return [
                dict(row)
                for row in conn.execute(
                    select(J).where(J.c.turn_id == turn_id).order_by(J.c.created_at, J.c.id)
                ).mappings()
            ]

        return await self._run(principal, work)

    async def reserve_tool(self, principal, turn_id, token, name, args):
        digest = fingerprint([name, args])

        def work(conn):
            self._fenced(conn, principal, turn_id, token)
            row = (
                conn.execute(select(J).where(J.c.turn_id == turn_id, J.c.request_hash == digest))
                .mappings()
                .first()
            )
            if row:
                if row["state"] != "done":
                    raise ConversationInterrupted()
                return dict(row), False
            values = dict(
                id=str(uuid4()),
                turn_id=turn_id,
                request_hash=digest,
                tool_name=name,
                state="started",
                created_at=self._clock(),
            )
            conn.execute(insert(J).values(**values))
            return values, True

        return await self._run(principal, work, write=True)

    async def finish_tool(self, principal, turn_id, token, call_id, result, ctx):
        def work(conn):
            self._fenced(conn, principal, turn_id, token)
            conn.execute(
                update(J)
                .where(J.c.id == call_id, J.c.turn_id == turn_id)
                .values(state="done", result_json=result)
            )
            conn.execute(
                update(T)
                .where(T.c.id == turn_id)
                .values(
                    draft_ids_json=json.dumps([d.id for d in ctx.drafts]),
                    alias_results_json=json.dumps(ctx.alias_results, ensure_ascii=False),
                    preference_results_json=json.dumps(ctx.preference_results, ensure_ascii=False),
                )
            )

        await self._run(principal, work, write=True)
