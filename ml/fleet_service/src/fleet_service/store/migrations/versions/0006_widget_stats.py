"""Daily widget aggregates.

Revision ID: 0006
Revises: 0005
"""

import sqlalchemy as sa
from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "widget_daily_stats",
        sa.Column("schema_id", sa.String(64), primary_key=True),
        sa.Column("vehicle_id", sa.String(128), primary_key=True),
        sa.Column("day", sa.String(10), primary_key=True),
        sa.Column("vehicle_name", sa.Text, nullable=False),
        sa.Column("vehicle_group", sa.Text),
        sa.Column("fuel", sa.Float, nullable=False),
        sa.Column("idle", sa.Float, nullable=False),
        sa.Column("mileage", sa.Float, nullable=False),
        sa.Column("refuels", sa.Integer, nullable=False),
        sa.Column("last_seen", sa.String(19)),
    )
    op.create_index("ix_widget_daily_stats_schema_day", "widget_daily_stats", ["schema_id", "day"])


def downgrade() -> None:
    op.drop_index("ix_widget_daily_stats_schema_day", table_name="widget_daily_stats")
    op.drop_table("widget_daily_stats")