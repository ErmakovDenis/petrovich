"""Ошибки обращения к AutoGRAPH. Тексты безопасно показывать пользователю: в них нет токена и адреса запроса."""


class SessionInvalid(Exception):
    """Токен сессии AutoGRAPH недействителен или истёк — приложение входит заново и повторяет запрос."""

    def __init__(self, message: str = "Сессия AutoGRAPH недействительна или истекла"):
        super().__init__(message)


class SchemaForbidden(Exception):
    pass


class LoginRejected(Exception):
    """AutoGRAPH не принял логин и пароль (вход стенда по сохранённому паролю)."""

    def __init__(self, message: str = "AutoGRAPH не принял логин или пароль"):
        super().__init__(message)


class AutoGraphUnavailable(Exception):
    def __init__(self, message: str = "AutoGRAPH недоступен"):
        super().__init__(message)


class TripTablesTooLarge(Exception):
    """Сбойный ответ GetTripTables: точек больше FS_TRIP_TABLES_MAX_POINTS."""
