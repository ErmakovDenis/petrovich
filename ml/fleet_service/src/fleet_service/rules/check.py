"""Проверка машины за период: телеметрия со стенда → правила → предиктивная аналитика и антифрод.

Результат не сохраняется (хранилище — шаг 4). Сбой или неготовность аналитики не ломает проверку по правилам:
статус каждого сервиса аналитики возвращается вместе с аномалиями.
"""

import asyncio
import logging
import time
from dataclasses import dataclass
from datetime import datetime

from ..analytics.client import AnalyticsClient
from ..config import Settings
from ..schemas.anomalies import Analytics, CheckResponse
from ..telemetry.mapper import BuildResult
from ..telemetry.service import TelemetryService
from . import baseline

log = logging.getLogger(__name__)


@dataclass
class CheckResult:
    response: CheckResponse
    telemetry: BuildResult


class AnomalyCheckService:
    def __init__(self, settings: Settings, telemetry: TelemetryService, analytics: AnalyticsClient):
        self._settings = settings
        self._telemetry = telemetry
        self._analytics = analytics

    async def check(
        self, session: str, schema_id: str, vehicle_id: str, from_: datetime, to: datetime, utc_offset_minutes: int
    ) -> CheckResult:
        started = time.monotonic()
        loaded = await self._telemetry.telemetry(session, schema_id, vehicle_id, from_, to, utc_offset_minutes)
        telemetry = loaded.telemetry
        rules = baseline.detect(telemetry, self._settings.rules)
        payload = self._analytics.payload(telemetry)
        predictive, antifraud = await asyncio.gather(
            self._analytics.run("predictive", payload), self._analytics.run("antifraud", payload)
        )
        # Как CompositeAnomalyDetector(ml, правила): порядок детекторов и без повторов по id.
        merged, seen = [], set()
        for a in [*predictive.anomalies, *antifraud.anomalies, *rules]:
            if a.id not in seen:
                seen.add(a.id)
                merged.append(a)
        response = CheckResponse(
            vehicle_id=telemetry.vehicle.id,
            anomalies=merged,
            rules_anomalies=len(rules),
            analytics=Analytics(predictive=predictive, antifraud=antifraud),
            models_ready=predictive.status == "ok" and antifraud.status == "ok",
        )
        log.info(
            "проверка: схема %s, машина %s, %s — %s: правила %d, аналитика %s/%s, всего %d, %.1f с",
            schema_id, vehicle_id, from_, to, len(rules), predictive.status, antifraud.status, len(merged),
            time.monotonic() - started,
        )
        return CheckResult(response, loaded)
