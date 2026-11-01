"""Telegram-бот: когда чату последний раз ушли утренний доклад и недельная сводка.

Revision ID: 0005
Revises: 0004
Create Date: 2026-10-28
"""

import sqlalchemy as sa
from alembic import op

revision = "0005"
down_revision = "0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("telegram_links", sa.Column("daily_sent_on", sa.String(10)))
    op.add_column("telegram_links", sa.Column("weekly_sent_on", sa.String(10)))


def downgrade() -> None:
    with op.batch_alter_table("telegram_links") as batch:
        batch.drop_column("weekly_sent_on")
        batch.drop_column("daily_sent_on")
