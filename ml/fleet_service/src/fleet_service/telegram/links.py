"""Подключения чатов Telegram: одноразовые коды из приложения, чаты с подписками и отметки уведомлений.

Чат подключается двумя путями: ссылкой t.me/<бот>?start=<код> — код выдаёт приложение пользователя (он вошёл в
AutoGRAPH), бот передаёт его стенду вместе с id чата; или кодом из чата — бот показывает его в ответ на /start, а
пользователь вводит в приложении (Telegram на другом устройстве). Своих секретов у подключения нет: бот работает по доступу фоновой проверки,
который приложение этого пользователя уже выдало стенду (telegram/sessions.py). Коды хранятся только хэшем.
"""

import hashlib
import json
import secrets
import time
from collections.abc import Collection
from dataclasses import dataclass

from sqlalchemy import Connection, delete, func, insert, select, update

from ..background.access import user_key
from ..schemas.contract import Severity
from ..schemas.telegram import LinkView, Subscription
from ..store.db import telegram_link_codes as C
from ..store.db import anomalies as A
from ..store.db import telegram_links as L
from ..store.db import telegram_notifications as N
from ..store.db import telegram_pair_codes as P
from ..store.repository import AnomalyRepository


def now_ms() -> int:
    return int(time.time() * 1000)


def _hash(code: str) -> str:
    return hashlib.sha256(code.encode()).hexdigest()


# Код из чата набирают руками: без похожих символов (0/O, 1/I/L), 8 знаков — 32^8 ≈ 10^12 вариантов.
PAIR_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
PAIR_LENGTH = 8


def normalize_pair_code(code: str) -> str:
    """Код из чата как набран: регистр, пробелы и дефисы не важны."""
    return "".join(ch for ch in code.upper() if ch.isalnum())


def _pair_hash(code: str) -> str:
    return _hash("pair:" + normalize_pair_code(code))


@dataclass(frozen=True)
class Link:
    chat_id: str
    schema_id: str
    schema_name: str
    user_name: str
    utc_offset_minutes: int
    subscription: Subscription
    linked_at: int
    access_warned_at: int | None
    title: str | None = None

    @property
    def user_key(self) -> str:
        return user_key(self.user_name, "")

    @classmethod
    def of(cls, row) -> "Link":  # noqa: ANN001 — строка SQLAlchemy
        return cls(
            chat_id=row.chat_id, schema_id=row.schema_id, schema_name=row.schema_name, user_name=row.user_name,
            utc_offset_minutes=row.utc_offset_minutes,
            subscription=Subscription.model_validate(json.loads(row.settings)),
            linked_at=row.linked_at, access_warned_at=row.access_warned_at, title=row.title,
        )

    def view(self) -> LinkView:
        return LinkView(
            chat_id=self.chat_id, schema_id=self.schema_id, schema_name=self.schema_name, user_name=self.user_name,
            utc_offset_minutes=self.utc_offset_minutes, subscription=self.subscription, linked_at=self.linked_at,
            title=self.title,
        )


# Одно и то же событие на машине: машина, тип, параметр.
Topic = tuple[str, str, str]


def _settings_json(subscription: Subscription) -> str:
    return subscription.model_dump_json(by_alias=True)


