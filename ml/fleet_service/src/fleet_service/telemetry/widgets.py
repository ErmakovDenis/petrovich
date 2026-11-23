from datetime import datetime, timedelta

from ..schemas.contract import CategoryTable, MetricCategory, VehicleTelemetry
from ..schemas.widgets import DailyVehicleStats
from .parameters import FUEL_LEVEL_NAMES

IDLE_SPEED = 1.0
MAX_GAP_SECONDS = 2 * 60 * 60
REFUEL_THRESHOLD = 1.0
# Пауза между соседними подъёмами уровня внутри одной заправки (ДУТ пишет залитие несколькими точками).
REFUEL_GAP_SECONDS = 30 * 60
# Скачок уровня / CAN больше бака между соседними точками — глюк датчика, не расход.
MAX_FUEL_STEP_L = 120.0
# Дребезг ДУТ: просадка и возврат — иначе сутки на частой сетке > недели.
FUEL_NOISE_WINDOW_SECONDS = 45 * 60
FUEL_NOISE_RECOVER = 0.7


def _column(table: CategoryTable | None, *names: str):
    if table is None:
        return None
    return next((c for c in table.columns if c.parameter.name in names), None)


def _is_fuel_noise_drop(timestamps: list, levels: list, drop_index: int, before: float, after: float) -> bool:
    drop = before - after
    if drop <= 0:
        return False
    t0 = timestamps[drop_index]
    deadline = t0 + timedelta(seconds=FUEL_NOISE_WINDOW_SECONDS)
    need = after + drop * FUEL_NOISE_RECOVER
    for j in range(drop_index + 1, len(timestamps)):
        if timestamps[j] > deadline:
            break
        v = levels[j]
        if v is not None and v >= need:
            return True
    return False


def _count_refuels(
    timestamps: list,
    levels: list,
    day_start: datetime,
    day_end: datetime,
) -> int:
    """Число заправок за сутки: непрерывный подъём уровня = одно событие, не каждый тик ДУТ.

    AutoGRAPH пишет заливку несколькими точками (например 40→55→70). Порог по шагу >1 л
    давал 2–3 «заправки» на один заезд на АЗС — поэтому склеиваем соседние подъёмы.
    """
    refuels = 0
    i = 1
    n = len(timestamps)
    while i < n:
        stamp = timestamps[i - 1]
        if not (day_start <= stamp < day_end):
            i += 1
            continue
        old, new = levels[i - 1], levels[i]
        if old is None or new is None or (new - old) <= REFUEL_THRESHOLD:
            i += 1
            continue
        start_level = old
        peak = new
        j = i
        while j + 1 < n:
            gap = (timestamps[j + 1] - timestamps[j]).total_seconds()
            if gap < 1 or gap > REFUEL_GAP_SECONDS:
                break
            a, b = levels[j], levels[j + 1]
            if a is None or b is None:
                break
            # Явное падение — заправка закончилась (дальше расход или новая позже).
            if b < a - REFUEL_THRESHOLD:
                break
            if b > peak:
                peak = b
            j += 1
        if peak - start_level > REFUEL_THRESHOLD:
            refuels += 1
        i = j + 1
    return refuels


def aggregate(telemetry: VehicleTelemetry, day_start: datetime, day_end: datetime) -> DailyVehicleStats:
    fuel = idle = mileage = 0.0
    refuels = 0
    last_seen: datetime | None = None
    motion = telemetry.tables.get(MetricCategory.MOTION)
    fuel_table = telemetry.tables.get(MetricCategory.FUEL)
    speed = _column(motion, "Speed")
    ignition = _column(telemetry.tables.get(MetricCategory.POWER), "DIgnition", "DIgnitionCAN")
    level = _column(fuel_table, *FUEL_LEVEL_NAMES)

    for table in telemetry.tables.values():
        for i, stamp in enumerate(table.timestamps):
            if day_start <= stamp < day_end and any(c.values[i] is not None for c in table.columns):
                last_seen = max(last_seen or stamp, stamp)

    if motion and speed:
        for i in range(len(motion.timestamps) - 1):
            start, end = motion.timestamps[i:i + 2]
            if not (day_start <= start < day_end):
                continue
            seconds = (end - start).total_seconds()
            if not 1 <= seconds <= MAX_GAP_SECONDS:
                continue
            v0, v1 = speed.values[i], speed.values[i + 1]
            if v0 is not None and v1 is not None:
                mileage += (v0 + v1) / 2 * seconds / 3600
            if ignition:
                ign = ignition.values[i]
                if ign is not None and ign >= 0.5 and v0 is not None and v0 < IDLE_SPEED:
                    idle += seconds / 3600

    if fuel_table and level:
        for i in range(1, len(fuel_table.timestamps)):
            stamp = fuel_table.timestamps[i - 1]
            if not (day_start <= stamp < day_end):
                continue
            old, new = level.values[i - 1], level.values[i]
            if old is not None and new is not None and old > new:
                # Только падение уровня. UpVol/DnVol у AutoGRAPH с Aggregation.MAX
                # залипают на много тиков: +Up раздувает расход до тысяч литров,
                # −Dn после отключения Up обнуляет его полностью.
                drop = old - new
                gap = (fuel_table.timestamps[i] - stamp).total_seconds()
                if (
                    1 <= gap <= MAX_GAP_SECONDS
                    and drop <= MAX_FUEL_STEP_L
                    and not _is_fuel_noise_drop(fuel_table.timestamps, level.values, i, old, new)
                ):
                    fuel += drop
        # Заправка — по росту уровня (UpVol один ненадёжен из‑за залипания),
        # соседние подъёмы склеиваем в одно событие.
        refuels = _count_refuels(fuel_table.timestamps, level.values, day_start, day_end)

    # Нет ДУТ / уровень плоский — берём прирост накопленного CAN-расхода за сутки.
    if fuel <= 0 and fuel_table:
        can = _column(fuel_table, "ConsumptionCAN")
        if can:
            prev: float | None = None
            for i, stamp in enumerate(fuel_table.timestamps):
                if not (day_start <= stamp < day_end):
                    continue
                v = can.values[i]
                if v is None:
                    continue
                if prev is not None and v > prev:
                    step = v - prev
                    if step <= MAX_FUEL_STEP_L:
                        fuel += step
                prev = v

    return DailyVehicleStats(vehicle=telemetry.vehicle, day=day_start.date(), fuel=fuel, idle=idle,
                             mileage=mileage, refuels=refuels,
                             last_seen=last_seen.isoformat(timespec="seconds") if last_seen else None)