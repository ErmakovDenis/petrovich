"""Ответы сервиса. `Anomaly` повторяет data class Anomaly из приложения (anomaly/Anomaly.kt)."""

from enum import Enum

from pydantic import Field

from .telemetry import CamelModel, MetricCategory


class Severity(str, Enum):
    INFO = "INFO"
    WARNING = "WARNING"
    CRITICAL = "CRITICAL"


class Anomaly(CamelModel):
    # Детерминированный ключ: приложение дедуплицирует аномалии по нему.
    # Формат: ml|<kind>|<vehicleId>|<parameterName>|<eventTime>.
    id: str
    vehicle_id: str
    vehicle_name: str
    category: MetricCategory
    parameter_name: str
    parameter_caption: str
    # Время события в данных, ISO-8601 (локальное время).
    event_time: str
    # Когда обнаружено, epoch millis.
    detected_at: int
    severity: Severity
    title: str
    description: str
    value: float | None = None
    score: float | None = None
    source: str


class DetectionResponse(CamelModel):
    # false — модель не загружена, список аномалий пуст (аналог isReady в приложении).
    ready: bool
    model_version: str | None = None
    anomalies: list[Anomaly] = Field(default_factory=list)


class ModelStatus(CamelModel):
    name: str
    ready: bool
    version: str | None = None


class ReadinessResponse(CamelModel):
    models: list[ModelStatus]
