from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Настройки бота из переменных окружения с префиксом TB_ (или файла .env). Описание — в .env.example."""

    model_config = SettingsConfigDict(env_prefix="TB_", env_file=".env", extra="ignore", env_ignore_empty=True)

    # Токен бота от @BotFather.
    bot_token: SecretStr
    # Адрес стенда fleet_service и ключ бота на нём (FS_TELEGRAM_SERVICE_TOKEN стенда).
    stand_url: str = "http://fleet_service:8000"
    stand_token: SecretStr
    # Срок ответа стенда: чат с ассистентом — до FS_CHAT_TIMEOUT_SECONDS стенда (100 с), с запасом.
    stand_timeout_seconds: float = Field(110, gt=0)
    # Как часто забирать уведомления со стенда, с.
    outbox_interval_seconds: float = Field(20, gt=0)
    # Короткие ключи кнопок (callback_data Telegram — до 64 байт, id аномалий длиннее): SQLite-файл.
    data_path: str = "data/bot.db"
    # Сколько последних сообщений диалога передаётся ассистенту (история — в памяти бота, /new — сначала).
    history_max_messages: int = Field(20, ge=2)
    log_level: str = "INFO"
