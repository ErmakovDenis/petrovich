"""Утренний доклад и недельная сводка в Telegram (макеты 7, 8).

Время и дни — в настройках чата (Subscription.reports), по местному времени пользователя. Стенд проверяет срок при
каждой выдаче бота (`POST /v1/telegram/bot/outbox`): пришло время и сегодня ещё не отправляли — доклад уходит в чат и
дата отмечается (telegram_links.daily_sent_on / weekly_sent_on). Бот был недоступен дольше LATE_HOURS после срока —
доклад за этот день пропускается, а не приходит к вечеру.

Что входит — как в уведомлениях: выбранные машины, не выключенные события, важность по подписке (критичные всегда).
Доклад — события, начавшиеся за последние сутки; сводка — за прошлую неделю пн–вс в сравнении с позапрошлой.
"""

from collections import Counter
from datetime import date, datetime, time, timedelta

from ..config import Settings
from ..rules.baseline import java_time
from ..schemas.contract import Severity, Vehicle
from ..schemas.telegram import DailyReport, NamedCount, ReportEvent, ReportGroup, WeeklyReport
from ..store.repository import AnomalyFilter, AnomalyRepository, Record, to_local
from .links import Link

# В группе доклада — не больше стольких событий (остальные — числом).
GROUP_ITEMS = 5
# В сводке — столько самых частых типов и машин.
TOP = 3
# Доклад опоздал больше чем на столько часов (бот или стенд были недоступны) — за этот день не присылаем.
LATE_HOURS = 3


def _clock(value: str) -> time:
    hours, minutes = value.split(":")
    return time(int(hours), int(minutes))


def _due(local: datetime, at: str, sent_on: str | None) -> bool:
    slot = datetime.combine(local.date(), _clock(at))
    return sent_on != local.date().isoformat() and slot <= local < slot + timedelta(hours=LATE_HOURS)


class TelegramReports:
    def __init__(self, settings: Settings, store: AnomalyRepository):
        self._settings = settings
        self._store = store

    @staticmethod
    def due(link: Link, now_utc: datetime) -> tuple[bool, bool]:
        """Пора ли отправить чату доклад и сводку."""
        r = link.subscription.reports
        local = to_local(now_utc, link.utc_offset_minutes)
        daily = r.daily and (not r.weekdays_only or local.isoweekday() <= 5) and \
            _due(local, r.daily_time, link.daily_sent_on)
        weekly = r.weekly and local.isoweekday() == r.weekly_day and _due(local, r.weekly_time, link.weekly_sent_on)
        return daily, weekly

    @staticmethod
    def local_date(link: Link, now_utc: datetime) -> str:
        return to_local(now_utc, link.utc_offset_minutes).date().isoformat()

    def _scope(self, link: Link, vehicles: list[Vehicle]) -> list[str]:
        chosen = link.subscription.vehicle_ids
        return [v.id for v in vehicles if chosen is None or v.id in chosen]

    async def _records(self, link: Link, ids: list[str], start_utc: datetime, end_utc: datetime) -> list[Record]:
        """События по подписке чата, начавшиеся в [start_utc, end_utc); сначала новые."""
        if not ids:
            return []
        sub = link.subscription
        _, records = await self._store.query(
            link.schema_id,
            AnomalyFilter(vehicle_ids=ids, from_utc=start_utc, to_utc=end_utc,
                          severities=[s.value for s in sub.severities()], exclude_kinds=sub.muted_kinds),
            self._settings.anomalies_max_limit,
        )
        return [r for r in records if start_utc <= r.start_utc < end_utc]

    async def daily(self, link: Link, vehicles: list[Vehicle], now_utc: datetime) -> DailyReport:
        ids = self._scope(link, vehicles)
        since = now_utc - timedelta(hours=24)
        records = await self._records(link, ids, since, now_utc)
        by_title: dict[str, list[Record]] = {}
        for r in records:
            by_title.setdefault(r.title, []).append(r)
        groups = sorted(by_title.values(), key=lambda rs: (-len(rs), min(_rank(r.severity) for r in rs), rs[0].title))
        offset = link.utc_offset_minutes
        return DailyReport(
            chat_id=link.chat_id, since=java_time(to_local(since, offset)), vehicles=len(ids), total=len(records),
            critical=sum(r.severity == Severity.CRITICAL.value for r in records),
            open=sum(r.resolution is None for r in records),
            groups=[ReportGroup(title=rs[0].title, count=len(rs), items=[
                ReportEvent(id=r.id, vehicle_name=r.vehicle_name, severity=Severity(r.severity),
                            event_time=java_time(to_local(r.start_utc, offset)), resolved=r.resolution is not None)
                for r in rs[:GROUP_ITEMS]
            ]) for rs in groups],
        )

    async def weekly(self, link: Link, vehicles: list[Vehicle], now_utc: datetime) -> WeeklyReport:
        ids = self._scope(link, vehicles)
        offset = link.utc_offset_minutes
        today = to_local(now_utc, offset).date()
        monday = today - timedelta(days=today.weekday())
        week_from, week_to = monday - timedelta(days=7), monday - timedelta(days=1)
        records = await self._records(link, ids, _utc(week_from, offset), _utc(monday, offset))
        previous = await self._records(link, ids, _utc(week_from - timedelta(days=7), offset), _utc(week_from, offset))
        kinds = Counter(r.title for r in records)
        per_vehicle: dict[str, list[Record]] = {}
        for r in records:
            per_vehicle.setdefault(r.vehicle_id, []).append(r)
        top = sorted(per_vehicle.values(), key=lambda rs: (-len(rs), rs[0].vehicle_name))[:TOP]
        return WeeklyReport(
            chat_id=link.chat_id, week_from=week_from.isoformat(), week_to=week_to.isoformat(),
            total=len(records), previous_total=len(previous),
            kinds=[NamedCount(name=name, count=n) for name, n in kinds.most_common(TOP)],
            confirmed=sum(r.resolution == "CONFIRMED" for r in records),
            false_alarms=sum(r.resolution == "FALSE_ALARM" for r in records),
            open=sum(r.resolution is None for r in records),
            top_vehicles=[NamedCount(name=rs[0].vehicle_name, count=len(rs),
                                     detail=Counter(r.title for r in rs).most_common(1)[0][0]) for rs in top],
            vehicles=len(ids), quiet_vehicles=len(set(ids) - set(per_vehicle)),
        )


def _rank(severity: str) -> int:
    return {Severity.CRITICAL.value: 0, Severity.WARNING.value: 1}.get(severity, 2)


def _utc(day: date, offset_minutes: int) -> datetime:
    """Местная полночь [day] в UTC (без пояса, как в хранилище)."""
    return datetime.combine(day, time()) - timedelta(minutes=offset_minutes)
