"""Уведомления в Telegram: какие события отправить в какой чат.

Бот раз в несколько секунд забирает выдачу (`POST /v1/telegram/bot/outbox`); стенд сам решает по подписке чата и
отмечает решение в базе — одно событие уходит в чат один раз. Новые события находит фоновая проверка стенда
(background/scheduler.py), здесь они только раздаются:
- события машин, видимых пользователю чата (EnumDevices по его доступу) и выбранных в подписке, ждущие решения,
  найденные впервые после подключения и не раньше FS_TELEGRAM_NOTIFY_MAX_AGE_HOURS назад;
- «Критично» — всегда; предупреждения и инфо — если включены, не в тихие часы и в пределах лимита на местные
  сутки. Выключенные события (muted_kinds) не приходят совсем, даже критичные. Повтор того же
  события на машине чаще раза в repeat_hours пропускается, а в следующей карточке о нём — «повторялось ещё N раз».
  Пропущенное по подписке потом не досылается (как уведомления приложения);
- не больше FS_TELEGRAM_NOTIFY_MAX_PER_CHAT карточек за выдачу, остальные — одной строкой «ещё N».
Нет доступа к AutoGRAPH — чат получает предупреждение не чаще раза в FS_TELEGRAM_ACCESS_WARNING_HOURS.
Утренний доклад и недельная сводка уходят с той же выдачей, когда пришло их время (telegram/reports.py).
"""

import asyncio
import logging
from collections.abc import Callable
from datetime import UTC, datetime, timedelta

from ..autograph.errors import AutoGraphUnavailable, SessionInvalid
from ..config import Settings
from ..rules.baseline import java_time
from ..schemas.contract import Severity, Vehicle
from ..schemas.telegram import ChatMessage, Notice, Outbox
from ..store.repository import AnomalyFilter, AnomalyRepository, Record, to_local
from ..telemetry.service import TelemetryService
from .links import Link, TelegramLinks, Topic, now_ms
from .reports import TelegramReports
from .sessions import NoAccess, TelegramSessions

log = logging.getLogger(__name__)

HOUR_MS = 3_600_000


def _utc(ms: int) -> datetime:
    return datetime.fromtimestamp(ms / 1000, UTC).replace(tzinfo=None)


def _ms(utc: datetime) -> int:
    return int(utc.replace(tzinfo=UTC).timestamp() * 1000)


def more_text(count: int) -> str:
    return f"И ещё {count} {_events(count)} ждут решения — /problems"


def _events(n: int) -> str:
    if n % 10 == 1 and n % 100 != 11:
        return "событие"
    if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14:
        return "события"
    return "событий"


