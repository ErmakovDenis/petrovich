"""Хранилище аномалий и решений по схемам AutoGRAPH.

Стенд — владелец id. id стенда: `<источник>|<тип>|<машина>|<параметр>|<начало события в UTC>Z`, например
`rule|overheat|veh-2|TemperatureCOOL|2026-09-16T01:30Z`. Формат совместим с приложением (тип — вторая часть,
Anomaly.kind), а «Z» в конце отличает id стенда от id, посчитанных на устройстве (там местное время без пояса).

Одно событие — одна запись, как бы ни пересекались окна проверок:
- проверки с сохранением идут на канонической сетке (store/scan.py), поэтому начало эпизода не зависит от окна;
- эпизод, обрезанный началом окна (начался раньше), или продолжение уже сохранённого эпизода сливаются с записью
  того же типа, машины и параметра, если эпизоды пересекаются или примыкают друг к другу (между ними нет ни одного
  интервала без условия). Резкое падение топлива (drop) — точечное событие: повтор в пределах паузы правила
  (FS_RULES__FUEL_DROP_COOLDOWN_MINUTES) — то же событие, как и в одной проверке приложения.
При слиянии id не меняется; начало сдвигается раньше, конец — позже, важность только растёт (пропадание питания
из кратковременного становится долгим). Если новый эпизод соединил несколько сохранённых записей, они сливаются
в одну (с тем же id или самую раннюю); решение и история решений переходят к ней.

Записи пишет один процесс стенда: запись сериализуется asyncio.Lock, запросы к SQLite идут в потоках.
"""

import asyncio
import time
from collections.abc import Callable, Collection
from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from typing import Any, TypeVar

from sqlalchemy import Connection, Engine, and_, delete, exists, func, insert, select, update

from ..rules.baseline import java_time
from ..schemas.contract import Anomaly
from ..schemas.store import Decision, Resolution, StoredAnomaly
from .db import anomalies as A
from .db import decisions as D
from .db import scan_marks as M

T = TypeVar("T")

_SEVERITY_RANK = {"INFO": 0, "WARNING": 1, "CRITICAL": 2}
DROP = "drop"

# Фильтр статуса разбора: open — без решения, resolved — с любым решением.
STATUSES = ("open", "resolved", "confirmed", "false_alarm")


@dataclass(frozen=True)
class Detection:
    """Аномалия, найденная проверкой (время — местное пользователя), и её эпизод в UTC."""

    anomaly: Anomaly
    start_utc: datetime
    end_utc: datetime


def to_utc(local: datetime, utc_offset_minutes: int) -> datetime:
    return local - timedelta(minutes=utc_offset_minutes)


def to_local(utc: datetime, utc_offset_minutes: int) -> datetime:
    return utc + timedelta(minutes=utc_offset_minutes)


def kind_of(anomaly_id: str) -> str:
    """Тип из id `<источник>|<тип>|…`, как Anomaly.kind в приложении; id без «|» — пустой тип."""
    parts = anomaly_id.split("|")
    return parts[1] if len(parts) > 1 else ""


def stand_id(anomaly: Anomaly, start_utc: datetime) -> str:
    parts = anomaly.id.split("|")
    source = parts[0] if len(parts) > 1 else "ml"
    return f"{source}|{kind_of(anomaly.id)}|{anomaly.vehicle_id}|{anomaly.parameter_name}|{java_time(start_utc)}Z"


@dataclass(frozen=True)
class Record:
    """Строка таблицы anomalies."""

    schema_id: str
    id: str
    vehicle_id: str
    vehicle_name: str
    kind: str
    category: str
    parameter_name: str
    parameter_caption: str
    source: str
    severity: str
    title: str
    description: str
    value: float | None
    score: float | None
    start_utc: datetime
    end_utc: datetime
    first_detected_at: int
    last_detected_at: int
    resolution: str | None
    false_alarm_reason: str | None
    resolved_by: str | None
    resolved_at: int | None

    @classmethod
    def of(cls, row: Any) -> "Record":
        return cls(**row._mapping)

    def to_api(self, utc_offset_minutes: int) -> StoredAnomaly:
        return StoredAnomaly(
            id=self.id,
            vehicle_id=self.vehicle_id,
            vehicle_name=self.vehicle_name,
            category=self.category,
            parameter_name=self.parameter_name,
            parameter_caption=self.parameter_caption,
            event_time=java_time(to_local(self.start_utc, utc_offset_minutes)),
            detected_at=self.first_detected_at,
            severity=self.severity,
            title=self.title,
            description=self.description,
            value=self.value,
            score=self.score,
            source=self.source,
            resolution=self.resolution,
            false_alarm_reason=self.false_alarm_reason,
            resolved_by=self.resolved_by,
            resolved_at=self.resolved_at,
            episode_end=java_time(to_local(self.end_utc, utc_offset_minutes)),
            last_detected_at=self.last_detected_at,
        )


