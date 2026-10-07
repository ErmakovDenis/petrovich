from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr
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

    # AutoGRAPH: проверка токена сессии пользователя.
    autograph_base_url: str = "https://web.tk-ekat.ru/ServiceJSON/"
    autograph_timeout_seconds: float = Field(15, gt=0)
    auth_cache_ttl_seconds: float = Field(60, ge=0)
    auth_cache_max_entries: int = Field(1000, ge=1)

    # AutoGRAPH: загрузка телеметрии (EnumDevices, EnumParameters, GetTripTables).
    # Всего попыток запроса при сетевой ошибке и пауза перед n-й повторной попыткой (n × значение).
    autograph_retries: int = Field(3, ge=1)
    autograph_retry_delay_seconds: float = Field(1, ge=0)
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
    # Готовая телеметрия — по схеме, машине, периоду и поясу, общая для всех пользователей схемы.
    telemetry_cache_ttl_seconds: float = Field(120, ge=0)
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

    # Пороги правил аномалий: FS_RULES__<ИМЯ>, по умолчанию — как в приложении (rules/thresholds.py).
    rules: RuleThresholds = Field(default_factory=RuleThresholds)

    @property
    def llm_configured(self) -> bool:
        key = self.openrouter_api_key.get_secret_value() if self.openrouter_api_key else ""
        return bool(key and self.llm_model)


@lru_cache
def get_settings() -> Settings:
    return Settings()
