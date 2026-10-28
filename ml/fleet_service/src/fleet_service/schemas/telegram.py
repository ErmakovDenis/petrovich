"""Telegram-бот: подключение и настройки из приложения (`/v1/telegram/link`, `/pair`, `/chats`) и запросы бота
(`/v1/telegram/bot/*`, `/v1/telegram/me`). Приложение: data/StandTelegram.kt; бот: ml/telegram_bot."""

from typing import Annotated, Literal

from pydantic import Field, field_validator

from .contract import CamelModel, Severity, Vehicle
from .store import StoredAnomaly

_OFFSET = Field(ge=-14 * 60, le=14 * 60)


class LinkRequest(CamelModel):
    """Приложение просит ссылку подключения: схема — из X-Schema-Id, логин — из X-User-Name."""

    # Установка приложения, чей доступ фоновой проверки уже на стенде: без него боту не по чему работать.
    device_id: str = Field(min_length=16, max_length=64)
    schema_name: str = Field("", max_length=200)
    utc_offset_minutes: int = _OFFSET


class LinkResponse(CamelModel):
    # https://t.me/<бот>?start=<код> — открыть в Telegram; код одноразовый.
    url: str
    # До какого момента ссылка действует, epoch millis.
    expires_at: int


# События базовых правил (Anomaly.kind), которые можно выключить в боте и в приложении. У аналитики типы свои — они
# приходят по важности.
KINDS = {
    "drain": "Слив топлива", "drop": "Резкое падение уровня топлива", "overheat": "Перегрев двигателя",
    "oil": "Низкое давление масла", "brake": "Низкое давление в тормозной системе", "volt": "Напряжение вне нормы",
    "power": "Пропадание питания",
}


class ListSettings(CamelModel):
    """Списки в боте: «Проблемы» (/problems) и «Парк» (/park)."""

    # True — только машины, выбранные в подписке; False — все, что видны пользователю. Выключенные события (muted_kinds)
    # в списках не показываются в любом случае.
    subscribed_only: bool = False
    min_severity: Severity = Severity.INFO
    # Только события за последние столько часов; None — за весь срок хранения.
    period_hours: int | None = Field(None, ge=1, le=90 * 24)
    page_size: int = Field(10, ge=3, le=30)
    # severity — сначала критичные, затем новые; newest — сначала новые.
    order: Literal["severity", "newest"] = "newest"


class CardSettings(CamelModel):
    """Что показывать в карточке события."""

    description: bool = True
    # «Петрович помнит»: когда раньше было такое же событие на этой машине.
    memory: bool = True
    # Параметр и значение: «Температура ОЖ: 111,7».
    parameter: bool = False
    # Кто нашёл: базовые правила или аналитика.
    source: bool = False
    # Кто и когда принял решение.
    resolved_by: bool = True
    review_button: bool = True
    # «Подтвердить», «Ложная тревога», «Вернуть в работу».
    resolve_buttons: bool = True


# Время суток «ЧЧ:ММ».
HHMM = Annotated[str, Field(pattern=r"^([01]\d|2[0-3]):[0-5]\d$")]


class ReportSettings(CamelModel):
    """Утренний доклад и недельная сводка (макеты 7, 8) — по местному времени пользователя чата."""

    daily: bool = True
    daily_time: HHMM = "09:30"
    # Только по будням (пн–пт).
    weekdays_only: bool = False
    # Присылать доклад, даже если за сутки событий не было («всё спокойно»).
    when_empty: bool = True
    weekly: bool = True
    # День недели сводки: 1 — понедельник … 7 — воскресенье.
    weekly_day: int = Field(1, ge=1, le=7)
    weekly_time: HHMM = "09:30"


class Subscription(CamelModel):
    """Настройки чата: что и по каким машинам присылать, как показывать списки и карточки. «Критично» по выбранным
    машинам приходит всегда, кроме выключенных событий (muted_kinds). Одни и те же настройки — в боте (/settings) и в
    приложении (Настройки → Telegram)."""

    # None — все машины пользователя (и новые тоже).
    vehicle_ids: list[str] | None = None
    warnings: bool = True
    info: bool = False
    # Не больше стольких некритичных уведомлений за местные сутки; None — без лимита.
    daily_limit: int | None = Field(3, ge=1, le=100)
    # Тихие часы по местному времени: некритичное не присылается; None или одинаковые часы — тихих часов нет.
    quiet_from_hour: int | None = Field(22, ge=0, le=23)
    quiet_to_hour: int | None = Field(7, ge=0, le=23)
    # Выключенные типы событий (KINDS): не приходят совсем, даже критичные, и не показываются в списках бота.
    muted_kinds: list[str] = Field(default_factory=list, max_length=50)
    # Повтор того же события на той же машине — не чаще раза в столько часов; None — каждый раз.
    repeat_hours: int | None = Field(None, ge=1, le=48)
    lists: ListSettings = Field(default_factory=ListSettings)
    card: CardSettings = Field(default_factory=CardSettings)
    reports: ReportSettings = Field(default_factory=ReportSettings)

    @field_validator("vehicle_ids")
    @classmethod
    def _unique(cls, v: list[str] | None) -> list[str] | None:
        return list(dict.fromkeys(v)) if v is not None else None

    @field_validator("muted_kinds")
    @classmethod
    def _kinds(cls, v: list[str]) -> list[str]:
        return list(dict.fromkeys(k.strip() for k in v if k.strip()))

    def severities(self) -> set[Severity]:
        return {Severity.CRITICAL} | ({Severity.WARNING} if self.warnings else set()) | \
            ({Severity.INFO} if self.info else set())

    def quiet(self, hour: int) -> bool:
        """Час [hour] местного времени — тихий."""
        a, b = self.quiet_from_hour, self.quiet_to_hour
        if a is None or b is None or a == b:
            return False
        return a <= hour < b if a < b else hour >= a or hour < b