class TelegramNotifier:
    def __init__(self, settings: Settings, links: TelegramLinks, sessions: TelegramSessions,
                 telemetry: TelemetryService, store: AnomalyRepository, reports: TelegramReports,
                 clock: Callable[[], int] = now_ms):
        """[clock] — текущее время, epoch millis (в тестах — подставное)."""
        self._settings = settings
        self._links = links
        self._sessions = sessions
        self._telemetry = telemetry
        self._store = store
        self._reports = reports
        self.clock = clock
        self._lock = asyncio.Lock()
        # Сообщения из запросов приложения (подключение по коду, отключение) — до следующей выдачи. Теряются при
        # перезапуске стенда: это только уведомление, подключение уже в базе.
        self._pending = Outbox()

    def linked(self, chat_id: str) -> None:
        """Чат подключён из приложения: бот поздоровается."""
        if chat_id not in self._pending.linked:
            self._pending.linked.append(chat_id)

    def say(self, chat_id: str, text: str) -> None:
        """Служебное сообщение в чат со следующей выдачей."""
        self._pending.messages.append(ChatMessage(chat_id=chat_id, text=text))

    async def pull(self) -> Outbox:
        async with self._lock:
            now = self.clock()
            await self._links.purge_notifications(now - self._settings.store_retention_days * 24 * HOUR_MS)
            out, self._pending = self._pending, Outbox()
            # Сессия и машины — одни на пользователя схемы, сколько бы чатов он ни подключил.
            users: dict[tuple[str, str], list[Vehicle] | Exception] = {}
            for link in await self._links.all():
                key = (link.schema_id, link.user_key)
                if key not in users:
                    try:
                        session = await self._sessions.session(link)
                        users[key] = await self._telemetry.vehicles(session, link.schema_id)
                    except (NoAccess, AutoGraphUnavailable, SessionInvalid) as e:
                        # SessionInvalid — токен истёк, а проверка сессии ещё в кэше: в следующей выдаче доступ
                        # обновится (вход по паролю) или чат получит предупреждение.
                        users[key] = e
                vehicles = users[key]
                if isinstance(vehicles, NoAccess):
                    await self._warn(link, str(vehicles), now, out)
                    continue
                if isinstance(vehicles, Exception):
                    # AutoGRAPH не ответил: события подождут следующей выдачи, пользователя не тревожим.
                    log.warning("telegram: чат %s — машины не получены: %s", _short(link.chat_id), vehicles)
                    continue
                if link.access_warned_at is not None:
                    await self._links.set_access_warned(link.chat_id, None)
                await self._chat(link, vehicles, now, out)
                await self._report(link, vehicles, now, out)
            if out.notices or out.messages or out.linked or out.daily or out.weekly:
                log.info("telegram: к отправке карточек %d, сообщений %d, докладов %d, сводок %d", len(out.notices),
                         len(out.messages), len(out.daily), len(out.weekly))
            return out

    async def _report(self, link: Link, vehicles: list[Vehicle], now: int, out: Outbox) -> None:
        """Доклад и сводка, если пришло их время; день отмечается, даже если пустой доклад не нужен."""
        now_utc = _utc(now)
        daily, weekly = self._reports.due(link, now_utc)
        today = self._reports.local_date(link, now_utc)
        if daily:
            report = await self._reports.daily(link, vehicles, now_utc)
            if report.total or link.subscription.reports.when_empty:
                out.daily.append(report)
            await self._links.set_report_sent(link.chat_id, weekly=False, on=today)
        if weekly:
            out.weekly.append(await self._reports.weekly(link, vehicles, now_utc))
            await self._links.set_report_sent(link.chat_id, weekly=True, on=today)

    async def _warn(self, link: Link, text: str, now: int, out: Outbox) -> None:
        every = self._settings.telegram_access_warning_hours * HOUR_MS
        if link.access_warned_at is None or now - link.access_warned_at >= every:
            out.messages.append(ChatMessage(chat_id=link.chat_id, text=text))
            await self._links.set_access_warned(link.chat_id, now)

    async def _chat(self, link: Link, vehicles: list[Vehicle], now: int, out: Outbox) -> None:
        s = self._settings
        sub = link.subscription
        ids = [v.id for v in vehicles if sub.vehicle_ids is None or v.id in sub.vehicle_ids]
        if not ids:
            return
        since = max(link.linked_at, now - s.telegram_notify_max_age_hours * HOUR_MS)
        _, records = await self._store.query(
            link.schema_id, AnomalyFilter(vehicle_ids=ids, status="open", detected_from_ms=since),
            s.anomalies_max_limit,
        )
        seen = await self._links.seen(link.chat_id, [r.id for r in records])
        fresh = [r for r in reversed(records) if r.id not in seen]
        if not fresh:
            return

        local = to_local(_utc(now), link.utc_offset_minutes)
        quiet = sub.quiet(local.hour)
        left = None
        if sub.daily_limit is not None:
            midnight = local.replace(hour=0, minute=0, second=0, microsecond=0)
            day_start = _ms(midnight - timedelta(minutes=link.utc_offset_minutes))
            left = max(0, sub.daily_limit - await self._links.delivered_since(link.chat_id, day_start))
        severities = {v.value for v in sub.severities()}
        muted = set(sub.muted_kinds)
        last, skipped = await self._repeats(link, ids, now)
        window = (sub.repeat_hours or 0) * HOUR_MS

        send: list[tuple[Record, int]] = []
        decided: list[tuple[str, str, bool, bool]] = []
        for r in fresh:
            critical = r.severity == Severity.CRITICAL.value
            if r.kind in muted or not critical and (
                    r.severity not in severities or quiet or left == 0):
                decided.append((r.id, r.severity, False, False))
                continue
            topic = (r.vehicle_id, r.kind, r.parameter_name)
            if window and topic in last and now - last[topic] < window:
                decided.append((r.id, r.severity, False, True))
                skipped[topic] = skipped.get(topic, 0) + 1
                continue
            if not critical and left is not None:
                left -= 1
            send.append((r, skipped.pop(topic, 0)))
            last[topic] = now
        cards, rest = send[:s.telegram_notify_max_per_chat], send[s.telegram_notify_max_per_chat:]
        decided += [(r.id, r.severity, True, False) for r, _ in cards] + \
            [(r.id, r.severity, False, False) for r, _ in rest]
        await self._links.record(link.chat_id, decided, at=now)
        for r, repeats in cards:
            earlier = await self._store.earlier(r, 3) if sub.card.memory else []
            out.notices.append(Notice(
                chat_id=link.chat_id, anomaly=r.to_api(link.utc_offset_minutes),
                previous=[java_time(to_local(t, link.utc_offset_minutes)) for t in earlier],
                utc_offset_minutes=link.utc_offset_minutes, repeats=repeats, card=sub.card,
            ))
        if rest:
            out.messages.append(ChatMessage(chat_id=link.chat_id, text=more_text(len(rest))))


    async def _repeats(self, link: Link, vehicle_ids: list[str], now: int) -> tuple[dict[Topic, int], dict[Topic, int]]:
        """Антиспам повторов: когда о событии на машине последний раз сообщили в чат и сколько его повторов пропущено
        с тех пор. Антиспам выключен — пусто."""
        hours = link.subscription.repeat_hours
        if hours is None:
            return {}, {}
        last: dict[Topic, int] = {}
        skipped: dict[Topic, int] = {}
        # Счёт повторов — не дальше двух суток назад, даже если окно меньше.
        since = now - max(hours, 48) * HOUR_MS
        for topic, at, delivered, repeat in await self._links.history(link.chat_id, link.schema_id, vehicle_ids, since):
            if delivered:
                last[topic] = at
                skipped[topic] = 0
            elif repeat:
                skipped[topic] = skipped.get(topic, 0) + 1
        return last, skipped


def _short(chat_id: str) -> str:
    """Начало id чата для логов."""
    return chat_id[:4] + "…"
