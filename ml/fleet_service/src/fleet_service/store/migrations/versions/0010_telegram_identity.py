"""Персональная привязка Telegram через проверенную личность приложения.

Revision ID: 0010
Revises: 0009
"""

import sqlalchemy as sa
from alembic import op

revision = "0010"
down_revision = "0009"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "identity_accounts",
        sa.Column("provider", sa.String(64), primary_key=True),
        sa.Column("login_hash", sa.String(64), primary_key=True),
        sa.Column("principal_id", sa.String(36), sa.ForeignKey("principals.principal_id"), nullable=False, unique=True),
        sa.Column("updated_at", sa.BigInteger, nullable=False),
    )
    op.create_table(
        "identity_account_events",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("principal_id", sa.String(36), sa.ForeignKey("principals.principal_id"), nullable=False),
        sa.Column("action", sa.String(16), nullable=False),
        sa.Column("previous_login_hash", sa.String(64), nullable=False),
        sa.Column("next_login_hash", sa.String(64)),
        sa.Column("at", sa.BigInteger, nullable=False),
    )
    # В 0009 provider_subject был хэшем точного логина; сохраняем существующие UUID при переходе.
    op.execute(sa.text("INSERT INTO identity_accounts (provider, login_hash, principal_id, updated_at) "
                       "SELECT provider, provider_subject, principal_id, created_at FROM principals"))
    op.create_table(
        "identity_telegram_codes",
        sa.Column("code_hash", sa.String(64), primary_key=True),
        sa.Column("chat_id", sa.String(32), nullable=False, unique=True),
        sa.Column("telegram_user_id", sa.String(32), nullable=False),
        sa.Column("expires_at", sa.BigInteger, nullable=False),
    )
    op.create_table(
        "identity_telegram_links",
        sa.Column("chat_id", sa.String(32), primary_key=True),
        sa.Column("telegram_user_id", sa.String(32), nullable=False),
        sa.Column("principal_id", sa.String(36), sa.ForeignKey("principals.principal_id"), nullable=False),
        sa.Column("tenant_id", sa.String(64), nullable=False),
        sa.Column("credential_hash", sa.String(64), sa.ForeignKey("identity_credentials.credential_hash"), nullable=False),
        sa.Column("session_ciphertext", sa.Text, nullable=False),
        sa.Column("linked_at", sa.BigInteger, nullable=False),
        sa.Column("expires_at", sa.BigInteger, nullable=False),
    )
    op.create_index("ix_identity_telegram_owner", "identity_telegram_links", ["principal_id", "tenant_id"])


def downgrade() -> None:
    op.drop_table("identity_telegram_links")
    op.drop_table("identity_telegram_codes")
    op.drop_table("identity_account_events")
    # Откат возвращает совместимые ключи 0009 и отзывает выданные credentials; UUID активных аккаунтов сохраняются.
    op.execute(sa.text("UPDATE identity_credentials SET revoked_at = CAST(strftime('%s','now') AS INTEGER) * 1000 "
                       "WHERE revoked_at IS NULL"))
    op.execute(sa.text("UPDATE principals SET provider_subject = 'retired:' || principal_id"))
    op.execute(sa.text("UPDATE principals SET provider_subject = (SELECT login_hash FROM identity_accounts "
                       "WHERE identity_accounts.principal_id = principals.principal_id) WHERE EXISTS "
                       "(SELECT 1 FROM identity_accounts WHERE identity_accounts.principal_id = principals.principal_id)"))
    op.drop_table("identity_accounts")
