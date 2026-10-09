"""Фоновая проверка: доступ стенда к AutoGRAPH по устройствам и отметки показанных уведомлений.

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-09
"""

import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "background_access",
        sa.Column("device_id", sa.String(64), primary_key=True),
        sa.Column("schema_id", sa.String(64), nullable=False),
        sa.Column("user_name", sa.String(200)),
        sa.Column("utc_offset_minutes", sa.Integer, nullable=False),
        sa.Column("token_enc", sa.Text),
        sa.Column("token_since", sa.BigInteger),
        sa.Column("password_enc", sa.Text),
        sa.Column("registered_at", sa.BigInteger, nullable=False),
        sa.Column("refreshed_at", sa.BigInteger, nullable=False),
        sa.Column("last_ok_at", sa.BigInteger),
        sa.Column("last_error", sa.Text),
    )
    op.create_index("ix_background_access_schema", "background_access", ["schema_id"])
    op.create_table(
        "notification_claims",
        sa.Column("schema_id", sa.String(64), primary_key=True),
        sa.Column("user_key", sa.String(200), primary_key=True),
        sa.Column("anomaly_id", sa.String(512), primary_key=True),
        sa.Column("device_id", sa.String(64), nullable=False),
        sa.Column("claimed_at", sa.BigInteger, nullable=False),
    )
    op.create_index("ix_notification_claims_at", "notification_claims", ["claimed_at"])


def downgrade() -> None:
    op.drop_index("ix_notification_claims_at", table_name="notification_claims")
    op.drop_table("notification_claims")
    op.drop_index("ix_background_access_schema", table_name="background_access")
    op.drop_table("background_access")
