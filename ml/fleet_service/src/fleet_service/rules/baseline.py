"""Пороговые правила аномалий — точный перенос `anomaly/BaselineAnomalyDetector.kt` приложения.

Только kinds API 1 курса (fallback без ленты): fuel_drain, towing, speed_without_movement, data_gap*,
telemetry_gap. Формат id `rule|<kind>|<vehicleId>|<parameterName>|<время начала эпизода>`.
Пороги — в RuleThresholds (FS_RULES__*). Совпадение проверяют эталоны `testdata/golden/anomalies`.

`detect_episodes` дополнительно отдаёт конец эпизода каждой аномалии (в приложении его нет).
"""

import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from enum import Enum

from ..schemas.contract import Anomaly, MetricCategory, ParameterColumn, ParameterInfo, Severity, VehicleTelemetry
from ..telemetry import parameters as P
from ..telemetry.mapper import java_round
from .thresholds import RuleThresholds

SOURCE = "Базовые правила"

# Приоритет gap на одном интервале: сильнее раньше.
_GAP_RANK = {
    "telemetry_gap": 0,
    "data_gap_all_channels_unavailable": 1,
    "data_gap_signal_unavailable": 2,
    "data_gap_gps_unavailable": 3,
    "data_gap_channel_unavailable": 4,
    "data_gap": 5,
}


def java_time(dt: datetime) -> str:
    """Как LocalDateTime.toString(): секунды — только если не ноль, доли — группами по 3 цифры."""
    text = f"{dt:%Y-%m-%dT%H:%M}"
    if dt.second or dt.microsecond:
        text += f":{dt.second:02d}"
        if dt.microsecond:
            text += f".{dt.microsecond // 1000:03d}" if dt.microsecond % 1000 == 0 else f".{dt.microsecond:06d}"
    return text


def fmt(v: float) -> str:
    """Число для текста описания: целое без дробной части, иначе один знак после запятой (русская локаль)."""
    r = java_round(v)
    if abs(v - r) < 0.05:
        return str(int(r))
    return f"{v:.1f}".replace(".", ",")


class _Report(Enum):
    START = "start"
    END = "end"


@dataclass(frozen=True)
class Episode:
    """Аномалия и конец её эпизода: последний интервал, где выполнялось условие правила (для drop — время события).
    Эпизод, дошедший до конца периода, заканчивается последним интервалом периода."""

    anomaly: Anomaly
    end: datetime


class _Ctx:
    def __init__(self, t: VehicleTelemetry, times: list[datetime]):
        self.t = t
        self.times = times
        self.step = times[1] - times[0]
        self.now = int(time.time() * 1000)
        # Конец эпизода по id аномалии (id уникален в пределах одной проверки).
        self.ends: dict[str, datetime] = {}

    def episodes(
        self,
        c: ParameterColumn,
        condition: Callable[[int, float], bool],
        build: Callable[[int, float, timedelta], Anomaly],
        min_duration: timedelta = timedelta(0),
        report: _Report = _Report.START,
    ) -> list[Anomaly]:
        """Одна аномалия на каждую непрерывную серию интервалов, где выполняется условие, если серия длится не
        меньше min_duration. Интервал без данных прерывает серию. build получает индекс начала серии, значение
        в нём и длительность серии."""
        result: list[Anomaly] = []
        values = c.values
        run_start = -1
        reported: Anomaly | None = None

        def close(end_exclusive: int) -> None:
            nonlocal run_start, reported
            if run_start >= 0 and reported is None and report is _Report.END:
                # Длительность — от начала первого до начала последнего интервала серии: одиночный интервал = 0.
                duration = self.step * (end_exclusive - 1 - run_start)
                if duration >= min_duration:
                    reported = build(run_start, values[run_start], duration)
                    result.append(reported)
            if run_start >= 0 and reported is not None:
                self.ends[reported.id] = self.times[end_exclusive - 1]
            run_start = -1
            reported = None

        for i, v in enumerate(values):
            if v is None or not condition(i, v):
                close(i)
                continue
            if run_start < 0:
                run_start = i
            duration = self.step * (i - run_start + 1)
            if report is _Report.START and reported is None and duration >= min_duration:
                reported = build(run_start, values[run_start], duration)
                result.append(reported)
        close(len(values))
        return result

    def anomaly(self, c: ParameterColumn, i: int, value: float | None, severity: Severity, kind: str, title: str,
                description: str) -> Anomaly:
        p = c.parameter
        event_time = java_time(self.times[i])
        return Anomaly(
            id=f"rule|{kind}|{self.t.vehicle.id}|{p.name}|{event_time}",
            vehicle_id=self.t.vehicle.id,
            vehicle_name=(
                f"{self.t.vehicle.name} {self.t.vehicle.plate}"
                if self.t.vehicle.plate and self.t.vehicle.plate.replace(" ", "").lower()
                not in self.t.vehicle.name.replace(" ", "").lower()
                else self.t.vehicle.name
            ),
            category=p.category,
            parameter_name=p.name,
            parameter_caption=p.caption,
            event_time=event_time,
            detected_at=self.now,
            severity=severity,
            title=title,
            description=description,
            value=value,
            source=SOURCE,
        )


