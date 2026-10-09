"""Какие параметры AutoGRAPH показывать и как их сворачивать — порт `data/AutoGraphParameters.kt` и
`MetricCategory.classify` (`data/Models.kt`) приложения. Логика и порядок должны совпадать с Kotlin: это
проверяют эталоны `testdata/golden/trip-tables` (tests/test_golden.py).
"""

from dataclasses import dataclass
from enum import Enum
from typing import Any

from ..schemas.contract import MetricCategory, ParameterInfo


class Aggregation(Enum):
    """Как сворачивать сырые точки (~каждые 10 с) в интервал. *_NONZERO — ноль означает «нет данных»
    (например, напряжение по CAN при выключенном зажигании)."""

    MEAN = ("MEAN", False)
    MIN = ("MIN", False)
    MAX = ("MAX", False)
    MEAN_NONZERO = ("MEAN_NONZERO", True)
    MIN_NONZERO = ("MIN_NONZERO", True)

    @property
    def zero_is_missing(self) -> bool:
        return self.value[1]


@dataclass(frozen=True)
class ParameterSpec:
    name: str
    category: MetricCategory
    caption: str
    aggregation: Aggregation = Aggregation.MEAN


@dataclass(frozen=True)
class RParameter:
    """Параметр прибора из EnumParameters (`OnlineParams`)."""

    name: str
    caption: str | None = None
    alias: str | None = None
    group_name: str | None = None
    unit: str | None = None
    # 0 — вкл/выкл, 1/2 — целые/флаги, 4 — число, 5 — дата, 6 — интервал, 12 — местоположение.
    return_type: int | None = None

    @classmethod
    def from_json(cls, raw: dict[str, Any]) -> "RParameter":
        def text(key: str) -> str | None:
            value = raw.get(key)
            return None if value is None else str(value)

        return_type = raw.get("ReturnType")
        return cls(
            name=str(raw["Name"]),
            caption=text("Caption"),
            alias=text("Alias"),
            group_name=text("GroupName"),
            unit=text("Unit"),
            return_type=return_type if isinstance(return_type, int) and not isinstance(return_type, bool) else None,
        )


# Стандартные имена параметров, используемые в правилах аномалий.
FUEL_LEVEL = "TankMainFuelLevel"
FUEL_DRAIN_VOLUME = "TankMainFuelDnVol"
FUEL_UP_VOLUME = "TankMainFuelUpVol"
POWER = "Power"
IGNITION = "DIgnition"
IGNITION_CAN = "DIgnitionCAN"
RPM = "Rotation"
COOLANT_TEMP = "TemperatureCOOL"
OIL_PRESSURE = "PressureOIL"
BRAKE_PRESSURE_1 = "PressureVSBC1"
BRAKE_PRESSURE_2 = "PressureVSBC2"
SPEED = "Speed"
BATTERY_VOLTAGE = "BattaryVOLTAGE"  # так в схеме AutoGRAPH (sic)

# Параметры-состояния: ноль в них — значимое значение (выключено), а не отсутствие датчика.
STATUS_PARAMETERS = frozenset({POWER, IGNITION, IGNITION_CAN})

_F, _P, _E, _M = MetricCategory.FUEL, MetricCategory.POWER, MetricCategory.ENGINE, MetricCategory.MOTION

# Порядок = порядок столбцов.
CURATED: list[ParameterSpec] = [
    ParameterSpec(FUEL_LEVEL, _F, "Уровень топлива"),
    ParameterSpec("FL1", _F, "ДУТ 1"),
    ParameterSpec("CANFinstant", _F, "Мгновенный расход"),
    ParameterSpec("ConsumptionCAN", _F, "Расход по CAN (накоп.)", Aggregation.MAX),
    ParameterSpec(FUEL_UP_VOLUME, _F, "Объём заправки", Aggregation.MAX),
    ParameterSpec(FUEL_DRAIN_VOLUME, _F, "Объём слива", Aggregation.MAX),

    ParameterSpec(BATTERY_VOLTAGE, _P, "Напряжение аккумулятора", Aggregation.MEAN_NONZERO),
    ParameterSpec(POWER, _P, "Питание (1 — есть)", Aggregation.MIN),
    ParameterSpec(IGNITION, _P, "Зажигание", Aggregation.MAX),
    ParameterSpec(IGNITION_CAN, _P, "Зажигание CAN", Aggregation.MAX),

    ParameterSpec(RPM, _E, "Обороты"),
    ParameterSpec(COOLANT_TEMP, _E, "Температура ОЖ", Aggregation.MAX),
    ParameterSpec(OIL_PRESSURE, _E, "Давление масла"),
    ParameterSpec("EngineLOAD", _E, "Нагрузка двигателя"),
    ParameterSpec("TemperatureBOOST", _E, "Температура наддува"),
    ParameterSpec("TemperatureOIL", _E, "Температура масла", Aggregation.MAX),
    ParameterSpec("GazLOAD", _E, "Педаль газа"),

    ParameterSpec(SPEED, _M, "Скорость"),
    ParameterSpec("SpeedCAN", _M, "Скорость CAN"),
    ParameterSpec(BRAKE_PRESSURE_1, _M, "Тормозной контур 1", Aggregation.MIN_NONZERO),
    ParameterSpec(BRAKE_PRESSURE_2, _M, "Тормозной контур 2", Aggregation.MIN_NONZERO),
]

