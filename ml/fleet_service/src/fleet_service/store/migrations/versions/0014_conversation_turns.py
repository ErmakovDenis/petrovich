"""P4: durable turn, аренда обработки, журнал tools и локальный стиль разговора."""

from alembic import op
import sqlalchemy as sa

revision = "0014"
down_revision = "0013"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("conversations", sa.Column("style_overrides", sa.Text, nullable=False, server_default="{}"))
    op.create_table(
        "conversation_turns",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("conversation_id", sa.String(36), sa.ForeignKey("conversations.id"), nullable=False),
        sa.Column("client_message_id", sa.String(128), nullable=False),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("base_version", sa.Integer, nullable=False),
        sa.Column("state", sa.String(16), nullable=False),
        sa.Column("lease_token", sa.String(36)),
        sa.Column("lease_until", sa.BigInteger, nullable=False),
        sa.Column(
            "user_message_id", sa.String(36), sa.ForeignKey("conversation_messages.id"), nullable=False
        ),
        sa.Column("assistant_message_id", sa.String(36), sa.ForeignKey("conversation_messages.id")),
        sa.Column("vehicle_ids_json", sa.Text, nullable=False),
        sa.Column("draft_ids_json", sa.Text, nullable=False),
        sa.Column("alias_results_json", sa.Text, nullable=False),
        sa.Column("preference_results_json", sa.Text, nullable=False),
        sa.Column("created_at", sa.BigInteger, nullable=False),
        sa.Column("updated_at", sa.BigInteger, nullable=False),
        sa.UniqueConstraint("conversation_id", "client_message_id", name="uq_turn_client_message"),
        sa.CheckConstraint(
            "state IN ('pending','completed','failed','interrupted','deleted')", name="ck_turn_state"
        ),
    )
    op.create_index(
        "uq_turn_pending",
        "conversation_turns",
        ["conversation_id"],
        unique=True,
        sqlite_where=sa.text("state = 'pending'"),
    )
    op.create_table(
        "conversation_tool_calls",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("turn_id", sa.String(36), sa.ForeignKey("conversation_turns.id"), nullable=False),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("tool_name", sa.String(80), nullable=False),
        sa.Column("state", sa.String(16), nullable=False),
        sa.Column("result_json", sa.Text),
        sa.Column("created_at", sa.BigInteger, nullable=False),
        sa.UniqueConstraint("turn_id", "request_hash", name="uq_turn_tool_request"),
        sa.CheckConstraint("state IN ('started','done','deleted')", name="ck_turn_tool_state"),
    )


def downgrade() -> None:
    op.drop_table("conversation_tool_calls")
    op.drop_table("conversation_turns")
    op.drop_column("conversations", "style_overrides")
