"""Клиент сервиса predictive_antifraud: POST /v1/predictive/analyze и /v1/antifraud/check с VehicleTelemetry.

Сбой аналитики не ломает проверку по правилам: клиент не бросает исключений, а возвращает статус —
`ok` (модель отработала), `not_ready` (модель не загружена), `unavailable` (сервис не настроен, не отвечает или
ответил ошибкой). Ключ X-API-Key в логи не пишется.
"""

import logging
from typing import Literal

import httpx
from pydantic import Field, ValidationError

from ..config import Settings
from ..schemas.contract import Anomaly, CamelModel, DetectionResponse, VehicleTelemetry

log = logging.getLogger(__name__)

Kind = Literal["predictive", "antifraud"]

_PATHS: dict[str, str] = {"predictive": "/v1/predictive/analyze", "antifraud": "/v1/antifraud/check"}
_NAMES: dict[str, str] = {"predictive": "предиктивная аналитика", "antifraud": "антифрод"}


class AnalyticsStatus(CamelModel):
    status: Literal["ok", "not_ready", "unavailable"]
    model_version: str | None = None
    # Почему результата нет — текстом для пользователя и ассистента.
    detail: str | None = None
    anomalies_found: int = 0
    # Сами аномалии уходят в общий список проверки, в статусе не дублируются.
    anomalies: list[Anomaly] = Field(default_factory=list, exclude=True)


class AnalyticsClient:
    def __init__(self, settings: Settings, http: httpx.AsyncClient):
        self._settings = settings
        self._http = http

    @staticmethod
    def payload(telemetry: VehicleTelemetry) -> dict:
        """Тело запроса — VehicleTelemetry в контракте; строится один раз на проверку для обоих сервисов."""
        return telemetry.model_dump(by_alias=True, mode="json")

    async def run(self, kind: Kind, payload: dict) -> AnalyticsStatus:
        s = self._settings
        name = _NAMES[kind]
        if not s.predictive_url.strip():
            return AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики не настроен на стенде")
        headers = {}
        if s.predictive_api_key is not None and s.predictive_api_key.get_secret_value():
            headers["X-API-Key"] = s.predictive_api_key.get_secret_value()
        try:
            resp = await self._http.post(
                s.predictive_url.rstrip("/") + _PATHS[kind],
                json=payload,
                headers=headers,
                timeout=s.predictive_timeout_seconds,
            )
        except httpx.HTTPError as e:
            log.warning("аналитика %s: сервис не ответил: %s", kind, type(e).__name__)
            return AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики не отвечает")
        if resp.status_code != 200:
            log.warning("аналитика %s: ответ %d", kind, resp.status_code)
            return AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики ответил ошибкой {resp.status_code}")
        try:
            result = DetectionResponse.model_validate_json(resp.content)
        except ValidationError:
            log.warning("аналитика %s: ответ не по контракту", kind)
            return AnalyticsStatus(status="unavailable", detail=f"{name}: сервис аналитики вернул ответ не по контракту")
        if not result.ready:
            return AnalyticsStatus(status="not_ready", detail=f"{name}: модель не загружена")
        return AnalyticsStatus(status="ok", model_version=result.model_version,
                               anomalies_found=len(result.anomalies), anomalies=result.anomalies)
