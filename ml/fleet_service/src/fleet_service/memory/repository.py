"""Короткие транзакции памяти. Principal поступает из проверенной server-side dependency.

Репозиторий проверяет собственника и локальный отзыв; свежие права AutoGRAPH и
доступные vehicle_id обязан проверить вызывающий application service. SQL не
принимает полномочия из заголовков или аргументов модели. P1 не запускает extractor.
"""

import hashlib
import json
import unicodedata
from collections.abc import Callable
from uuid import uuid4

from sqlalchemy import Connection, delete, insert, select, update

from ..identity.principal import IdentityRejected, Principal
from ..store.db import (
    conversation_messages as M,
    conversation_summaries as S,
    conversations as C,
    memory_audit as A,
    memory_alias_operations as AO,
    memory_preference_operations as PO,
    memory_candidates as K,
    memory_outbox as O,
    principal_scopes as PS,
    principals as P,
    user_entity_aliases as E,
    user_preferences as U,
)
from ..store.repository import AnomalyRepository, now_ms
from .preference_values import PreferenceValues, validate_preference


class MemoryNotFound(Exception):
    """Запись отсутствует или недоступна в текущем личном scope."""


class MemoryConflict(Exception):
    """Версия, повтор операции или существующая привязка противоречат запросу."""


def bounded(value: str, limit: int) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > limit or "\x00" in value:
        raise ValueError("Недопустимое значение памяти")
    return value


def normalize_alias(alias: str) -> str:
    bounded(alias, 120)
    result = " ".join(unicodedata.normalize("NFKC", alias).casefold().split())
    return bounded(result, 240)


def owner(table, principal: Principal):
    return (table.c.tenant_id == principal.tenant_id) & (table.c.principal_id == principal.principal_id)


