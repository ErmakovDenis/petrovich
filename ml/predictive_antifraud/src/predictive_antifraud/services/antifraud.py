"""Антифрод: сливы и фиктивные заправки топлива, расход, не согласующийся с пробегом и работой двигателя."""

from ..config import Settings
from ..models.registry import ModelName, ModelRegistry
from ..schemas.results import DetectionResponse
from ..schemas.telemetry import MetricCategory, VehicleTelemetry
from .detection import DetectionProfile, detect


class AntifraudService:
    def __init__(self, registry: ModelRegistry, settings: Settings):
        self.registry = registry
        self.profile = DetectionProfile(
            kind="fraud",
            source="ML: антифрод",
            title="Подозрение на мошенничество",
            threshold=settings.antifraud_threshold,
            critical_threshold=settings.critical_threshold,
            culprit_categories=frozenset({MetricCategory.FUEL, MetricCategory.MOTION}),
        )

    def check(self, telemetry: VehicleTelemetry) -> DetectionResponse:
        return detect(telemetry, self.registry.get(ModelName.ANTIFRAUD), self.profile)
