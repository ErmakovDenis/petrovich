"""Письма: черновик готовит ассистент (tool draft_email), отправляет стенд — только по подтверждению пользователя
в приложении (`POST /v1/emails/{id}/confirm`). Вызова, которым модель могла бы отправить письмо, нет.

- Получатели — только из файла получателей (recipients.py), по id; адрес в свободной форме не принимается.
- Черновик живёт FS_EMAIL_DRAFT_TTL_MINUTES и принадлежит создавшему его пользователю (схема и логин); id
  черновика — случайный, его знает только приложение пользователя.
- Подтвердить можно один раз: статус draft → sending меняется одним UPDATE, второй запрос его уже не застанет.
  Ошибка SMTP возвращает черновик в draft (письмо не отправлено, можно повторить); если письмо ушло не всем,
  черновик закрывается (failed) — повтор дал бы дубли.
- Не больше FS_EMAIL_LIMIT_PER_USER отправленных писем на пользователя за FS_EMAIL_LIMIT_PERIOD_HOURS.
- Журнал email_log: каждый черновик, отправка и отказ — кто, кому, когда, тема, итог. Текст письма и адреса не
  пишутся ни в журнал, ни в общий лог.
"""

import hashlib
import logging
import secrets
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime

from sqlalchemy import Connection, and_, delete, func, insert, or_, select, update

from ..background.access import now_ms, user_key
from ..config import Settings
from ..store.db import email_drafts as E
from ..store.db import email_log as L
from ..store.repository import AnomalyRepository
from .recipients import ID, Recipient, Recipients
from .sender import SendFailed, SmtpSender

log = logging.getLogger(__name__)

# Черновики (с текстом письма) удаляются через столько после истечения срока; журнал остаётся.
_DRAFT_KEEP_MS = 7 * 24 * 3600 * 1000


class EmailRefused(Exception):
    """Отказ с текстом для пользователя; [status] — HTTP-код ответа API."""

    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


@dataclass(frozen=True)
class Draft:
    id: str
    schema_id: str
    user_name: str
    recipients: list[Recipient]
    subject: str
    body: str
    created_at: int
    expires_at: int
    status: str
    sent_at: int | None = None
    recipients_hash: str = ""


