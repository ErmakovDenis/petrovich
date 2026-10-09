"""Проверка с сохранением (`POST /v1/anomalies/scan`) и разовый перенос локальных решений с устройства
(`POST /v1/anomalies/import`).

Каноническая сетка: проверка для сохранения всегда идёт с шагом FS_SCAN_BUCKET_MINUTES, а начало и конец периода
выравниваются вниз по этому шагу от начала эпохи в UTC. Так граница интервалов не зависит ни от длины окна, ни от
момента запуска, ни от пояса пользователя, и одно событие получает одно начало, а значит — один id
(ml/ANALYTICS.md §7, проблема 1). Эпизоды, обрезанные началом окна, сливает хранилище (store/repository.py).
"""

import asyncio
import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from ..autograph.errors import AutoGraphUnavailable, SessionInvalid, TripTablesTooLarge
from ..config import Settings
from ..rules.check import AnomalyCheckService
from ..schemas.store import (
    ImportItem,
    ImportRequest,
    ImportResponse,
    ImportUnmatched,
    ScanResponse,
    VehicleScan,
)
from ..telemetry.service import PeriodInvalid, TelemetryService, VehicleNotFound
from .repository import AnomalyRepository, Detection, to_local, to_utc

log = logging.getLogger(__name__)

_EPOCH = datetime(1970, 1, 1)


class ImportTooLarge(ValueError):
    pass


@dataclass(frozen=True)
class _Window:
    vehicle_id: str
    from_: datetime
    to: datetime
    error: str | None = None


