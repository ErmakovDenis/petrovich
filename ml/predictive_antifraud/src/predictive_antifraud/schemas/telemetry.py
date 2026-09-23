"""Входные данные — JSON-представление `VehicleTelemetry` из Android-приложения (data/Models.kt)."""

from datetime import datetime
from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    """Поля в camelCase, как в kotlinx.serialization приложения; из Python доступны и по snake_case."""

    # protected_namespaces=() — чтобы поле model_version не конфликтовало со служебным префиксом pydantic.
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, protected_namespaces=())


class MetricCategory(str, Enum):
    FUEL = "FUEL"
    POWER = "POWER"
    ENGINE = "ENGINE"
    MOTION = "MOTION"


class Vehicle(CamelModel):
    id: str
    name: str
    group: str | None = None


class ParameterInfo(CamelModel):
    name: str
    caption: str
    unit: str | None = None
    category: MetricCategory


class ParameterColumn(CamelModel):
    parameter: ParameterInfo
    # Значения, выровненные по CategoryTable.timestamps; None — нет данных.
    values: list[float | None]


class CategoryTable(CamelModel):
    category: MetricCategory
    # Локальное время без зоны (LocalDateTime в приложении).
    timestamps: list[datetime]
    columns: list[ParameterColumn] = Field(default_factory=list)

    @model_validator(mode="after")
    def _columns_aligned(self) -> "CategoryTable":
        for c in self.columns:
            if len(c.values) != len(self.timestamps):
                raise ValueError(
                    f"{c.parameter.name}: {len(c.values)} значений при {len(self.timestamps)} отметках времени"
                )
        return self


class VehicleTelemetry(CamelModel):
    vehicle: Vehicle
    from_: datetime = Field(alias="from")
    to: datetime
    tables: dict[MetricCategory, CategoryTable] = Field(default_factory=dict)
