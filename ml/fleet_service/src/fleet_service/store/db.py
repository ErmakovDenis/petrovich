"""Схема базы хранилища (SQLAlchemy Core) и подключение. Изменения схемы — только миграциями Alembic
(store/migrations/versions), таблицы здесь описаны для запросов и должны совпадать с последней миграцией
(tests/test_store.py сверяет).

Время событий хранится в UTC без пояса: пользователи одной схемы могут жить в разных поясах, а id события не должен
от этого зависеть. В местное время пользователя оно переводится при выдаче.
"""

from pathlib import Path

from sqlalchemy import (
    BigInteger,
    Column,
    DateTime,
    Engine,
    Float,
    Index,
    Integer,
    MetaData,
    String,
    Table,
    Text,
    create_engine,
    event,
)
from sqlalchemy.engine import make_url

metadata = MetaData()

anomalies = Table(
    "anomalies",
    metadata,
    Column("schema_id", String(64), primary_key=True),
    # id стенда: <источник>|<тип>|<машина>|<параметр>|<начало события в UTC>Z — см. store/repository.py.
    Column("id", String(512), primary_key=True),
    Column("vehicle_id", String(128), nullable=False),
    Column("vehicle_name", Text, nullable=False),
    # Тип из id (drain, drop, power, volt, overheat, oil, brake; у аналитики — свой).
    Column("kind", String(64), nullable=False),
    Column("category", String(16), nullable=False),
    Column("parameter_name", String(128), nullable=False),
    Column("parameter_caption", Text, nullable=False),
    Column("source", Text, nullable=False),
    Column("severity", String(16), nullable=False),
    Column("title", Text, nullable=False),
    Column("description", Text, nullable=False),
    Column("value", Float),
    Column("score", Float),
    # Начало события и конец эпизода (последний интервал, где выполнялось условие правила), UTC.
    Column("start_utc", DateTime, nullable=False),
    Column("end_utc", DateTime, nullable=False),
    # Когда событие впервые и в последний раз найдено проверкой, epoch millis.
    Column("first_detected_at", BigInteger, nullable=False),
    Column("last_detected_at", BigInteger, nullable=False),
    # Текущее решение: CONFIRMED / FALSE_ALARM; NULL — не разобрано. История — в decisions.
    Column("resolution", String(16)),
    Column("false_alarm_reason", Text),
    Column("resolved_by", String(200)),
    Column("resolved_at", BigInteger),
    Index("ix_anomalies_schema_start", "schema_id", "start_utc"),
    Index("ix_anomalies_episode", "schema_id", "vehicle_id", "kind", "parameter_name", "start_utc"),
)

decisions = Table(
    "decisions",
    metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("schema_id", String(64), nullable=False),
    Column("anomaly_id", String(512), nullable=False),
    # NULL — аномалию вернули в «ждут решения».
    Column("resolution", String(16)),
    Column("reason", Text),
    # Логин AutoGRAPH из заголовка X-User-Name (стенд его не проверяет); NULL — приложение не передало.
    Column("user_name", String(200)),
    Column("decided_at", BigInteger, nullable=False),
    # app — решение из приложения, import — перенос решений с устройства.
    Column("origin", String(16), nullable=False),
    Index("ix_decisions_anomaly", "schema_id", "anomaly_id"),
)

scan_marks = Table(
    "scan_marks",
    metadata,
    Column("schema_id", String(64), primary_key=True),
    # Последняя проверка с сохранением, в которой ответила хотя бы одна машина, epoch millis.
    Column("last_scan_at", BigInteger, nullable=False),
)


def create_db_engine(url: str) -> Engine:
    """Движок SQLAlchemy; для SQLite-файла создаётся каталог, включается WAL и ожидание блокировки."""
    parsed = make_url(url)
    sqlite = parsed.get_backend_name() == "sqlite"
    if sqlite and parsed.database and parsed.database != ":memory:":
        Path(parsed.database).parent.mkdir(parents=True, exist_ok=True)
    engine = create_engine(url, connect_args={"check_same_thread": False} if sqlite else {})
    if sqlite:
        @event.listens_for(engine, "connect")
        def _pragmas(connection, _record) -> None:  # noqa: ANN001 — DBAPI-соединение sqlite3
            cursor = connection.cursor()
            cursor.execute("PRAGMA journal_mode=WAL")
            cursor.execute("PRAGMA busy_timeout=5000")
            cursor.close()

    return engine
