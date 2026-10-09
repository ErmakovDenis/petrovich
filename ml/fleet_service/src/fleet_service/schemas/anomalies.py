"""Запрос и ответ `POST /v1/anomalies/check`. Приложение: anomaly/VehicleChecker.kt (ServerVehicleChecker)."""

from datetime import datetime

from pydantic import Field

from ..analytics.client import AnalyticsStatus
from .contract import Anomaly, CamelModel


class CheckRequest(CamelModel):
    vehicle_id: str = Field(min_length=1)
    # Местное время пользователя без пояса.
    from_: datetime = Field(alias="from")
    to: datetime
    # Смещение пояса пользователя (как при входе в AutoGRAPH) — входит в ключ кэша телеметрии.
    utc_offset_minutes: int = Field(ge=-14 * 60, le=14 * 60)


class Analytics(CamelModel):
    predictive: AnalyticsStatus
    antifraud: AnalyticsStatus


class CheckResponse(CamelModel):
    vehicle_id: str
    # Аномалии аналитики и правил, без повторов по id (как CompositeAnomalyDetector: сначала ML, потом правила).
    anomalies: list[Anomaly]
    # Сколько из них нашли правила.
    rules_anomalies: int
    analytics: Analytics
    # true — обе модели загружены и отработали; false — проверка только по правилам (или частично).
    models_ready: bool