class TelegramLinks:
    def __init__(self, store: AnomalyRepository):
        """База — та же, что у хранилища аномалий, запись — под его замком."""
        self._run = store.run

    # --- коды подключения ---

    async def create_code(self, schema_id: str, schema_name: str, user_name: str, utc_offset_minutes: int,
                          ttl_ms: int, at: int | None = None) -> tuple[str, int]:
        """Новый одноразовый код и срок его действия; просроченные коды удаляются."""
        at = at or now_ms()
        # 16 байт: в ссылке Telegram допустимы A-Z a-z 0-9 _ - и не больше 64 символов.
        code = secrets.token_urlsafe(16)
        expires = at + ttl_ms

        def work(conn: Connection) -> None:
            conn.execute(delete(C).where(C.c.expires_at < at))
            conn.execute(insert(C).values(code_hash=_hash(code), schema_id=schema_id, schema_name=schema_name,
                                          user_name=user_name, utc_offset_minutes=utc_offset_minutes,
                                          expires_at=expires))

        await self._run(work, write=True)
        return code, expires

    async def redeem(self, code: str, chat_id: str, title: str | None = None, at: int | None = None) -> Link | None:
        """Подключить чат по коду из ссылки (код гасится). None — код неизвестен или просрочен."""
        at = at or now_ms()

        def work(conn: Connection) -> Link | None:
            row = conn.execute(select(C).where(C.c.code_hash == _hash(code))).first()
            if row is None:
                return None
            conn.execute(delete(C).where(C.c.code_hash == row.code_hash))
            if row.expires_at < at:
                return None
            return _bind(conn, chat_id, title, row.schema_id, row.schema_name, row.user_name, row.utc_offset_minutes, at)

        return await self._run(work, write=True)

    async def create_pair_code(self, chat_id: str, title: str | None, ttl_ms: int,
                               at: int | None = None) -> tuple[str, int]:
        """Код из чата для ввода в приложении (XXXX-XXXX) и срок его действия. Прежний код этого чата гасится."""
        at = at or now_ms()
        raw = "".join(secrets.choice(PAIR_ALPHABET) for _ in range(PAIR_LENGTH))
        expires = at + ttl_ms

        def work(conn: Connection) -> None:
            conn.execute(delete(P).where((P.c.expires_at < at) | (P.c.chat_id == chat_id)))
            conn.execute(insert(P).values(code_hash=_pair_hash(raw), chat_id=chat_id, title=title, expires_at=expires))

        await self._run(work, write=True)
        return f"{raw[:4]}-{raw[4:]}", expires

    async def pair(self, code: str, schema_id: str, schema_name: str, user_name: str, utc_offset_minutes: int,
                   at: int | None = None) -> Link | None:
        """Подключить чат по коду из чата, введённому в приложении (код гасится). None — код неизвестен или просрочен."""
        at = at or now_ms()

        def work(conn: Connection) -> Link | None:
            row = conn.execute(select(P).where(P.c.code_hash == _pair_hash(code))).first()
            if row is None:
                return None
            conn.execute(delete(P).where(P.c.code_hash == row.code_hash))
            if row.expires_at < at:
                return None
            return _bind(conn, row.chat_id, row.title, schema_id, schema_name, user_name, utc_offset_minutes, at)

        return await self._run(work, write=True)

    # --- подключения ---

    async def get(self, chat_id: str) -> Link | None:
        row = await self._run(lambda conn: conn.execute(select(L).where(L.c.chat_id == chat_id)).first())
        return Link.of(row) if row is not None else None

    async def of_user(self, schema_id: str, user_name: str) -> list[Link]:
        """Чаты, подключённые пользователем [user_name] к схеме (логин — без учёта регистра)."""
        key = user_key(user_name, "")
        rows = await self._run(lambda conn: conn.execute(
            select(L).where(L.c.schema_id == schema_id).order_by(L.c.linked_at)).all())
        return [link for link in map(Link.of, rows) if link.user_key == key]

    async def all(self) -> list[Link]:
        rows = await self._run(lambda conn: conn.execute(select(L).order_by(L.c.chat_id)).all())
        return [Link.of(r) for r in rows]

    async def set_subscription(self, chat_id: str, subscription: Subscription) -> Link | None:
        def work(conn: Connection) -> Link | None:
            changed = conn.execute(update(L).where(L.c.chat_id == chat_id)
                                   .values(settings=_settings_json(subscription))).rowcount
            return Link.of(conn.execute(select(L).where(L.c.chat_id == chat_id)).one()) if changed else None

        return await self._run(work, write=True)

    async def delete(self, chat_id: str) -> bool:
        def work(conn: Connection) -> bool:
            conn.execute(delete(N).where(N.c.chat_id == chat_id))
            return conn.execute(delete(L).where(L.c.chat_id == chat_id)).rowcount > 0

        return await self._run(work, write=True)

    async def set_access_warned(self, chat_id: str, at: int | None) -> None:
        await self._run(lambda conn: conn.execute(
            update(L).where(L.c.chat_id == chat_id).values(access_warned_at=at)), write=True)

    # --- уведомления ---

    async def seen(self, chat_id: str, anomaly_ids: Collection[str]) -> set[str]:
        """Какие из [anomaly_ids] уже отправлены в чат или пропущены по подписке."""
        ids = list(anomaly_ids)
        if not ids:
            return set()
        return set(await self._run(lambda conn: conn.execute(
            select(N.c.anomaly_id).where(N.c.chat_id == chat_id, N.c.anomaly_id.in_(ids))).scalars().all()))

    async def delivered_since(self, chat_id: str, since_ms: int) -> int:
        """Сколько некритичных уведомлений ушло в чат с [since_ms] — для лимита в день."""
        return await self._run(lambda conn: conn.execute(
            select(func.count()).select_from(N).where(
                N.c.chat_id == chat_id, N.c.delivered.is_(True), N.c.severity != Severity.CRITICAL.value,
                N.c.at >= since_ms)).scalar_one())

    async def record(self, chat_id: str, items: list[tuple[str, str, bool, bool]], at: int | None = None) -> None:
        """Отметить решение по событиям: (id, важность, отправлено ли в чат, пропущено ли как повтор)."""
        if not items:
            return
        at = at or now_ms()
        await self._run(lambda conn: conn.execute(insert(N), [
            {"chat_id": chat_id, "anomaly_id": i, "severity": s, "delivered": d, "repeat": r, "at": at}
            for i, s, d, r in items
        ]), write=True)

    async def history(self, chat_id: str, schema_id: str, vehicle_ids: Collection[str], since_ms: int) \
            -> list[tuple[Topic, int, bool, bool]]:
        """Отметки чата с [since_ms] по событиям этих машин: (машина, тип, параметр), когда, отправлено, повтор —
        для антиспама повторов. Сначала ранние."""
        ids = list(vehicle_ids)
        if not ids:
            return []
        rows = await self._run(lambda conn: conn.execute(
            select(A.c.vehicle_id, A.c.kind, A.c.parameter_name, N.c.at, N.c.delivered, N.c.repeat)
            .select_from(N.join(A, (A.c.id == N.c.anomaly_id) & (A.c.schema_id == schema_id)))
            .where(N.c.chat_id == chat_id, N.c.at >= since_ms, A.c.vehicle_id.in_(ids))
            .order_by(N.c.at, N.c.delivered.desc())).all())
        return [((r.vehicle_id, r.kind, r.parameter_name), r.at, r.delivered, r.repeat) for r in rows]

    async def purge_notifications(self, border_ms: int) -> int:
        return await self._run(
            lambda conn: conn.execute(delete(N).where(N.c.at < border_ms)).rowcount, write=True
        )


