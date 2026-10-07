"""Tools ассистента: list_vehicles, get_vehicle_summary (телеметрия) и check_vehicle (правила и аналитика).

Все числа считает код: модель получает готовые агрегаты по параметрам, а не ряды по точкам, поэтому размер ответа
зависит от числа параметров, но не от длины периода. «Нет данных» и «0» различаются явно: агрегаты считаются только
по интервалам с данными, доля таких интервалов — в `coverage`; параметры без единого значения — в `noDataParameters`,
параметры, весь период равные 0, — отдельно в `allZeroParameters` (машина стояла или датчик не подключён).
"""

from datetime import datetime, timedelta, timezone
from typing import Any

from ..config import Settings
from ..rules.check import AnomalyCheckService, CheckResult
from ..telemetry.mapper import BuildResult
from ..telemetry.service import TelemetryService, VehicleNotFound
from .tools import Tool, ToolContext

_TIME = "%Y-%m-%dT%H:%M"


def _parse_time(value: Any, name: str) -> datetime | None:
    if value is None or value == "":
        return None
    try:
        dt = datetime.fromisoformat(str(value))
    except ValueError:
        raise ValueError(f"{name}: ожидается время вида 2026-09-16T08:00") from None
    if dt.tzinfo is not None:
        raise ValueError(f"{name}: время указывается без часового пояса, как местное время пользователя")
    return dt


def _round(x: float) -> float:
    return round(x, 1)


