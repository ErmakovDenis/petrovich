"""Подтверждённая личность и отзывные credentials для P0 памяти.

Revision ID: 0009
Revises: 0008
"""

import sqlalchemy as sa
from alembic import op

revision = "0009"
down_revision = "0008"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "principals",
        sa.Column("principal_id", sa.String(36), primary_key=True),
        sa.Column("provider", sa.String(64), nullable=False),
        sa.Column("provider_subject", sa.String(64), nullable=False),
        sa.Column("created_at", sa.BigInteger, nullable=False),
        sa.Column("disabled_at", sa.BigInteger),
        sa.UniqueConstraint("provider", "provider_subject", name="uq_principals_provider_subject"),
    )
    op.create_table(
        "principal_scopes",
        sa.Column("principal_id", sa.String(36), sa.ForeignKey("principals.principal_id"), primary_key=True),
        sa.Column("tenant_id", sa.String(64), primary_key=True),
        sa.Column("linked_at", sa.BigInteger, nullable=False),
        sa.Column("revoked_at", sa.BigInteger),
    )
    op.create_table(
        "identity_credentials",
        sa.Column("credential_hash", sa.String(64), primary_key=True),
        sa.Column("principal_id", sa.String(36), sa.ForeignKey("principals.principal_id"), nullable=False),
        sa.Column("tenant_id", sa.String(64), nullable=False),
        sa.Column("session_hash", sa.String(64), nullable=False),
        sa.Column("created_at", sa.BigInteger, nullable=False),
        sa.Column("expires_at", sa.BigInteger, nullable=False),
        sa.Column("revoked_at", sa.BigInteger),
    )
    op.create_index("ix_identity_credentials_principal", "identity_credentials", ["principal_id"])


def downgrade() -> None:
    op.drop_table("identity_credentials")
    op.drop_table("principal_scopes")
    op.drop_table("principals")
