"""Фоновая проверка на стенде: раз в FS_BACKGROUND_INTERVAL_MINUTES проверяет с сохранением каждую схему, где
приложение выдало доступ, за последние FS_BACKGROUND_WINDOW_HOURS — без телефона.

Одна схема проверяется один раз, сколько бы пользователей и устройств её ни открывали:
- доступы схемы группируются по пользователю (логину): от каждого пользователя берётся один рабочий доступ, самый
  свежий, — EnumDevices показывает, какие машины ему видны (видимость у пользователей схемы может различаться);
- каждая машина проверяется один раз, с доступом первого пользователя, которому она видна. Тяжёлые запросы
  (EnumParameters, GetTripTables) не удваиваются, лёгкий EnumDevices — по одному на пользователя.
Параллельно — не больше FS_BACKGROUND_CONCURRENCY машин по всем схемам.

Токен истёк: с паролем (согласие пользователя) стенд входит сам, без пароля доступ ждёт нового токена от
приложения. Отметка последней проверки схемы двигается, только если ответила хотя бы одна машина (как
AnomalyScanner в приложении), иначе сводка сообщит «всё в порядке» при недоступном источнике.
"""

import asyncio
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from ..autograph.client import AutoGraphClient
from ..autograph.errors import AutoGraphUnavailable, LoginRejected, SessionInvalid
from ..config import Settings
from ..schemas.contract import Vehicle
from ..store.repository import AnomalyRepository, to_local
from ..store.scan import ScanService, error_text
from ..telemetry.service import TelemetryService
from .access import AccessRepository, Grant, now_ms

log = logging.getLogger(__name__)

TOKEN_EXPIRED = "Сессия AutoGRAPH истекла — фоновая проверка возобновится, когда приложение обновит доступ"
PASSWORD_REJECTED = "AutoGRAPH не принял сохранённый пароль — пароль удалён со стенда, разрешите вход заново"


def utc_now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


@dataclass
class SchemaRun:
    """Итог фоновой проверки схемы."""

    schema_id: str
    vehicles: int = 0
    failed: int = 0
    new: int = 0
    # Доступов, с которыми удалось войти, из всех доступов схемы.
    usable: int = 0
    grants: int = 0
    marked: bool = False
    errors: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class _Owner:
    grant: Grant
    session: str