class ScanService:
    def __init__(self, settings: Settings, telemetry: TelemetryService, checks: AnomalyCheckService,
                 store: AnomalyRepository):
        self._settings = settings
        self._telemetry = telemetry
        self._checks = checks
        self._store = store
        self.bucket = timedelta(minutes=settings.scan_bucket_minutes)

    def canonical_period(self, from_: datetime, to: datetime, utc_offset_minutes: int) -> tuple[datetime, datetime]:
        """Начало и конец периода (местное время), выровненные вниз по шагу сетки от начала эпохи в UTC."""
        step = int(self.bucket.total_seconds())

        def floor(local: datetime) -> datetime:
            seconds = (to_utc(local, utc_offset_minutes) - _EPOCH) // timedelta(seconds=1)
            return to_local(_EPOCH + timedelta(seconds=seconds - seconds % step), utc_offset_minutes)

        f, t = floor(from_), floor(to)
        if f >= t:
            raise PeriodInvalid(f"Период короче шага сетки проверки ({self._settings.scan_bucket_minutes} мин)")
        return f, t

    async def scan_vehicle(
        self, session: str, schema_id: str, vehicle_id: str, from_: datetime, to: datetime, utc_offset_minutes: int
    ) -> VehicleScan:
        """Проверка одной машины за период, уже выровненный canonical_period, с сохранением результата."""
        result = await self._checks.check(
            session, schema_id, vehicle_id, from_, to, utc_offset_minutes, bucket=self.bucket
        )
        detections = []
        for a in result.response.anomalies:
            start = _local_time(a.event_time, utc_offset_minutes)
            if start is None:
                # Время от аналитики не разобрать — без начала события нет id; правила это не затрагивает.
                log.warning("проверка с сохранением: машина %s, аномалия %s без разборчивого времени события — "
                            "не сохранена", vehicle_id, a.id)
                continue
            end = max(result.ends.get(a.id, start), start)
            detections.append(Detection(a, to_utc(start, utc_offset_minutes), to_utc(end, utc_offset_minutes)))
        saved = await self._store.save(schema_id, detections)
        # Две находки одной проверки могут слиться в одну запись — в ответе она одна, в последнем виде.
        records = {r.id: r for r, _ in saved}
        created = list(dict.fromkeys(r.id for r, new in saved if new))
        return VehicleScan(
            vehicle_id=vehicle_id,
            ok=True,
            anomalies=[r.to_api(utc_offset_minutes) for r in records.values()],
            new_ids=created,
            models_ready=result.response.models_ready,
        )

    async def scan(
        self, session: str, schema_id: str, vehicle_ids: list[str] | None, from_: datetime, to: datetime,
        utc_offset_minutes: int,
    ) -> ScanResponse:
        """Проверка машин пользователя (или выбранных) с сохранением. Ошибка одной машины не прерывает остальные;
        отметка последней проверки схемы двигается, только если ответила хотя бы одна машина."""
        self._telemetry.check_period(from_, to)
        from_c, to_c = self.canonical_period(from_, to, utc_offset_minutes)
        visible = {v.id for v in await self._telemetry.vehicles(session, schema_id)}
        ids = list(dict.fromkeys(vehicle_ids)) if vehicle_ids is not None else sorted(visible)
        limit = asyncio.Semaphore(self._settings.scan_concurrency)

        async def one(vehicle_id: str) -> VehicleScan:
            if vehicle_id not in visible:
                return VehicleScan(vehicle_id=vehicle_id, ok=False, error=str(VehicleNotFound()))
            async with limit:
                try:
                    return await self.scan_vehicle(session, schema_id, vehicle_id, from_c, to_c, utc_offset_minutes)
                except SessionInvalid:
                    raise
                except Exception as e:  # noqa: BLE001 — сбой одной машины не прерывает проверку остальных
                    return VehicleScan(vehicle_id=vehicle_id, ok=False, error=error_text(e))

        outcomes = await asyncio.gather(*(one(v) for v in ids), return_exceptions=True)
        for o in outcomes:
            if isinstance(o, BaseException):
                raise o
        results: list[VehicleScan] = list(outcomes)  # type: ignore[arg-type]
        if not ids or any(r.ok for r in results):
            await self._store.mark_scan(schema_id)
        await self._store.purge_if_due(datetime.now(UTC).replace(tzinfo=None))
        log.info(
            "проверка с сохранением: схема %s, %s — %s, машин %d, с ошибкой %d, новых аномалий %d",
            schema_id, from_c, to_c, len(results), sum(not r.ok for r in results),
            sum(len(r.new_ids) for r in results),
        )
        return ScanResponse(
            from_=from_c, to=to_c, results=results, last_scan_at=await self._store.last_scan(schema_id)
        )

    # --- перенос решений с устройства ---

    def _windows(self, vehicle_id: str, times: list[datetime], now_local: datetime) -> list[_Window]:
        """Периоды проверки вокруг событий с устройства: близкие события — в одном периоде не длиннее предела."""
        margin = timedelta(minutes=self._settings.import_scan_margin_minutes)
        max_span = max(timedelta(hours=self._settings.telemetry_max_period_hours) - 2 * margin, timedelta(0))
        clusters: list[list[datetime]] = []
        for t in sorted(times):
            if clusters and t - clusters[-1][-1] <= 2 * margin and t - clusters[-1][0] <= max_span:
                clusters[-1].append(t)
            else:
                clusters.append([t])
        windows = []
        for c in clusters:
            # Конец — не позже текущего момента и не раньше конца эпизода: правила с минимальной длительностью
            # находят событие только после неё.
            to = min(c[-1] + margin, now_local)
            windows.append(_Window(vehicle_id, c[0] - margin, max(to, c[0] - margin + self.bucket)))
        return windows

    async def import_decisions(
        self, session: str, schema_id: str, user_name: str | None, request: ImportRequest
    ) -> ImportResponse:
        """Разовый перенос локальной истории: стенд проверяет периоды вокруг событий с устройства (события появляются
        в хранилище с id стенда), затем сопоставляет каждую запись по машине, типу, параметру и времени события
        с допуском FS_IMPORT_TIME_TOLERANCE_MINUTES. Решение переносится, если на стенде его ещё нет."""
        s = self._settings
        if len(request.items) > s.import_max_items:
            raise ImportTooLarge(f"За один раз переносится не больше {s.import_max_items} записей")
        offset = request.utc_offset_minutes
        now_local = to_local(datetime.now(UTC).replace(tzinfo=None), offset)
        visible = {v.id for v in await self._telemetry.vehicles(session, schema_id)}

        by_vehicle: dict[str, list[ImportItem]] = {}
        for item in request.items:
            by_vehicle.setdefault(item.vehicle_id, []).append(item)
        names = {vehicle_id: items[0].vehicle_name or vehicle_id for vehicle_id, items in by_vehicle.items()}
        planned = [
            w for vehicle_id, items in by_vehicle.items() if vehicle_id in visible
            for w in self._windows(vehicle_id, [i.event_time.replace(tzinfo=None) for i in items], now_local)
        ]
        # Периоды проверяются параллельно (FS_SCAN_CONCURRENCY), как машины в /scan: перенос — одна долгая операция,
        # приложение отправляет его по машине, чтобы каждый запрос укладывался в срок ожидания.
        limit = asyncio.Semaphore(s.scan_concurrency)

        async def check_window(w: _Window) -> _Window:
            async with limit:
                try:
                    f, t = self.canonical_period(w.from_, w.to, offset)
                    await self.scan_vehicle(session, schema_id, w.vehicle_id, f, t, offset)
                    return w
                except SessionInvalid:
                    raise
                except Exception as e:  # noqa: BLE001 — недоступный период отмечается в отчёте
                    return _Window(w.vehicle_id, w.from_, w.to, error_text(e))

        outcomes = await asyncio.gather(*(check_window(w) for w in planned), return_exceptions=True)
        for o in outcomes:
            if isinstance(o, BaseException):
                raise o
        windows: list[_Window] = list(outcomes)  # type: ignore[arg-type]
        scan_errors = [f"{names[w.vehicle_id]}, {w.from_:%d.%m %H:%M} — {w.to:%d.%m %H:%M}: {w.error}"
                       for w in windows if w.error]

        tolerance = timedelta(minutes=s.import_time_tolerance_minutes)
        applied = already = restored = not_found = 0
        unmatched: list[ImportUnmatched] = []
        for item in request.items:
            event = item.event_time.replace(tzinfo=None)
            match = None
            if item.vehicle_id in visible:
                match = await self._store.find_match(
                    schema_id, item.vehicle_id, item.kind, item.parameter_name, to_utc(event, offset), tolerance
                )
            if item.resolution is None:
                restored += match is not None
                not_found += match is None
                continue
            if match is None:
                unmatched.append(ImportUnmatched(
                    local_id=item.local_id, vehicle_id=item.vehicle_id, vehicle_name=item.vehicle_name,
                    title=item.title, event_time=event, resolution=item.resolution, reason=item.reason,
                    why=self._why_unmatched(item, event, visible, windows),
                ))
                continue
            outcome = await self._store.resolve(
                schema_id, match.id, item.resolution, item.reason, user_name, origin="import", only_if_open=True
            )
            if outcome is not None and outcome[1]:
                applied += 1
            else:
                already += 1
        log.info(
            "перенос решений: схема %s, записей %d, решений перенесено %d, уже были %d, без пары %d, "
            "аномалий найдено %d, не найдено %d, ошибок проверки %d",
            schema_id, len(request.items), applied, already, len(unmatched), restored, not_found, len(scan_errors),
        )
        return ImportResponse(
            decisions=sum(i.resolution is not None for i in request.items), applied=applied, already_resolved=already,
            unmatched=unmatched, restored=restored, not_found=not_found, scan_errors=scan_errors,
        )

    @staticmethod
    def _why_unmatched(item: ImportItem, event: datetime, visible: set[str], windows: list[_Window]) -> str:
        if item.vehicle_id not in visible:
            return "машина недоступна этому пользователю на стенде"
        failed = next((w for w in windows if w.vehicle_id == item.vehicle_id and w.error and w.from_ <= event <= w.to),
                      None)
        if failed is not None:
            return f"период не удалось проверить: {failed.error}"
        return "стенд не нашёл это событие при проверке того же периода"


def _local_time(text: str, utc_offset_minutes: int) -> datetime | None:
    """Время события (местное, без пояса). С поясом — переводится в местное время пользователя; не ISO — None."""
    try:
        t = datetime.fromisoformat(text)
    except (TypeError, ValueError):
        return None
    if t.tzinfo is not None:
        t = to_local(t.astimezone(UTC).replace(tzinfo=None), utc_offset_minutes)
    return t


def error_text(e: Exception) -> str:
    if isinstance(e, (VehicleNotFound, PeriodInvalid, AutoGraphUnavailable)):
        return str(e)
    if isinstance(e, TripTablesTooLarge):
        return f"AutoGRAPH вернул сбойный ответ: {e}"
    log.exception("проверка с сохранением: необработанная ошибка")
    return "внутренняя ошибка стенда"
