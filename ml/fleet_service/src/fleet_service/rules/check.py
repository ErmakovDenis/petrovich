"""Проверка машины за период: телеметрия со стенда → правила → предиктивная аналитика и антифрод.

`POST /v1/anomalies/check` результат не сохраняет; проверку с сохранением (store/scan.py) делает тот же код на
канонической сетке. Сбой или неготовность аналитики не ломает проверку по правилам: статус каждого сервиса аналитики
возвращается вместе с аномалиями.
"""

import asyncio
import logging
import time
from dataclasses import dataclass
from datetime import datetime, timedelta

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
    # Конец эпизода по id аномалии правил (rules.baseline.Episode); у аналитики эпизода нет — конец = начало.
    ends: dict[str, datetime]


class AnomalyCheckService:
    def __init__(self, settings: Settings, telemetry: TelemetryService, analytics: AnalyticsClient):
        self._settings = settings
        self._telemetry = telemetry
        self._analytics = analytics

    async def check(
        self, session: str, schema_id: str, vehicle_id: str, from_: datetime, to: datetime, utc_offset_minutes: int,
        bucket: timedelta | None = None,
    ) -> CheckResult:
        """[bucket] — шаг интервалов; по умолчанию — как в приложении (зависит от длины периода)."""
        started = time.monotonic()
        loaded = await self._telemetry.telemetry(
            session, schema_id, vehicle_id, from_, to, utc_offset_minutes, bucket
        )
        telemetry = loaded.telemetry
        episodes = baseline.detect_episodes(telemetry, self._settings.rules)
        rules = [e.anomaly for e in episodes]
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
        return CheckResult(response, loaded, {e.anomaly.id: e.end for e in episodes})
