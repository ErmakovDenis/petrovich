"""Предиктивная аналитика: признаки деградации узлов до отказа (двигатель, охлаждение, АКБ)."""

from ..config import Settings
from ..models.registry import ModelName, ModelRegistry
from ..schemas.results import DetectionResponse
from ..schemas.telemetry import MetricCategory, VehicleTelemetry
from .detection import DetectionProfile, detect


class PredictiveService:
    def __init__(self, registry: ModelRegistry, settings: Settings):
        self.registry = registry
        self.profile = DetectionProfile(
            kind="predict",
            source="ML: предиктивная аналитика",
            title="Риск неисправности",
            threshold=settings.predictive_threshold,
            critical_threshold=settings.critical_threshold,
            culprit_categories=frozenset({MetricCategory.ENGINE, MetricCategory.POWER}),
        )

    def analyze(self, telemetry: VehicleTelemetry) -> DetectionResponse:
        return detect(telemetry, self.registry.get(ModelName.PREDICTIVE), self.profile)
