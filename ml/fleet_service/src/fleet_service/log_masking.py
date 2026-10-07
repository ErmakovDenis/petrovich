"""Маскирование секретов в логах стенда.

AutoGRAPH принимает логин, пароль и токен сессии в query-строке (`UserName=`, `Password=`, `session=`), а httpx
пишет адрес запроса в лог. Ключ OpenRouter и токен пользователя ходят в заголовке `Authorization: Bearer`.
Маскирование встроено в фабрику записей logging, поэтому действует для всех логгеров и обработчиков
(uvicorn, httpx, свои), включая текст исключений — как `SECRET_QUERY_PARAMS` в ApiFactory приложения.
"""

import logging
import re

_PATTERNS = [
    (re.compile(r"(?i)\b(session|username|password)=[^&\s\"']*"), r"\1=***"),
    (re.compile(r"(?i)\b(bearer\s+)[A-Za-z0-9\-._~+/=]+"), r"\1***"),
    (re.compile(r"sk-or-[A-Za-z0-9\-_]+"), "sk-or-***"),
]

_installed = False


def mask_secrets(text: str) -> str:
    for pattern, replacement in _PATTERNS:
        text = pattern.sub(replacement, text)
    return text


def _install_record_factory() -> None:
    global _installed
    if _installed:
        return
    previous = logging.getLogRecordFactory()
    plain = logging.Formatter()

    def factory(*args, **kwargs) -> logging.LogRecord:
        record = previous(*args, **kwargs)
        try:
            message = record.getMessage()
        except Exception:  # noqa: BLE001 — некорректные args: logging сам сообщит при выводе
            return record
        masked = mask_secrets(message)
        if masked != message:
            record.msg, record.args = masked, None
        if record.exc_info and not record.exc_text:
            # Formatter берёт готовый exc_text и не форматирует исключение повторно.
            record.exc_text = mask_secrets(plain.formatException(record.exc_info))
        return record

    logging.setLogRecordFactory(factory)
    _installed = True


def configure_logging(level: str = "INFO") -> None:
    _install_record_factory()
    logging.basicConfig(level=level.upper(), format="%(asctime)s %(levelname)s %(name)s: %(message)s")