_CURATED_NAMES = {s.name for s in CURATED}

# Возвращаемые типы, которые не имеют смысла в таблице/графике (даты, интервалы, координаты, битовые флаги).
NON_NUMERIC_RETURN_TYPES = frozenset({2, 5, 6, 12})

_VOLTAGE_KEYWORDS = ["напряж", "volt", "аккум", "батар", "vbat", "бортсет"]

# Ключевые слова разделов — MetricCategory.keywords в приложении; порядок разделов важен (первое совпадение).
CATEGORY_KEYWORDS: dict[MetricCategory, list[str]] = {
    MetricCategory.FUEL: ["топлив", "fuel", "бак", "дут", "lls", "расход", "заправ", "слив"],
    MetricCategory.POWER: ["аккум", "питан", "напряж", "voltage", "power", "батар", "бортов", "vbat"],
    MetricCategory.ENGINE: ["двигат", "engine", "оборот", "rpm", "охлажд", "coolant", "масл", "oil", "моточас"],
    MetricCategory.MOTION: ["скорост", "speed", "пробег", "odometer", "mileage", "одометр"],
}


def classify(*texts: str | None) -> MetricCategory | None:
    haystack = " ".join(t for t in texts if t is not None).lower()
    for category, keywords in CATEGORY_KEYWORDS.items():
        if any(k in haystack for k in keywords):
            return category
    return None


def _unit(p: RParameter) -> str | None:
    # unit?.takeIf { it.isNotBlank() }
    return p.unit if p.unit is not None and p.unit.strip() else None


def _caption(p: RParameter) -> str:
    # caption ?: name — пустая подпись остаётся пустой, как в Kotlin.
    return p.caption if p.caption is not None else p.name


def select(available: list[RParameter]) -> tuple[list[ParameterInfo], dict[str, Aggregation]]:
    """Параметры прибора для показа: сначала известные из CURATED, плюс любые параметры напряжения.
    Если прибор настроен нестандартно и известных имён нет — классификация по ключевым словам."""
    by_name = {p.name: p for p in available}
    chosen: list[ParameterInfo] = []
    aggregation: dict[str, Aggregation] = {}

    for spec in CURATED:
        p = by_name.get(spec.name)
        if p is None:
            continue
        chosen.append(ParameterInfo(name=p.name, caption=spec.caption, unit=_unit(p), category=spec.category))
        aggregation[p.name] = spec.aggregation

    numeric = [p for p in available if p.name not in _CURATED_NAMES and p.return_type not in NON_NUMERIC_RETURN_TYPES]
    for p in numeric:
        # Как "${p.name} ${p.caption}" в Kotlin: отсутствующая подпись превращается в «null».
        text = f"{p.name} {p.caption if p.caption is not None else 'null'}".lower()
        if any(k in text for k in _VOLTAGE_KEYWORDS):
            chosen.append(ParameterInfo(name=p.name, caption=_caption(p), unit=_unit(p),
                                        category=MetricCategory.POWER))
            aggregation[p.name] = Aggregation.MEAN_NONZERO

    if all(c.category == MetricCategory.POWER for c in chosen):
        for p in numeric:
            if any(c.name == p.name for c in chosen):
                continue
            category = classify(p.name, p.caption, p.alias, p.group_name)
            if category is None or sum(1 for c in chosen if c.category == category) >= 6:
                continue
            chosen.append(ParameterInfo(name=p.name, caption=_caption(p), unit=_unit(p), category=category))
            aggregation[p.name] = Aggregation.MEAN
    return chosen, aggregation
