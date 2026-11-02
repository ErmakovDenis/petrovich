from datetime import datetime

from ..schemas.contract import CategoryTable, MetricCategory, VehicleTelemetry
from ..schemas.widgets import DailyVehicleStats

IDLE_SPEED = 1.0
MAX_GAP_SECONDS = 2 * 60 * 60
REFUEL_THRESHOLD = 1.0


def _column(table: CategoryTable | None, *names: str):
    if table is None:
        return None
    return next((c for c in table.columns if c.parameter.name in names), None)


def aggregate(telemetry: VehicleTelemetry, day_start: datetime, day_end: datetime) -> DailyVehicleStats:
    fuel = idle = mileage = 0.0
    refuels = 0
    last_seen: datetime | None = None
    motion = telemetry.tables.get(MetricCategory.MOTION)
    fuel_table = telemetry.tables.get(MetricCategory.FUEL)
    speed = _column(motion, "Speed")
    ignition = _column(telemetry.tables.get(MetricCategory.POWER), "DIgnition", "DIgnitionCAN")
    level = _column(fuel_table, "TankMainFuelLevel")
    drain = _column(fuel_table, "TankMainFuelDnVol")
    up = _column(fuel_table, "FuelUpVolume", "TankMainFuelUpVol")

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
            if old is not None and new is not None:
                fuel += max(0.0, old - new - (drain.values[i] if drain and drain.values[i] else 0)
                            + (up.values[i] if up and up.values[i] else 0))
            if up and (up.values[i] or 0) > REFUEL_THRESHOLD:
                refuels += 1

    return DailyVehicleStats(vehicle=telemetry.vehicle, day=day_start.date(), fuel=fuel, idle=idle,
                             mileage=mileage, refuels=refuels,
                             last_seen=last_seen.isoformat(timespec="seconds") if last_seen else None)