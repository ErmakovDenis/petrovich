"""P1: постоянное хранилище памяти. Снимок схемы, независимый от runtime metadata."""

from alembic import op
from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Column,
    ForeignKey,
    ForeignKeyConstraint,
    Integer,
    String,
    Text,
    UniqueConstraint,
    text,
)

revision = "0011"
down_revision = "0010"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "conversations",
        Column("id", String(36), primary_key=True),
        Column("tenant_id", String(64), nullable=False),
        Column("principal_id", String(36), nullable=False),
        Column("channel", String(16), nullable=False),
        Column("created_at", BigInteger, nullable=False),
        Column("updated_at", BigInteger, nullable=False),
        Column("version", Integer, nullable=False),
        Column("deleted_at", BigInteger),
        ForeignKeyConstraint(
            ["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]
        ),
        CheckConstraint("channel IN ('android','telegram')", name="ck_conversations_channel"),
        CheckConstraint("version >= 0", name="ck_conversations_version"),
    )
    op.create_table(
        "conversation_messages",
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
    )
    op.create_table(
        "conversation_summaries",
        Column("conversation_id", String(36), ForeignKey("conversations.id"), primary_key=True),
        Column("version", Integer, nullable=False),
        Column("summary", Text, nullable=False),
        Column("source_range", Text, nullable=False),
        Column("updated_at", BigInteger, nullable=False),
        CheckConstraint("length(summary) <= 8000", name="ck_summaries_length"),
    )
    op.create_table(
        "user_entity_aliases",
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
        ForeignKeyConstraint(
            ["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]
        ),
        CheckConstraint("entity_type = 'vehicle'", name="ck_aliases_entity"),
        CheckConstraint("status IN ('active','deleted')", name="ck_aliases_status"),
    )
    op.create_table(
        "user_preferences",
        Column("id", String(36), primary_key=True),
        Column("tenant_id", String(64), nullable=False),
        Column("principal_id", String(36), nullable=False),
        Column("key", String(64), nullable=False),
        Column("value_json", Text, nullable=False),
        Column("source_type", String(16), nullable=False),
        Column("status", String(16), nullable=False),
        Column("updated_at", BigInteger, nullable=False),
        Column("deleted_at", BigInteger),
        ForeignKeyConstraint(
            ["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]
        ),
        CheckConstraint("source_type IN ('explicit','settings')", name="ck_preferences_source"),
        CheckConstraint("status IN ('active','deleted')", name="ck_preferences_status"),
        CheckConstraint(
            "key IN ('response_length','response_format','technical_detail','language','auto_extract_enabled')",
            name="ck_preferences_key",
        ),
    )
    op.create_table(
        "investigation_notes",
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
        ForeignKeyConstraint(
            ["author_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]
        ),
        CheckConstraint(
            "status IN ('observation','hypothesis','verified_reason','action')", name="ck_notes_status"
        ),
        CheckConstraint("length(note) <= 4000", name="ck_notes_length"),
    )
    op.create_table(
        "memory_candidates",
        Column("id", String(36), primary_key=True),
        Column("tenant_id", String(64), nullable=False),
        Column("principal_id", String(36), nullable=False),
        Column("kind", String(16), nullable=False),
        Column("dedupe_key", String(64), nullable=False),
        Column("payload_json", Text, nullable=False),
        Column("evidence_message_id", String(36), ForeignKey("conversation_messages.id"), nullable=False),
        Column("status", String(16), nullable=False),
        Column("expires_at", BigInteger, nullable=False),
        ForeignKeyConstraint(
            ["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]
        ),
        UniqueConstraint("tenant_id", "principal_id", "dedupe_key", name="uq_candidates_dedupe"),
        CheckConstraint("kind IN ('alias','preference')", name="ck_candidates_kind"),
        CheckConstraint("status IN ('pending','accepted','rejected','expired')", name="ck_candidates_status"),
        CheckConstraint("length(payload_json) <= 4000", name="ck_candidates_length"),
    )
    op.create_table(
        "memory_outbox",
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
        ForeignKeyConstraint(
            ["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]
        ),
        UniqueConstraint("event_type", "payload_ref", name="uq_outbox_event"),
        CheckConstraint("event_type = 'turn_committed'", name="ck_outbox_event"),
        CheckConstraint(
            "state IN ('pending','processing','done','dead','cancelled')", name="ck_outbox_state"
        ),
        CheckConstraint("attempts >= 0", name="ck_outbox_attempts"),
    )
    op.create_table(
        "memory_audit",
        Column("id", String(36), primary_key=True),
        Column("tenant_id", String(64), nullable=False),
        Column("principal_id", String(36), nullable=False),
        Column("action", String(32), nullable=False),
        Column("target_type", String(32), nullable=False),
        Column("target_id", String(36), nullable=False),
        Column("at", BigInteger, nullable=False),
        Column("reason", String(32), nullable=False),
        ForeignKeyConstraint(
            ["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]
        ),
    )
    op.create_index("ix_conversations_owner", "conversations", ["tenant_id", "principal_id", "updated_at"])
    op.create_index(
        "ix_messages_conversation", "conversation_messages", ["conversation_id", "created_at", "id"]
    )
    op.create_index(
        "uq_aliases_active",
        "user_entity_aliases",
        ["tenant_id", "principal_id", "entity_type", "normalized_alias"],
        unique=True,
        sqlite_where=text("deleted_at IS NULL AND status = 'active'"),
    )
    op.create_index(
        "uq_preferences_active",
        "user_preferences",
        ["tenant_id", "principal_id", "key"],
        unique=True,
        sqlite_where=text("deleted_at IS NULL AND status = 'active'"),
    )
    op.create_index("ix_notes_anomaly", "investigation_notes", ["tenant_id", "anomaly_id"])
    op.create_index("ix_outbox_due", "memory_outbox", ["state", "next_run_at"])
    op.create_index("ix_memory_audit_owner", "memory_audit", ["tenant_id", "principal_id", "at"])


def downgrade() -> None:
    op.drop_table("memory_audit")
    op.drop_table("memory_outbox")
    op.drop_table("memory_candidates")
    op.drop_table("investigation_notes")
    op.drop_table("user_preferences")
    op.drop_table("user_entity_aliases")
    op.drop_table("conversation_summaries")
    op.drop_table("conversation_messages")
    op.drop_table("conversations")
