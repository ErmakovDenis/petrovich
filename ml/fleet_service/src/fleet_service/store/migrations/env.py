"""Окружение Alembic: адрес базы — из конфигурации, которую строит store/migrate.py (FS_DB_URL)."""

from alembic import context

from fleet_service.store.db import create_db_engine

config = context.config


def run_migrations_online() -> None:
    engine = create_db_engine(config.get_main_option("sqlalchemy.url"))
    try:
        with engine.connect() as connection:
            # render_as_batch: SQLite не умеет ALTER COLUMN — Alembic пересоздаёт таблицу.
            context.configure(connection=connection, render_as_batch=True)
            with context.begin_transaction():
                context.run_migrations()
    finally:
        engine.dispose()


def run_migrations_offline() -> None:
    context.configure(url=config.get_main_option("sqlalchemy.url"), literal_binds=True, render_as_batch=True)
    with context.begin_transaction():
        context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
