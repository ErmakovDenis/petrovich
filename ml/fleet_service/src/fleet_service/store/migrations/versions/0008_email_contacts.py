"""Почтовые контакты приложения по схеме и владельцу."""

import sqlalchemy as sa
from alembic import op

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "email_contacts",
        sa.Column("schema_id", sa.String(64), primary_key=True),
        sa.Column("user_key", sa.String(200), primary_key=True),
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("name", sa.String(200), nullable=False),
        sa.Column("role", sa.String(200), nullable=False),
        sa.Column("email", sa.String(320), nullable=False),
    )


def downgrade() -> None:
    op.drop_table("email_contacts")
