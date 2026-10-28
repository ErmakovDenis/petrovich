"""Telegram-бот: коды подключения (ссылка из приложения и код из чата), подключённые чаты с настройками и отметки
отправленных уведомлений.

Revision ID: 0004
Revises: 0003
Create Date: 2026-10-28
"""

import sqlalchemy as sa
from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "telegram_link_codes",
        sa.Column("code_hash", sa.String(64), primary_key=True),
        sa.Column("schema_id", sa.String(64), nullable=False),
        sa.Column("schema_name", sa.Text, nullable=False),
        sa.Column("user_name", sa.String(200), nullable=False),
        sa.Column("utc_offset_minutes", sa.Integer, nullable=False),
        sa.Column("expires_at", sa.BigInteger, nullable=False),
    )
    op.create_table(
        "telegram_pair_codes",
        sa.Column("code_hash", sa.String(64), primary_key=True),
        sa.Column("chat_id", sa.String(32), nullable=False),
        sa.Column("title", sa.String(200)),
        sa.Column("expires_at", sa.BigInteger, nullable=False),
    )
    op.create_index("ix_telegram_pair_codes_chat", "telegram_pair_codes", ["chat_id"])
    op.create_table(
        "telegram_links",
        sa.Column("chat_id", sa.String(32), primary_key=True),
        sa.Column("schema_id", sa.String(64), nullable=False),
        sa.Column("schema_name", sa.Text, nullable=False),
        sa.Column("user_name", sa.String(200), nullable=False),
        sa.Column("utc_offset_minutes", sa.Integer, nullable=False),
        sa.Column("settings", sa.Text, nullable=False),
        sa.Column("linked_at", sa.BigInteger, nullable=False),
        sa.Column("access_warned_at", sa.BigInteger),
        sa.Column("title", sa.String(200)),
    )
    op.create_index("ix_telegram_links_schema", "telegram_links", ["schema_id"])
    op.create_table(
        "telegram_notifications",
        sa.Column("chat_id", sa.String(32), primary_key=True),
        sa.Column("anomaly_id", sa.String(512), primary_key=True),
        sa.Column("severity", sa.String(16), nullable=False),
        sa.Column("delivered", sa.Boolean, nullable=False),
        sa.Column("repeat", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("at", sa.BigInteger, nullable=False),
    )
    op.create_index("ix_telegram_notifications_at", "telegram_notifications", ["at"])


def downgrade() -> None:
    op.drop_index("ix_telegram_notifications_at", table_name="telegram_notifications")
    op.drop_table("telegram_notifications")
    op.drop_index("ix_telegram_links_schema", table_name="telegram_links")
    op.drop_table("telegram_links")
    op.drop_index("ix_telegram_pair_codes_chat", table_name="telegram_pair_codes")
    op.drop_table("telegram_pair_codes")
    op.drop_table("telegram_link_codes")
