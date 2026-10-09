"""Tools ассистента по хранилищу: list_anomalies (сохранённые аномалии с фильтрами) и get_anomaly (одна аномалия
и история решений по ней). Видны только машины пользователя; ответ ограничен FS_TOOL_MAX_ANOMALIES записями
и содержит общее количество.

Пустой список — не всегда «аномалий нет»: если проверок с сохранением ещё не было, в ответе это сказано явно.
"""

from datetime import UTC, datetime, timedelta
from typing import Any

from ..config import Settings
from ..schemas.contract import Severity
from ..store.repository import STATUSES, AnomalyFilter, AnomalyRepository, Record, to_utc
from ..telemetry.service import TelemetryService
from .fleet_tools import _TIME, find_vehicle_id, period_args
from .tools import Tool, ToolContext

_RESOLUTION = {None: "ждёт решения", "CONFIRMED": "подтверждена", "FALSE_ALARM": "ложная тревога"}


def _local(ms: int | None, ctx: ToolContext) -> str | None:
    if ms is None:
        return None
    t = datetime.fromtimestamp(ms / 1000, UTC).replace(tzinfo=None) + timedelta(minutes=ctx.utc_offset_minutes)
    return t.strftime(_TIME)


def _item(r: Record, ctx: ToolContext, full: bool = False) -> dict[str, Any]:
    a = r.to_api(ctx.utc_offset_minutes)
    item: dict[str, Any] = {
        "id": a.id,
        "type": r.kind,
        "title": a.title,
        "severity": a.severity.value,
        "vehicle": {"id": a.vehicle_id, "name": a.vehicle_name},
        "eventTime": a.event_time,
        "episodeEnd": a.episode_end,
        "parameter": a.parameter_caption,
        "value": a.value,
        "status": _RESOLUTION.get(r.resolution, r.resolution),
        "falseAlarmReason": r.false_alarm_reason,
        "resolvedBy": r.resolved_by,
        "resolvedAt": _local(r.resolved_at, ctx),
    }
    if full:
        item.update(description=a.description, source=a.source, score=a.score,
                    firstDetectedAt=_local(r.first_detected_at, ctx), lastDetectedAt=_local(r.last_detected_at, ctx))
    return item


def build_store_tools(settings: Settings, service: TelemetryService, store: AnomalyRepository) -> list[Tool]:
    async def list_anomalies(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
        from_, to = period_args(args, ctx)
        visible = [v.id for v in await service.vehicles(ctx.session, ctx.schema_id)]
        vehicle = str(args.get("vehicle_id") or "").strip()
        vehicle_id = await find_vehicle_id(service, ctx, vehicle) if vehicle else None
        severity = args.get("severity") or []
        severities = [severity] if isinstance(severity, str) else list(severity)
        known = {s.value for s in Severity}
        if any(s not in known for s in severities):
            raise ValueError(f"severity: допустимо {', '.join(sorted(known))}")
        status = str(args.get("status") or "").strip() or None
        if status is not None and status not in STATUSES:
            raise ValueError(f"status: допустимо {', '.join(STATUSES)}")
        f = AnomalyFilter(
            vehicle_ids=visible, from_utc=to_utc(from_, ctx.utc_offset_minutes),
            to_utc=to_utc(to, ctx.utc_offset_minutes), vehicle_id=vehicle_id, severities=severities, status=status,
        )
        limit = settings.tool_max_anomalies
        total, records = await store.query(ctx.schema_id, f, limit)
        last_scan = await store.last_scan(ctx.schema_id)
        result: dict[str, Any] = {
            "period": {"from": from_.strftime(_TIME), "to": to.strftime(_TIME)},
            "total": total,
            "anomalies": [_item(r, ctx) for r in records],
            "truncated": total > len(records),
            "lastScanAt": _local(last_scan, ctx),
        }
        if last_scan is None:
            result["message"] = (
                "Проверок с сохранением по этой схеме ещё не было: пустой список не означает, что аномалий нет."
            )
        elif total == 0:
            result["message"] = "В хранилище за этот период аномалий нет (с учётом фильтров)."
        return result

    async def get_anomaly(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
        anomaly_id = str(args.get("id") or "").strip()
        if not anomaly_id:
            raise ValueError("не указан id")
        visible = {v.id for v in await service.vehicles(ctx.session, ctx.schema_id)}
        record = await store.get(ctx.schema_id, anomaly_id, visible)
        if record is None:
            raise ValueError("аномалия не найдена в хранилище или недоступна этому пользователю")
        history = await store.history(ctx.schema_id, anomaly_id)
        return {
            "anomaly": _item(record, ctx, full=True),
            "decisions": [
                {"status": _RESOLUTION.get(d.resolution.value if d.resolution else None),
                 "falseAlarmReason": d.reason, "by": d.user_name, "at": _local(d.decided_at, ctx),
                 "origin": "перенесено с устройства" if d.origin == "import" else "приложение"}
                for d in history
            ],
        }

    return [
        Tool(
            name="list_anomalies",
            description="Сохранённые на стенде аномалии машин пользователя (лента уведомлений) за период и решения "
            "по ним. Фильтры: vehicle_id, severity (CRITICAL, WARNING, INFO), status: open — ждут решения, "
            "resolved — разобраны, confirmed — подтверждены, false_alarm — ложная тревога. Период — местное время, "
            "по умолчанию последние 24 часа; в выборку попадают события, эпизод которых пересекается с периодом. "
            f"Возвращает не больше {settings.tool_max_anomalies} записей, сначала новые; total — сколько всего. "
            "lastScanAt — время последней проверки с сохранением: если её не было, пустой список не значит, что "
            "аномалий нет.",
            parameters={
                "type": "object",
                "properties": {
                    "from": {"type": "string", "description": "Начало периода, местное время (необязательно)"},
                    "to": {"type": "string", "description": "Конец периода, местное время (необязательно)"},
                    "vehicle_id": {"type": "string", "description": "id машины из list_vehicles (необязательно)"},
                    "severity": {"type": "array", "items": {"type": "string", "enum": ["CRITICAL", "WARNING", "INFO"]},
                                 "description": "Важность (необязательно)"},
                    "status": {"type": "string", "enum": list(STATUSES), "description": "Статус разбора (необязательно)"},
                },
            },
            handler=list_anomalies,
        ),
        Tool(
            name="get_anomaly",
            description="Одна сохранённая аномалия по id (из list_anomalies или из карточки, откуда открыт чат): "
            "описание, время события и конец эпизода, текущее решение владельца (кто и когда) и история решений.",
            parameters={
                "type": "object",
                "properties": {"id": {"type": "string", "description": "id аномалии"}},
                "required": ["id"],
            },
            handler=get_anomaly,
        ),
    ]
