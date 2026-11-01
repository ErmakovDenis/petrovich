"""Короткие ключи для кнопок: callback_data в Telegram — не длиннее 64 байт, а id аномалий стенда и черновиков писем
длиннее. Ключ — начало SHA-256 значения, пара хранится в SQLite и переживает перезапуск бота: кнопки старых
уведомлений продолжают работать. Записи старше срока удаляются."""

import hashlib
import sqlite3
import threading
import time
from pathlib import Path


class Refs:
    def __init__(self, path: str, ttl_days: int = 60):
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.execute("CREATE TABLE IF NOT EXISTS refs (key TEXT PRIMARY KEY, value TEXT NOT NULL, at INTEGER NOT NULL)")
        self._db.commit()
        self._lock = threading.Lock()
        self._ttl = ttl_days * 86400
        self._purged = 0.0

    def key(self, value: str) -> str:
        key = hashlib.sha256(value.encode()).hexdigest()[:16]
        now = time.time()
        with self._lock:
            self._db.execute("INSERT INTO refs (key, value, at) VALUES (?, ?, ?) "
                             "ON CONFLICT(key) DO UPDATE SET at = excluded.at", (key, value, int(now)))
            if now - self._purged > 3600:
                self._db.execute("DELETE FROM refs WHERE at < ?", (int(now - self._ttl),))
                self._purged = now
            self._db.commit()
        return key

    def value(self, key: str) -> str | None:
        with self._lock:
            row = self._db.execute("SELECT value FROM refs WHERE key = ?", (key,)).fetchone()
        return row[0] if row else None

    def close(self) -> None:
        self._db.close()
