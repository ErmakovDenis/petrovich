"""Доступ стенда к AutoGRAPH для фоновой проверки и отметки показанных уведомлений.

Решение владельца проекта (шаг 5): по умолчанию стенд работает на токене сессии, который приложение присылает при
каждом обращении, пока токен жив. Пароль стенд хранит, только если пользователь включил «Разрешить стенду входить
самостоятельно»; тогда стенд сам входит в AutoGRAPH, когда токен истёк. Токен и пароль лежат в базе шифротекстом
(crypto.py) и в логи не попадают.

Доступ привязан к установке приложения (случайный id устройства): одна запись на устройство, повторная выдача
заменяет её, отзыв удаляет. Логин пользователя — из X-User-Name; для доступа по паролю он проверен входом.
"""

import time
from collections.abc import Collection
from dataclasses import dataclass
from enum import Enum

from sqlalchemy import Connection, delete, insert, or_, select, update

from ..store.db import background_access as B
from ..store.db import notification_claims as N
from ..store.repository import AnomalyRepository
from .crypto import SealBroken, SecretBox

KEY_CHANGED = "Ключ шифрования стенда сменился — доступ выдаётся заново при следующем обращении приложения"


class PasswordAction(Enum):
    KEEP = "keep"
    CLEAR = "clear"
    SET = "set"


@dataclass(frozen=True)
class Grant:
    """Доступ одного устройства; token и password — расшифрованные (None — нет или истёк)."""

    device_id: str
    schema_id: str
    user_name: str | None
    utc_offset_minutes: int
    token: str | None
    token_since: int | None
    password: str | None
    registered_at: int
    refreshed_at: int
    last_ok_at: int | None
    last_error: str | None

    @property
    def user_key(self) -> str:
        """Пользователь для группировки: логин без учёта регистра, без логина — устройство."""
        return user_key(self.user_name, self.device_id)


def user_key(user_name: str | None, device_id: str) -> str:
    return user_name.strip().lower() if user_name and user_name.strip() else f"device:{device_id}"


def now_ms() -> int:
    return int(time.time() * 1000)