@dataclass(frozen=True)
class AnomalyFilter:
    """Фильтры списка. Период — по пересечению с эпизодом события (UTC); vehicle_ids — машины, видимые
    пользователю (обязательно), vehicle_id — одна из них."""

    vehicle_ids: Collection[str]
    from_utc: datetime | None = None
    to_utc: datetime | None = None
    vehicle_id: str | None = None
    severities: Collection[str] = ()
    status: str | None = None


def now_ms() -> int:
    return int(time.time() * 1000)


class AnomalyRepository:
    def __init__(self, engine: Engine, bucket: timedelta, drop_cooldown: timedelta, retention: timedelta):
        """[bucket] — шаг канонической сетки; [drop_cooldown] — пауза правила drop; [retention] — срок хранения."""
        self._engine = engine
        self._bucket = bucket
        self._drop_cooldown = drop_cooldown
        self._retention = retention
        self._write = asyncio.Lock()
        self._last_purge = float("-inf")

    def dispose(self) -> None:
        self._engine.dispose()

    async def run(self, fn: Callable[[Connection], T], write: bool = False) -> T:
        """Запрос к базе в потоке; запись — под общим замком (им пользуется и background/access.py)."""
        def work() -> T:
            with self._engine.begin() as conn:
                return fn(conn)

        if not write:
            return await asyncio.to_thread(work)
        async with self._write:
            return await asyncio.to_thread(work)

    # --- запись результатов проверки ---

    async def save(self, schema_id: str, detections: list[Detection], at: int | None = None) -> list[tuple[Record, bool]]:
        """Сохраняет найденное проверкой: новое событие — новая запись (True), уже известное — обновляет её (False)."""
        at = at or now_ms()
        return await self.run(lambda conn: [self._save_one(conn, schema_id, d, at) for d in detections], write=True)

    def _bound(self, kind: str, start: datetime, end: datetime) -> datetime:
        """Новое событие того же типа, начавшееся раньше этой границы, — продолжение (слияние)."""
        if kind == DROP:
            return start + self._drop_cooldown
        # Эпизод, начавшийся в следующем интервале после конца, — тот же (без разрыва); через интервал — новый.
        return end + 2 * self._bucket

    def _same_event(self, kind: str, a: tuple[datetime, datetime], b: tuple[datetime, datetime]) -> bool:
        return b[0] < self._bound(kind, *a) and a[0] < self._bound(kind, *b)

    def _save_one(self, conn: Connection, schema_id: str, d: Detection, at: int) -> tuple[Record, bool]:
        a = d.anomaly
        sid = stand_id(a, d.start_utc)
        kind = kind_of(a.id)
        exact = conn.execute(select(A).where(A.c.schema_id == schema_id, A.c.id == sid)).first()
        span = max(self._drop_cooldown, 2 * self._bucket)
        candidates = conn.execute(
            select(A)
            .where(
                A.c.schema_id == schema_id, A.c.vehicle_id == a.vehicle_id, A.c.kind == kind,
                A.c.parameter_name == a.parameter_name, A.c.source == a.source,
                A.c.start_utc <= d.end_utc + span, A.c.end_utc >= d.start_utc - span,
            )
            .order_by(A.c.start_utc)
        ).all()
        matched = [Record.of(c) for c in candidates
                   if self._same_event(kind, (c.start_utc, c.end_utc), (d.start_utc, d.end_utc))]
        if exact is not None and all(r.id != exact.id for r in matched):
            matched.insert(0, Record.of(exact))
        if not matched:
            values = {
                "schema_id": schema_id, "id": sid, "vehicle_id": a.vehicle_id, "vehicle_name": a.vehicle_name,
                "kind": kind, "category": a.category.value, "parameter_name": a.parameter_name,
                "parameter_caption": a.parameter_caption, "source": a.source, "severity": a.severity.value,
                "title": a.title, "description": a.description, "value": a.value, "score": a.score,
                "start_utc": d.start_utc, "end_utc": d.end_utc, "first_detected_at": at, "last_detected_at": at,
                "resolution": None, "false_alarm_reason": None, "resolved_by": None, "resolved_at": None,
            }
            conn.execute(insert(A).values(**values))
            return Record(**values), True

        # Запись с тем же id, иначе — самая ранняя; остальные совпавшие (новый эпизод соединил их) сливаются в неё.
        keep = next((r for r in matched if r.id == sid), matched[0])
        absorbed = [r for r in matched if r.id != keep.id]
        group = [keep, *absorbed]
        changes: dict[str, Any] = {
            "start_utc": min([d.start_utc, *(r.start_utc for r in group)]),
            "end_utc": max([d.end_utc, *(r.end_utc for r in group)]),
            "first_detected_at": min(r.first_detected_at for r in group),
            "last_detected_at": at,
            "vehicle_name": a.vehicle_name,
        }
        # Важность только растёт: описание — от самой важной из находок.
        worst = max(group, key=lambda r: _SEVERITY_RANK.get(r.severity, 0))
        if _SEVERITY_RANK[a.severity.value] > _SEVERITY_RANK.get(worst.severity, 0):
            changes.update(severity=a.severity.value, title=a.title, description=a.description, value=a.value,
                           score=a.score)
        elif worst is not keep:
            changes.update(severity=worst.severity, title=worst.title, description=worst.description,
                           value=worst.value, score=worst.score)
        # Решение по слитой записи не теряется: если у сохраняемой его нет, берётся последнее принятое.
        decided = [r for r in absorbed if r.resolution is not None]
        if keep.resolution is None and decided:
            last = max(decided, key=lambda r: r.resolved_at or 0)
            changes.update(resolution=last.resolution, false_alarm_reason=last.false_alarm_reason,
                           resolved_by=last.resolved_by, resolved_at=last.resolved_at)
        for r in absorbed:
            conn.execute(update(D).where(D.c.schema_id == schema_id, D.c.anomaly_id == r.id).values(anomaly_id=keep.id))
            conn.execute(delete(A).where(A.c.schema_id == schema_id, A.c.id == r.id))
        conn.execute(update(A).where(A.c.schema_id == schema_id, A.c.id == keep.id).values(**changes))
        return replace(keep, **changes), False

    async def mark_scan(self, schema_id: str, at: int | None = None) -> int:
        at = at or now_ms()

        def work(conn: Connection) -> int:
            done = conn.execute(update(M).where(M.c.schema_id == schema_id).values(last_scan_at=at))
            if done.rowcount == 0:
                conn.execute(insert(M).values(schema_id=schema_id, last_scan_at=at))
            return at

        return await self.run(work, write=True)

    async def last_scan(self, schema_id: str) -> int | None:
        return await self.run(
            lambda conn: conn.execute(select(M.c.last_scan_at).where(M.c.schema_id == schema_id)).scalar()
        )

    # --- чтение ---

    @staticmethod
    def _where(schema_id: str, f: AnomalyFilter) -> Any:
        conditions = [A.c.schema_id == schema_id, A.c.vehicle_id.in_(list(f.vehicle_ids))]
        if f.vehicle_id is not None:
            conditions.append(A.c.vehicle_id == f.vehicle_id)
        if f.from_utc is not None:
            conditions.append(A.c.end_utc >= f.from_utc)
        if f.to_utc is not None:
            conditions.append(A.c.start_utc <= f.to_utc)
        if f.severities:
            conditions.append(A.c.severity.in_(list(f.severities)))
        if f.status == "open":
            conditions.append(A.c.resolution.is_(None))
        elif f.status == "resolved":
            conditions.append(A.c.resolution.is_not(None))
        elif f.status == "confirmed":
            conditions.append(A.c.resolution == Resolution.CONFIRMED.value)
        elif f.status == "false_alarm":
            conditions.append(A.c.resolution == Resolution.FALSE_ALARM.value)
        return and_(*conditions)

    async def query(self, schema_id: str, f: AnomalyFilter, limit: int, offset: int = 0) -> tuple[int, list[Record]]:
        """Сначала новые (по началу события)."""
        where = self._where(schema_id, f)

        def work(conn: Connection) -> tuple[int, list[Record]]:
            total = conn.execute(select(func.count()).select_from(A).where(where)).scalar_one()
            rows = conn.execute(
                select(A).where(where).order_by(A.c.start_utc.desc(), A.c.id).limit(limit).offset(offset)
            ).all()
            return total, [Record.of(r) for r in rows]

        return await self.run(work)

    async def get(self, schema_id: str, anomaly_id: str, vehicle_ids: Collection[str]) -> Record | None:
        """Запись схемы по id, если её машина видна пользователю."""
        def work(conn: Connection) -> Record | None:
            row = conn.execute(select(A).where(A.c.schema_id == schema_id, A.c.id == anomaly_id)).first()
            return Record.of(row) if row is not None and row.vehicle_id in vehicle_ids else None

        return await self.run(work)

    async def history(self, schema_id: str, anomaly_id: str) -> list[Decision]:
        """Решения по аномалии от первого к последнему."""
        def work(conn: Connection) -> list[Decision]:
            rows = conn.execute(
                select(D).where(D.c.schema_id == schema_id, D.c.anomaly_id == anomaly_id).order_by(D.c.id)
            ).all()
            return [Decision(resolution=r.resolution, reason=r.reason, user_name=r.user_name,
                             decided_at=r.decided_at, origin=r.origin) for r in rows]

        return await self.run(work)

    async def find_match(
        self, schema_id: str, vehicle_id: str, kind: str, parameter_name: str, event_utc: datetime,
        tolerance: timedelta,
    ) -> Record | None:
        """Сохранённое событие, в эпизод которого (с допуском) попадает время события с устройства; из нескольких —
        с ближайшим началом."""
        def work(conn: Connection) -> Record | None:
            rows = conn.execute(select(A).where(
                A.c.schema_id == schema_id, A.c.vehicle_id == vehicle_id, A.c.kind == kind,
                A.c.parameter_name == parameter_name,
                A.c.start_utc <= event_utc + tolerance, A.c.end_utc >= event_utc - tolerance,
            )).all()
            if not rows:
                return None
            return Record.of(min(rows, key=lambda r: abs(r.start_utc - event_utc)))

        return await self.run(work)

    # --- решения ---

    async def resolve(
        self, schema_id: str, anomaly_id: str, resolution: Resolution | None, reason: str | None,
        user_name: str | None, origin: str = "app", at: int | None = None, only_if_open: bool = False,
    ) -> tuple[Record, bool] | None:
        """Решение по аномалии (None — вернуть в «ждут решения»); история пишется в decisions. Последнее решение
        действует для всех пользователей схемы. [only_if_open] — не трогать уже разобранную (перенос с устройства).
        Возвращает запись и признак, что решение записано; None — записи нет."""
        at = at or now_ms()
        if resolution is not Resolution.FALSE_ALARM:
            reason = None

        def work(conn: Connection) -> tuple[Record, bool] | None:
            row = conn.execute(select(A).where(A.c.schema_id == schema_id, A.c.id == anomaly_id)).first()
            if row is None:
                return None
            old = Record.of(row)
            if only_if_open and old.resolution is not None:
                return old, False
            value = resolution.value if resolution is not None else None
            changes = {
                "resolution": value, "false_alarm_reason": reason,
                "resolved_by": user_name if value is not None else None,
                "resolved_at": at if value is not None else None,
            }
            conn.execute(update(A).where(A.c.schema_id == schema_id, A.c.id == anomaly_id).values(**changes))
            conn.execute(insert(D).values(schema_id=schema_id, anomaly_id=anomaly_id, resolution=value, reason=reason,
                                          user_name=user_name, decided_at=at, origin=origin))
            return replace(old, **changes), True

        return await self.run(work, write=True)

    # --- срок хранения ---

    async def purge(self, now_utc: datetime) -> int:
        """Удаляет события, закончившиеся раньше срока хранения, и их решения."""
        border = now_utc - self._retention

        def work(conn: Connection) -> int:
            removed = conn.execute(delete(A).where(A.c.end_utc < border)).rowcount
            conn.execute(delete(D).where(~exists().where(A.c.schema_id == D.c.schema_id, A.c.id == D.c.anomaly_id)))
            return removed

        return await self.run(work, write=True)

    async def purge_if_due(self, now_utc: datetime, every_seconds: float = 3600) -> None:
        """Не чаще раза в [every_seconds]: удаление по сроку — после проверок, без отдельного планировщика."""
        if time.monotonic() - self._last_purge < every_seconds:
            return
        self._last_purge = time.monotonic()
        await self.purge(now_utc)
