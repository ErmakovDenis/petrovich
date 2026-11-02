from datetime import date

from pydantic import Field

from .contract import CamelModel, Vehicle


class DailyVehicleStats(CamelModel):
    vehicle: Vehicle
    day: date
    fuel: float = 0
    idle: float = 0
    mileage: float = 0
    refuels: int = 0
    last_seen: str | None = None


class WidgetStatsResponse(CamelModel):
    from_: date = Field(alias="from")
    to: date
    items: list[DailyVehicleStats] = Field(default_factory=list)