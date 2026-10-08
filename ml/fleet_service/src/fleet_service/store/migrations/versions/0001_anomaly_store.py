"""Хранилище аномалий и решений: anomalies, decisions, scan_marks.

Revision ID: 0001
Revises:
Create Date: 2026-10-08
"""

import sqlalchemy as sa
from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "anomalies",
        sa.Column("schema_id", sa.String(64), primary_key=True),
        sa.Column("id", sa.String(512), primary_key=True),
        sa.Column("vehicle_id", sa.String(128), nullable=False),
        sa.Column("vehicle_name", sa.Text, nullable=False),
        sa.Column("kind", sa.String(64), nullable=False),
        sa.Column("category", sa.String(16), nullable=False),
        sa.Column("parameter_name", sa.String(128), nullable=False),
        sa.Column("parameter_caption", sa.Text, nullable=False),
        sa.Column("source", sa.Text, nullable=False),
        sa.Column("severity", sa.String(16), nullable=False),
        sa.Column("title", sa.Text, nullable=False),
        sa.Column("description", sa.Text, nullable=False),
        sa.Column("value", sa.Float),
        sa.Column("score", sa.Float),
        sa.Column("start_utc", sa.DateTime, nullable=False),
        sa.Column("end_utc", sa.DateTime, nullable=False),
        sa.Column("first_detected_at", sa.BigInteger, nullable=False),
        sa.Column("last_detected_at", sa.BigInteger, nullable=False),
        sa.Column("resolution", sa.String(16)),
        sa.Column("false_alarm_reason", sa.Text),
        sa.Column("resolved_by", sa.String(200)),
        sa.Column("resolved_at", sa.BigInteger),
    )
    op.create_index("ix_anomalies_schema_start", "anomalies", ["schema_id", "start_utc"])
    op.create_index(
        "ix_anomalies_episode", "anomalies", ["schema_id", "vehicle_id", "kind", "parameter_name", "start_utc"]
    )
    op.create_table(
        "decisions",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column("schema_id", sa.String(64), nullable=False),
        sa.Column("anomaly_id", sa.String(512), nullable=False),
        sa.Column("resolution", sa.String(16)),
        sa.Column("reason", sa.Text),
        sa.Column("user_name", sa.String(200)),
        sa.Column("decided_at", sa.BigInteger, nullable=False),
        sa.Column("origin", sa.String(16), nullable=False),
    )
    op.create_index("ix_decisions_anomaly", "decisions", ["schema_id", "anomaly_id"])
    op.create_table(
        "scan_marks",
        sa.Column("schema_id", sa.String(64), primary_key=True),
        sa.Column("last_scan_at", sa.BigInteger, nullable=False),
    )


def downgrade() -> None:
    op.drop_table("scan_marks")
    op.drop_index("ix_decisions_anomaly", table_name="decisions")
    op.drop_table("decisions")
    op.drop_index("ix_anomalies_episode", table_name="anomalies")
    op.drop_index("ix_anomalies_schema_start", table_name="anomalies")
    op.drop_table("anomalies")
