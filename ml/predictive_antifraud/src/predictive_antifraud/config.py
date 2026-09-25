from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Настройки сервиса из переменных окружения с префиксом PA_ (или файла .env)."""

    model_config = SettingsConfigDict(env_prefix="PA_", env_file=".env", extra="ignore")

    app_name: str = "Петрович: предиктивная аналитика и антифрод"
    models_dir: Path = Path("models")
    # Порог оценки модели (0..1), с которого строка считается аномальной.
    predictive_threshold: float = 0.8
    antifraud_threshold: float = 0.8
    # Выше этого порога — CRITICAL, иначе WARNING (как в MlAnomalyDetector).
    critical_threshold: float = 0.95
    # Ключ для заголовка X-API-Key; None — проверка отключена.
    api_key: str | None = None


@lru_cache
def get_settings() -> Settings:
    return Settings()