def detect(telemetry: VehicleTelemetry, th: RuleThresholds | None = None) -> list[Anomaly]:
    return [e.anomaly for e in detect_episodes(telemetry, th)]


def detect_episodes(telemetry: VehicleTelemetry, th: RuleThresholds | None = None) -> list[Episode]:
    """Те же аномалии, что detect(), в том же порядке, с концом эпизода каждой."""
    th = th or RuleThresholds()
    columns = [c for table in telemetry.tables.values() for c in table.columns]
    first = next(iter(telemetry.tables.values()), None)
    if first is None or len(first.timestamps) < 2:
        return []
    times = first.timestamps

    def col(name: str, *keywords: str) -> ParameterColumn | None:
        exact = next((c for c in columns if c.parameter.name == name), None)
        if exact is not None or not keywords:
            return exact
        return next(
            (c for c in columns if any(k in f"{c.parameter.name} {c.parameter.caption}".lower() for k in keywords)),
            None,
        )

    ctx = _Ctx(telemetry, times)
    out: list[Anomaly] = []
    rpm = col(P.RPM, "оборот", "rpm")
    ignition = col(P.IGNITION)
    if ignition is None:
        ignition = col(P.IGNITION_CAN)
    speed = col(P.SPEED) or col(P.SPEED_CAN)
    gps_speed = col(P.GPS_SPEED)
    power = col(P.POWER)

    def unit(c: ParameterColumn) -> str:
        return c.parameter.unit or ""

    # Топливо: слив по данным AutoGRAPH → kind как у API 1 курса.
    if (c := col(P.FUEL_DRAIN_VOLUME)) is not None:
        out += ctx.episodes(c, lambda _, v: v > 0, lambda i, v, _d, c=c: ctx.anomaly(
            c, i, v, Severity.CRITICAL, "fuel_drain", "Слив топлива",
            f"AutoGRAPH зафиксировал слив {fmt(v)} {unit(c)}."))

    # Буксировка / скорость без движения / пропуски — те же kinds, что у API 1 курса.
    if speed is not None:
        out += _towing(ctx, speed, ignition, rpm, th)
        if gps_speed is not None:
            out += _speed_without_movement(ctx, speed, gps_speed, th)

    out += _gaps(ctx, columns, power, gps_speed, th)

    return [Episode(a, ctx.ends.get(a.id, times[-1])) for a in out]