class MemoryRepository:
    def __init__(self, store: AnomalyRepository, clock: Callable[[], int] = now_ms):
        self._store = store
        self._clock = clock

    async def _run(self, principal: Principal, work, *, write=False):
        if not principal.verified or principal.tenant_id not in principal.allowed_schema_ids:
            raise IdentityRejected()

        def checked(conn: Connection):
            if write:
                # Первая DML сериализует проверку и запись даже между разными процессами SQLite.
                conn.execute(
                    update(P)
                    .where(P.c.principal_id == principal.principal_id)
                    .values(created_at=P.c.created_at)
                )
            active = conn.execute(
                select(P.c.principal_id)
                .join(PS)
                .where(
                    P.c.principal_id == principal.principal_id,
                    P.c.disabled_at.is_(None),
                    PS.c.tenant_id == principal.tenant_id,
                    PS.c.revoked_at.is_(None),
                )
            ).first()
            if active is None:
                raise IdentityRejected()
            return work(conn)

        return await self._store.run(checked, write=write)

    def _audit(self, conn, principal, action, kind, target, at):
        # Только служебные коды и UUID: текст сообщений, aliases и значения не попадают в аудит.
        conn.execute(
            insert(A).values(
                id=str(uuid4()),
                tenant_id=principal.tenant_id,
                principal_id=principal.principal_id,
                action=action,
                target_type=kind,
                target_id=target,
                at=at,
                reason="explicit",
            )
        )

    def _conversation(self, conn, principal, conversation_id):
        row = (
            conn.execute(
                select(C).where(owner(C, principal), C.c.id == conversation_id, C.c.deleted_at.is_(None))
            )
            .mappings()
            .first()
        )
        if row is None:
            raise MemoryNotFound()
        return row

    async def create_conversation(self, principal: Principal, channel: str) -> str:
        if channel not in {"android", "telegram"}:
            raise ValueError("Неизвестный канал")
        identifier, at = str(uuid4()), self._clock()

        def work(conn):
            conn.execute(
                insert(C).values(
                    id=identifier,
                    tenant_id=principal.tenant_id,
                    principal_id=principal.principal_id,
                    channel=channel,
                    created_at=at,
                    updated_at=at,
                    version=0,
                )
            )
            self._audit(conn, principal, "create", "conversation", identifier, at)
            return identifier

        return await self._run(principal, work, write=True)

    async def conversations(self, principal: Principal, *, limit=50, offset=0) -> list[dict]:
        self._page(limit, offset)
        return await self._run(
            principal,
            lambda conn: [
                dict(row)
                for row in conn.execute(
                    select(C)
                    .where(owner(C, principal), C.c.deleted_at.is_(None))
                    .order_by(C.c.updated_at.desc(), C.c.id)
                    .limit(limit)
                    .offset(offset)
                ).mappings()
            ],
        )

    @staticmethod
    def _page(limit, offset):
        if type(limit) is not int or type(offset) is not int or not 1 <= limit <= 100 or offset < 0:
            raise ValueError("Недопустимая страница")

    async def messages(self, principal: Principal, conversation_id: str, *, limit=50, offset=0) -> list[dict]:
        self._page(limit, offset)

        def work(conn):
            self._conversation(conn, principal, conversation_id)
            return [
                dict(row)
                for row in conn.execute(
                    select(M)
                    .where(
                        M.c.conversation_id == conversation_id,
                        M.c.state == "committed",
                    )
                    .order_by(M.c.created_at, M.c.turn_id, M.c.role.desc(), M.c.id)
                    .limit(limit)
                    .offset(offset)
                ).mappings()
            ]

        return await self._run(principal, work)

    async def append_turn(
        self,
        principal: Principal,
        conversation_id: str,
        client_message_id: str,
        user_content: str,
        assistant_content: str,
        *,
        expected_version: int
    ) -> str:
        """Атомарно сохраняет готовую пару и ссылку outbox; один client ID — одна пара.

        Это примитив хранения P1. Захват pending turn до вызова LLM и восстановление
        незавершённой генерации относятся к будущему API разговоров P4.
        """
        bounded(client_message_id, 128)
        bounded(user_content, 32000)
        bounded(assistant_content, 32000)
        if type(expected_version) is not int or expected_version < 0:
            raise ValueError("Недопустимая версия")

        def work(conn):
            conversation = self._conversation(conn, principal, conversation_id)
            previous = (
                conn.execute(
                    select(M).where(
                        M.c.conversation_id == conversation_id, M.c.client_message_id == client_message_id
                    )
                )
                .mappings()
                .all()
            )
            if previous:
                contents = {row["role"]: row["content"] for row in previous if row["state"] == "committed"}
                if contents != {"user": user_content, "assistant": assistant_content}:
                    raise MemoryConflict()
                return previous[0]["turn_id"]
            if conversation["version"] != expected_version:
                raise MemoryConflict()
            # at монотонен внутри разговора, поэтому пагинация и повтор операции устойчивы при одном clock tick.
            at = max(self._clock(), conversation["updated_at"] + 1)
            turn_id, user_id = str(uuid4()), str(uuid4())
            for role, content, message_id in (
                ("user", user_content, user_id),
                ("assistant", assistant_content, str(uuid4())),
            ):
                conn.execute(
                    insert(M).values(
                        id=message_id,
                        conversation_id=conversation_id,
                        client_message_id=client_message_id,
                        role=role,
                        content=content,
                        state="committed",
                        created_at=at,
                        turn_id=turn_id,
                    )
                )
            conn.execute(
                insert(O).values(
                    id=str(uuid4()),
                    tenant_id=principal.tenant_id,
                    principal_id=principal.principal_id,
                    event_type="turn_committed",
                    aggregate_id=conversation_id,
                    payload_ref=user_id,
                    state="pending",
                    attempts=0,
                    next_run_at=at,
                )
            )
            conn.execute(
                update(C).where(C.c.id == conversation_id).values(version=expected_version + 1, updated_at=at)
            )
            return turn_id

        return await self._run(principal, work, write=True)

    async def delete_conversation(self, principal: Principal, conversation_id: str) -> None:
        def work(conn):
            row = (
                conn.execute(select(C).where(owner(C, principal), C.c.id == conversation_id))
                .mappings()
                .first()
            )
            if row is None:
                raise MemoryNotFound()
            if row["deleted_at"] is not None:
                return
            at = self._clock()
            message_ids = select(M.c.id).where(M.c.conversation_id == conversation_id)
            conn.execute(
                update(K)
                .where(owner(K, principal), K.c.evidence_message_id.in_(message_ids))
                .values(payload_json="{}", status="rejected")
            )
            conn.execute(
                update(O)
                .where(owner(O, principal), O.c.aggregate_id == conversation_id)
                .values(state="cancelled", lease_token=None)
            )
            conn.execute(delete(S).where(S.c.conversation_id == conversation_id))
            conn.execute(
                update(M).where(M.c.conversation_id == conversation_id).values(content="", state="deleted")
            )
            conn.execute(
                update(C)
                .where(C.c.id == conversation_id)
                .values(deleted_at=at, updated_at=at, version=C.c.version + 1)
            )
            self._audit(conn, principal, "delete", "conversation", conversation_id, at)

        await self._run(principal, work, write=True)

    async def set_alias(self, principal: Principal, alias: str, vehicle_id: str) -> str:
        """Внутренний примитив P1; production tools используют mutate_alias с operation ID."""
        normalize_alias(alias)
        bounded(vehicle_id, 128)
        return await self._run(
            principal, lambda conn: self._set_alias(conn, principal, alias, vehicle_id), write=True
        )

    def _set_alias(self, conn, principal, alias, vehicle_id):
        normalized = normalize_alias(alias)
        row = (
            conn.execute(
                select(E).where(
                    owner(E, principal),
                    E.c.normalized_alias == normalized,
                    E.c.status == "active",
                    E.c.deleted_at.is_(None),
                )
            )
            .mappings()
            .first()
        )
        if row:
            if row["entity_id"] != vehicle_id:
                raise MemoryConflict()
            return row["id"]
        identifier, at = str(uuid4()), self._clock()
        conn.execute(
            insert(E).values(
                id=identifier,
                tenant_id=principal.tenant_id,
                principal_id=principal.principal_id,
                entity_type="vehicle",
                entity_id=vehicle_id,
                alias=alias.strip(),
                normalized_alias=normalized,
                status="active",
                created_at=at,
                updated_at=at,
            )
        )
        self._audit(conn, principal, "create", "alias", identifier, at)
        return identifier

    async def aliases(self, principal: Principal, *, limit=50, offset=0) -> list[dict]:
        self._page(limit, offset)
        return await self._run(
            principal,
            lambda conn: [
                dict(row)
                for row in conn.execute(
                    select(E)
                    .where(owner(E, principal), E.c.status == "active", E.c.deleted_at.is_(None))
                    .order_by(E.c.normalized_alias, E.c.id)
                    .limit(limit)
                    .offset(offset)
                ).mappings()
            ],
        )

    async def find_alias(self, principal: Principal, alias: str) -> dict | None:
        # Длинное официальное имя может не быть допустимым alias, но остаётся допустимой ссылкой на ТС.
        if not isinstance(alias, str) or not alias.strip() or len(alias) > 120:
            return None
        normalized = normalize_alias(alias)

        def work(conn):
            row = (
                conn.execute(
                    select(E).where(
                        owner(E, principal),
                        E.c.normalized_alias == normalized,
                        E.c.status == "active",
                        E.c.deleted_at.is_(None),
                    )
                )
                .mappings()
                .first()
            )
            return dict(row) if row else None

        return await self._run(principal, work)

    async def visible_aliases(self, principal: Principal, vehicle_ids: set[str]) -> list[dict]:
        # Без пагинации по личной памяти: SQL сразу пересекает её с актуальным разрешённым парком.
        return await self._run(
            principal,
            lambda conn: [
                dict(row)
                for row in conn.execute(
                    select(E)
                    .where(
                        owner(E, principal),
                        E.c.entity_id.in_(vehicle_ids),
                        E.c.status == "active",
                        E.c.deleted_at.is_(None),
                    )
                    .order_by(E.c.normalized_alias)
                ).mappings()
            ],
        )

    async def mutate_alias(
        self, principal: Principal, action: str, alias: str, operation_id: str, vehicle_id: str | None = None
    ) -> dict:
        """Запись alias + аудит + ключ повтора в одной транзакции после проверки прав service'ом."""
        if action not in {"set", "delete"}:
            raise ValueError("Неизвестная операция")
        normalized = normalize_alias(alias)
        bounded(operation_id, 128)
        if action == "set":
            bounded(vehicle_id, 128)
        payload = json.dumps(
            [action, normalized, vehicle_id if action == "set" else None], ensure_ascii=False
        )
        request_hash = hashlib.sha256(payload.encode()).hexdigest()

        def work(conn):
            prior = (
                conn.execute(select(AO).where(owner(AO, principal), AO.c.operation_id == operation_id))
                .mappings()
                .first()
            )
            if prior:
                if prior["request_hash"] != request_hash:
                    raise MemoryConflict()
                target = (
                    conn.execute(select(E).where(owner(E, principal), E.c.id == prior["target_id"]))
                    .mappings()
                    .first()
                )
                if action == "set":
                    active = (
                        target is not None and target["deleted_at"] is None and target["status"] == "active"
                    )
                    return {"status": "saved" if active else "superseded"}
                current = conn.execute(
                    select(E.c.id).where(
                        owner(E, principal),
                        E.c.normalized_alias == normalized,
                        E.c.status == "active",
                        E.c.deleted_at.is_(None),
                    )
                ).scalar()
                return {"status": "superseded" if current is not None else "deleted"}
            if action == "set":
                identifier = self._set_alias(conn, principal, alias, vehicle_id)
            else:
                target = (
                    conn.execute(
                        select(E).where(
                            owner(E, principal),
                            E.c.normalized_alias == normalized,
                            E.c.status == "active",
                            E.c.deleted_at.is_(None),
                        )
                    )
                    .mappings()
                    .first()
                )
                identifier = target["id"] if target else str(uuid4())
                if target is not None:
                    self._delete_item(conn, principal, "alias", identifier)
                else:
                    # Даже если активной записи нет, «забудь» подавляет будущую обработку старых сообщений.
                    at = self._clock()
                    conn.execute(
                        insert(E).values(
                            id=identifier,
                            tenant_id=principal.tenant_id,
                            principal_id=principal.principal_id,
                            entity_type="vehicle",
                            entity_id="",
                            alias="",
                            normalized_alias=normalized,
                            status="deleted",
                            created_at=at,
                            updated_at=at,
                            deleted_at=at,
                        )
                    )
                    self._invalidate(conn, principal, "alias")
                    self._audit(conn, principal, "delete", "alias", identifier, at)
            conn.execute(
                insert(AO).values(
                    tenant_id=principal.tenant_id,
                    principal_id=principal.principal_id,
                    operation_id=operation_id,
                    request_hash=request_hash,
                    action=action,
                    target_id=identifier,
                    at=self._clock(),
                )
            )
            return {"status": "saved" if action == "set" else "deleted"}

        return await self._run(principal, work, write=True)

    async def set_preference(self, principal: Principal, key: str, value) -> str:
        value = validate_preference(key, value)
        return await self._run(
            principal, lambda conn: self._set_preference(conn, principal, key, value), write=True
        )

    def _set_preference(self, conn, principal, key, value):
        encoded = json.dumps(value, ensure_ascii=False, sort_keys=True)
        row = (
            conn.execute(
                select(U).where(
                    owner(U, principal), U.c.key == key, U.c.status == "active", U.c.deleted_at.is_(None)
                )
            )
            .mappings()
            .first()
        )
        at = self._clock()
        if row:
            if row["value_json"] == encoded:
                if key == "auto_extract_enabled" and value is False:
                    self._invalidate(conn, principal, "preference")
                return row["id"]
            identifier = row["id"]
            conn.execute(
                update(U)
                .where(U.c.id == identifier)
                .values(value_json=encoded, updated_at=at, version=U.c.version + 1)
            )
        else:
            identifier = str(uuid4())
            conn.execute(
                insert(U).values(
                    id=identifier,
                    tenant_id=principal.tenant_id,
                    principal_id=principal.principal_id,
                    key=key,
                    value_json=encoded,
                    source_type="explicit",
                    status="active",
                    updated_at=at,
                    version=1,
                )
            )
        if key == "auto_extract_enabled" and value is False:
            self._invalidate(conn, principal, "preference")
        self._audit(conn, principal, "set", "preference", identifier, at)
        return identifier

    async def preference_records(self, principal: Principal) -> list[dict]:
        return await self._run(
            principal,
            lambda conn: [
                dict(row)
                for row in conn.execute(
                    select(U)
                    .where(owner(U, principal), U.c.status == "active", U.c.deleted_at.is_(None))
                    .order_by(U.c.key)
                ).mappings()
            ],
        )

    async def mutate_preference(
        self, principal: Principal, action: str, key: str, operation_id: str, value=None
    ) -> dict:
        if key not in PreferenceValues.model_fields or action not in {"set", "delete"}:
            raise ValueError("Недопустимое предпочтение")
        if action == "set":
            value = validate_preference(key, value)
        bounded(operation_id, 128)
        payload = json.dumps(
            [action, key, value if action == "set" else None], ensure_ascii=False, sort_keys=True
        )
        request_hash = hashlib.sha256(payload.encode()).hexdigest()

        def work(conn):
            prior = (
                conn.execute(select(PO).where(owner(PO, principal), PO.c.operation_id == operation_id))
                .mappings()
                .first()
            )
            if prior:
                if prior["request_hash"] != request_hash:
                    raise MemoryConflict()
                row = (
                    conn.execute(select(U).where(owner(U, principal), U.c.id == prior["target_id"]))
                    .mappings()
                    .one()
                )
                active = conn.execute(
                    select(U.c.id).where(
                        owner(U, principal), U.c.key == key, U.c.status == "active", U.c.deleted_at.is_(None)
                    )
                ).scalar()
                current = row["version"] == prior["target_version"]
                if action == "set":
                    current = current and active == row["id"]
                else:
                    current = current and active is None
                return {"status": ("saved" if action == "set" else "deleted") if current else "superseded"}
            if action == "set":
                identifier = self._set_preference(conn, principal, key, value)
            else:
                row = (
                    conn.execute(
                        select(U).where(
                            owner(U, principal),
                            U.c.key == key,
                            U.c.status == "active",
                            U.c.deleted_at.is_(None),
                        )
                    )
                    .mappings()
                    .first()
                )
                identifier = row["id"] if row else str(uuid4())
                if row:
                    self._delete_item(conn, principal, "preference", identifier)
                else:
                    at = self._clock()
                    conn.execute(
                        insert(U).values(
                            id=identifier,
                            tenant_id=principal.tenant_id,
                            principal_id=principal.principal_id,
                            key=key,
                            value_json="null",
                            source_type="explicit",
                            status="deleted",
                            updated_at=at,
                            deleted_at=at,
                            version=1,
                        )
                    )
                    self._invalidate(conn, principal, "preference")
                    self._audit(conn, principal, "delete", "preference", identifier, at)
            version = conn.execute(select(U.c.version).where(U.c.id == identifier)).scalar_one()
            conn.execute(
                insert(PO).values(
                    tenant_id=principal.tenant_id,
                    principal_id=principal.principal_id,
                    operation_id=operation_id,
                    request_hash=request_hash,
                    action=action,
                    target_id=identifier,
                    target_version=version,
                    at=self._clock(),
                )
            )
            return {"status": "saved" if action == "set" else "deleted"}

        return await self._run(principal, work, write=True)

    async def preferences(self, principal: Principal) -> dict:
        return await self._run(
            principal,
            lambda conn: {
                row.key: json.loads(row.value_json)
                for row in conn.execute(
                    select(U.c.key, U.c.value_json).where(
                        owner(U, principal), U.c.status == "active", U.c.deleted_at.is_(None)
                    )
                )
            },
        )

    async def delete_item(self, principal: Principal, kind: str, identifier: str) -> None:
        """Удаление элемента с tombstone и отменой производных данных текущего scope."""
        if kind not in {"alias", "preference"}:
            raise ValueError("Неизвестный тип памяти")
        await self._run(
            principal, lambda conn: self._delete_item(conn, principal, kind, identifier), write=True
        )

    def _delete_item(self, conn, principal, kind, identifier):
        table = E if kind == "alias" else U
        row = (
            conn.execute(select(table).where(owner(table, principal), table.c.id == identifier))
            .mappings()
            .first()
        )
        if row is None:
            raise MemoryNotFound()
        if row["deleted_at"] is not None:
            return
        at = self._clock()
        values = {"status": "deleted", "deleted_at": at, "updated_at": at}
        # Нормализованный ключ остаётся для будущей проверки tombstone extractor'ом.
        values.update({"alias": "", "entity_id": ""} if kind == "alias" else {"value_json": "null"})
        if kind == "preference":
            values["version"] = U.c.version + 1
        conn.execute(update(table).where(table.c.id == identifier).values(**values))
        self._invalidate(conn, principal, kind)
        self._audit(conn, principal, "delete", kind, identifier, at)

    def _invalidate(self, conn, principal, kind):
        candidate_kind = "alias" if kind == "alias" else "preference"
        conn.execute(
            update(K)
            .where(owner(K, principal), K.c.kind == candidate_kind)
            .values(status="rejected", payload_json="{}")
        )
        conn.execute(
            update(O)
            .where(owner(O, principal), O.c.state.in_(["pending", "processing"]))
            .values(state="cancelled", lease_token=None)
        )
        conn.execute(delete(S).where(S.c.conversation_id.in_(select(C.c.id).where(owner(C, principal)))))