_TITLE = Field(None, max_length=200)


class ConnectRequest(CamelModel):
    chat_id: str = Field(min_length=1, max_length=32)
    code: str = Field(min_length=1, max_length=64)
    # Как чат называется в Telegram (@имя или имя) — чтобы узнать его в приложении.
    title: str | None = _TITLE


class PairCodeRequest(CamelModel):
    """Бот просит код для чата, который ещё не подключён: пользователь введёт его в приложении."""

    chat_id: str = Field(min_length=1, max_length=32)
    title: str | None = _TITLE


class PairCodeResponse(CamelModel):
    # XXXX-XXXX; регистр, пробелы и дефис при вводе не важны.
    code: str
    expires_at: int


class PairRequest(CamelModel):
    """Приложение подключает чат по коду из бота: схема — из X-Schema-Id, логин — из X-User-Name."""

    device_id: str = Field(min_length=16, max_length=64)
    schema_name: str = Field("", max_length=200)
    utc_offset_minutes: int = _OFFSET
    code: str = Field(min_length=1, max_length=32)


class LinkView(CamelModel):
    """Подключение чата: схема, пользователь, пояс и подписка."""

    chat_id: str
    schema_id: str
    schema_name: str
    user_name: str
    utc_offset_minutes: int
    subscription: Subscription
    linked_at: int
    title: str | None = None


class MeResponse(LinkView):
    # Машины пользователя в схеме (для выбора в подписке и /park); пусто — AutoGRAPH не ответил.
    vehicles: list[Vehicle] = Field(default_factory=list)
    # Почему машины не получены (нет доступа, AutoGRAPH недоступен).
    vehicles_error: str | None = None


class TelegramChats(CamelModel):
    """Для приложения: настроен ли бот, чаты пользователя в схеме с подписками и его машины (для выбора в подписке)."""

    configured: bool
    # https://t.me/<бот> — открыть бота и получить код; пусто — бот не настроен.
    bot_url: str | None = None
    chats: list[LinkView] = Field(default_factory=list)
    vehicles: list[Vehicle] = Field(default_factory=list)
    vehicles_error: str | None = None


class Notice(CamelModel):
    """Карточка события для чата; previous — когда раньше было такое же событие на этой машине (местное время)."""

    chat_id: str
    anomaly: StoredAnomaly
    previous: list[str] = Field(default_factory=list)
    # Пояс пользователя чата: «сегодня в 11:40» или «15 сентября в 15:40».
    utc_offset_minutes: int
    # Сколько раз то же событие на этой машине пропущено как повтор с прошлого уведомления о нём.
    repeats: int = 0
    card: CardSettings = Field(default_factory=CardSettings)


class ChatProblems(CamelModel):
    """Список «Проблемы» в боте по настройкам списков чата."""

    total: int
    items: list[StoredAnomaly]
    lists: ListSettings


class ChatMessage(CamelModel):
    """Служебное сообщение в чат: «ещё N событий», «нет доступа к AutoGRAPH»."""

    chat_id: str
    text: str


class ReportEvent(CamelModel):
    id: str
    vehicle_name: str
    severity: Severity
    # Начало события, местное время ISO.
    event_time: str
    resolved: bool


class ReportGroup(CamelModel):
    """События одного типа в докладе: сначала новые."""

    title: str
    count: int
    items: list[ReportEvent]


class DailyReport(CamelModel):
    """Утренний доклад (макет 7): события по подписке чата за сутки до момента доклада."""

    chat_id: str
    # Начало периода, местное время ISO.
    since: str
    vehicles: int
    total: int
    critical: int
    open: int
    # По типу события, сначала самые частые; в группе — не больше нескольких событий.
    groups: list[ReportGroup] = Field(default_factory=list)


class NamedCount(CamelModel):
    name: str
    count: int
    # Машина: самое частое событие на ней.
    detail: str | None = None


class WeeklyReport(CamelModel):
    """Недельная сводка (макет 8) за прошлую неделю пн–вс по подписке чата."""

    chat_id: str
    # Понедельник и воскресенье недели, местные даты ISO.
    week_from: str
    week_to: str
    total: int
    previous_total: int
    kinds: list[NamedCount] = Field(default_factory=list)
    confirmed: int
    false_alarms: int
    open: int
    top_vehicles: list[NamedCount] = Field(default_factory=list)
    vehicles: int
    quiet_vehicles: int


class Outbox(CamelModel):
    notices: list[Notice] = Field(default_factory=list)
    messages: list[ChatMessage] = Field(default_factory=list)
    daily: list[DailyReport] = Field(default_factory=list)
    weekly: list[WeeklyReport] = Field(default_factory=list)
    # Чаты, подключённые из приложения по коду: бот здоровается в них (как после /start по ссылке).
    linked: list[str] = Field(default_factory=list)