def _towing(
    ctx: _Ctx,
    speed: ParameterColumn,
    ignition: ParameterColumn | None,
    rpm: ParameterColumn | None,
    th: RuleThresholds,
) -> list[Anomaly]:
    min_dur = timedelta(minutes=th.towing_min_minutes)

    def towed(i: int, v: float) -> bool:
        if v < th.towing_min_speed_kmh:
            return False
        ign_off = ignition is not None and (ignition.values[i] or 0.0) < 0.5
        rpm_low = rpm is not None and (rpm.values[i] or 0.0) <= th.towing_max_rpm
        # Без зажигания и оборотов — только по скорости не считаем (мало контекста).
        if ignition is None and rpm is None:
            return False
        return ign_off or rpm_low

    return ctx.episodes(
        speed, towed,
        lambda i, v, d: ctx.anomaly(
            speed, i, v, Severity.WARNING, "towing", "Буксировка",
            f"Скорость {fmt(v)} км/ч ≈{int(d.total_seconds() // 60)} мин при выключенном двигателе — возможна буксировка."),
        min_duration=min_dur,
    )


def _speed_without_movement(
    ctx: _Ctx, speed: ParameterColumn, gps_speed: ParameterColumn, th: RuleThresholds,
) -> list[Anomaly]:
    """Спидометр ≥ порога, а GpsSpeed ≈ 0 — скорость без перемещения по GPS."""
    min_dur = timedelta(minutes=th.speed_without_min_minutes)

    def stuck(i: int, v: float) -> bool:
        if v < th.speed_without_min_kmh:
            return False
        g = gps_speed.values[i]
        return g is not None and g <= th.speed_without_max_km * 10  # max_km 0.1 → порог GPS 1 км/ч

    return ctx.episodes(
        speed, stuck,
        lambda i, v, d: ctx.anomaly(
            speed, i, v, Severity.WARNING, "speed_without_movement", "Скорость без движения",
            f"Спидометр {fmt(v)} км/ч ≈{int(d.total_seconds() // 60)} мин при GpsSpeed ≈0 — "
            "проверьте датчик скорости."),
        min_duration=min_dur,
    )


def _pseudo_column(name: str, caption: str, category: MetricCategory, n: int) -> ParameterColumn:
    return ParameterColumn(
        parameter=ParameterInfo(name=name, caption=caption, unit=None, category=category),
        values=[None] * n,
    )


def _gap_runs(mask: list[bool]) -> list[tuple[int, int]]:
    """Непрерывные серии True → (start, end_exclusive)."""
    runs: list[tuple[int, int]] = []
    start = -1
    for i, on in enumerate(mask):
        if on:
            if start < 0:
                start = i
        elif start >= 0:
            runs.append((start, i))
            start = -1
    if start >= 0:
        runs.append((start, len(mask)))
    return runs


