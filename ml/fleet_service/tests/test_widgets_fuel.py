"""Расход топлива по уровню бака: залипшие UpVol/DnVol не должны ломать сутки."""

from datetime import datetime, timedelta

from fleet_service.schemas.contract import (
    CategoryTable,
    MetricCategory,
    ParameterColumn,
    ParameterInfo,
    Vehicle,
    VehicleTelemetry,
)
from fleet_service.telemetry.widgets import aggregate


def _fuel_telemetry(
    levels: list[float],
    ups: list[float] | None = None,
    drains: list[float] | None = None,
    start: datetime | None = None,
) -> VehicleTelemetry:
    start = start or datetime(2026, 11, 19, 8, 0, 0)
    ups = ups or [0.0] * len(levels)
    drains = drains or [0.0] * len(levels)
    assert len(levels) == len(ups) == len(drains)
    stamps = [start + timedelta(minutes=i) for i in range(len(levels))]

    def col(name: str, caption: str, values: list[float]) -> ParameterColumn:
        return ParameterColumn(
            parameter=ParameterInfo(name=name, caption=caption, unit="л", category=MetricCategory.FUEL),
            values=values,
        )

    table = CategoryTable(
        category=MetricCategory.FUEL,
        timestamps=stamps,
        columns=[
            col("TankMainFuelLevel", "Уровень", levels),
            col("TankMainFuelUpVol", "Заправка", ups),
            col("TankMainFuelDnVol", "Слив", drains),
        ],
    )
    return VehicleTelemetry(
        vehicle=Vehicle(id="v1", name="Тягач"),
        from_=stamps[0],
        to=stamps[-1],
        tables={MetricCategory.FUEL: table},
    )


def test_sticky_upvol_does_not_inflate_consumption():
    start = datetime(2026, 11, 19, 8, 0, 0)
    levels = [200.0, 280.0, 278.0, 276.0, 274.0]
    ups = [0.0, 80.0, 80.0, 80.0, 80.0]
    day = aggregate(_fuel_telemetry(levels, ups, start=start), start, start + timedelta(days=1))
    assert abs(day.fuel - 6.0) < 0.01
    assert day.refuels == 1


def test_sticky_dnvol_does_not_zero_consumption():
    """После отключения залипшего UpVol залипший DnVol обнулял весь расход."""
    start = datetime(2026, 11, 19, 8, 0, 0)
    levels = [100.0, 99.0, 98.0, 97.0]
    drains = [50.0, 50.0, 50.0, 50.0]
    day = aggregate(_fuel_telemetry(levels, drains=drains, start=start), start, start + timedelta(days=1))
    assert abs(day.fuel - 3.0) < 0.01


def test_refuel_is_level_rise_not_upvol():
    start = datetime(2026, 11, 19, 8, 0, 0)
    levels = [100.0, 150.0, 148.0]
    ups = [0.0, 50.0, 50.0]
    day = aggregate(_fuel_telemetry(levels, ups, start=start), start, start + timedelta(days=1))
    assert abs(day.fuel - 2.0) < 0.01
    assert day.refuels == 1
