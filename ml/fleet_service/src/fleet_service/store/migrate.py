"""Миграции схемы базы (Alembic). Стенд применяет их при старте (FS_DB_AUTO_MIGRATE=true) или вручную:

    python -m fleet_service.store.migrate                      # до последней версии
    python -m fleet_service.store.migrate downgrade <версия>   # откат (base — до пустой базы)

Новая миграция — файл в store/migrations/versions/ (см. существующие); store/db.py обновляется вместе с ней.
"""

import sys
from pathlib import Path

from alembic import command
from alembic.config import Config

from ..config import get_settings

MIGRATIONS = Path(__file__).resolve().parent / "migrations"


def alembic_config(db_url: str) -> Config:
    config = Config()
    config.set_main_option("script_location", str(MIGRATIONS))
    # configparser: «%» в адресе (пароль в URL-кодировке) нужно удвоить.
    config.set_main_option("sqlalchemy.url", db_url.replace("%", "%%"))
    return config


def upgrade(db_url: str, revision: str = "head") -> None:
    command.upgrade(alembic_config(db_url), revision)


def downgrade(db_url: str, revision: str) -> None:
    command.downgrade(alembic_config(db_url), revision)


def main(argv: list[str]) -> None:
    from .db import create_db_engine

    url = get_settings().db_url
    create_db_engine(url).dispose()  # создаёт каталог SQLite-файла
    if argv[:1] == ["downgrade"] and len(argv) == 2:
        downgrade(url, argv[1])
    elif argv[:1] in ([], ["upgrade"]):
        upgrade(url, argv[1] if len(argv) > 1 else "head")
    else:
        sys.exit("использование: python -m fleet_service.store.migrate [upgrade [версия] | downgrade <версия>]")


if __name__ == "__main__":
    main(sys.argv[1:])