def _gaps(
    ctx: _Ctx,
    columns: list[ParameterColumn],
    power: ParameterColumn | None,
    gps_speed: ParameterColumn | None,
    th: RuleThresholds,
) -> list[Anomaly]:
    if not columns:
        return []
    n = len(ctx.times)
    others = [c for c in columns if c is not power]
    all_null = [all(c.values[i] is None for c in columns) for i in range(n)]
    others_null = [
        (not others) or all(c.values[i] is None for c in others)
        for i in range(n)
    ]
    power_on = [
        power is not None and power.values[i] is not None and power.values[i] >= 0.5
        for i in range(n)
    ]
    any_other = [any(c.values[i] is not None for c in others) for i in range(n)] if others else [False] * n

    claimed = [False] * n  # индексы, уже покрытые более сильным gap
    result: list[Anomaly] = []
    telemetry_col = _pseudo_column("telemetry", "Телеметрия", MetricCategory.POWER, n)

    def emit(kind: str, title: str, description: str, start: int, end_exclusive: int,
             col: ParameterColumn, value: float | None = None) -> None:
        for i in range(start, end_exclusive):
            claimed[i] = True
        a = ctx.anomaly(col, start, value, Severity.WARNING, kind, title, description)
        result.append(a)
        ctx.ends[a.id] = ctx.times[end_exclusive - 1]

    def duration_ok(start: int, end_exclusive: int, minutes: int) -> bool:
        return ctx.step * (end_exclusive - start) >= timedelta(minutes=minutes)

    def has_data_before(start: int) -> bool:
        return any(not all_null[i] for i in range(0, start))

    def has_data_after(end: int) -> bool:
        return any(not all_null[i] for i in range(end, n))

    # 1) Внутренний разрыв телеметрии (all-null с данными до и после) — раньше all_channels.
    for start, end in _gap_runs(all_null):
        if start == 0 or end >= n:
            continue
        if not has_data_before(start) or not has_data_after(end):
            continue
        if duration_ok(start, end, th.gap_telemetry_min_minutes):
            mins = int((ctx.step * (end - start)).total_seconds() // 60)
            emit("telemetry_gap", "Разрыв телеметрии",
                 f"Пауза в телеметрии ≈{mins} мин при данных до и после.", start, end, telemetry_col)

    # 2) Все каналы null (offline), не перекрытые telemetry_gap.
    for start, end in _gap_runs([all_null[i] and not claimed[i] for i in range(n)]):
        if duration_ok(start, end, th.gap_all_channels_min_minutes):
            mins = int((ctx.step * (end - start)).total_seconds() // 60)
            emit("data_gap_all_channels_unavailable", "Машина не на связи",
                 f"Нет данных ни по одному каналу ≈{mins} мин.", start, end, telemetry_col)

    # 3) Питание есть, остальные каналы молчат.
    signal_mask = [power_on[i] and others_null[i] and not claimed[i] for i in range(n)]
    for start, end in _gap_runs(signal_mask):
        if duration_ok(start, end, th.gap_signal_min_minutes):
            mins = int((ctx.step * (end - start)).total_seconds() // 60)
            emit("data_gap_signal_unavailable", "Нет сигнала",
                 f"Питание есть, остальные каналы молчат ≈{mins} мин.", start, end,
                 power or telemetry_col, 1.0)

    # 4) Нет GPS при живых других каналах.
    if gps_speed is not None:
        gps_mask = [
            gps_speed.values[i] is None and any_other[i] and not claimed[i]
            for i in range(n)
        ]
        for start, end in _gap_runs(gps_mask):
            if duration_ok(start, end, th.gap_gps_min_minutes):
                mins = int((ctx.step * (end - start)).total_seconds() // 60)
                emit("data_gap_gps_unavailable", "Нет GPS",
                     f"Скорость GPS не передавалась ≈{mins} мин при других данных.", start, end, gps_speed)

    # 5) Отдельный канал молчит, пока другие живы.
    for c in columns:
        if c is power:
            continue
        mask = [
            c.values[i] is None and any(o.values[i] is not None for o in columns if o is not c) and not claimed[i]
            for i in range(n)
        ]
        for start, end in _gap_runs(mask):
            if duration_ok(start, end, th.gap_channel_min_minutes):
                mins = int((ctx.step * (end - start)).total_seconds() // 60)
                emit("data_gap_channel_unavailable", "Нет данных с датчика",
                     f"{c.parameter.caption} молчит ≈{mins} мин при других данных.", start, end, c)

    # 6) Частичный пропуск: часть колонок null, без более сильного gap.
    partial_mask = []
    for i in range(n):
        if claimed[i]:
            partial_mask.append(False)
            continue
        nulls = sum(1 for c in columns if c.values[i] is None)
        lives = len(columns) - nulls
        partial_mask.append(lives > 0 and nulls > 0 and nulls >= max(1, len(columns) // 2))
    for start, end in _gap_runs(partial_mask):
        if duration_ok(start, end, th.gap_partial_min_minutes):
            mins = int((ctx.step * (end - start)).total_seconds() // 60)
            emit("data_gap", "Пропуск данных",
                 f"Часть каналов без данных ≈{mins} мин.", start, end, telemetry_col)

    # Сортировка по приоритету kind, затем по времени — стабильный порядок с остальными правилами: gaps в конце.
    result.sort(key=lambda a: (_GAP_RANK.get(a.id.split("|")[1], 99), a.event_time, a.id))
    return result
