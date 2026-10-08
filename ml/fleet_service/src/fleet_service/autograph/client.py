"""Клиент AutoGRAPH для загрузки телеметрии от имени пользователя: EnumDevices, EnumParameters, GetTripTables.

Повторяет `AutoGraphTelemetryRepository` и `ApiFactory` приложения: даты `yyyyMMdd-HHmm` в поясе токена (UTCOffset
при Login), период частями по FS_AUTOGRAPH_CHUNK_HOURS, gzip, до FS_AUTOGRAPH_RETRIES попыток при сетевой ошибке.
Токен уходит в query `session=` — в логах его маскирует log_masking.
"""

import asyncio
import logging
from datetime import datetime, timedelta
from typing import Any

import httpx
import ijson

from ..config import Settings
from ..schemas.contract import Vehicle
from ..telemetry.mapper import TripTablesBuilder
from ..telemetry.parameters import RParameter
from .errors import AutoGraphUnavailable, SessionInvalid

log = logging.getLogger(__name__)

API_DATE = "%Y%m%d-%H%M"


def chunk_ranges(from_: datetime, to: datetime, chunk: timedelta) -> list[tuple[datetime, datetime]]:
    """Части периода: [from, from+chunk), … последняя заканчивается в to."""
    ranges = []
    start = from_
    while start < to:
        ranges.append((start, min(start + chunk, to)))
        start += chunk
    return ranges


class AutoGraphClient:
    def __init__(self, settings: Settings, http: httpx.AsyncClient):
        self._settings = settings
        self._http = http

    def _url(self, method: str) -> str:
        return self._settings.autograph_base_url.rstrip("/") + "/" + method

    async def _send(self, method: str, params: dict[str, Any], timeout: float, stream: bool = False) -> httpx.Response:
        """Запрос с повторами при сетевой ошибке; 401/403 → SessionInvalid, иной код ≠ 200 → AutoGraphUnavailable."""
        attempts = self._settings.autograph_retries
        last: Exception | None = None
        for attempt in range(1, attempts + 1):
            request = self._http.build_request("GET", self._url(method), params=params, timeout=timeout)
            try:
                resp = await self._http.send(request, stream=stream)
            except httpx.TransportError as e:
                last = e
                log.warning("AutoGRAPH %s: попытка %d из %d не удалась: %s", method, attempt, attempts, type(e).__name__)
                if attempt < attempts:
                    await asyncio.sleep(self._settings.autograph_retry_delay_seconds * attempt)
                continue
            if resp.status_code == 200:
                return resp
            await resp.aclose()
            if resp.status_code in (401, 403):
                raise SessionInvalid()
            log.warning("AutoGRAPH %s ответил %d", method, resp.status_code)
            raise AutoGraphUnavailable(f"AutoGRAPH ответил ошибкой {resp.status_code}")
        raise AutoGraphUnavailable(f"AutoGRAPH недоступен: нет ответа после {attempts} попыток") from last

    async def _json(self, method: str, params: dict[str, Any]) -> Any:
        resp = await self._send(method, params, self._settings.autograph_timeout_seconds)
        try:
            return resp.json()
        except ValueError:
            # С недействительным токеном AutoGRAPH может ответить 200 не JSON-ом — как и при проверке сессии.
            raise SessionInvalid() from None

    async def enum_devices(self, session: str, schema_id: str) -> list[Vehicle]:
        """Машины схемы, доступные пользователю (Allowed), по имени — как vehicles() в приложении."""
        data = await self._json("EnumDevices", {"session": session, "schemaID": schema_id})
        if not isinstance(data, dict):
            raise SessionInvalid()
        groups = {str(g.get("ID")): g.get("Name") for g in data.get("Groups") or [] if isinstance(g, dict)}
        vehicles = []
        for item in data.get("Items") or []:
            if not isinstance(item, dict) or item.get("ID") is None or item.get("Allowed", True) is False:
                continue
            vid = str(item["ID"])
            name = item.get("Name")
            if name is None:
                serial = item.get("Serial")
                name = f"ТС {serial if serial is not None else vid[:8]}"
            parent = item.get("ParentID")
            group = groups.get(str(parent)) if parent is not None else None
            vehicles.append(Vehicle(id=vid, name=str(name), group=group))
        return sorted(vehicles, key=lambda v: v.name)

    async def enum_parameters(self, session: str, schema_id: str, vehicle_id: str) -> list[RParameter]:
        data = await self._json("EnumParameters", {"session": session, "schemaID": schema_id, "IDs": vehicle_id})
        device = data.get(vehicle_id) if isinstance(data, dict) else None
        raw = device.get("OnlineParams") if isinstance(device, dict) else None
        return [RParameter.from_json(p) for p in raw or [] if isinstance(p, dict) and p.get("Name") is not None]

    def _batches(self, base: dict[str, Any], names: list[str]) -> list[list[str]]:
        """Делит параметры так, чтобы адрес запроса не превышал FS_AUTOGRAPH_MAX_QUERY_CHARS."""
        limit = self._settings.autograph_max_query_chars
        url = self._url("GetTripTables")

        def length(batch: list[str]) -> int:
            return len(str(httpx.URL(url, params={**base, "onlineParams": ",".join(batch)})))

        batches: list[list[str]] = []
        current: list[str] = []
        for name in names:
            if current and length(current + [name]) > limit:
                batches.append(current)
                current = []
            current.append(name)
        if current:
            batches.append(current)
        return batches

    async def trip_tables(
        self, session: str, schema_id: str, vehicle_id: str, builder: TripTablesBuilder, names: list[str]
    ) -> None:
        """GetTripTables за период builder'а: части по FS_AUTOGRAPH_CHUNK_HOURS последовательно (память на разбор
        нужна только под один поток), каждая — потоково в builder."""
        chunk = timedelta(hours=self._settings.autograph_chunk_hours)
        for sd, ed in chunk_ranges(builder.from_, builder.to, chunk):
            base = {
                "session": session, "schemaID": schema_id, "IDs": vehicle_id,
                "SD": sd.strftime(API_DATE), "ED": ed.strftime(API_DATE), "tripSplitterIndex": -1,
            }
            for batch in self._batches(base, names):
                params = {**base, "onlineParams": ",".join(batch)}
                resp = await self._send(
                    "GetTripTables", params, self._settings.autograph_trip_tables_timeout_seconds, stream=True
                )
                try:
                    reader = builder.reader()
                    async for data in resp.aiter_bytes():
                        reader.feed(data)
                    reader.close()
                except httpx.TransportError as e:
                    raise AutoGraphUnavailable("AutoGRAPH оборвал передачу данных") from e
                except (ijson.JSONError, ValueError) as e:
                    log.warning("AutoGRAPH GetTripTables: некорректный ответ: %s", type(e).__name__)
                    raise AutoGraphUnavailable("AutoGRAPH вернул некорректный ответ") from e
                finally:
                    await resp.aclose()
