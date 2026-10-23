"""Клиент AutoGRAPH для загрузки телеметрии от имени пользователя: EnumDevices, EnumParameters, GetTripTables;
Login и EnumSchemas — для фоновой проверки, когда пользователь разрешил стенду входить самостоятельно.

Повторяет `AutoGraphTelemetryRepository` и `ApiFactory` приложения: даты `yyyyMMdd-HHmm` в поясе токена (UTCOffset
при Login), период частями по FS_AUTOGRAPH_CHUNK_HOURS, gzip, до FS_AUTOGRAPH_RETRIES попыток при сетевой ошибке.
Токен, логин и пароль уходят в query (`session=`, `UserName=`, `Password=`) — в логах их маскирует log_masking.
"""

import asyncio
import logging
import random
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from typing import Any

import httpx
import ijson

from ..breaker import BreakerOpen, CircuitBreaker, counts_as_failure
from ..config import Settings
from ..limits import UpstreamLimiter
from ..schemas.contract import Vehicle
from ..telemetry.mapper import TripTablesBuilder
from ..telemetry.parameters import RParameter
from .errors import AutoGraphUnavailable, LoginRejected, SessionInvalid

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


def retry_delay(settings: Settings, attempt: int) -> float:
    """Пауза перед повтором после [attempt]-й неудачной попытки: экспоненциальная, с jitter 0,5…1."""
    base = settings.autograph_retry_delay_seconds * 2 ** (attempt - 1)
    return min(settings.autograph_retry_max_seconds, base) * random.uniform(0.5, 1)


class AutoGraphClient:
    def __init__(self, settings: Settings, http: httpx.AsyncClient, limiter: UpstreamLimiter, breaker: CircuitBreaker):
        """[limiter] и [breaker] — общие на процесс для AutoGRAPH (main.create_app), с проверкой сессии."""
        self._settings = settings
        self._http = http
        self._limiter = limiter
        self._breaker = breaker

    def _url(self, method: str) -> str:
        return self._settings.autograph_base_url.rstrip("/") + "/" + method

    @asynccontextmanager
    async def _request(
        self, method: str, params: dict[str, Any], timeout: float, stream: bool = False
    ) -> AsyncIterator[httpx.Response]:
        """Запрос под breaker'ом AutoGRAPH. Сбой для breaker'а — запрос целиком (после всех повторов) не получил
        ответа, получил 5xx или оборвался при чтении потока; 401/403 и прочие 4xx — AutoGRAPH работает. Breaker
        открыт — сразу AutoGraphUnavailable, без запроса; начатые повторы он не прерывает."""
        try:
            with self._breaker.attempt() as outcome:
                try:
                    async with self._attempts(method, params, timeout, stream) as resp:
                        yield resp
                except AutoGraphUnavailable as e:
                    if e.upstream_failure:
                        outcome.fail()
                    raise
                except httpx.TransportError:
                    outcome.fail()
                    raise
        except BreakerOpen as e:
            log.warning("AutoGRAPH %s: breaker открыт — запрос не отправлен", method)
            raise AutoGraphUnavailable(
                f"AutoGRAPH недоступен: много сбоев подряд, повтор через {max(1, round(e.retry_after))} с"
            ) from None

    @asynccontextmanager
    async def _attempts(
        self, method: str, params: dict[str, Any], timeout: float, stream: bool
    ) -> AsyncIterator[httpx.Response]:
        """Ответ 200 с повторами при сетевой ошибке; 401/403 → SessionInvalid, иной код → AutoGraphUnavailable.
        Место в пределе FS_AUTOGRAPH_CONCURRENCY занято на каждую попытку, у потокового ответа — пока его читают;
        на паузу между попытками место освобождается."""
        attempts = self._settings.autograph_retries
        last: Exception | None = None
        for attempt in range(1, attempts + 1):
            request = self._http.build_request("GET", self._url(method), params=params, timeout=timeout)
            slot = await self._limiter.acquire()
            try:
                resp = await self._http.send(request, stream=stream)
            except httpx.TransportError as e:
                slot.release()
                last = e
                log.warning("AutoGRAPH %s: попытка %d из %d не удалась: %s", method, attempt, attempts, type(e).__name__)
                if attempt < attempts:
                    await asyncio.sleep(retry_delay(self._settings, attempt))
                continue
            except BaseException:
                slot.release()
                raise
            try:
                if resp.status_code == 200:
                    yield resp
                    return
            finally:
                # Место освобождается, даже если закрытие ответа прервано (отмена запроса, обрыв связи).
                try:
                    await resp.aclose()
                finally:
                    slot.release()
            if resp.status_code in (401, 403):
                raise SessionInvalid()
            log.warning("AutoGRAPH %s ответил %d", method, resp.status_code)
            raise AutoGraphUnavailable(f"AutoGRAPH ответил ошибкой {resp.status_code}",
                                       upstream_failure=counts_as_failure(resp.status_code))
        raise AutoGraphUnavailable(f"AutoGRAPH недоступен: нет ответа после {attempts} попыток") from last

    async def _json(self, method: str, params: dict[str, Any]) -> Any:
        async with self._request(method, params, self._settings.autograph_timeout_seconds) as resp:
            try:
                return resp.json()
            except ValueError:
                # С недействительным токеном AutoGRAPH может ответить 200 не JSON-ом — как и при проверке сессии.
                raise SessionInvalid() from None

    async def login(self, user_name: str, password: str, utc_offset_minutes: int) -> str:
        """Вход по логину и паролю (фоновая проверка с согласия пользователя): токен сессии в поясе UTCOffset —
        как Login в приложении. Неверные логин или пароль — LoginRejected."""
        params = {"UserName": user_name, "Password": password, "UTCOffset": utc_offset_minutes}
        try:
            async with self._request("Login", params, self._settings.autograph_timeout_seconds) as resp:
                token = resp.text.strip().strip('"')
        except SessionInvalid:
            raise LoginRejected() from None
        if not token:
            raise LoginRejected()
        return token

    async def schema_ids(self, session: str) -> set[str]:
        """Схемы, доступные пользователю токена (EnumSchemas)."""
        data = await self._json("EnumSchemas", {"session": session})
        if not isinstance(data, list):
            raise SessionInvalid()
        return {str(s.get("ID")) for s in data if isinstance(s, dict)}

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
                timeout = self._settings.autograph_trip_tables_timeout_seconds
                async with self._request("GetTripTables", params, timeout, stream=True) as resp:
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