class BackgroundScanner:
    def __init__(self, settings: Settings, telemetry: TelemetryService, client: AutoGraphClient, scans: ScanService,
                 store: AnomalyRepository, access: AccessRepository, clock: Callable[[], datetime] = utc_now):
        """[clock] — текущее время UTC без пояса (в тестах — подставное)."""
        self._settings = settings
        self._telemetry = telemetry
        self._client = client
        self._scans = scans
        self._store = store
        self._access = access
        self.clock = clock
        self._limit = asyncio.Semaphore(settings.background_concurrency)
        self._running = asyncio.Lock()

    async def run_forever(self) -> None:
        interval = self._settings.background_interval_minutes * 60
        log.info("фоновая проверка: каждые %s мин, окно %d ч", self._settings.background_interval_minutes,
                 self._settings.background_window_hours)
        while True:
            started = time.monotonic()
            try:
                await self.run_once()
            except Exception:  # noqa: BLE001 — сбой одного прохода не останавливает расписание
                log.exception("фоновая проверка: проход прерван")
            await asyncio.sleep(max(0.0, interval - (time.monotonic() - started)))

    async def run_once(self) -> list[SchemaRun]:
        """Один проход по всем схемам. Если предыдущий проход ещё идёт — пропуск (пустой список)."""
        if self._running.locked():
            log.warning("фоновая проверка: предыдущий проход ещё идёт — пропуск")
            return []
        async with self._running:
            s = self._settings
            day_ms = 24 * 3600 * 1000
            removed = await self._access.purge_stale(now_ms() - s.background_access_ttl_days * day_ms)
            if removed:
                log.info("фоновая проверка: удалено доступов, не подтверждённых %d дн.: %d",
                         s.background_access_ttl_days, removed)
            await self._access.purge_claims(now_ms() - s.store_retention_days * day_ms)
            by_schema: dict[str, list[Grant]] = {}
            for g in await self._access.usable():
                by_schema.setdefault(g.schema_id, []).append(g)
            runs = list(await asyncio.gather(*(self._schema(k, v) for k, v in sorted(by_schema.items()))))
            await self._store.purge_if_due(self.clock())
            return runs

    async def _schema(self, schema_id: str, grants: list[Grant]) -> SchemaRun:
        run = SchemaRun(schema_id, grants=len(grants))
        # Самый свежий доступ пользователя первым: его токен вероятнее жив.
        by_user: dict[str, list[Grant]] = {}
        for g in sorted(grants, key=lambda g: g.refreshed_at, reverse=True):
            by_user.setdefault(g.user_key, []).append(g)
        owners: dict[str, _Owner] = {}
        used: list[str] = []
        for user_grants in by_user.values():
            for g in user_grants:
                opened = await self._open(g)
                if opened is None:
                    continue
                session, vehicles = opened
                run.usable += 1
                used.append(g.device_id)
                for v in vehicles:
                    owners.setdefault(v.id, _Owner(g, session))
                break
        if not used:
            run.errors.append("нет действующего доступа к AutoGRAPH")
            log.warning("фоновая проверка: схема %s — нет действующего доступа (доступов %d)", schema_id, len(grants))
            return run

        results = await asyncio.gather(*(self._vehicle(schema_id, vid, o) for vid, o in owners.items()))
        run.vehicles = len(results)
        run.failed = sum(isinstance(r, str) for r in results)
        run.new = sum(r for r in results if isinstance(r, int))
        run.errors += [r for r in results if isinstance(r, str)]
        ok = run.vehicles > run.failed
        if not owners or ok:
            await self._store.mark_scan(schema_id)
            run.marked = True
            await self._access.mark(used, ok=True)
        else:
            await self._access.mark(used, ok=False, error=f"AutoGRAPH не ответил ни по одной машине: {run.errors[0]}")
        log.info("фоновая проверка: схема %s, пользователей %d, машин %d, с ошибкой %d, новых аномалий %d%s",
                 schema_id, len(used), run.vehicles, run.failed, run.new,
                 "" if run.marked else " — отметка проверки не сдвинута")
        return run

    async def _open(self, g: Grant) -> tuple[str, list[Vehicle]] | None:
        """Рабочий токен доступа и машины пользователя; None — войти не удалось (причина — в доступе)."""
        if g.token:
            try:
                # Мимо кэша машин: кэш мог пережить токен, и тогда истёкший токен прошёл бы проверку, а машины — нет.
                return g.token, await self._telemetry.fresh_vehicles(g.token, g.schema_id)
            except SessionInvalid:
                lived = (now_ms() - g.token_since) / 3_600_000 if g.token_since else None
                # Для оценки срока жизни токена (ml/docs/assistant-server-plan.md, шаг 5): от выдачи стенду до отказа.
                log.info("фоновая проверка: токен устройства %s истёк, стенд знал его %s", _short(g.device_id),
                         f"{lived:.1f} ч" if lived is not None else "неизвестно сколько")
                await self._access.drop_token(g.device_id, None if g.password else TOKEN_EXPIRED)
            except AutoGraphUnavailable as e:
                await self._access.mark([g.device_id], ok=False, error=str(e))
                return None
        if not g.password or not g.user_name:
            return None
        try:
            token = await self._client.login(g.user_name, g.password, g.utc_offset_minutes)
            vehicles = await self._telemetry.fresh_vehicles(token, g.schema_id)
        except LoginRejected:
            log.warning("фоновая проверка: устройство %s — AutoGRAPH не принял сохранённый пароль, пароль удалён",
                        _short(g.device_id))
            await self._access.drop_password(g.device_id, PASSWORD_REJECTED)
            return None
        except (SessionInvalid, AutoGraphUnavailable) as e:
            await self._access.mark([g.device_id], ok=False, error=str(e))
            return None
        log.info("фоновая проверка: устройство %s — стенд вошёл в AutoGRAPH по сохранённому паролю", _short(g.device_id))
        await self._access.set_token(g.device_id, token)
        return token, vehicles

    async def _vehicle(self, schema_id: str, vehicle_id: str, owner: _Owner) -> int | str:
        """Новых аномалий машины или текст ошибки."""
        offset = owner.grant.utc_offset_minutes
        to = to_local(self.clock(), offset)
        from_ = to - timedelta(hours=self._settings.background_window_hours)
        async with self._limit:
            try:
                f, t = self._scans.canonical_period(from_, to, offset)
                result = await self._scans.scan_vehicle(owner.session, schema_id, vehicle_id, f, t, offset)
                return len(result.new_ids)
            except SessionInvalid:
                return f"машина {vehicle_id}: сессия AutoGRAPH истекла во время проверки"
            except Exception as e:  # noqa: BLE001 — сбой одной машины не прерывает проверку остальных
                return f"машина {vehicle_id}: {error_text(e)}"


def _short(device_id: str) -> str:
    """Начало id устройства для логов: достаточно, чтобы отличить, и не годится для отзыва чужого доступа."""
    return device_id[:6] + "…"
