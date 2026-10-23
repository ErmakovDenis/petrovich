"""Телеметрия стенда: список машин пользователя и VehicleTelemetry за период с кэшами.

Готовая телеметрия кэшируется по ключу «схема + машина + период + пояс», а не по пользователю: пользователи одной
схемы видят одни и те же данные. Закрытый период (уже не изменится) живёт в кэше дольше открытого. Пояс входит в ключ, потому что AutoGRAPH трактует SD/ED и отдаёт время в поясе,
заданном при Login токена. Перед выдачей (в том числе из кэша) машина проверяется по EnumDevices этого пользователя.
"""

import logging
import time
from collections.abc import Awaitable, Callable, Hashable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import TypeVar

from ..autograph.client import AutoGraphClient
from ..autograph.errors import SessionInvalid
from ..autograph.session import session_key
from ..cache import TtlCache
from ..config import Settings
from ..schemas.contract import Vehicle
from .mapper import BuildResult, TripTablesBuilder
from .parameters import Aggregation, select

log = logging.getLogger(__name__)

T = TypeVar("T")


class VehicleNotFound(Exception):
    def __init__(self) -> None:
        super().__init__("Машина не найдена или недоступна этому пользователю")


class PeriodInvalid(ValueError):
    pass


@dataclass(frozen=True)
class _Selected:
    parameters: list
    aggregation: dict[str, Aggregation]


async def _shared(
    cache: TtlCache[T], key: Hashable, load: Callable[[], Awaitable[T]], ttl: float | None = None
) -> T:
    """Значение из кэша, общего для пользователей схемы. Общая загрузка могла идти с чужим токеном, который истёк:
    тогда повторяем сами, своим токеном и без ожидания чужих загрузок. Истёк наш — 401 будет и здесь."""
    try:
        return await cache.get(key, load, ttl)
    except SessionInvalid:
        value = await load()
        cache.put(key, value, ttl)
        return value


def utc_now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