def summarize(result: BuildResult) -> dict[str, Any]:
    """Сводка VehicleTelemetry для модели: агрегаты по каждому параметру за период."""
    t = result.telemetry
    intervals = 0
    parameters = []
    for table in t.tables.values():
        intervals = len(table.timestamps)
        for column in table.columns:
            points = [(ts, v) for ts, v in zip(table.timestamps, column.values) if v is not None]
            values = [v for _, v in points]
            p = column.parameter
            last_time, last = points[-1]
            parameters.append({
                "name": p.name,
                "caption": p.caption,
                "unit": p.unit,
                "category": p.category.value,
                "min": _round(min(values)),
                "max": _round(max(values)),
                "mean": _round(sum(values) / len(values)),
                "last": last,
                "lastTime": last_time.strftime(_TIME),
                "coverage": round(len(values) / len(table.timestamps), 2),
            })
    summary: dict[str, Any] = {
        "vehicle": {"id": t.vehicle.id, "name": t.vehicle.name, "group": t.vehicle.group},
        "period": {
            "from": t.from_.strftime(_TIME),
            "to": t.to.strftime(_TIME),
            "bucketMinutes": int(result.bucket.total_seconds() // 60),
            "intervals": intervals,
        },
        "parameters": parameters,
        "noDataParameters": [p.caption for p in result.empty],
        "allZeroParameters": [p.caption for p in result.zero],
    }
    if not parameters:
        summary["message"] = "За период нет данных ни по одному параметру"
    return summary


def _kind(anomaly_id: str) -> str:
    """Тип из id `<источник>|<тип>|…` (как Anomaly.kind в приложении); id аналитики без «|» — пустой тип."""
    parts = anomaly_id.split("|")
    return parts[1] if len(parts) > 1 else ""


def check_summary(result: CheckResult, max_anomalies: int) -> dict[str, Any]:
    """Итог проверки для модели: один из трёх исходов и явное поле о доступности аналитики."""
    r = result.response
    t = result.telemetry.telemetry
    analytics = {
        name: {"status": s.status, "detail": s.detail, "anomaliesFound": s.anomalies_found,
               "modelVersion": s.model_version}
        for name, s in (("predictive", r.analytics.predictive), ("antifraud", r.analytics.antifraud))
    }
    available = r.models_ready
    if r.anomalies:
        outcome = "anomalies_found"
    elif available:
        outcome = "no_anomalies"
    else:
        outcome = "analytics_unavailable"
    summary: dict[str, Any] = {
        "vehicle": {"id": t.vehicle.id, "name": t.vehicle.name, "group": t.vehicle.group},
        "period": {"from": t.from_.strftime(_TIME), "to": t.to.strftime(_TIME)},
        "outcome": outcome,
        "total": len(r.anomalies),
        "rulesAnomalies": r.rules_anomalies,
        "anomalies": [
            {"type": _kind(a.id), "title": a.title, "severity": a.severity.value, "eventTime": a.event_time,
             "parameter": a.parameter_caption, "value": a.value, "description": a.description, "source": a.source}
            for a in r.anomalies[:max_anomalies]
        ],
        "truncated": len(r.anomalies) > max_anomalies,
        "predictiveCheckAvailable": available,
        "analytics": analytics,
    }
    if not available:
        reasons = "; ".join(s["detail"] for s in analytics.values() if s["detail"])
        summary["message"] = (
            "Проверка выполнена только по правилам: предиктивная проверка недоступна"
            + (f" ({reasons})" if reasons else "")
            + ". Отсутствие аномалий по правилам не означает, что нарушений нет."
        )
    return summary


def build_fleet_tools(settings: Settings, service: TelemetryService, checks: AnomalyCheckService) -> list[Tool]:
    async def list_vehicles(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
        vehicles = await service.vehicles(ctx.session, ctx.schema_id)
        query = str(args.get("query") or "").strip().lower()
        if query:
            vehicles = [v for v in vehicles if query in v.name.lower() or query in (v.group or "").lower()]
        limit = settings.tool_max_vehicles
        return {
            "total": len(vehicles),
            "vehicles": [{"id": v.id, "name": v.name, "group": v.group} for v in vehicles[:limit]],
            "truncated": len(vehicles) > limit,
        }

    async def vehicle_and_period(args: dict[str, Any], ctx: ToolContext) -> tuple[str, datetime, datetime]:
        vehicle_id = str(args.get("vehicle_id") or "").strip()
        if not vehicle_id:
            raise ValueError("не указан vehicle_id")
        vehicles = await service.vehicles(ctx.session, ctx.schema_id)
        if all(v.id != vehicle_id for v in vehicles):
            # Модель могла передать название вместо id — принимаем только однозначное совпадение.
            by_name = [v for v in vehicles if v.name.strip().lower() == vehicle_id.lower()]
            if len(by_name) != 1:
                raise VehicleNotFound()
            vehicle_id = by_name[0].id
        now = (datetime.now(timezone.utc) + timedelta(minutes=ctx.utc_offset_minutes)).replace(tzinfo=None)
        to = _parse_time(args.get("to"), "to") or now.replace(second=0, microsecond=0)
        from_ = _parse_time(args.get("from"), "from") or to - timedelta(hours=24)
        return vehicle_id, from_, to

    async def get_vehicle_summary(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
        vehicle_id, from_, to = await vehicle_and_period(args, ctx)
        result = await service.telemetry(
            ctx.session, ctx.schema_id, vehicle_id, from_, to, ctx.utc_offset_minutes
        )
        return summarize(result)

    async def check_vehicle(args: dict[str, Any], ctx: ToolContext) -> dict[str, Any]:
        vehicle_id, from_, to = await vehicle_and_period(args, ctx)
        result = await checks.check(ctx.session, ctx.schema_id, vehicle_id, from_, to, ctx.utc_offset_minutes)
        return check_summary(result, settings.tool_max_anomalies)

    max_hours = settings.telemetry_max_period_hours
    period = {
        "from": {"type": "string", "description": "Начало периода, местное время (необязательно)"},
        "to": {"type": "string", "description": "Конец периода, местное время (необязательно)"},
    }
    return [
        Tool(
            name="list_vehicles",
            description="Список машин автопарка пользователя: id, название, группа. Используй, чтобы найти id машины "
            "по названию или номеру. query — необязательная подстрока названия или группы.",
            parameters={
                "type": "object",
                "properties": {"query": {"type": "string", "description": "Подстрока названия или группы машины"}},
            },
            handler=list_vehicles,
        ),
        Tool(
            name="get_vehicle_summary",
            description="Сводка телеметрии машины за период: по каждому параметру (топливо, аккумулятор, двигатель, "
            "движение) минимум, максимум, среднее по интервалам с данными, последнее значение и его время, доля "
            "интервалов с данными (coverage, 0..1), единицы измерения. noDataParameters — параметры, по которым "
            "за период не пришло ни одного значения (это не ноль). allZeroParameters — параметры, весь период "
            "равные 0: показатель действительно нулевой (например, машина стояла, сливов не было) или датчик не "
            "подключён — по этим данным их не различить. Для Power, DIgnition, DIgnitionCAN значения 1/0: среднее — доля "
            "времени во включённом состоянии. Время — местное время пользователя без пояса, формат 2026-09-16T08:00. "
            f"По умолчанию — последние 24 часа; период не длиннее {max_hours} ч.",
            parameters={
                "type": "object",
                "properties": {"vehicle_id": {"type": "string", "description": "id машины из list_vehicles"}, **period},
                "required": ["vehicle_id"],
            },
            handler=get_vehicle_summary,
        ),
        Tool(
            name="check_vehicle",
            description="Проверка машины на аномалии за период: пороговые правила (слив и резкое падение топлива, "
            "пропадание питания, напряжение, перегрев, давление масла, тормоза) и сервисы аналитики (предиктивная "
            "аналитика и антифрод). outcome: anomalies_found — аномалии найдены (список в anomalies); "
            "no_anomalies — правила и аналитика отработали и ничего не нашли; analytics_unavailable — правила ничего "
            "не нашли, но предиктивная проверка или антифрод недоступны (predictiveCheckAvailable=false, причина в "
            "analytics) — в этом случае нельзя утверждать, что нарушений нет. Время — местное время пользователя, "
            f"по умолчанию последние 24 часа; период не длиннее {max_hours} ч.",
            parameters={
                "type": "object",
                "properties": {"vehicle_id": {"type": "string", "description": "id машины из list_vehicles"}, **period},
                "required": ["vehicle_id"],
            },
            handler=check_vehicle,
        ),
    ]
