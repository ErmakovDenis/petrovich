"""Пороговые правила аномалий — точный перенос `anomaly/BaselineAnomalyDetector.kt` приложения.

Те же типы (drain, drop, power, volt, overheat, oil, brake), логика эпизодов, порядок результатов и формат id
`rule|<kind>|<vehicleId>|<parameterName>|<время начала эпизода как LocalDateTime.toString()>`. Пороги — в
RuleThresholds (FS_RULES__*), по умолчанию равны зашитым в Kotlin. Совпадение по id, типу, важности и времени
события проверяют эталоны `testdata/golden/anomalies` (tests/test_rules_golden.py).

Особенности данных: при выключенном зажигании AutoGRAPH держит последнее значение оборотов и шлёт нули по CAN,
поэтому правила двигателя срабатывают только при включённом зажигании; 0 у давления масла и тормозных контуров,
напряжение ниже voltage_min_value — «нет данных».
"""

import time
from collections.abc import Callable
from datetime import datetime, timedelta
from enum import Enum

from ..schemas.contract import Anomaly, ParameterColumn, Severity, VehicleTelemetry
from ..telemetry import parameters as P
from ..telemetry.mapper import java_round
from .thresholds import RuleThresholds

SOURCE = "Базовые правила"

_VOLTAGE_KEYWORDS = ["напряж", "volt", "аккум", "батар", "бортсет"]


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