class EmailService:
    def __init__(self, settings: Settings, recipients: Recipients, sender: SmtpSender, store: AnomalyRepository,
                 clock: Callable[[], int] = now_ms):
        """[clock] — текущее время, epoch millis (в тестах — подставное)."""
        self._s = settings
        self._recipients = recipients
        self._sender = sender
        self._run = store.run
        self.clock = clock

    def recipients(self, schema_id: str) -> list[Recipient]:
        return self._recipients.for_schema(schema_id)

    # --- черновик ---

    async def draft(self, schema_id: str, user_name: str | None, recipient_ids: object, subject: object,
                    body: object) -> Draft:
        """Черновик от ассистента. Неверные получатели, тема, текст или лимит — EmailRefused (ошибка tool)."""
        s = self._s
        name = (user_name or "").strip()
        ids = recipient_ids if isinstance(recipient_ids, list) else []
        shown = ", ".join(_safe_id(i) for i in ids) or "—"
        subject_text = subject.strip() if isinstance(subject, str) else ""

        async def refuse(message: str) -> EmailRefused:
            await self._journal(schema_id, name or None, None, shown, subject_text, "rejected", message)
            return EmailRefused(422, message)

        if not name:
            raise await refuse("письмо может подготовить только пользователь с логином AutoGRAPH (X-User-Name)")
        if not isinstance(recipient_ids, list) or not ids:
            raise await refuse("recipient_ids — непустой список id из list_recipients")
        if len(ids) > s.email_max_recipients:
            raise await refuse(f"не больше {s.email_max_recipients} получателей в одном письме")
        recipients: list[Recipient] = []
        for raw in ids:
            if not isinstance(raw, str) or "@" in raw or not ID.match(raw):
                raise await refuse("адрес в свободной форме не принимается: получатели — только id из list_recipients")
            r = self._recipients.get(raw, schema_id)
            if r is None:
                raise await refuse(f"неизвестный получатель {raw}: получатели — только id из list_recipients")
            if r not in recipients:
                recipients.append(r)
        if not subject_text or len(subject_text) > s.email_max_subject_chars:
            raise await refuse(f"тема — от 1 до {s.email_max_subject_chars} символов")
        if any(ord(ch) < 32 for ch in subject_text):
            raise await refuse("тема — одна строка, без переводов строки")
        body_text = body.strip() if isinstance(body, str) else ""
        if not body_text or len(body_text) > s.email_max_body_chars:
            raise await refuse(f"текст — от 1 до {s.email_max_body_chars} символов")
        limit = await self._run(lambda conn: self._limit_reached(conn, schema_id, name))
        if limit:
            await self._journal(schema_id, name, None, _names(recipients), subject_text, "limit", limit)
            raise EmailRefused(429, limit)

        now = self.clock()
        draft = Draft(
            id=secrets.token_urlsafe(18), schema_id=schema_id, user_name=name, recipients=recipients,
            subject=subject_text, body=body_text, created_at=now,
            expires_at=now + s.email_draft_ttl_minutes * 60_000, status="draft",
        )

        def work(conn: Connection) -> None:
            conn.execute(delete(E).where(E.c.expires_at < now - _DRAFT_KEEP_MS))
            conn.execute(insert(E).values(
                id=draft.id, schema_id=schema_id, user_key=user_key(name, ""), user_name=name,
                recipient_ids=",".join(r.id for r in recipients), recipients_hash=_fingerprint(recipients),
                subject=draft.subject, body=draft.body,
                created_at=now, expires_at=draft.expires_at, status="draft", sent_at=None,
            ))

        await self._run(work, write=True)
        await self._journal(schema_id, name, draft.id, _names(recipients), draft.subject, "drafted")
        log.info("письма: схема %s, черновик %s…, получателей %d", schema_id, draft.id[:6], len(recipients))
        return draft

    # --- подтверждение и отмена (только из приложения) ---

    async def confirm(self, draft_id: str, schema_id: str, user_name: str | None) -> Draft:
        """Отправить черновик по подтверждению пользователя. Отказ — EmailRefused с причиной, записан в журнал."""
        draft = await self._owned(draft_id, schema_id, user_name)
        names = _names(draft.recipients)
        if draft.status != "draft":
            raise await self._refuse(draft, "repeat", 409, _status_text(draft.status))
        if self.clock() >= draft.expires_at:
            raise await self._refuse(draft, "expired", 410,
                                     "Срок черновика истёк — попросите ассистента подготовить письмо заново")
        # Получатель мог пропасть из файла или сменить адрес после подготовки: пользователь видел прежние адреса.
        current = [self._recipients.get(r.id, schema_id) for r in draft.recipients]
        if any(r is None for r in current):
            raise await self._refuse(draft, "rejected", 409,
                                     "Получателя больше нет в списке получателей — подготовьте письмо заново")
        if _fingerprint(current) != draft.recipients_hash:
            raise await self._refuse(draft, "rejected", 409,
                                     "Адрес получателя изменился после подготовки черновика — подготовьте письмо заново")

        def take(conn: Connection) -> str | None:
            """Лимит и взятие черновика в отправку — одной транзакцией под замком записи: параллельные
            подтверждения разных черновиков не обойдут лимит (отправляемые тоже считаются)."""
            if (limit := self._limit_reached(conn, schema_id, draft.user_name)) is not None:
                return limit
            done = conn.execute(update(E).where(E.c.id == draft.id, E.c.status == "draft").values(status="sending"))
            return None if done.rowcount == 1 else "repeat"

        outcome = await self._run(take, write=True)
        if outcome == "repeat":
            raise await self._refuse(draft, "repeat", 409, "Письмо уже отправляется или отправлено")
        if outcome is not None:
            raise await self._refuse(draft, "limit", 429, outcome)
        footer = f"\n\n—\nПодготовлено ассистентом «Петрович» по запросу пользователя {draft.user_name}."
        try:
            await self._sender.send([r.email for r in current if r], draft.subject, draft.body + footer)
        except SendFailed as e:
            status = "failed" if e.partial else "draft"
            await self._set_status(draft.id, status)
            await self._journal(schema_id, draft.user_name, draft.id, names, draft.subject, "failed", str(e))
            log.warning("письма: схема %s, черновик %s… не отправлен: %s", schema_id, draft.id[:6], e)
            raise EmailRefused(502, f"Письмо не отправлено: {e}") from None
        except BaseException:
            # Неожиданный сбой (или отмена запроса) до отправки — черновик не должен застрять в «отправляется».
            await self._set_status(draft.id, "draft")
            raise
        sent_at = self.clock()
        await self._run(lambda conn: conn.execute(
            update(E).where(E.c.id == draft.id).values(status="sent", sent_at=sent_at)), write=True)
        await self._journal(schema_id, draft.user_name, draft.id, names, draft.subject, "sent")
        log.info("письма: схема %s, черновик %s… отправлен, получателей %d", schema_id, draft.id[:6], len(current))
        return Draft(**{**draft.__dict__, "status": "sent", "sent_at": sent_at})

    async def cancel(self, draft_id: str, schema_id: str, user_name: str | None) -> Draft:
        draft = await self._owned(draft_id, schema_id, user_name)
        if draft.status == "cancelled":
            return draft

        def work(conn: Connection) -> bool:
            done = conn.execute(update(E).where(E.c.id == draft.id, E.c.status == "draft").values(status="cancelled"))
            return done.rowcount == 1

        if not await self._run(work, write=True):
            raise await self._refuse(draft, "repeat", 409, _status_text(draft.status))
        await self._journal(schema_id, draft.user_name, draft.id, _names(draft.recipients), draft.subject, "cancelled")
        return Draft(**{**draft.__dict__, "status": "cancelled"})

    # --- внутреннее ---

    async def _set_status(self, draft_id: str, status: str) -> None:
        await self._run(lambda conn: conn.execute(update(E).where(E.c.id == draft_id).values(status=status)),
                        write=True)

    async def _owned(self, draft_id: str, schema_id: str, user_name: str | None) -> Draft:
        """Черновик этого пользователя; чужой или несуществующий — 404 (не раскрываем, что он есть)."""
        def work(conn: Connection):  # noqa: ANN202
            return conn.execute(select(E).where(E.c.id == draft_id)).first()

        row = await self._run(work)
        if row is None or row.schema_id != schema_id or row.user_key != user_key(user_name, ""):
            await self._journal(schema_id, user_name, draft_id[:64], "—", "—", "not_found",
                                "черновик не найден или принадлежит другому пользователю")
            raise EmailRefused(404, "Черновик не найден")
        # Получатели — из текущего файла; удалённый получатель — как «нет в списке» при отправке.
        recipients = [self._recipients.get(i, schema_id) or Recipient(i, i, "", "") for i in row.recipient_ids.split(",")]
        return Draft(id=row.id, schema_id=row.schema_id, user_name=row.user_name, recipients=recipients,
                     subject=row.subject, body=row.body, created_at=row.created_at, expires_at=row.expires_at,
                     status=row.status, sent_at=row.sent_at, recipients_hash=row.recipients_hash)

    def _limit_reached(self, conn: Connection, schema_id: str, name: str) -> str | None:
        """Текст отказа, если лимит пользователя или схемы исчерпан (отправленные за период и отправляемые)."""
        s = self._s
        period = s.email_limit_period_hours * 3_600_000
        since = self.clock() - period
        counted = and_(E.c.schema_id == schema_id,
                       or_(and_(E.c.status == "sent", E.c.sent_at >= since), E.c.status == "sending"))
        for where, limit, whose in (
            (and_(counted, E.c.user_key == user_key(name, "")), s.email_limit_per_user, "на пользователя"),
            (counted, s.email_limit_per_schema, "на схему"),
        ):
            count, oldest = conn.execute(select(func.count(), func.min(E.c.sent_at)).where(where)).one()
            if count >= limit:
                free_at = datetime.fromtimestamp(((oldest or self.clock()) + period) / 1000, UTC)
                return (f"Лимит писем исчерпан: не больше {limit} за {s.email_limit_period_hours} ч {whose}. "
                        f"Следующее можно будет отправить после {free_at:%d.%m %H:%M} UTC")
        return None

    async def _refuse(self, draft: Draft, outcome: str, status: int, message: str) -> EmailRefused:
        await self._journal(draft.schema_id, draft.user_name, draft.id, _names(draft.recipients), draft.subject,
                            outcome, message)
        return EmailRefused(status, message)

    async def _journal(self, schema_id: str, user_name: str | None, draft_id: str | None, recipients: str,
                       subject: str, outcome: str, error: str | None = None) -> None:
        at = self.clock()
        await self._run(lambda conn: conn.execute(insert(L).values(
            at=at, schema_id=schema_id, user_name=user_name, draft_id=draft_id, recipients=recipients,
            subject=subject[:500], outcome=outcome, error=error)), write=True)

    async def journal(self, schema_id: str, limit: int = 100) -> list[dict]:
        """Последние записи журнала схемы (для тестов и разбора)."""
        def work(conn: Connection) -> list[dict]:
            rows = conn.execute(select(L).where(L.c.schema_id == schema_id).order_by(L.c.id.desc()).limit(limit)).all()
            return [dict(r._mapping) for r in rows]

        return await self._run(work)


def _fingerprint(recipients: list[Recipient | None]) -> str:
    return hashlib.sha256(";".join(f"{r.id}={r.email}" for r in recipients if r).encode()).hexdigest()


def _names(recipients: list[Recipient]) -> str:
    return ", ".join(f"{r.id} ({r.name})" for r in recipients)


def _safe_id(raw: object) -> str:
    """id для журнала: похожее на адрес не пишется (модель могла подставить адрес из данных)."""
    return raw if isinstance(raw, str) and ID.match(raw) else "<не id>"


def _status_text(status: str) -> str:
    return {
        "sent": "Письмо уже отправлено",
        "sending": "Письмо уже отправляется",
        "cancelled": "Черновик отменён",
        "failed": "Письмо ушло не всем получателям — подготовьте новое для остальных",
    }.get(status, "Черновик нельзя отправить")
