"""Письма: черновики и журнал отправок и отказов.

Revision ID: 0003
Revises: 0002
Create Date: 2026-10-09
"""

import sqlalchemy as sa
from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "email_drafts",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("schema_id", sa.String(64), nullable=False),
        sa.Column("user_key", sa.String(200), nullable=False),
        sa.Column("user_name", sa.String(200), nullable=False),
        sa.Column("recipient_ids", sa.Text, nullable=False),
        sa.Column("recipients_hash", sa.String(64), nullable=False),
        sa.Column("subject", sa.Text, nullable=False),
        sa.Column("body", sa.Text, nullable=False),
        sa.Column("created_at", sa.BigInteger, nullable=False),
        sa.Column("expires_at", sa.BigInteger, nullable=False),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("sent_at", sa.BigInteger),
    )
    op.create_index("ix_email_drafts_user", "email_drafts", ["schema_id", "user_key", "status", "sent_at"])
    op.create_table(
        "email_log",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("at", sa.BigInteger, nullable=False),
        sa.Column("schema_id", sa.String(64), nullable=False),
        sa.Column("user_name", sa.String(200)),
        sa.Column("draft_id", sa.String(64)),
        sa.Column("recipients", sa.Text, nullable=False),
        sa.Column("subject", sa.Text, nullable=False),
        sa.Column("outcome", sa.String(16), nullable=False),
        sa.Column("error", sa.Text),
    )
    op.create_index("ix_email_log_at", "email_log", ["at"])


def downgrade() -> None:
    op.drop_index("ix_email_log_at", table_name="email_log")
    op.drop_table("email_log")
    op.drop_index("ix_email_drafts_user", table_name="email_drafts")
    op.drop_table("email_drafts")
