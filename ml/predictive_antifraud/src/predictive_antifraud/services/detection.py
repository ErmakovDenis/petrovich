"""Общий путь от телеметрии к аномалиям: признаки → оценки модели → эпизоды → Anomaly."""

import time
from dataclasses import dataclass

import numpy as np

from ..features.extractor import FeatureMatrix, extract
from ..models.base import ScoringModel
from ..schemas.results import Anomaly, DetectionResponse, Severity
from ..schemas.telemetry import MetricCategory, VehicleTelemetry


@dataclass(frozen=True)
class DetectionProfile:
    # Тип события в id (ml|<kind>|...), по нему приложение группирует аномалии.
    kind: str
    source: str
    title: str
    threshold: float
    critical_threshold: float
    # Среди каких категорий искать параметр-«виновник»; None — среди всех.
    culprit_categories: frozenset[MetricCategory] | None = None


def detect(telemetry: VehicleTelemetry, model: ScoringModel | None, profile: DetectionProfile) -> DetectionResponse:
    if model is None:
        return DetectionResponse(ready=False)

    features = extract(telemetry)
    if model.expected_features is not None:
        features = features.reindex(model.expected_features)
    if not features.timestamps or not features.feature_names:
        return DetectionResponse(ready=True, model_version=model.version)

    scores = np.asarray(model.score(features), dtype=np.float32).reshape(-1)
    if scores.shape[0] != len(features.timestamps):
        raise ValueError(f"Модель вернула {scores.shape[0]} оценок на {len(features.timestamps)} строк")

    now = int(time.time() * 1000)
    anomalies = [
        _to_anomaly(telemetry, features, scores, start, end, profile, now)
        for start, end in _episodes(scores >= profile.threshold)
    ]
    return DetectionResponse(ready=True, model_version=model.version, anomalies=anomalies)


def _episodes(flags: np.ndarray) -> list[tuple[int, int]]:
    """Непрерывные участки строк над порогом: [start, end). Один эпизод — одна аномалия, а не по штуке на строку."""
    result, start = [], None
    for i, flag in enumerate(flags):
        if flag and start is None:
            start = i
        elif not flag and start is not None:
            result.append((start, i))
            start = None
    if start is not None:
        result.append((start, len(flags)))
    return result


def _culprit(features: FeatureMatrix, row: int, profile: DetectionProfile) -> int:
    """Параметр с наибольшим относительным отклонением от медианы — как в MlAnomalyDetector.kt."""
    candidates = [
        j for j, name in enumerate(features.feature_names)
        if profile.culprit_categories is None
        or (name in features.parameters and features.parameters[name].category in profile.culprit_categories)
    ] or list(range(len(features.feature_names)))
    median = np.median(features.rows[:, candidates], axis=0)
    deviation = np.abs(features.rows[row, candidates] - median) / (np.abs(median) + 1e-3)
    return candidates[int(np.argmax(deviation))]


def _to_anomaly(
    telemetry: VehicleTelemetry,
    features: FeatureMatrix,
    scores: np.ndarray,
    start: int,
    end: int,
    profile: DetectionProfile,
    now: int,
) -> Anomaly:
    peak = start + int(np.argmax(scores[start:end]))
    score = float(scores[peak])
    column = _culprit(features, peak, profile)
    name = features.feature_names[column]
    info = features.parameters.get(name)
    caption = info.caption if info else name
    # id привязан к началу эпизода: при повторных сканах (окно 3 ч каждые 15 мин) он не меняется.
    event_time = features.timestamps[start].isoformat()
    return Anomaly(
        id=f"ml|{profile.kind}|{telemetry.vehicle.id}|{name}|{event_time}",
        vehicle_id=telemetry.vehicle.id,
        vehicle_name=telemetry.vehicle.name,
        category=info.category if info else MetricCategory.ENGINE,
        parameter_name=name,
        parameter_caption=caption,
        event_time=event_time,
        detected_at=now,
        severity=Severity.CRITICAL if score > profile.critical_threshold else Severity.WARNING,
        title=f"{profile.title}: {caption}",
        description=f"Модель оценила состояние как аномальное (score={score:.2f}), длительность — {end - start} отсч.",
        value=float(features.rows[peak, column]),
        score=score,
        source=profile.source,
    )
