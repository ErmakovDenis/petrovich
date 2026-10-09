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

# Доступ стенда к AutoGRAPH для фоновой проверки: одна запись на установку приложения (устройство).
# Токен и пароль — только шифротекстом (background/crypto.py), пароль — только с согласия пользователя.
background_access = Table(
    "background_access",
    metadata,
    # Случайный id установки приложения: им же доступ отзывается.
    Column("device_id", String(64), primary_key=True),
    Column("schema_id", String(64), nullable=False),
    # Логин AutoGRAPH из X-User-Name; для доступа по паролю проверен входом в AutoGRAPH.
    Column("user_name", String(200)),
    # Смещение пояса пользователя: с ним стенд входит по паролю и задаёт периоды проверки.
    Column("utc_offset_minutes", Integer, nullable=False),
    # Последний токен сессии от приложения или от входа стенда по паролю; NULL — истёк.
    Column("token_enc", Text),
    # С какого момента стенд знает этот токен, epoch millis — для оценки срока жизни токена.
    Column("token_since", BigInteger),
    Column("password_enc", Text),
    Column("registered_at", BigInteger, nullable=False),
    # Когда приложение последний раз подтвердило доступ; давно не подтверждали — запись удаляется.
    Column("refreshed_at", BigInteger, nullable=False),
    # Последний раз доступ сработал в фоновой проверке, и последняя ошибка (текст для пользователя).
    Column("last_ok_at", BigInteger),
    Column("last_error", Text),
    Index("ix_background_access_schema", "schema_id"),
)

# Какие аномалии уже показаны уведомлением пользователю (на любом из его устройств): одно событие — одно уведомление.
notification_claims = Table(
    "notification_claims",
    metadata,
    Column("schema_id", String(64), primary_key=True),
    # Логин в нижнем регистре; без логина — id устройства.
    Column("user_key", String(200), primary_key=True),
    Column("anomaly_id", String(512), primary_key=True),
    Column("device_id", String(64), nullable=False),
    Column("claimed_at", BigInteger, nullable=False),
    Index("ix_notification_claims_at", "claimed_at"),
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

# Черновики писем: готовит ассистент (tool draft_email), отправляет стенд только по подтверждению в приложении.
email_drafts = Table(
    "email_drafts",
    metadata,
    # Случайный id (секрет черновика: его знает только приложение пользователя).
    Column("id", String(64), primary_key=True),
    Column("schema_id", String(64), nullable=False),
    # Владелец — логин из X-User-Name без учёта регистра; подтвердить или отменить может только он.
    Column("user_key", String(200), nullable=False),
    Column("user_name", String(200), nullable=False),
    # id получателей через запятую (адреса — из файла получателей при отправке).
    Column("recipient_ids", Text, nullable=False),
    # SHA-256 пар «id=адрес» на момент подготовки: изменился адрес в файле — черновик не отправляется.
    Column("recipients_hash", String(64), nullable=False),
    Column("subject", Text, nullable=False),
    Column("body", Text, nullable=False),
    Column("created_at", BigInteger, nullable=False),
    Column("expires_at", BigInteger, nullable=False),
    # draft — ждёт подтверждения, sending — отправляется, sent, cancelled, failed — ушло не всем (повтор запрещён).
    Column("status", String(16), nullable=False),
    Column("sent_at", BigInteger),
    Index("ix_email_drafts_user", "schema_id", "user_key", "status", "sent_at"),
)

# Журнал писем: каждая отправка и каждый отказ — кто, кому, когда, тема, итог. Текста письма и адресов здесь нет.
email_log = Table(
    "email_log",
    metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("at", BigInteger, nullable=False),
    Column("schema_id", String(64), nullable=False),
    Column("user_name", String(200)),
    Column("draft_id", String(64)),
    # Получатели: «id (имя)» через запятую.
    Column("recipients", Text, nullable=False),
    Column("subject", Text, nullable=False),
    # drafted, sent, failed, cancelled, limit, expired, repeat, not_found, rejected
    Column("outcome", String(16), nullable=False),
    Column("error", Text),
    Index("ix_email_log_at", "at"),
)
