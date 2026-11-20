"""Схема базы хранилища (SQLAlchemy Core) и подключение. Изменения схемы — только миграциями Alembic
(store/migrations/versions), таблицы здесь описаны для запросов и должны совпадать с последней миграцией
(tests/test_store.py сверяет).

Время событий хранится в UTC без пояса: пользователи одной схемы могут жить в разных поясах, а id события не должен
от этого зависеть. В местное время пользователя оно переводится при выдаче.
"""

from pathlib import Path

from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    CheckConstraint,
    ForeignKeyConstraint,
    DateTime,
    Engine,
    Float,
    ForeignKey,
    Index,
    Integer,
    MetaData,
    String,
    Table,
    Text,
    UniqueConstraint,
    create_engine,
    event,
    false,
    text,
)
from sqlalchemy.engine import make_url

metadata = MetaData()

# Личность подтверждается серверным Login, а не заголовком X-User-Name.
principals = Table(
    "principals", metadata,
    Column("principal_id", String(36), primary_key=True),
    Column("provider", String(64), nullable=False),
    Column("provider_subject", String(64), nullable=False),
    Column("created_at", BigInteger, nullable=False),
    Column("disabled_at", BigInteger),
    UniqueConstraint("provider", "provider_subject", name="uq_principals_provider_subject"),
)

principal_scopes = Table(
    "principal_scopes", metadata,
    Column("principal_id", String(36), ForeignKey("principals.principal_id"), primary_key=True),
    Column("tenant_id", String(64), primary_key=True),
    Column("linked_at", BigInteger, nullable=False),
    Column("revoked_at", BigInteger),
)

# Только хэши секретов; сессия строго та, которая получена при проверенном Login.
identity_credentials = Table(
    "identity_credentials", metadata,
    Column("credential_hash", String(64), primary_key=True),
    Column("principal_id", String(36), ForeignKey("principals.principal_id"), nullable=False),
    Column("tenant_id", String(64), nullable=False),
    Column("session_hash", String(64), nullable=False),
    Column("created_at", BigInteger, nullable=False),
    Column("expires_at", BigInteger, nullable=False),
    Column("revoked_at", BigInteger),
    Index("ix_identity_credentials_principal", "principal_id"),
)

# Реестр управляемых аккаунтов: логин — изменяемая привязка, principal_id — неизменная личность.
identity_accounts = Table(
    "identity_accounts", metadata,
    Column("provider", String(64), primary_key=True),
    Column("login_hash", String(64), primary_key=True),
    Column("principal_id", String(36), ForeignKey("principals.principal_id"), nullable=False, unique=True),
    Column("updated_at", BigInteger, nullable=False),
)

identity_account_events = Table(
    "identity_account_events", metadata,
    Column("id", String(36), primary_key=True),
    Column("principal_id", String(36), ForeignKey("principals.principal_id"), nullable=False),
    Column("action", String(16), nullable=False),
    Column("previous_login_hash", String(64), nullable=False),
    Column("next_login_hash", String(64)),
    Column("at", BigInteger, nullable=False),
)

# Персональные коды Telegram не совместимы с прежними кодами подключения к схеме.
identity_telegram_codes = Table(
    "identity_telegram_codes", metadata,
    Column("code_hash", String(64), primary_key=True),
    Column("chat_id", String(32), nullable=False, unique=True),
    Column("telegram_user_id", String(32), nullable=False),
    Column("expires_at", BigInteger, nullable=False),
)

identity_telegram_links = Table(
    "identity_telegram_links", metadata,
    Column("chat_id", String(32), primary_key=True),
    Column("telegram_user_id", String(32), nullable=False),
    Column("principal_id", String(36), ForeignKey("principals.principal_id"), nullable=False),
    Column("tenant_id", String(64), nullable=False),
    Column("credential_hash", String(64), ForeignKey("identity_credentials.credential_hash"), nullable=False),
    Column("session_ciphertext", Text, nullable=False),
    Column("linked_at", BigInteger, nullable=False),
    Column("expires_at", BigInteger, nullable=False),
    Index("ix_identity_telegram_owner", "principal_id", "tenant_id"),
)

