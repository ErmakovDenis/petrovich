"""Состояние ленты Petrovich AI по схеме: источник ответа и время последнего успешного API.

Revision ID: 0007
Revises: 0006
"""

import sqlalchemy as sa
from alembic import op

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "ai_feed_state",
        sa.Column("schema_id", sa.String(64), primary_key=True),
        # api | api_cache | rules | unavailable
        sa.Column("feed_source", sa.String(32), nullable=False),
        # Когда лента API последний раз успешно ответила, epoch millis; NULL — ещё не было.
        sa.Column("api_updated_at", sa.BigInteger),
        # Последнее предпочтение клиента: при сбое API уходить в правила.
        sa.Column("fallback_rules", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("updated_at", sa.BigInteger, nullable=False),
    )


def downgrade() -> None:
    op.drop_table("ai_feed_state")
