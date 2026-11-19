"""Login доказывает личность, EnumSchemas независимо подтверждает текущее право на схему."""

from dataclasses import dataclass, field

from ..autograph.client import AutoGraphClient
from ..autograph.errors import SchemaForbidden
from .principal import Principal
from .repository import BoundIdentity, IdentityRepository, digest


@dataclass(frozen=True)
class Enrollment:
    identity: BoundIdentity
    session: str = field(repr=False)


class IdentityService:
    def __init__(self, repository: IdentityRepository, autograph: AutoGraphClient, base_url: str):
        self._repository = repository
        self._autograph = autograph
        # Две установки AutoGRAPH не разделяют аккаунты с одинаковым логином.
        self._provider = digest(base_url.rstrip("/"))

    async def bind(self, username: str, password: str, schema_id: str, offset: int) -> Enrollment:
        session = await self._autograph.login(username, password, offset)
        if schema_id not in await self._autograph.schema_ids(session):
            raise SchemaForbidden()
        # Логин проверен Login. Не нормализуем регистр/Unicode: нельзя склеить разные аккаунты.
        bound = await self._repository.issue(self._provider, username, schema_id, session)
        return Enrollment(bound, session)

    async def authenticate(self, credential: str, session: str, schema_id: str) -> Principal:
        await self._repository.authenticate(credential, session, schema_id, self._provider)
        # Без положительного auth-кэша: отзыв схемы/сессии не обходим старыми правами.
        if schema_id not in await self._autograph.schema_ids(session):
            raise SchemaForbidden()
        # Отзыв credential мог произойти во время внешнего запроса.
        return await self._repository.authenticate(credential, session, schema_id, self._provider)

    async def revoke(self, credential: str) -> None:
        await self._repository.revoke(credential)

    async def revoke_bound(self, credential: str, session: str, schema_id: str) -> None:
        await self._repository.revoke_bound(credential, session, schema_id, self._provider)
