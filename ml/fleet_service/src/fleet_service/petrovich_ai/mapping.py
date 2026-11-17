"""Событие ленты «Petrovich AI» → аномалия контракта стенда (как ApiAnomalyChecker.kt в приложении).

id стабилен (`api|<kind>|<id>`) — хранилище дедуплицирует по нему повторные проверки, как у правил. Машина ленты
сопоставляется с парком схемы по имени (точное совпадение, затем вхождение) — у сервиса свои id, не id AutoGRAPH.
"""

import logging
from datetime import UTC, datetime, timedelta

from ..schemas.contract import Anomaly, MetricCategory, Severity, Vehicle

log = logging.getLogger(__name__)

# Заголовки видов для владельца: kind/title в ленте технические («fuel_drain») — как в приложении.
KIND_TITLES = {
    "fuel_drain": "Слив топлива",
    "towing": "Буксировка",
    "speed_without_movement": "Скорость без движения",
    "telemetry_gap": "Разрыв телеметрии",
    "data_gap": "Пропуск данных",
    "data_gap_channel_unavailable": "Нет данных с датчика",
    "data_gap_all_channels_unavailable": "Машина не на связи",
    "data_gap_signal_unavailable": "Нет сигнала",
    "data_gap_gps_unavailable": "Нет GPS",
}

_KIND_CATEGORY = {
    "fuel_drain": MetricCategory.FUEL,
    "towing": MetricCategory.MOTION,
    "speed_without_movement": MetricCategory.MOTION,
}

_SEVERITY = {"high": Severity.CRITICAL, "med": Severity.WARNING}


def match_vehicle(name: str, vehicles: list[Vehicle]) -> Vehicle | None:
    """Машина парка схемы для имени из ленты: точное совпадение, затем вхождение (нормализуем регистр и пробелы)."""
    exact = next((v for v in vehicles if v.name == name), None)
    if exact is not None:
        return exact
    norm = " ".join(name.lower().split())
    return next((v for v in vehicles if norm and norm in " ".join(v.name.lower().split())), None)


def to_anomaly(event: dict, vehicle: Vehicle, utc_offset_minutes: int) -> tuple[Anomaly, datetime, datetime] | None:
    """Событие ленты → (аномалия, начало эпизода местное, конец эпизода местное); None — время начала не разобрать."""
    started = _parse_time(event.get("started_at"))
    if started is None:
        log.warning("событие ленты %s: неразборчивое started_at — пропущено", event.get("id"))
        return None
    kind = str(event.get("kind") or "")
    title = KIND_TITLES.get(kind) or str(event.get("title") or kind)
    volume = event.get("volume_l")
    metric = event.get("metric")
    details = []
    if isinstance(volume, (int, float)):
        details.append(f"объём {volume:.1f} л")
    elif isinstance(metric, (int, float)):
        details.append(f"значение {metric:.0f} {event.get('metric_unit') or ''}".rstrip())
    start_local = _to_local(started, utc_offset_minutes)
    ended = _parse_time(event.get("ended_at"))
    end_local = _to_local(ended, utc_offset_minutes) if ended is not None else start_local
    if end_local < start_local:
        end_local = start_local
    if ended is not None:
        details.append(f"до {end_local:%H:%M}")
    producer = " ".join(filter(None, (event.get("producer_name"), event.get("producer_version"))))
    if producer:
        details.append(f"источник: {producer}")
    anomaly = Anomaly(
        # Детерминированный ключ: повторная проверка не плодит дубликаты.
        id=f"api|{kind}|{event.get('id')}",
        vehicle_id=vehicle.id,
        vehicle_name=(
            f"{vehicle.name} {vehicle.plate}"
            if vehicle.plate and vehicle.plate.replace(" ", "").lower() not in vehicle.name.replace(" ", "").lower()
            else vehicle.name
        ),
        category=_KIND_CATEGORY.get(kind, MetricCategory.ENGINE),
        parameter_name=kind,
        parameter_caption=title,
        event_time=f"{start_local:%Y-%m-%dT%H:%M:%S}",
        detected_at=int(started.timestamp() * 1000),
        severity=_SEVERITY.get(str(event.get("severity") or ""), Severity.INFO),
        title=title,
        description=f"{title}, начало {start_local:%d.%m %H:%M}" + (f" ({'; '.join(details)})" if details else ""),
        value=volume if isinstance(volume, (int, float)) else (metric if isinstance(metric, (int, float)) else None),
        source="API Петрович AI",
    )
    return anomaly, start_local, end_local


def _parse_time(text: object) -> datetime | None:
    """ISO-8601 с поясом (как в ленте) → UTC без зоны; без пояса — как есть; не время — None."""
    if not isinstance(text, str) or not text:
        return None
    try:
        t = datetime.fromisoformat(text)
    except ValueError:
        return None
    if t.tzinfo is None:
        return t
    return t.astimezone(UTC).replace(tzinfo=None)


def _to_local(utc: datetime, utc_offset_minutes: int) -> datetime:
    return utc + timedelta(minutes=utc_offset_minutes)
