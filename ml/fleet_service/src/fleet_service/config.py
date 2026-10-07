from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Настройки стенда из переменных окружения с префиксом FS_ (или файла .env). Описание — в .env.example."""

    # env_ignore_empty: пустая переменная (FS_X= из .env.example) — значение по умолчанию.
    model_config = SettingsConfigDict(env_prefix="FS_", env_file=".env", extra="ignore", env_ignore_empty=True)

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

    @property
    def llm_configured(self) -> bool:
        key = self.openrouter_api_key.get_secret_value() if self.openrouter_api_key else ""
        return bool(key and self.llm_model)


@lru_cache
def get_settings() -> Settings:
    return Settings()
