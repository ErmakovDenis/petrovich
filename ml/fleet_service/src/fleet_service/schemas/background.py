"""Фоновая проверка: доступ стенда к AutoGRAPH (`/v1/background/access`) и отметки показанных уведомлений
(`/v1/notifications/claim`). Приложение: anomaly/StandBackground.kt."""

from pydantic import Field, SecretStr

from .contract import CamelModel

_OFFSET = Field(ge=-14 * 60, le=14 * 60)
# Случайный id установки приложения (UUID); короткий — легко подобрать, поэтому не меньше 16 символов.
_DEVICE = Field(min_length=16, max_length=64, pattern=r"^[A-Za-z0-9-]+$")


class AccessRequest(CamelModel):
    """Выдать или обновить доступ: токен — из Authorization, логин — из X-User-Name, схема — из X-Schema-Id."""

    device_id: str = _DEVICE
    utc_offset_minutes: int = _OFFSET
    # Разрешить стенду входить самостоятельно (хранить пароль). false — сохранённый пароль удаляется.
    save_password: bool = False
    # Пароль AutoGRAPH: нужен, только когда savePassword = true и на стенде пароля ещё нет (passwordStored=false).
    password: SecretStr | None = Field(None, max_length=200)


class DeviceRequest(CamelModel):
    device_id: str = _DEVICE


class AccessStatus(CamelModel):
    # Фоновая проверка на стенде включена и настроена (FS_BACKGROUND_ENABLED, FS_ACCESS_ENCRYPTION_KEY).
    enabled: bool
    # У стенда есть доступ этого устройства.
    registered: bool
    # Действующий токен сессии (не истёк) и с какого момента стенд его знает, epoch millis.
    token_active: bool = False
    token_since: int | None = None
    # Стенд хранит пароль (согласие пользователя) и войдёт сам, когда токен истечёт.
    password_stored: bool = False
    # Когда доступ последний раз сработал в фоновой проверке; последняя ошибка — текст для пользователя.
    last_ok_at: int | None = None
    last_error: str | None = None
    # Последняя успешная проверка схемы (фоновая или из приложения), epoch millis.
    last_scan_at: int | None = None
    interval_minutes: float
    window_hours: int


class ClaimRequest(CamelModel):
    device_id: str = _DEVICE
    # id аномалий стенда, о которых устройство собирается уведомить.
    ids: list[str]


class ClaimResponse(CamelModel):
    # Те из ids, о которых пользователь ещё не получал уведомления (ни на одном устройстве), — уведомлять о них.
    ids: list[str]
