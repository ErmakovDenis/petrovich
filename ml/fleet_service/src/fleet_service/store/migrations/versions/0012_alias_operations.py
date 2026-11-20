"""P2: идемпотентные операции персональных прозвищ, без исходного текста команды."""

from alembic import op
import sqlalchemy as sa

revision = "0012"
down_revision = "0011"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "memory_alias_operations",
        sa.Column("tenant_id", sa.String(64), primary_key=True),
        sa.Column("principal_id", sa.String(36), primary_key=True),
        sa.Column("operation_id", sa.String(128), primary_key=True),
        sa.Column("request_hash", sa.String(64), nullable=False),
        sa.Column("action", sa.String(16), nullable=False),
        sa.Column("target_id", sa.String(36), sa.ForeignKey("user_entity_aliases.id")),
        sa.Column("at", sa.BigInteger, nullable=False),
        sa.ForeignKeyConstraint(
            ["principal_id", "tenant_id"], ["principal_scopes.principal_id", "principal_scopes.tenant_id"]
        ),
        sa.CheckConstraint("action IN ('set', 'delete')", name="ck_alias_operation_action"),
    )


def downgrade() -> None:
    op.drop_table("memory_alias_operations")
