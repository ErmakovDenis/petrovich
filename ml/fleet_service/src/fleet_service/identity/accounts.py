"""Административный реестр личности: переименование сохраняет UUID, повторная выдача логина — новый UUID.

Запускается оператором локально с явным --db-url; без --apply только проверяет возможность операции.
Пароль AutoGRAPH не нужен. Нельзя передавать эти операции модели или обычным клиентам API.
"""

import argparse
import asyncio
from datetime import timedelta
from uuid import uuid4

from sqlalchemy import Connection, delete, insert, select, update

from ..store.db import create_db_engine, identity_accounts as M, identity_account_events as E
from ..store.db import identity_credentials as C, identity_telegram_links as L, principal_scopes as S, principals as P
from ..store.repository import AnomalyRepository, now_ms
from .repository import digest


class AccountConflict(Exception):
    pass


class AccountRegistry:
    def __init__(self, store: AnomalyRepository, provider_url: str):
        self._store = store
        self._provider = digest(provider_url.rstrip("/"))

    @staticmethod
    def _login(value: str) -> str:
        if not value or value != value.strip() or len(value) > 200 or any(ord(c) < 32 for c in value):
            raise AccountConflict("Нужен точный непустой логин без крайних пробелов и управляющих символов")
        return digest(value)

    async def change(self, login: str, *, new_login: str | None = None, apply: bool = False) -> None:
        old_hash = self._login(login)
        new_hash = self._login(new_login) if new_login is not None else None
        if new_hash == old_hash:
            raise AccountConflict("Новый логин совпадает с прежним")
        at = now_ms()
        def work(conn: Connection) -> None:
            if apply:
                conn.execute(update(M).where(M.c.provider == self._provider, M.c.login_hash == old_hash)
                             .values(updated_at=M.c.updated_at))
            row = conn.execute(select(M).where(M.c.provider == self._provider, M.c.login_hash == old_hash)).mappings().first()
            if row is None:
                raise AccountConflict("Аккаунт не найден в реестре этого провайдера")
            if new_hash is not None and conn.execute(select(M).where(
                M.c.provider == self._provider, M.c.login_hash == new_hash,
            )).first() is not None:
                raise AccountConflict("Новый логин уже занят в реестре; объединение личностей запрещено")
            if not apply:
                return
            pid = row["principal_id"]
            conn.execute(delete(L).where(L.c.principal_id == pid))
            conn.execute(update(C).where(C.c.principal_id == pid, C.c.revoked_at.is_(None)).values(revoked_at=at))
            if new_hash is None:
                conn.execute(update(P).where(P.c.principal_id == pid).values(disabled_at=at))
                conn.execute(update(S).where(S.c.principal_id == pid).values(revoked_at=at))
                conn.execute(delete(M).where(M.c.provider == self._provider, M.c.login_hash == old_hash))
            else:
                conn.execute(update(M).where(M.c.provider == self._provider, M.c.login_hash == old_hash)
                             .values(login_hash=new_hash, updated_at=at))
            conn.execute(insert(E).values(id=str(uuid4()), principal_id=pid, action="retire" if new_hash is None else "rename",
                                          previous_login_hash=old_hash, next_login_hash=new_hash, at=at))
        await self._store.run(work, write=apply)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Реестр личности Петровича: безопасные rename/retire")
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--autograph-url", required=True)
    commands = parser.add_subparsers(dest="action", required=True)
    for command in ("rename", "retire"):
        sub = commands.add_parser(command)
        sub.add_argument("--login", required=True)
        if command == "rename":
            sub.add_argument("--new-login", required=True)
        sub.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    store = AnomalyRepository(create_db_engine(args.db_url), timedelta(minutes=1), timedelta(hours=1), timedelta(days=180))
    try:
        asyncio.run(AccountRegistry(store, args.autograph_url).change(
            args.login, new_login=getattr(args, "new_login", None), apply=args.apply,
        ))
    except AccountConflict as error:
        print(str(error))
        return 1
    finally:
        store.dispose()
    print("Операция выполнена; прежние credentials отозваны" if args.apply else "Проверка пройдена; изменений нет (для записи нужен --apply)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
