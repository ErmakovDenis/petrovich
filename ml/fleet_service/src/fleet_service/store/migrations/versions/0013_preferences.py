"""P3: версия настроек общения и атомарный журнал повторов операций."""

from alembic import op
import sqlalchemy as sa

revision = "0013"
down_revision = "0012"
branch_labels = None
depends_on = None

OLD_KEYS = "'response_length','response_format','technical_detail','language','auto_extract_enabled'"


def upgrade() -> None:
    with op.batch_alter_table("user_preferences") as batch:
        batch.add_column(sa.Column("version", sa.Integer, nullable=False, server_default="1"))
        batch.create_check_constraint("ck_preferences_version", "version >= 1")
        batch.drop_constraint("ck_preferences_key", type_="check")
        batch.create_check_constraint("ck_preferences_key", f"key IN ({OLD_KEYS},'preferred_terms')")
    op.create_table(
        "memory_preference_operations",
        sa.Column("tenant_id", sa.String(64), primary_key=True),
        sa.Column("principal_id", sa.String(36), primary_key=True),
        sa.Column("operation_id", sa.String(128), primary_key=True),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("action", sa.String(16), nullable=False),
        sa.Column("target_id", sa.String(36), sa.ForeignKey("user_preferences.id"), nullable=False),
        sa.Column("target_version", sa.Integer, nullable=False),
        sa.Column("at", sa.BigInteger, nullable=False),
        sa.ForeignKeyConstraint(
            ["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]
        ),
        sa.CheckConstraint("action IN ('set', 'delete')", name="ck_preference_operation_action"),
    )


def downgrade() -> None:
    op.drop_table("memory_preference_operations")
    # Новый вид настройки несовместим со схемой 0012; остальные значения и UUID сохраняются.
    op.execute(sa.text("DELETE FROM user_preferences WHERE key = 'preferred_terms'"))
    with op.batch_alter_table("user_preferences") as batch:
        batch.drop_constraint("ck_preferences_key", type_="check")
        batch.create_check_constraint("ck_preferences_key", f"key IN ({OLD_KEYS})")
        batch.drop_constraint("ck_preferences_version", type_="check")
        batch.drop_column("version")