class _Ctx:
    def __init__(self, t: VehicleTelemetry, times: list[datetime]):
        self.t = t
        self.times = times
        self.step = times[1] - times[0]
        self.now = int(time.time() * 1000)

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
        reported = False

        def close(end_exclusive: int) -> None:
            nonlocal run_start, reported
            if run_start >= 0 and not reported and report is _Report.END:
                # Длительность — от начала первого до начала последнего интервала серии: одиночный интервал = 0.
                duration = self.step * (end_exclusive - 1 - run_start)
                if duration >= min_duration:
                    result.append(build(run_start, values[run_start], duration))
            run_start = -1
            reported = False

        for i, v in enumerate(values):
            if v is None or not condition(i, v):
                close(i)
                continue
            if run_start < 0:
                run_start = i
            duration = self.step * (i - run_start + 1)
            if report is _Report.START and not reported and duration >= min_duration:
                result.append(build(run_start, values[run_start], duration))
                reported = True
        close(len(values))
        return result

    def anomaly(self, c: ParameterColumn, i: int, value: float, severity: Severity, kind: str, title: str,
                description: str) -> Anomaly:
        p = c.parameter
        event_time = java_time(self.times[i])
        return Anomaly(
            id=f"rule|{kind}|{self.t.vehicle.id}|{p.name}|{event_time}",
            vehicle_id=self.t.vehicle.id,
            vehicle_name=self.t.vehicle.name,
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

    def value(c: ParameterColumn | None, i: int) -> float:
        v = c.values[i] if c is not None else None
        return v if v is not None else 0.0

    ctx = _Ctx(telemetry, times)
    out: list[Anomaly] = []
    rpm = col(P.RPM, "оборот", "rpm")
    ignition = col(P.IGNITION)
    if ignition is None:
        ignition = col(P.IGNITION_CAN)

    # При выключенном зажигании AutoGRAPH держит последнее значение оборотов и интерполирует остальные
    # CAN-параметры, поэтому одних оборотов недостаточно.
    def engine_running(i: int, min_rpm: float) -> bool:
        if ignition is not None and value(ignition, i) < 0.5:
            return False
        return rpm is None or value(rpm, i) > min_rpm

    def unit(c: ParameterColumn) -> str:
        return c.parameter.unit or ""

    # Топливо: слив по данным AutoGRAPH.
    if (c := col(P.FUEL_DRAIN_VOLUME)) is not None:
        out += ctx.episodes(c, lambda _, v: v > 0, lambda i, v, _d, c=c: ctx.anomaly(
            c, i, v, Severity.CRITICAL, "drain", "Слив топлива", f"AutoGRAPH зафиксировал слив {fmt(v)} {unit(c)}."))

    # Топливо: резкое падение уровня за окно.
    if (c := col(P.FUEL_LEVEL, "уровень топлива", "fuellevel")) is not None:
        out += _fuel_drop(ctx, c, th)

    # Пропадание питания: короткое — информационное, дольше power_long_minutes — предупреждение.
    if (c := col(P.POWER)) is not None:
        def power(i: int, v: float, duration: timedelta, c: ParameterColumn = c) -> Anomaly:
            long = duration >= timedelta(minutes=th.power_long_minutes)
            # ≈ минимальная оценка: с учётом шага интервалов фактическая длительность может быть больше.
            text = "Прибор сообщил об отключении основного питания" + (
                f" (≈{int(duration.total_seconds() // 60)} мин)." if long else " (кратковременно).")
            return ctx.anomaly(c, i, v, Severity.WARNING if long else Severity.INFO, "power", "Пропадание питания", text)

        out += ctx.episodes(c, lambda _, v: v < 0.5, power, report=_Report.END)

    # Напряжение бортсети/аккумулятора — только на работающем двигателе и дольше voltage_min_minutes.
    voltage_columns = [
        c for c in columns
        if c.parameter.name == P.BATTERY_VOLTAGE or (
            c.parameter.name != P.POWER
            and any(k in f"{c.parameter.name} {c.parameter.caption}".lower() for k in _VOLTAGE_KEYWORDS)
        )
    ]
    for c in voltage_columns:
        present = sorted(v for v in c.values if v is not None and v >= th.voltage_min_value)
        if not present:
            continue
        median = present[len(present) // 2]
        if median > th.voltage_24v_median:
            low, critical, high = th.voltage_24v_low, th.voltage_24v_critical, th.voltage_24v_high
        else:
            low, critical, high = th.voltage_12v_low, th.voltage_12v_critical, th.voltage_12v_high

        def voltage(i: int, v: float, _d: timedelta, c: ParameterColumn = c, low: float = low,
                    critical: float = critical, high: float = high) -> Anomaly:
            severity = Severity.CRITICAL if v < critical or v > high + th.voltage_critical_over_high else Severity.WARNING
            what = (f"ниже {fmt(low)} В — возможна неисправность генератора или АКБ" if v < low
                    else f"выше {fmt(high)} В — перезаряд")
            return ctx.anomaly(c, i, v, severity, "volt", "Напряжение вне нормы",
                               f"{c.parameter.caption}: {fmt(v)} В на работающем двигателе, {what}.")

        out += ctx.episodes(
            c,
            lambda i, v, low=low, high=high: v >= th.voltage_min_value and (v < low or v > high)
            and engine_running(i, th.voltage_min_rpm),
            voltage,
            min_duration=timedelta(minutes=th.voltage_min_minutes),
        )

    # Перегрев двигателя.
    if (c := col(P.COOLANT_TEMP, "охл", "coolant")) is not None:
        out += ctx.episodes(
            c, lambda _, v: v > th.overheat_celsius,
            lambda i, v, _d, c=c: ctx.anomaly(
                c, i, v, Severity.CRITICAL if v > th.overheat_critical_celsius else Severity.WARNING, "overheat",
                "Перегрев двигателя",
                f"Температура охлаждающей жидкости {fmt(v)} °C (норма до {fmt(th.overheat_celsius)} °C)."),
            min_duration=timedelta(minutes=th.overheat_min_minutes),
        )

    # Низкое давление масла на работающем двигателе (0 — нет данных с датчика).
    if (oil := col(P.OIL_PRESSURE, "давление масла")) is not None and rpm is not None:
        out += ctx.episodes(
            oil, lambda i, v: 0 < v < th.oil_low_kpa and engine_running(i, th.oil_min_rpm),
            lambda i, v, _d: ctx.anomaly(
                oil, i, v, Severity.CRITICAL, "oil", "Низкое давление масла",
                f"Давление масла {fmt(v)} кПа при {fmt(value(rpm, i))} об/мин. Проверьте уровень масла."),
            min_duration=timedelta(minutes=th.oil_min_minutes),
        )

    # Низкое давление в тормозных контурах на работающем двигателе (после запуска давление набирается
    # несколько минут).
    if rpm is not None:
        for brake in (b for b in (col(P.BRAKE_PRESSURE_1), col(P.BRAKE_PRESSURE_2)) if b is not None):
            out += ctx.episodes(
                brake, lambda i, v: v < th.brake_low_kpa and engine_running(i, th.brake_min_rpm),
                lambda i, v, _d, brake=brake: ctx.anomaly(
                    brake, i, v, Severity.WARNING, "brake", "Низкое давление в тормозной системе",
                    f"{brake.parameter.caption}: {fmt(v)} кПа при работающем двигателе дольше "
                    f"{th.brake_min_minutes} минут (обычно 600–1050 кПа)."),
                min_duration=timedelta(minutes=th.brake_min_minutes),
            )
    return out


def _fuel_drop(ctx: _Ctx, c: ParameterColumn, th: RuleThresholds) -> list[Anomaly]:
    window = timedelta(minutes=th.fuel_drop_window_minutes)
    result: list[Anomaly] = []
    values, times = c.values, ctx.times
    window_start = 0
    cooldown_until: datetime | None = None
    for i, v in enumerate(values):
        if v is None:
            continue
        while times[i] - times[window_start] > window:
            window_start += 1
        if cooldown_until is not None and times[i] < cooldown_until:
            continue
        previous = [values[k] for k in range(window_start, i) if values[k] is not None]
        if not previous:
            continue
        peak = max(previous)
        if peak - v >= th.fuel_drop_min_liters:
            unit = c.parameter.unit or ""
            result.append(ctx.anomaly(
                c, i, v, Severity.CRITICAL, "drop", "Резкое падение уровня топлива",
                f"Уровень упал с {fmt(peak)} до {fmt(v)} {unit} (−{fmt(peak - v)}) за ≤{th.fuel_drop_window_minutes} "
                "мин. Возможен слив."))
            cooldown_until = times[i] + timedelta(minutes=th.fuel_drop_cooldown_minutes)
    return result
