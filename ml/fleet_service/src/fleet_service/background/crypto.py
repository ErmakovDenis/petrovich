"""Шифрование доступа к AutoGRAPH в базе стенда (токен сессии, пароль по согласию пользователя).

AES-256-GCM, ключ — FS_ACCESS_ENCRYPTION_KEY (32 байта в base64) из .env, в базе и её резервных копиях только
шифротекст. Шифротекст привязан к устройству и полю (associated data): строку нельзя переставить в другую запись
или из столбца пароля в столбец токена — расшифровка не пройдёт.
"""

import base64
import binascii
import os

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

_VERSION = "v1:"
_NONCE_BYTES = 12


class SealBroken(Exception):
    """Шифротекст не расшифровать: другой ключ (сменили FS_ACCESS_ENCRYPTION_KEY) или запись повреждена."""


def generate_key() -> str:
    """Новый ключ для FS_ACCESS_ENCRYPTION_KEY."""
    return base64.urlsafe_b64encode(os.urandom(32)).decode()


class SecretBox:
    def __init__(self, key: str):
        try:
            raw = base64.urlsafe_b64decode(key.strip() + "=" * (-len(key.strip()) % 4))
        except (binascii.Error, ValueError):
            raw = b""
        if len(raw) != 32:
            raise ValueError("FS_ACCESS_ENCRYPTION_KEY: нужен ключ 32 байта в base64 "
                             "(python -c \"import base64, os; print(base64.urlsafe_b64encode(os.urandom(32)).decode())\")")
        self._aead = AESGCM(raw)

    def seal(self, plain: str, context: str) -> str:
        nonce = os.urandom(_NONCE_BYTES)
        sealed = self._aead.encrypt(nonce, plain.encode(), context.encode())
        return _VERSION + base64.urlsafe_b64encode(nonce + sealed).decode()

    def open(self, sealed: str, context: str) -> str:
        if not sealed.startswith(_VERSION):
            raise SealBroken()
        try:
            raw = base64.urlsafe_b64decode(sealed[len(_VERSION):])
            return self._aead.decrypt(raw[:_NONCE_BYTES], raw[_NONCE_BYTES:], context.encode()).decode()
        except (binascii.Error, ValueError, InvalidTag) as e:
            raise SealBroken() from e
