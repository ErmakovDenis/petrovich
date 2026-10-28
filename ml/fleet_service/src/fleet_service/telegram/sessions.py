"""Сессия AutoGRAPH для чата Telegram: бот работает от имени пользователя по доступу фоновой проверки, который
приложение этого пользователя выдало стенду (background/access.py). Своего пароля или токена у бота нет.

Берётся самый свежий доступ пользователя в схеме: живой токен — как есть; истёк — с паролем (согласие пользователя)
стенд входит сам, как фоновая проверка; без пароля доступ ждёт нового токена от приложения.
"""

import logging

from ..autograph.client import AutoGraphClient
from ..autograph.errors import AutoGraphUnavailable, LoginRejected, SchemaForbidden, SessionInvalid
from ..autograph.session import AutoGraphSessionChecker
from ..background.access import AccessRepository
from ..background.scheduler import PASSWORD_REJECTED, TOKEN_EXPIRED
from .links import Link

log = logging.getLogger(__name__)

NO_ACCESS = ("Нет действующего доступа к AutoGRAPH. Откройте приложение «Петрович» — оно обновит доступ стенда "
             "(нужна «Фоновая проверка на стенде»; чтобы бот работал без телефона — «Разрешить стенду входить "
             "самостоятельно»).")


class NoAccess(Exception):
    def __init__(self, detail: str = NO_ACCESS):
        super().__init__(detail)


class TelegramSessions:
    def __init__(self, access: AccessRepository, checker: AutoGraphSessionChecker, client: AutoGraphClient):
        self._access = access
        self._checker = checker
        self._client = client

    async def session(self, link: Link) -> str:
        """Рабочий токен пользователя чата. NoAccess — доступа нет; AutoGraphUnavailable — AutoGRAPH не ответил."""
        for g in await self._access.of_user(link.schema_id, link.user_key):
            if g.token:
                try:
                    await self._checker.verify(g.token, link.schema_id)
                    return g.token
                except SessionInvalid:
                    await self._access.drop_token(g.device_id, None if g.password else TOKEN_EXPIRED)
                except SchemaForbidden:
                    continue
            if not g.password or not g.user_name:
                continue
            try:
                token = await self._client.login(g.user_name, g.password, g.utc_offset_minutes)
                await self._checker.verify(token, link.schema_id)
            except LoginRejected:
                log.warning("telegram: AutoGRAPH не принял сохранённый пароль пользователя чата — пароль удалён")
                await self._access.drop_password(g.device_id, PASSWORD_REJECTED)
                continue
            except (SessionInvalid, SchemaForbidden):
                continue
            await self._access.set_token(g.device_id, token)
            return token
        raise NoAccess()


__all__ = ["AutoGraphUnavailable", "NO_ACCESS", "NoAccess", "TelegramSessions"]