# Контакты телефона: отдельный список по схеме и владельцу, сохраняется между перезапусками.
email_contacts = Table(
    "email_contacts", metadata,
    Column("schema_id", String(64), primary_key=True),
    Column("user_key", String(200), primary_key=True),
    Column("id", String(64), primary_key=True),
    Column("name", String(200), nullable=False),
    Column("role", String(200), nullable=False),
    Column("email", String(320), nullable=False),
)

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

# Состояние ленты «Petrovich AI» по схеме: чем сейчас отвечаем и когда API последний раз ответил.
ai_feed_state = Table(
    "ai_feed_state",
    metadata,
    Column("schema_id", String(64), primary_key=True),
    # api | api_cache | rules | unavailable
    Column("feed_source", String(32), nullable=False),
    Column("api_updated_at", BigInteger),
    Column("fallback_rules", Boolean, nullable=False, server_default=false()),
    Column("updated_at", BigInteger, nullable=False),
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

widget_daily_stats = Table(
    "widget_daily_stats",
    metadata,
    Column("schema_id", String(64), primary_key=True),
    Column("vehicle_id", String(128), primary_key=True),
    Column("day", String(10), primary_key=True),
    Column("vehicle_name", Text, nullable=False),
    Column("vehicle_group", Text),
    Column("fuel", Float, nullable=False),
    Column("idle", Float, nullable=False),
    Column("mileage", Float, nullable=False),
    Column("refuels", Integer, nullable=False),
    Column("last_seen", String(19)),
    Index("ix_widget_daily_stats_schema_day", "schema_id", "day"),
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

# Telegram-бот: одноразовый код подключения, который приложение выдаёт для ссылки t.me/<бот>?start=<код>.
# Хранится только хэш кода: код знают приложение пользователя и Telegram.
telegram_link_codes = Table(
    "telegram_link_codes",
    metadata,
    Column("code_hash", String(64), primary_key=True),
    Column("schema_id", String(64), nullable=False),
    Column("schema_name", Text, nullable=False),
    # Логин AutoGRAPH: бот работает по доступу фоновой проверки этого пользователя в этой схеме.
    Column("user_name", String(200), nullable=False),
    Column("utc_offset_minutes", Integer, nullable=False),
    Column("expires_at", BigInteger, nullable=False),
)

# Подключённые чаты Telegram: один чат — одна схема и один пользователь AutoGRAPH; подписки — JSON (telegram/links.py).
telegram_links = Table(
    "telegram_links",
    metadata,
    Column("chat_id", String(32), primary_key=True),
    Column("schema_id", String(64), nullable=False),
    Column("schema_name", Text, nullable=False),
    Column("user_name", String(200), nullable=False),
    Column("utc_offset_minutes", Integer, nullable=False),
    Column("settings", Text, nullable=False),
    Column("linked_at", BigInteger, nullable=False),
    # Когда бот последний раз предупредил, что доступа к AutoGRAPH нет; NULL — доступ есть.
    Column("access_warned_at", BigInteger),
    # Как чат называется в Telegram (@имя или имя пользователя) — чтобы узнать его в приложении.
    Column("title", String(200)),
    # Местная дата (ISO), за которую ушли утренний доклад и недельная сводка: не чаще раза в день.
    Column("daily_sent_on", String(10)),
    Column("weekly_sent_on", String(10)),
    Index("ix_telegram_links_schema", "schema_id"),
)

# Код подключения, который бот показывает в чате: пользователь вводит его в приложении (Telegram на другом устройстве).
# Хранится только хэш кода.
telegram_pair_codes = Table(
    "telegram_pair_codes",
    metadata,
    Column("code_hash", String(64), primary_key=True),
    Column("chat_id", String(32), nullable=False),
    Column("title", String(200)),
    Column("expires_at", BigInteger, nullable=False),
    Index("ix_telegram_pair_codes_chat", "chat_id"),
)

# Какие аномалии уже ушли в чат (delivered) или пропущены по подписке (тихие часы, лимит в день, повтор): одно событие —
# одно решение «отправить или нет» на чат.
telegram_notifications = Table(
    "telegram_notifications",
    metadata,
    Column("chat_id", String(32), primary_key=True),
    Column("anomaly_id", String(512), primary_key=True),
    Column("severity", String(16), nullable=False),
    Column("delivered", Boolean, nullable=False),
    # Пропущено как повтор того же события на машине (антиспам подписки) — для строки «повторялось ещё N раз».
    Column("repeat", Boolean, nullable=False, server_default=false()),
    Column("at", BigInteger, nullable=False),
    Index("ix_telegram_notifications_at", "at"),
)

# P1: данные памяти. Все персональные записи принадлежат проверенному principal и схеме.
conversations = Table(
    "conversations", metadata,
    Column("id", String(36), primary_key=True),
    Column("tenant_id", String(64), nullable=False),
    Column("principal_id", String(36), nullable=False),
    Column("channel", String(16), nullable=False),
    Column("created_at", BigInteger, nullable=False),
    Column("updated_at", BigInteger, nullable=False),
    Column("version", Integer, nullable=False),
    Column("deleted_at", BigInteger),
    ForeignKeyConstraint(["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]),
    Column("style_overrides", Text, nullable=False, server_default="{}"),
    CheckConstraint("channel IN ('android','telegram')", name="ck_conversations_channel"),
    CheckConstraint("version >= 0", name="ck_conversations_version"),
    Index("ix_conversations_owner", "tenant_id", "principal_id", "updated_at"),
)

conversation_messages = Table(
    "conversation_messages", metadata,
    Column("id", String(36), primary_key=True),
    Column("conversation_id", String(36), ForeignKey("conversations.id"), nullable=False),
    Column("client_message_id", String(128), nullable=False),
    Column("role", String(16), nullable=False),
    Column("content", Text, nullable=False),
    Column("state", String(16), nullable=False),
    Column("created_at", BigInteger, nullable=False),
    Column("turn_id", String(36), nullable=False),
    UniqueConstraint("conversation_id", "client_message_id", "role", name="uq_messages_client_role"),
    CheckConstraint("role IN ('user','assistant')", name="ck_messages_role"),
    CheckConstraint("state IN ('pending','committed','failed','deleted')", name="ck_messages_state"),
    CheckConstraint("length(content) <= 32000", name="ck_messages_length"),
    Index("ix_messages_conversation", "conversation_id", "created_at", "id"),
)

conversation_summaries = Table(
    "conversation_summaries", metadata,
    Column("conversation_id", String(36), ForeignKey("conversations.id"), primary_key=True),
    Column("version", Integer, nullable=False),
    Column("summary", Text, nullable=False),
    Column("source_range", Text, nullable=False),
    Column("updated_at", BigInteger, nullable=False),
    CheckConstraint("length(summary) <= 8000", name="ck_summaries_length"),
)

user_entity_aliases = Table(
    "user_entity_aliases", metadata,
    Column("id", String(36), primary_key=True),
    Column("tenant_id", String(64), nullable=False),
    Column("principal_id", String(36), nullable=False),
    Column("entity_type", String(16), nullable=False),
    Column("entity_id", String(128), nullable=False),
    Column("alias", String(120), nullable=False),
    Column("normalized_alias", String(240), nullable=False),
    Column("status", String(16), nullable=False),
    Column("source_message_id", String(36), ForeignKey("conversation_messages.id")),
    Column("created_at", BigInteger, nullable=False),
    Column("updated_at", BigInteger, nullable=False),
    Column("deleted_at", BigInteger),
    ForeignKeyConstraint(["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]),
    CheckConstraint("entity_type = 'vehicle'", name="ck_aliases_entity"),
    CheckConstraint("status IN ('active','deleted')", name="ck_aliases_status"),
    Index("uq_aliases_active", "tenant_id", "principal_id", "entity_type", "normalized_alias", unique=True,
          sqlite_where=text("deleted_at IS NULL AND status = 'active'")),
)

user_preferences = Table(
    "user_preferences", metadata,
    Column("id", String(36), primary_key=True),
    Column("tenant_id", String(64), nullable=False),
    Column("principal_id", String(36), nullable=False),
    Column("key", String(64), nullable=False),
    Column("value_json", Text, nullable=False),
    Column("source_type", String(16), nullable=False),
    Column("version", Integer, nullable=False, server_default="1"),
    CheckConstraint("version >= 1", name="ck_preferences_version"),
    Column("status", String(16), nullable=False),
    Column("updated_at", BigInteger, nullable=False),
    Column("deleted_at", BigInteger),
    ForeignKeyConstraint(["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]),
    CheckConstraint("source_type IN ('explicit','settings')", name="ck_preferences_source"),
    CheckConstraint("status IN ('active','deleted')", name="ck_preferences_status"),
    CheckConstraint("key IN ('response_length','response_format','technical_detail','language','auto_extract_enabled','preferred_terms')", name="ck_preferences_key"),
    Index("uq_preferences_active", "tenant_id", "principal_id", "key", unique=True,
          sqlite_where=text("deleted_at IS NULL AND status = 'active'")),
)

investigation_notes = Table(
    "investigation_notes", metadata,
    Column("id", String(36), primary_key=True),
    Column("tenant_id", String(64), nullable=False),
    Column("anomaly_id", String(512), nullable=False),
    Column("vehicle_id", String(128), nullable=False),
    Column("author_id", String(36), nullable=False),
    Column("note", Text, nullable=False),
    Column("status", String(24), nullable=False),
    Column("created_at", BigInteger, nullable=False),
    Column("updated_at", BigInteger, nullable=False),
    Column("deleted_at", BigInteger),
    ForeignKeyConstraint(["author_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]),
    CheckConstraint("status IN ('observation','hypothesis','verified_reason','action')", name="ck_notes_status"),
    CheckConstraint("length(note) <= 4000", name="ck_notes_length"),
    Index("ix_notes_anomaly", "tenant_id", "anomaly_id"),
)

memory_candidates = Table(
    "memory_candidates", metadata,
    Column("id", String(36), primary_key=True),
    Column("tenant_id", String(64), nullable=False),
    Column("principal_id", String(36), nullable=False),
    Column("kind", String(16), nullable=False),
    Column("dedupe_key", String(64), nullable=False),
    Column("payload_json", Text, nullable=False),
    Column("evidence_message_id", String(36), ForeignKey("conversation_messages.id"), nullable=False),
    Column("status", String(16), nullable=False),
    Column("expires_at", BigInteger, nullable=False),
    ForeignKeyConstraint(["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]),
    UniqueConstraint("tenant_id", "principal_id", "dedupe_key", name="uq_candidates_dedupe"),
    CheckConstraint("kind IN ('alias','preference')", name="ck_candidates_kind"),
    CheckConstraint("status IN ('pending','accepted','rejected','expired')", name="ck_candidates_status"),
    CheckConstraint("length(payload_json) <= 4000", name="ck_candidates_length"),
)

memory_outbox = Table(
    "memory_outbox", metadata,
    Column("id", String(36), primary_key=True),
    Column("tenant_id", String(64), nullable=False),
    Column("principal_id", String(36), nullable=False),
    Column("event_type", String(32), nullable=False),
    Column("aggregate_id", String(36), ForeignKey("conversations.id"), nullable=False),
    Column("payload_ref", String(36), ForeignKey("conversation_messages.id"), nullable=False),
    Column("state", String(16), nullable=False),
    Column("attempts", Integer, nullable=False),
    Column("next_run_at", BigInteger, nullable=False),
    Column("lease_token", String(36)),
    ForeignKeyConstraint(["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]),
    UniqueConstraint("event_type", "payload_ref", name="uq_outbox_event"),
    CheckConstraint("event_type = 'turn_committed'", name="ck_outbox_event"),
    CheckConstraint("state IN ('pending','processing','done','dead','cancelled')", name="ck_outbox_state"),
    CheckConstraint("attempts >= 0", name="ck_outbox_attempts"),
    Index("ix_outbox_due", "state", "next_run_at"),
)

memory_audit = Table(
    "memory_audit", metadata,
    Column("id", String(36), primary_key=True),
    Column("tenant_id", String(64), nullable=False),
    Column("principal_id", String(36), nullable=False),
    Column("action", String(32), nullable=False),
    Column("target_type", String(32), nullable=False),
    Column("target_id", String(36), nullable=False),
    Column("at", BigInteger, nullable=False),
    Column("reason", String(32), nullable=False),
    ForeignKeyConstraint(["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]),
    Index("ix_memory_audit_owner", "tenant_id", "principal_id", "at"),
)

# Операции P2: повтор запроса не восстанавливает удалённую запись и не удаляет новую привязку.
memory_alias_operations = Table(
    "memory_alias_operations", metadata,
    Column("tenant_id", String(64), primary_key=True),
    Column("principal_id", String(36), primary_key=True),
    Column("operation_id", String(128), primary_key=True),
    Column("request_hash", String(64), nullable=False),
    Column("action", String(16), nullable=False),
    Column("target_id", String(36), ForeignKey("user_entity_aliases.id")),
    Column("at", BigInteger, nullable=False),
    ForeignKeyConstraint(["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]),
    CheckConstraint("action IN ('set', 'delete')", name="ck_alias_operation_action"),
)

# P3: повтор старой операции не перезаписывает более новую настройку.
memory_preference_operations = Table(
    "memory_preference_operations", metadata,
    Column("tenant_id", String(64), primary_key=True),
    Column("principal_id", String(36), primary_key=True),
    Column("operation_id", String(128), primary_key=True),
    Column("request_hash", String(64), nullable=False),
    Column("action", String(16), nullable=False),
    Column("target_id", String(36), ForeignKey("user_preferences.id"), nullable=False),
    Column("target_version", Integer, nullable=False),
    Column("at", BigInteger, nullable=False),
    ForeignKeyConstraint(["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]),
    CheckConstraint("action IN ('set', 'delete')", name="ck_preference_operation_action"),
)

# P4: обработка и результаты tools переживают перезапуск сервера.

conversation_turns = Table(
    "conversation_turns",
    metadata,
    Column("id", String(36), primary_key=True),
    Column("conversation_id", String(36), ForeignKey("conversations.id"), nullable=False),
    Column("client_message_id", String(128), nullable=False),
    Column("request_hash", String(64), nullable=False),
    Column("base_version", Integer, nullable=False),
    Column("state", String(16), nullable=False),
    Column("lease_token", String(36)),
    Column("lease_until", BigInteger, nullable=False),
    Column("user_message_id", String(36), ForeignKey("conversation_messages.id"), nullable=False),
    Column("assistant_message_id", String(36), ForeignKey("conversation_messages.id")),
    Column("vehicle_ids_json", Text, nullable=False),
    Column("draft_ids_json", Text, nullable=False),
    Column("alias_results_json", Text, nullable=False),
    Column("preference_results_json", Text, nullable=False),
    Column("created_at", BigInteger, nullable=False),
    Column("updated_at", BigInteger, nullable=False),
    UniqueConstraint("conversation_id", "client_message_id", name="uq_turn_client_message"),
    CheckConstraint(
        "state IN ('pending','completed','failed','interrupted','deleted')", name="ck_turn_state"
    ),
)

Index(
    "uq_turn_pending",
    "conversation_id",
    unique=True,
    sqlite_where=text("state = 'pending'"),
    _table=conversation_turns,
)

conversation_tool_calls = Table(
    "conversation_tool_calls",
    metadata,
    Column("id", String(36), primary_key=True),
    Column("turn_id", String(36), ForeignKey("conversation_turns.id"), nullable=False),
    Column("request_hash", String(64), nullable=False),
    Column("tool_name", String(80), nullable=False),
    Column("state", String(16), nullable=False),
    Column("result_json", Text),
    Column("created_at", BigInteger, nullable=False),
    UniqueConstraint("turn_id", "request_hash", name="uq_turn_tool_request"),
    CheckConstraint("state IN ('started','done','deleted')", name="ck_turn_tool_state"),
)
