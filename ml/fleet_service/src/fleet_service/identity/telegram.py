"""Отдельная персональная привязка личного Telegram-чата; старый user_name не используется как identity."""

import secrets
from dataclasses import dataclass, field

from sqlalchemy import Connection, delete, select
from sqlalchemy.dialects.sqlite import insert

from ..autograph.client import AutoGraphClient
from ..autograph.errors import SchemaForbidden
from ..background.crypto import SealBroken, SecretBox
from ..store.db import identity_credentials as C, identity_telegram_codes as Q, identity_telegram_links as L
from ..store.db import principal_scopes as S, principals as P
from ..store.repository import AnomalyRepository, now_ms
from ..telegram.links import PAIR_ALPHABET, normalize_pair_code
from .principal import IdentityRejected, Principal
from .repository import digest

PAIR_TTL_MS = 5 * 60_000


@dataclass(frozen=True)
class TelegramIdentity:
    principal: Principal
    session: str = field(repr=False)
    credential_hash: str = field(repr=False)
    expires_at: int


def _context(chat_id: str, user_id: str, tenant_id: str, credential_hash: str) -> str:
    return f"identity-telegram|{chat_id}|{user_id}|{tenant_id}|{credential_hash}"


class TelegramIdentityService:
    def __init__(self, store: AnomalyRepository, autograph: AutoGraphClient, base_url: str,
                 box: SecretBox | None, clock=now_ms):
        self._store, self._autograph, self._box, self._clock = store, autograph, box, clock
        self._provider = digest(base_url.rstrip("/"))

    @property
    def configured(self) -> bool:
        return self._box is not None

    async def create_code(self, chat_id: str, user_id: str) -> tuple[str, int]:
        code = "".join(secrets.choice(PAIR_ALPHABET) for _ in range(8))
        expires = self._clock() + PAIR_TTL_MS
        def work(conn: Connection) -> None:
            conn.execute(delete(Q).where(Q.c.expires_at <= self._clock()))
            conn.execute(delete(Q).where(Q.c.chat_id == chat_id))
            conn.execute(insert(Q).values(code_hash=digest("identity-pair:" + code), chat_id=chat_id,
                                          telegram_user_id=user_id, expires_at=expires))
        await self._store.run(work, write=True)
        return code, expires

    def _valid_credentials(self, credential_hash: str, principal: Principal | None = None):
        query = select(C).join(P, C.c.principal_id == P.c.principal_id).join(
            S, (S.c.principal_id == C.c.principal_id) & (S.c.tenant_id == C.c.tenant_id),
        ).where(C.c.credential_hash == credential_hash, C.c.expires_at > self._clock(), C.c.revoked_at.is_(None),
                P.c.provider == self._provider, P.c.disabled_at.is_(None), S.c.revoked_at.is_(None))
        if principal:
            query = query.where(C.c.principal_id == principal.principal_id, C.c.tenant_id == principal.tenant_id)
        return query

    async def pair(self, code: str, principal: Principal, credential: str, session: str) -> dict:
        if self._box is None:
            raise IdentityRejected()
        credential_hash = digest(credential)
        def work(conn: Connection) -> dict:
            # Первая операция — запись: конкурирующее погашение кода сериализуется и между процессами SQLite.
            row = conn.execute(delete(Q).where(
                Q.c.code_hash == digest("identity-pair:" + normalize_pair_code(code)), Q.c.expires_at > self._clock(),
            ).returning(Q.c.chat_id, Q.c.telegram_user_id)).mappings().first()
            credentials = conn.execute(self._valid_credentials(credential_hash, principal)).mappings().first()
            if row is None or credentials is None or credentials["session_hash"] != digest(session):
                raise IdentityRejected()
            chat_id, user_id = row["chat_id"], row["telegram_user_id"]
            values = dict(telegram_user_id=user_id, principal_id=principal.principal_id, tenant_id=principal.tenant_id,
                          credential_hash=credential_hash, linked_at=self._clock(), expires_at=credentials["expires_at"],
                          session_ciphertext=self._box.seal(session, _context(chat_id, user_id, principal.tenant_id, credential_hash)))
            conn.execute(insert(L).values(chat_id=chat_id, **values).on_conflict_do_update(
                index_elements=[L.c.chat_id], set_=values,
            ))
            return {"chat_id": chat_id, "expires_at": credentials["expires_at"]}
        return await self._store.run(work, write=True)

    async def _read(self, chat_id: str, user_id: str) -> TelegramIdentity:
        if self._box is None:
            raise IdentityRejected()
        def work(conn: Connection) -> TelegramIdentity:
            row = conn.execute(select(L).where(L.c.chat_id == chat_id, L.c.telegram_user_id == user_id,
                                               L.c.expires_at > self._clock())).mappings().first()
            if row is None or conn.execute(self._valid_credentials(row["credential_hash"])).first() is None:
                raise IdentityRejected()
            try:
                session = self._box.open(row["session_ciphertext"], _context(chat_id, user_id, row["tenant_id"], row["credential_hash"]))
            except SealBroken:
                raise IdentityRejected() from None
            # Связь и credential проверяются независимо: перестановка строк/схем не даёт доступ.
            credentials = conn.execute(self._valid_credentials(row["credential_hash"])).mappings().one()
            if credentials["principal_id"] != row["principal_id"] or credentials["tenant_id"] != row["tenant_id"] or \
                    credentials["session_hash"] != digest(session):
                raise IdentityRejected()
            principal = Principal(row["tenant_id"], row["principal_id"], frozenset({row["tenant_id"]}))
            return TelegramIdentity(principal, session, row["credential_hash"], row["expires_at"])
        return await self._store.run(work)

    async def authenticate(self, chat_id: str, user_id: str) -> TelegramIdentity:
        before = await self._read(chat_id, user_id)
        if before.principal.tenant_id not in await self._autograph.schema_ids(before.session):
            raise SchemaForbidden()
        after = await self._read(chat_id, user_id)
        if after != before:
            raise IdentityRejected()
        return after

    async def list_links(self, principal: Principal) -> list[dict]:
        def work(conn: Connection) -> list[dict]:
            rows = conn.execute(select(L).where(L.c.principal_id == principal.principal_id,
                                                 L.c.tenant_id == principal.tenant_id, L.c.expires_at > self._clock())).mappings()
            return [dict(chat_id=r["chat_id"], expires_at=r["expires_at"]) for r in rows
                    if conn.execute(self._valid_credentials(r["credential_hash"], principal)).first() is not None]
        return await self._store.run(work)

    async def is_linked(self, chat_id: str) -> bool:
        def work(conn: Connection) -> bool:
            row = conn.execute(select(L.c.credential_hash).where(L.c.chat_id == chat_id, L.c.expires_at > self._clock())).first()
            return row is not None and conn.execute(self._valid_credentials(row.credential_hash)).first() is not None
        return await self._store.run(work)

    async def unlink(self, chat_id: str, user_id: str | None = None, principal: Principal | None = None) -> None:
        def work(conn: Connection) -> None:
            query = delete(L).where(L.c.chat_id == chat_id)
            if principal:
                query = query.where(L.c.principal_id == principal.principal_id, L.c.tenant_id == principal.tenant_id)
            if user_id:
                query = query.where(L.c.telegram_user_id == user_id)
                conn.execute(delete(Q).where(Q.c.chat_id == chat_id, Q.c.telegram_user_id == user_id))
            conn.execute(query)
        await self._store.run(work, write=True)