def _bind(conn: Connection, chat_id: str, title: str | None, schema_id: str, schema_name: str, user_name: str,
          utc_offset_minutes: int, at: int) -> Link:
    """Подключить чат к схеме и пользователю. Чат, уже подключённый тем же пользователем к той же схеме, сохраняет
    подписку; иначе подписка — по умолчанию, а прежние отметки уведомлений удаляются."""
    old = conn.execute(select(L).where(L.c.chat_id == chat_id)).first()
    same = old is not None and old.schema_id == schema_id and user_key(old.user_name, "") == user_key(user_name, "")
    values = {
        "schema_id": schema_id, "schema_name": schema_name, "user_name": user_name,
        "utc_offset_minutes": utc_offset_minutes,
        "settings": old.settings if same else _settings_json(Subscription()),
        "linked_at": old.linked_at if same else at, "access_warned_at": None,
        "title": title if title is not None else (old.title if old is not None else None),
    }
    if old is None:
        conn.execute(insert(L).values(chat_id=chat_id, **values))
    else:
        conn.execute(update(L).where(L.c.chat_id == chat_id).values(**values))
        if not same:
            # Другая схема или пользователь: прежние отметки уведомлений к новым событиям не относятся.
            conn.execute(delete(N).where(N.c.chat_id == chat_id))
    return Link.of(conn.execute(select(L).where(L.c.chat_id == chat_id)).one())
