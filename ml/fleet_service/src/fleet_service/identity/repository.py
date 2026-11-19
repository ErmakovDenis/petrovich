"""Короткие транзакции идентичности; пароли и исходные токены не сохраняются."""

import hashlib
import hmac
import secrets
from collections.abc import Callable
from dataclasses import dataclass, field
from uuid import uuid4

from sqlalchemy import Connection, delete, select, update
from sqlalchemy.dialects.sqlite import insert

from ..store.db import identity_accounts as M, identity_credentials as C, identity_telegram_links as T
from ..store.db import principal_scopes as S, principals as P
from ..store.repository import AnomalyRepository, now_ms
from .principal import IdentityRejected, Principal

CREDENTIAL_TTL_MS = 60 * 60 * 1000


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


@dataclass(frozen=True)
class BoundIdentity:
    principal: Principal
    credential: str = field(repr=False)
    expires_at: int


class IdentityRepository:
    def __init__(self, store: AnomalyRepository, clock: Callable[[], int] = now_ms):
        self._store = store
        self._clock = clock

    async def issue(self, provider: str, subject: str, tenant_id: str, session: str) -> BoundIdentity:
        at = self._clock()
        credential = "pvi_" + secrets.token_urlsafe(32)
        expires = at + CREDENTIAL_TTL_MS

        def work(conn: Connection) -> BoundIdentity:
            # Первая DML-операция получает блокировку SQLite и сериализует вход с rename/retire.
            conn.execute(update(M).where(M.c.provider == provider, M.c.login_hash == digest(subject))
                         .values(updated_at=M.c.updated_at))
            account = conn.execute(select(M).where(M.c.provider == provider, M.c.login_hash == digest(subject))).mappings().first()
            if account is None:
                principal_id = str(uuid4())
                conn.execute(insert(P).values(principal_id=principal_id, provider=provider,
                                              provider_subject=digest(str(uuid4())), created_at=at))
                conn.execute(insert(M).values(provider=provider, login_hash=digest(subject),
                                              principal_id=principal_id, updated_at=at))
            else:
                principal_id = account["principal_id"]
            row = conn.execute(select(P).where(P.c.principal_id == principal_id, P.c.provider == provider)).mappings().one()
            if row["disabled_at"] is not None:
                raise IdentityRejected()
            principal_id = row["principal_id"]
            conn.execute(insert(S).values(
                principal_id=principal_id, tenant_id=tenant_id, linked_at=at,
            ).on_conflict_do_nothing(index_elements=[S.c.principal_id, S.c.tenant_id]))
            scope = conn.execute(select(S).where(
                S.c.principal_id == principal_id, S.c.tenant_id == tenant_id,
            )).mappings().one()
            if scope["revoked_at"] is not None:
                raise IdentityRejected()
            conn.execute(insert(C).values(
                credential_hash=digest(credential), principal_id=principal_id, tenant_id=tenant_id,
                session_hash=digest(session), created_at=at, expires_at=expires,
            ))
            return BoundIdentity(Principal(tenant_id, principal_id, frozenset({tenant_id})), credential, expires)

        return await self._store.run(work, write=True)

    async def authenticate(self, credential: str, session: str, tenant_id: str, provider: str) -> Principal:
        def work(conn: Connection) -> Principal:
            at = self._clock()
            row = conn.execute(select(C.c.principal_id, C.c.session_hash).join(
                P, C.c.principal_id == P.c.principal_id,
            ).join(S, (S.c.principal_id == C.c.principal_id) & (S.c.tenant_id == C.c.tenant_id)).where(
                C.c.credential_hash == digest(credential), C.c.tenant_id == tenant_id,
                C.c.revoked_at.is_(None), C.c.expires_at > at,
                P.c.disabled_at.is_(None), P.c.provider == provider, S.c.revoked_at.is_(None),
            )).mappings().first()
            if row is None or not hmac.compare_digest(row["session_hash"], digest(session)):
                raise IdentityRejected()
            return Principal(tenant_id, row["principal_id"], frozenset({tenant_id}))

        return await self._store.run(work)

    async def revoke(self, credential: str) -> None:
        def work(conn: Connection) -> None:
            conn.execute(delete(T).where(T.c.credential_hash == digest(credential)))
            conn.execute(update(C).where(
                C.c.credential_hash == digest(credential), C.c.revoked_at.is_(None),
            ).values(revoked_at=self._clock()))
        await self._store.run(work, write=True)

    async def revoke_bound(self, credential: str, session: str, tenant_id: str, provider: str) -> None:
        """Отзыв собственных полномочий доступен без действующего AutoGRAPH и не раскрывает чужую пару."""
        def work(conn: Connection) -> None:
            matched = select(C.c.credential_hash).where(
                C.c.credential_hash == digest(credential), C.c.session_hash == digest(session),
                C.c.tenant_id == tenant_id,
                C.c.principal_id.in_(select(P.c.principal_id).where(P.c.provider == provider)),
            )
            conn.execute(delete(T).where(T.c.credential_hash.in_(matched)))
            conn.execute(update(C).where(C.c.credential_hash.in_(matched), C.c.revoked_at.is_(None))
                         .values(revoked_at=self._clock()))
        await self._store.run(work, write=True)