class AccessRepository:
    def __init__(self, store: AnomalyRepository, box: SecretBox | None):
        """База — та же, что у хранилища аномалий, запись — под его замком. [box] = None — ключ шифрования не задан:
        доступ не выдаётся, работают только отзыв и уведомления."""
        self._run = store.run
        self._box = box

    @property
    def can_store(self) -> bool:
        return self._box is not None

    def _seal(self, value: str | None, device_id: str, field: str) -> str | None:
        if value is None:
            return None
        if self._box is None:
            raise RuntimeError("ключ шифрования доступа не задан")
        return self._box.seal(value, f"{device_id}|{field}")

    def _open(self, sealed: str | None, device_id: str, field: str) -> str | None:
        if sealed is None:
            return None
        if self._box is None:
            raise SealBroken()
        return self._box.open(sealed, f"{device_id}|{field}")

    def _grant(self, conn: Connection, row) -> Grant:  # noqa: ANN001 — строка SQLAlchemy
        """Строка → Grant; не расшифровать (сменили ключ) — секреты стираются, причина — в last_error.
        Ключ не задан вовсе — секретов нет, но в базе они не стираются (вернут ключ — заработают)."""
        try:
            token = self._open(row.token_enc, row.device_id, "token")
            password = self._open(row.password_enc, row.device_id, "password")
            error = row.last_error
        except SealBroken:
            token = password = None
            error = KEY_CHANGED
            if self._box is not None:
                conn.execute(update(B).where(B.c.device_id == row.device_id)
                             .values(token_enc=None, token_since=None, password_enc=None, last_error=error))
        return Grant(
            device_id=row.device_id, schema_id=row.schema_id, user_name=row.user_name,
            utc_offset_minutes=row.utc_offset_minutes, token=token, token_since=row.token_since if token else None,
            password=password, registered_at=row.registered_at, refreshed_at=row.refreshed_at,
            last_ok_at=row.last_ok_at, last_error=error,
        )

    async def upsert(
        self, device_id: str, schema_id: str, user_name: str | None, utc_offset_minutes: int, token: str,
        password_action: PasswordAction, password: str | None = None, at: int | None = None,
    ) -> Grant:
        """Выдать или обновить доступ устройства. Другая схема или другой логин — сохранённый пароль стирается
        (он от прежней учётной записи), если вместе с ними не передан новый."""
        at = at or now_ms()

        def work(conn: Connection) -> Grant:
            row = conn.execute(select(B).where(B.c.device_id == device_id)).first()
            old = self._grant(conn, row) if row is not None else None
            same_owner = old is not None and old.schema_id == schema_id and \
                user_key(old.user_name, device_id) == user_key(user_name, device_id)
            if password_action is PasswordAction.SET:
                new_password = password
            elif password_action is PasswordAction.KEEP and same_owner:
                new_password = old.password
            else:
                new_password = None
            values = {
                "schema_id": schema_id, "user_name": user_name, "utc_offset_minutes": utc_offset_minutes,
                "token_enc": self._seal(token, device_id, "token"),
                # Тот же токен — срок его жизни считается от первой выдачи.
                "token_since": old.token_since if old is not None and old.token == token and old.token_since else at,
                "password_enc": self._seal(new_password, device_id, "password"),
                "refreshed_at": at,
                # Свежий доступ — прежняя ошибка (истёк токен, не подошёл пароль) больше не относится к делу.
                "last_error": None,
            }
            if row is None:
                conn.execute(insert(B).values(device_id=device_id, registered_at=at, last_ok_at=None, **values))
            else:
                conn.execute(update(B).where(B.c.device_id == device_id).values(**values))
            return self._grant(conn, conn.execute(select(B).where(B.c.device_id == device_id)).one())

        return await self._run(work, write=True)

    async def get(self, device_id: str) -> Grant | None:
        def work(conn: Connection) -> Grant | None:
            row = conn.execute(select(B).where(B.c.device_id == device_id)).first()
            return self._grant(conn, row) if row is not None else None

        return await self._run(work, write=True)

    async def delete(self, device_id: str) -> bool:
        return await self._run(
            lambda conn: conn.execute(delete(B).where(B.c.device_id == device_id)).rowcount > 0, write=True
        )

    async def usable(self) -> list[Grant]:
        """Доступы, с которыми можно войти: есть токен или пароль."""
        def work(conn: Connection) -> list[Grant]:
            rows = conn.execute(select(B).where(or_(B.c.token_enc.is_not(None), B.c.password_enc.is_not(None)))).all()
            return [g for g in (self._grant(conn, r) for r in rows) if g.token or g.password]

        return await self._run(work, write=True)

    async def set_token(self, device_id: str, token: str, at: int | None = None) -> None:
        """Токен от входа стенда по паролю."""
        at = at or now_ms()
        await self._run(lambda conn: conn.execute(update(B).where(B.c.device_id == device_id).values(
            token_enc=self._seal(token, device_id, "token"), token_since=at)), write=True)

    async def drop_token(self, device_id: str, error: str | None) -> None:
        await self._run(lambda conn: conn.execute(update(B).where(B.c.device_id == device_id).values(
            token_enc=None, token_since=None, last_error=error)), write=True)

    async def drop_password(self, device_id: str, error: str) -> None:
        await self._run(lambda conn: conn.execute(update(B).where(B.c.device_id == device_id).values(
            password_enc=None, last_error=error)), write=True)

    async def mark(self, device_ids: Collection[str], ok: bool, error: str | None = None,
                   at: int | None = None) -> None:
        """Итог фоновой проверки для доступов: сработал (ok) или нет (error)."""
        if not device_ids:
            return
        values = {"last_ok_at": at or now_ms(), "last_error": None} if ok else {"last_error": error}
        await self._run(lambda conn: conn.execute(
            update(B).where(B.c.device_id.in_(list(device_ids))).values(**values)), write=True)

    async def purge_stale(self, border_ms: int) -> int:
        """Удаляет доступ, который приложение не подтверждало с [border_ms]."""
        return await self._run(
            lambda conn: conn.execute(delete(B).where(B.c.refreshed_at < border_ms)).rowcount, write=True
        )

    # --- уведомления ---

    async def claim(self, schema_id: str, key: str, device_id: str, anomaly_ids: list[str],
                    at: int | None = None) -> list[str]:
        """Отмечает аномалии как показанные пользователю [key]; возвращает те, что ещё не были показаны
        (на этом или другом его устройстве), в порядке запроса."""
        at = at or now_ms()
        ids = list(dict.fromkeys(anomaly_ids))

        def work(conn: Connection) -> list[str]:
            if not ids:
                return []
            seen = set(conn.execute(select(N.c.anomaly_id).where(
                N.c.schema_id == schema_id, N.c.user_key == key, N.c.anomaly_id.in_(ids))).scalars())
            fresh = [i for i in ids if i not in seen]
            if fresh:
                conn.execute(insert(N), [{"schema_id": schema_id, "user_key": key, "anomaly_id": i,
                                          "device_id": device_id, "claimed_at": at} for i in fresh])
            return fresh

        return await self._run(work, write=True)

    async def purge_claims(self, border_ms: int) -> int:
        return await self._run(
            lambda conn: conn.execute(delete(N).where(N.c.claimed_at < border_ms)).rowcount, write=True
        )

