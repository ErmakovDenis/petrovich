"""Хранилище аномалий: `GET /v1/anomalies`, `POST /v1/anomalies/{id}/resolve`, `/scan`, `/import`.
Приложение: anomaly/StandAnomalies.kt. Время — местное время пользователя без пояса (по utcOffsetMinutes запроса).
"""

from datetime import datetime
from enum import Enum

from pydantic import Field

from .contract import Anomaly, CamelModel

_OFFSET = Field(ge=-14 * 60, le=14 * 60)


class Resolution(str, Enum):
    CONFIRMED = "CONFIRMED"
    FALSE_ALARM = "FALSE_ALARM"


class StoredAnomaly(Anomaly):
    """Anomaly контракта (id — стенда, eventTime — начало события) и решение по ней. Те же поля решения есть
    в Anomaly приложения (resolution, falseAlarmReason); остальные приложение может не читать."""

    resolution: Resolution | None = None
    false_alarm_reason: str | None = None
    # Логин, под которым принято решение (как передало приложение), и когда, epoch millis.
    resolved_by: str | None = None
    resolved_at: int | None = None
    # Конец эпизода (последний интервал, где выполнялось условие), местное время.
    episode_end: str
    # Когда событие последний раз подтверждено проверкой, epoch millis (detectedAt — когда найдено впервые).
    last_detected_at: int


class Decision(CamelModel):
    # None — аномалию вернули в «ждут решения».
    resolution: Resolution | None
    reason: str | None
    user_name: str | None
    decided_at: int
    # app — решение из приложения; import — перенесено с устройства.
    origin: str


class AnomalyList(CamelModel):
    # Сколько записей подходит под фильтры (items может быть меньше — limit/offset).
    total: int
    items: list[StoredAnomaly]
    # Последняя успешная проверка с сохранением по схеме, epoch millis; None — проверок ещё не было.
    last_scan_at: int | None


class ResolveRequest(CamelModel):
    # None или поле не передано — вернуть в «ждут решения» (приложение не сериализует null-поля).
    resolution: Resolution | None = None
    reason: str | None = Field(None, max_length=500)
    utc_offset_minutes: int = _OFFSET


class ScanRequest(CamelModel):
    # Какие машины проверить; не задано — все машины пользователя.
    vehicle_ids: list[str] | None = None
    from_: datetime = Field(alias="from")
    to: datetime
    utc_offset_minutes: int = _OFFSET


class VehicleScan(CamelModel):
    vehicle_id: str
    ok: bool
    # Почему машину не удалось проверить (ok=false).
    error: str | None = None
    # Аномалии машины, найденные этой проверкой, в том виде, как они сохранены (id и решения стенда).
    anomalies: list[StoredAnomaly] = Field(default_factory=list)
    # id из anomalies, которых в хранилище до этой проверки не было.
    new_ids: list[str] = Field(default_factory=list)
    # false — проверка только по правилам (аналитика не готова или недоступна); None — машину не проверили.
    models_ready: bool | None = None


class ScanResponse(CamelModel):
    # Период проверки на канонической сетке (начало и конец выровнены по FS_SCAN_BUCKET_MINUTES).
    from_: datetime = Field(alias="from")
    to: datetime
    results: list[VehicleScan]
    last_scan_at: int | None


class ImportItem(CamelModel):
    """Аномалия из локальной истории приложения (id устройства), с решением или без."""

    local_id: str
    vehicle_id: str
    vehicle_name: str = ""
    # Тип из id (drain, drop, …) — Anomaly.kind приложения.
    kind: str
    parameter_name: str
    title: str = ""
    # Время события на устройстве, местное.
    event_time: datetime
    resolution: Resolution | None = None
    reason: str | None = Field(None, max_length=500)


class ImportRequest(CamelModel):
    utc_offset_minutes: int = _OFFSET
    items: list[ImportItem]


class ImportUnmatched(CamelModel):
    """Решение, которому не нашлось пары на стенде: в отчёте о переносе, а не потеряно молча."""

    local_id: str
    vehicle_id: str
    vehicle_name: str
    title: str
    event_time: datetime
    resolution: Resolution
    reason: str | None
    # Почему не сопоставлено.
    why: str


class ImportResponse(CamelModel):
    # Решений в запросе; перенесено; на стенде уже было решение (не перезаписано); без пары — в unmatched.
    decisions: int
    applied: int
    already_resolved: int
    unmatched: list[ImportUnmatched]
    # Аномалии без решения: найдены на стенде (снова будут в ленте) и не найдены.
    restored: int
    not_found: int
    # Периоды, которые не удалось проверить (AutoGRAPH недоступен и т. п.).
    scan_errors: list[str]
