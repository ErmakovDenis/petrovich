from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from .rules.thresholds import RuleThresholds


class Settings(BaseSettings):
    """Настройки стенда из переменных окружения с префиксом FS_ (или файла .env). Описание — в .env.example."""

    # env_ignore_empty: пустая переменная (FS_X= из .env.example) — значение по умолчанию.
    # env_nested_delimiter: вложенные настройки (пороги правил) — FS_RULES__FUEL_DROP_MIN_LITERS и т. п.
    model_config = SettingsConfigDict(
        env_prefix="FS_", env_file=".env", extra="ignore", env_ignore_empty=True, env_nested_delimiter="__",
    )

    app_name: str = "Петрович: стенд ассистента и аналитики"
    log_level: str = "INFO"

    # OpenRouter (формат OpenAI chat completions).
    openrouter_api_key: SecretStr | None = None
    openrouter_base_url: str = "https://openrouter.ai/api/v1"
    # Маршрутизация провайдеров OpenRouter: только те, что поддерживают все параметры запроса (tools, temperature),
    # и политика сбора данных провайдером.
    openrouter_require_parameters: bool = True
    openrouter_data_collection: Literal["allow", "deny"] = "deny"
    # Порядок провайдеров одной модели (provider.sort); пусто — порядок OpenRouter по умолчанию. allow_fallbacks —
    # переходить к следующему провайдеру при сбое.
    openrouter_provider_sort: Literal["", "latency", "throughput", "price"] = ""
    openrouter_allow_fallbacks: bool = True

    # Прокси LiteLLM (сервис llm_proxy в docker compose): повторы и запасные модели. Пусто — стенд обращается
    # к OpenRouter напрямую, без повторов. Конфигурацию прокси строит python -m fleet_service.llm_proxy из этих же FS_*.
    llm_proxy_url: str = ""
    # Запасные модели OpenRouter через запятую: берутся по порядку, если основная не ответила (через прокси — после
    # исчерпания повторов основной, без прокси — полем models запроса).
    # Только модели, прошедшие проверку на tools (python -m fleet_service.agent.eval_tools).
    llm_fallback_models: str = ""
    # Повторы обращения к модели через прокси (без первой попытки) при 408, 429, 5xx и сетевой ошибке. Пауза перед
    # n-м повтором — base · 2^(n-1), не больше max, плюс случайные до 0,75 с; Retry-After провайдера до 60 с — как есть.
    llm_retries: int = Field(2, ge=0, le=5)
    llm_retry_base_seconds: float = Field(1, ge=0)
    llm_retry_max_seconds: float = Field(10, ge=0)
    # Резервная модель вне OpenRouter — любой OpenAI-совместимый адрес (свой vLLM, профиль reserve-llm в compose).
    # Прокси переходит к ней, когда не ответили основная и запасные модели (в том числе при 402 и недоступном
    # OpenRouter). Пустой адрес — резерва нет. Только через прокси.
    llm_reserve_base_url: str = ""
    llm_reserve_model: str = ""
    llm_reserve_api_key: SecretStr | None = None
    # Резерв хуже основной модели по проверке на tools — к ответу через него добавляется пометка «упрощённый режим».
    llm_reserve_degraded: bool = True

    # Circuit breaker на AutoGRAPH, predictive_antifraud и модель: после FS_BREAKER_FAILURES сбоев за
    # FS_BREAKER_WINDOW_SECONDS — сразу отказ на FS_BREAKER_OPEN_SECONDS, затем один пробный запрос. Для модели сбой —
    # и ответ резерва; пока открыт, стенд обращается сразу к резерву (если он задан), минуя OpenRouter.
    breaker_failures: int = Field(5, ge=1)
    breaker_window_seconds: float = Field(30, gt=0)
    breaker_open_seconds: float = Field(60, gt=0)
    # Ключ администратора для GET /v1/status (Authorization: Bearer); пусто — эндпоинт выключен (404).
    admin_token: SecretStr | None = None

    # Модель и генерация. Пустая модель — ассистент не настроен (/v1/chat отвечает 503).
    llm_model: str = ""
    llm_temperature: float = Field(0.2, ge=0, le=2)
    llm_max_tokens: int = Field(1024, gt=0)
    # Таймаут одного обращения к модели.
    llm_timeout_seconds: float = Field(60, gt=0)
    # Сколько раз за один ответ можно обратиться к модели (каждый вызов tools — ещё одно обращение).
    agent_max_iterations: int = Field(5, ge=1)
    # Общий срок на ответ /v1/chat со всеми обращениями к модели и tools. Приложение ждёт 120 с — держите меньше.
    chat_timeout_seconds: float = Field(100, gt=0)
    # Файл системного промпта; не задан — встроенный prompts/system.md.
    system_prompt_path: Path | None = None

    # Лимиты запроса чата: старые сообщения сверх лимита отбрасываются, слишком длинное сообщение — 422.
    chat_max_messages: int = Field(40, ge=1)
    chat_max_message_chars: int = Field(4000, ge=1)

    # Нагрузка чата. Rate limit на пользователя (сессию AutoGRAPH и схему): FS_CHAT_BURST вопросов подряд, дальше —
    # FS_CHAT_RATE_PER_MINUTE в минуту; сверх — 429. Один ответ — до FS_AGENT_MAX_ITERATIONS обращений к модели.
    chat_rate_per_minute: float = Field(10, gt=0)
    chat_burst: int = Field(3, ge=1)
    # Одновременных ответов чата на процесс и ожидающих в очереди; очередь полна — сразу 503. Ожидание места
    # входит в FS_CHAT_TIMEOUT_SECONDS.
    chat_concurrency: int = Field(8, ge=1)
    chat_queue_max: int = Field(16, ge=0)
    # Общий на процесс предел одновременных запросов к AutoGRAPH и к predictive_antifraud — для всех путей (чат,
    # проверки, scan, фоновая проверка). Фоновая проверка занимает не больше половины и уступает запросам
    # пользователей. FS_PREDICTIVE_CONCURRENCY держите не больше PA_MAX_CONCURRENCY аналитики: тогда очередь — здесь,
    # где запросы пользователей идут вперёд фоновых, а не в простой очереди аналитики.
    autograph_concurrency: int = Field(8, ge=1)
    predictive_concurrency: int = Field(2, ge=1)

    # AutoGRAPH: проверка токена сессии пользователя.
    autograph_base_url: str = "https://web.tk-ekat.ru/ServiceJSON/"
    autograph_timeout_seconds: float = Field(15, gt=0)
    auth_cache_ttl_seconds: float = Field(60, ge=0)
    auth_cache_max_entries: int = Field(1000, ge=1)

    # AutoGRAPH: загрузка телеметрии (EnumDevices, EnumParameters, GetTripTables).
    # Всего попыток запроса при сетевой ошибке. Пауза перед n-й повторной попыткой — база · 2^(n-1), не больше
    # максимума, умноженная на случайное 0,5…1 (jitter: при массовом сбое повторы не приходят одновременно).
    autograph_retries: int = Field(3, ge=1)
    autograph_retry_delay_seconds: float = Field(1, ge=0)
    autograph_retry_max_seconds: float = Field(10, ge=0)
    # GetTripTables отвечает медленно и много: отдельный таймаут и длина одной части периода.
    autograph_trip_tables_timeout_seconds: float = Field(60, gt=0)
    autograph_chunk_hours: int = Field(6, ge=1, le=24)
    # Предел длины адреса запроса; длиннее — параметры (onlineParams) запрашиваются пачками.
    autograph_max_query_chars: int = Field(1900, ge=200)
    # Защита от сбойного ответа: больше точек в одном треке — ответ отвергается.
    trip_tables_max_points: int = Field(500_000, ge=1)

    # Телеметрия стенда: кэши и пределы.
    # Список машин пользователя (EnumDevices) — по токену и схеме.
    devices_cache_ttl_seconds: float = Field(300, ge=0)
    # Набор параметров прибора (EnumParameters) — по схеме и машине.
    parameters_cache_ttl_seconds: float = Field(3600, ge=0)
    parameters_cache_max_entries: int = Field(5000, ge=1)
    # Готовая телеметрия — по схеме, машине, периоду и поясу, общая для всех пользователей схемы. Период,
    # захватывающий «сейчас», — FS_TELEMETRY_CACHE_TTL_SECONDS; закрытый период (конец раньше «сейчас − задержка
    # поступления данных AutoGRAPH») уже не изменится — FS_TELEMETRY_CLOSED_CACHE_TTL_SECONDS.
    telemetry_cache_ttl_seconds: float = Field(120, ge=0)
    telemetry_closed_cache_ttl_seconds: float = Field(21600, ge=0)
    telemetry_settle_minutes: int = Field(30, ge=0)
    telemetry_cache_max_entries: int = Field(200, ge=1)
    # Самый длинный период одного запроса телеметрии (в приложении — 7 дней).
    telemetry_max_period_hours: int = Field(168, ge=1)

    # Tools ассистента: сколько машин отдаёт list_vehicles, сколько аномалий — check_vehicle, и предел размера
    # ответа одного tool в символах JSON.
    tool_max_vehicles: int = Field(50, ge=1)
    tool_max_anomalies: int = Field(20, ge=1)
    tool_max_result_chars: int = Field(16000, ge=1000)

    # Сервис predictive_antifraud (предиктивная аналитика и антифрод). Пустой адрес — аналитика не вызывается.
    predictive_url: str = "http://predictive_antifraud:8000"
    # Ключ X-API-Key сервиса (PA_API_KEY в его .env); пусто — заголовок не передаётся.
    predictive_api_key: SecretStr | None = None
    predictive_timeout_seconds: float = Field(30, gt=0)
    # Кэш результатов аналитики (только status=ok) — по виду, схеме, машине, периоду и версии модели, с теми же
    # сроками, что телеметрия. Версии моделей стенд узнаёт из GET /ready аналитики и помнит столько секунд.
    analytics_cache_max_entries: int = Field(500, ge=1)
    predictive_ready_ttl_seconds: float = Field(60, ge=0)

    # Пороги правил аномалий: FS_RULES__<ИМЯ>, по умолчанию — как в приложении (rules/thresholds.py).
    rules: RuleThresholds = Field(default_factory=RuleThresholds)

    # Хранилище аномалий и решений (SQLite через SQLAlchemy, миграции Alembic в store/migrations).
    # В docker compose файл базы лежит в томе, чтобы данные переживали перезапуск контейнера.
    db_url: str = "sqlite:///./data/fleet.db"
    # Применять миграции схемы базы при старте стенда (иначе — вручную: python -m fleet_service.store.migrate).
    db_auto_migrate: bool = True
    # Шаг канонической сетки проверок с сохранением, мин. Сетка привязана к началу эпохи (UTC) и не зависит от
    # окна и момента запуска, поэтому одно событие получает один id. Изменение шага меняет id новых аномалий.
    scan_bucket_minutes: int = Field(1, ge=1, le=60)
    # Сколько машин проверяется параллельно в одном запросе /v1/anomalies/scan (в приложении — 2).
    scan_concurrency: int = Field(2, ge=1, le=16)
    # Сколько дней хранить аномалии и решения (по времени события).
    store_retention_days: int = Field(180, ge=1)
    # Больше записей за один запрос GET /v1/anomalies не отдаётся.
    anomalies_max_limit: int = Field(500, ge=1)
    # Перенос решений с устройства: допуск по времени события при сопоставлении, мин; запас окна проверки вокруг
    # времени перенесённых аномалий, мин; сколько записей принимается за один запрос.
    import_time_tolerance_minutes: int = Field(10, ge=0)
    import_scan_margin_minutes: int = Field(60, ge=0)
    import_max_items: int = Field(1000, ge=1)

    # Фоновая проверка на стенде: каждые FS_BACKGROUND_INTERVAL_MINUTES по каждой схеме, где приложение выдало доступ,
    # за последние FS_BACKGROUND_WINDOW_HOURS. Без ключа шифрования доступ не принимается и проверка не идёт.
    background_enabled: bool = True
    background_interval_minutes: float = Field(15, gt=0)
    background_window_hours: int = Field(3, ge=1)
    # Сколько машин одновременно проверяется в фоне по всем схемам (в приложении — 2).
    background_concurrency: int = Field(2, ge=1, le=16)
    # Доступ, который приложение не обновляло столько дней (приложение удалено, телефон потерян), удаляется.
    background_access_ttl_days: int = Field(30, ge=1)
    # Ключ шифрования доступа в базе (токен сессии, пароль по согласию): 32 байта в base64. Сменили ключ —
    # сохранённый доступ не расшифровать, приложения выдадут его заново.
    access_encryption_key: SecretStr | None = None
    # Больше id аномалий за один запрос /v1/notifications/claim не принимается.
    notification_claim_max_ids: int = Field(500, ge=1)

    # Письма (ассистент готовит черновик, пользователь подтверждает в приложении, стенд отправляет). Без адреса
    # SMTP, отправителя или списка получателей письма выключены: tools писем модели не предлагаются.
    smtp_host: str = ""
    smtp_port: int = Field(587, ge=1, le=65535)
    # starttls — STARTTLS после подключения (порт 587), ssl — TLS сразу (465), none — без шифрования (только локально).
    smtp_security: Literal["starttls", "ssl", "none"] = "starttls"
    smtp_username: str = ""
    smtp_password: SecretStr | None = None
    # Адрес отправителя (From).
    smtp_from: str = ""
    smtp_timeout_seconds: float = Field(30, gt=0)
    # Файл получателей (TOML, образец — recipients.example.toml): id, имя, роль, адрес, схемы. Читается при старте.
    email_recipients_path: Path | None = None
    # Сколько живёт черновик: позже подтвердить нельзя.
    email_draft_ttl_minutes: int = Field(30, ge=1)
    # Не больше стольких отправленных писем на пользователя за период, ч. Логин из X-User-Name стенд не проверяет,
    # поэтому есть и общий предел на схему за тот же период.
    email_limit_per_user: int = Field(20, ge=1)
    email_limit_per_schema: int = Field(100, ge=1)
    email_limit_period_hours: int = Field(24, ge=1)
    # Пределы черновика: получателей, символов темы и текста, черновиков за один ответ ассистента.
    email_max_recipients: int = Field(10, ge=1)
    email_max_subject_chars: int = Field(200, ge=1)
    email_max_body_chars: int = Field(5000, ge=1)
    email_max_drafts_per_reply: int = Field(3, ge=1)

    # Telegram-бот (ml/telegram_bot): имя бота без @ — для ссылки подключения t.me/<имя>?start=<код>; ключ, с которым
    # бот обращается к стенду (Authorization: Bearer, тот же, что TB_STAND_TOKEN бота). Без любого из них —
    # подключение и запросы бота выключены (503). Бот работает по доступу фоновой проверки пользователя.
    telegram_bot_username: str = ""
    telegram_service_token: SecretStr | None = None
    # Сколько живёт код подключения из приложения, мин.
    telegram_link_ttl_minutes: int = Field(15, ge=1)
    # Уведомления в чат: о событиях, найденных не раньше стольких часов назад и не раньше подключения; не больше
    # стольких карточек за одну выдачу на чат — остальные одной строкой «ещё N».
    telegram_notify_max_age_hours: int = Field(24, ge=1)
    telegram_notify_max_per_chat: int = Field(5, ge=1)
    # Предупреждение «нет доступа к AutoGRAPH» — не чаще раза в столько часов на чат.
    telegram_access_warning_hours: int = Field(12, ge=1)

    @model_validator(mode="after")
    def _background_window_fits(self) -> "Settings":
        if self.background_window_hours > self.telemetry_max_period_hours:
            raise ValueError("FS_BACKGROUND_WINDOW_HOURS не может быть больше FS_TELEMETRY_MAX_PERIOD_HOURS")
        return self

    @property
    def llm_configured(self) -> bool:
        key = self.openrouter_api_key.get_secret_value() if self.openrouter_api_key else ""
        return bool(key and self.llm_model)

    @property
    def reserve_configured(self) -> bool:
        return bool(self.llm_reserve_base_url.strip() and self.llm_reserve_model.strip())

    @property
    def fallback_models(self) -> list[str]:
        """FS_LLM_FALLBACK_MODELS списком, без пустых и без повтора основной модели."""
        names = [m.strip() for m in self.llm_fallback_models.split(",")]
        return list(dict.fromkeys(m for m in names if m and m != self.llm_model))

    @property
    def email_configured(self) -> bool:
        """Письма включены: заданы SMTP, отправитель и файл получателей."""
        return bool(self.smtp_host and self.smtp_from and self.email_recipients_path)

    @property
    def background_configured(self) -> bool:
        """Фоновая проверка включена и задан ключ шифрования доступа."""
        key = self.access_encryption_key.get_secret_value() if self.access_encryption_key else ""
        return self.background_enabled and bool(key)

    @property
    def telegram_configured(self) -> bool:
        """Бот включён: заданы имя бота и ключ бота; без фоновой проверки боту не по чему работать."""
        token = self.telegram_service_token.get_secret_value() if self.telegram_service_token else ""
        return bool(self.telegram_bot_username.strip() and token) and self.background_configured


@lru_cache
def get_settings() -> Settings:
    return Settings()