class TelemetryService:
    def __init__(self, settings: Settings, client: AutoGraphClient, clock: Callable[[], datetime] = utc_now):
        """[clock] — текущее время UTC без пояса (в тестах — подставное)."""
        self._settings = settings
        self._client = client
        self.clock = clock
        s = settings
        self._devices: TtlCache[list[Vehicle]] = TtlCache(s.devices_cache_ttl_seconds, s.auth_cache_max_entries)
        self._parameters: TtlCache[_Selected] = TtlCache(s.parameters_cache_ttl_seconds, s.parameters_cache_max_entries)
        self._telemetry: TtlCache[BuildResult] = TtlCache(s.telemetry_cache_ttl_seconds, s.telemetry_cache_max_entries)

    def cache_sizes(self) -> dict[str, int]:
        """Записей в кэшах (для /v1/status)."""
        return {"devices": len(self._devices), "parameters": len(self._parameters), "telemetry": len(self._telemetry)}

    async def vehicles(self, session: str, schema_id: str) -> list[Vehicle]:
        return await self._devices.get(session_key(session, schema_id), lambda: self._client.enum_devices(session, schema_id))

    async def fresh_vehicles(self, session: str, schema_id: str) -> list[Vehicle]:
        """Машины мимо кэша (заодно проверка, что токен жив), результат кладётся в кэш для следующих запросов."""
        vehicles = await self._client.enum_devices(session, schema_id)
        self._devices.put(session_key(session, schema_id), vehicles)
        return vehicles

    async def vehicle(self, session: str, schema_id: str, vehicle_id: str) -> Vehicle:
        for v in await self.vehicles(session, schema_id):
            if v.id == vehicle_id:
                return v
        raise VehicleNotFound()

    def check_period(self, from_: datetime, to: datetime) -> None:
        if from_.tzinfo is not None or to.tzinfo is not None:
            raise PeriodInvalid("Время периода передаётся без часового пояса — как местное время пользователя")
        if from_ >= to:
            raise PeriodInvalid("Начало периода должно быть раньше конца")
        limit = self._settings.telemetry_max_period_hours
        if to - from_ > timedelta(hours=limit):
            raise PeriodInvalid(f"Период длиннее {limit} ч")

    async def telemetry(
        self, session: str, schema_id: str, vehicle_id: str, from_: datetime, to: datetime, utc_offset_minutes: int,
        bucket: timedelta | None = None,
    ) -> BuildResult:
        """[bucket] — шаг интервалов; по умолчанию зависит от длины периода, как в приложении (bucket_for)."""
        self.check_period(from_, to)
        vehicle = await self.vehicle(session, schema_id, vehicle_id)
        key = self.cache_key(schema_id, vehicle_id, from_, to, utc_offset_minutes, bucket)
        cached = self._telemetry.peek(key)
        if cached is not None and cached.closed:
            log.info("телеметрия: схема %s, машина %s, %s — %s — из кэша (закрытый период)", schema_id, vehicle_id,
                     from_, to)
        # Закрыт ли период — на момент загрузки: запись, загруженная открытой, живёт короткий срок, даже если
        # период закрылся, пока она в кэше (в ней могло не хватать поздних точек).
        closed = self.is_closed(to, utc_offset_minutes)
        return await _shared(
            self._telemetry, key, lambda: self._load(session, schema_id, vehicle, from_, to, bucket, closed),
            self.cache_ttl(closed),
        )

    @staticmethod
    def cache_key(
        schema_id: str, vehicle_id: str, from_: datetime, to: datetime, utc_offset_minutes: int, bucket: timedelta | None
    ) -> Hashable:
        """Ключ телеметрии в кэше — его же использует кэш результатов аналитики (rules/check.py)."""
        return schema_id, vehicle_id, from_, to, utc_offset_minutes, bucket

    def is_closed(self, to: datetime, utc_offset_minutes: int) -> bool:
        """Период закрыт: его конец (местное время пользователя) раньше «сейчас − FS_TELEMETRY_SETTLE_MINUTES» —
        AutoGRAPH уже не дошлёт в него точек, данные не изменятся."""
        now_local = self.clock() + timedelta(minutes=utc_offset_minutes)
        return to <= now_local - timedelta(minutes=self._settings.telemetry_settle_minutes)

    def cache_ttl(self, closed: bool) -> float:
        s = self._settings
        return s.telemetry_closed_cache_ttl_seconds if closed else s.telemetry_cache_ttl_seconds

    async def _selected(self, session: str, schema_id: str, vehicle_id: str) -> _Selected:
        async def load() -> _Selected:
            available = await self._client.enum_parameters(session, schema_id, vehicle_id)
            return _Selected(*select(available))

        return await _shared(self._parameters, (schema_id, vehicle_id), load)

    async def _load(
        self, session: str, schema_id: str, vehicle: Vehicle, from_: datetime, to: datetime, bucket: timedelta | None,
        closed: bool = False,
    ) -> BuildResult:
        started = time.monotonic()
        selected = await self._selected(session, schema_id, vehicle.id)
        builder = TripTablesBuilder(
            vehicle, from_, to, selected.parameters, selected.aggregation, bucket=bucket,
            max_points=self._settings.trip_tables_max_points,
        )
        if selected.parameters:
            await self._client.trip_tables(session, schema_id, vehicle.id, builder, [p.name for p in selected.parameters])
        result = builder.build()
        result.closed = closed
        tables = list(result.telemetry.tables.values())
        values = sum(len(c.values) for t in tables for c in t.columns)
        stamps = sum(len(t.timestamps) for t in tables)
        # Размер записи кэша — оценка для выбора *_MAX_ENTRIES: число в списке Python ~32 байта (объект + ссылка),
        # datetime ~56, плюс ~1 КБ на столбец (параметр, список).
        size_kb = (values * 32 + stamps * 56 + sum(len(t.columns) for t in tables) * 1024) // 1024
        log.info(
            "телеметрия: схема %s, машина %s, %s — %s, параметров %d, столбцов %d, ячеек %d (~%d КБ)%s, %.1f с",
            schema_id, vehicle.id, from_, to, len(selected.parameters), sum(len(t.columns) for t in tables),
            values + stamps, size_kb, ", закрытый период" if closed else "", time.monotonic() - started,
        )
        return result
