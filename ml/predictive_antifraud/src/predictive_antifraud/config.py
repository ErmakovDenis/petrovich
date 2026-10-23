from functools import lru_cache
from pathlib import Path

from pydantic import Field
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

    # Предел нагрузки на /v1/* (расчёт идёт в пуле потоков): одновременно не больше max_concurrency запросов,
    # ждут не больше queue_max — очередь полна, сразу 503. Запрос, простоявший в очереди дольше deadline, не
    # считается (503): клиент его уже не ждёт. Держите deadline меньше FS_PREDICTIVE_TIMEOUT_SECONDS стенда.
    # max_concurrency выбран нагрузочным тестом (README, «Нагрузка»).
    max_concurrency: int = Field(2, ge=1)
    queue_max: int = Field(16, ge=0)
    request_deadline_seconds: float = Field(25, gt=0)


@lru_cache
def get_settings() -> Settings:
    return Settings()
