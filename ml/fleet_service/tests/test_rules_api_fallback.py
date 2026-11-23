"""Fallback-правила с kinds API 1 курса: буксировка без FP на работающем двигателе, приоритет gap."""

from datetime import datetime, timedelta

from fleet_service.rules.baseline import detect
from fleet_service.schemas.contract import (
    CategoryTable,
    MetricCategory,
    ParameterColumn,
    ParameterInfo,
    Vehicle,
    VehicleTelemetry,
)


def _series(n: int, f):
    return [f(i) for i in range(n)]


def _telemetry(columns: list[tuple[ParameterInfo, list[float | None]]], minutes: int = 30) -> VehicleTelemetry:
    start = datetime(2026, 9, 16, 6, 0)
    times = [start + timedelta(minutes=i) for i in range(minutes + 1)]
    tables: dict[MetricCategory, CategoryTable] = {}
    by_cat: dict[MetricCategory, list[ParameterColumn]] = {}
    for info, values in columns:
        by_cat.setdefault(info.category, []).append(ParameterColumn(parameter=info, values=values))
    for cat, cols in by_cat.items():
        tables[cat] = CategoryTable(category=cat, timestamps=times, columns=cols)
    return VehicleTelemetry(
        vehicle=Vehicle(id="t1", name="Тест"),
        **{"from": times[0]},
        to=times[-1],
        tables=tables,
    )


def _p(name: str, caption: str, cat: MetricCategory, unit: str | None = None) -> ParameterInfo:
    return ParameterInfo(name=name, caption=caption, unit=unit, category=cat)


def test_towing_not_when_engine_running():
    """Скорость + высокие обороты и зажигание — не буксировка."""
    n = 31
    t = _telemetry([
        (_p("Speed", "Скорость", MetricCategory.MOTION, "км/ч"), _series(n, lambda _: 40.0)),
        (_p("DIgnition", "Зажигание", MetricCategory.POWER), _series(n, lambda _: 1.0)),
        (_p("Rotation", "Обороты", MetricCategory.ENGINE, "об/мин"), _series(n, lambda _: 1500.0)),
    ])
    kinds = {a.id.split("|")[1] for a in detect(t)}
    assert "towing" not in kinds


def test_towing_when_ignition_off():
    n = 31
    t = _telemetry([
        (_p("Speed", "Скорость", MetricCategory.MOTION, "км/ч"), _series(n, lambda _: 40.0)),
        (_p("DIgnition", "Зажигание", MetricCategory.POWER), _series(n, lambda _: 0.0)),
        (_p("Rotation", "Обороты", MetricCategory.ENGINE, "об/мин"), _series(n, lambda _: 0.0)),
    ])
    kinds = {a.id.split("|")[1] for a in detect(t)}
    assert "towing" in kinds


def test_internal_all_null_is_telemetry_gap_not_all_channels():
    """Внутренний silent ≥60 мин → telemetry_gap, не all_channels."""
    n = 121  # 0..120
    def val(i: int) -> float | None:
        if 20 <= i < 90:  # 70 мин
            return None
        return 1.0

    t = _telemetry([
        (_p("Power", "Питание", MetricCategory.POWER), _series(n, val)),
        (_p("Speed", "Скорость", MetricCategory.MOTION, "км/ч"), _series(n, val)),
    ], minutes=120)
    found = detect(t)
    kinds = [a.id.split("|")[1] for a in found]
    assert "telemetry_gap" in kinds
    assert "data_gap_all_channels_unavailable" not in kinds
