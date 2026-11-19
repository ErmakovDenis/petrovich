"""Управляемый жизненный цикл: rename сохраняет личность, retire закрывает старую и освобождает логин."""

import asyncio
from datetime import timedelta

import pytest
from sqlalchemy import select

from fleet_service.identity.accounts import AccountConflict, AccountRegistry, main
from fleet_service.identity.principal import IdentityRejected
from fleet_service.identity.repository import IdentityRepository, digest
from fleet_service.store.db import (
    create_db_engine, identity_accounts as M, identity_account_events as E, identity_credentials as C, principals as P,
)
from fleet_service.store.migrate import downgrade, upgrade
from fleet_service.store.repository import AnomalyRepository

from .conftest import test_settings
from .test_identity_memory import enroll
from .test_identity_telegram import config, attach, status

URL = "http://fakes/autograph/ServiceJSON/"
PROVIDER = digest(URL.rstrip("/"))


@pytest.fixture
def store(tmp_path):
    db_url = f"sqlite:///{tmp_path / 'accounts.db'}"
    upgrade(db_url)
    repo = AnomalyRepository(create_db_engine(db_url), timedelta(minutes=1), timedelta(hours=1), timedelta(days=180))
    yield repo, db_url
    repo.dispose()


def test_lifecycle_must_be_explicitly_configured(make_client, fakes):
    with make_client(test_settings(identity_enabled=True)) as client:
        result = client.post("/v2/identity/bind", json={"userName": "Пётр", "password": "fake-password", "schemaId": "schema-1",
                                                        "utcOffsetMinutes": 300, "consent": True})
        assert result.status_code == 503
    assert fakes.state.calls["Login"] == 0


def test_rename_preserves_principal_and_revokes_old_credentials(store):
    repo, _ = store
    async def run():
        identity = IdentityRepository(repo)
        first = await identity.issue(PROVIDER, "Пётр", "schema-1", "session-1")
        registry = AccountRegistry(repo, URL)
        await registry.change("Пётр", new_login="Пётр Новый", apply=True)
        second = await identity.issue(PROVIDER, "Пётр Новый", "schema-1", "session-2")
        assert first.principal.principal_id == second.principal.principal_id
        with pytest.raises(IdentityRejected):
            await identity.authenticate(first.credential, "session-1", "schema-1", PROVIDER)
        # Старый логин после согласованного переименования может быть выдан другому человеку — это новая личность.
        reused = await identity.issue(PROVIDER, "Пётр", "schema-1", "session-3")
        assert reused.principal.principal_id != second.principal.principal_id
    asyncio.run(run())


def test_retire_then_recycled_login_never_opens_old_principal(store):
    repo, _ = store
    async def run():
        identity = IdentityRepository(repo)
        a = await identity.issue(PROVIDER, "Пётр", "schema-1", "session-a")
        await AccountRegistry(repo, URL).change("Пётр", apply=True)
        b = await identity.issue(PROVIDER, "Пётр", "schema-1", "session-b")
        assert a.principal.principal_id != b.principal.principal_id
        with pytest.raises(IdentityRejected):
            await identity.authenticate(a.credential, "session-a", "schema-1", PROVIDER)
        with repo._engine.connect() as conn:
            old = conn.execute(select(P).where(P.c.principal_id == a.principal.principal_id)).mappings().one()
            assert old["disabled_at"] is not None
            assert conn.execute(select(M.c.principal_id)).scalar() == b.principal.principal_id
            assert "Пётр" not in repr(conn.execute(select(E)).all())
    asyncio.run(run())


def test_dry_run_changes_nothing_and_rename_conflict_never_merges_principals(store):
    repo, _ = store
    async def run():
        identity = IdentityRepository(repo)
        a = await identity.issue(PROVIDER, "Пётр", "schema-1", "session-a")
        b = await identity.issue(PROVIDER, "Иван", "schema-1", "session-b")
        registry = AccountRegistry(repo, URL)
        await registry.change("Пётр", new_login="Свободный")
        await registry.change("Пётр")
        with pytest.raises(AccountConflict):
            await registry.change("Пётр", new_login="Иван", apply=True)
        assert await identity.authenticate(a.credential, "session-a", "schema-1", PROVIDER) == a.principal
        assert await identity.authenticate(b.credential, "session-b", "schema-1", PROVIDER) == b.principal
        with repo._engine.connect() as conn:
            assert conn.execute(select(E)).all() == []
    asyncio.run(run())


def test_rename_and_retire_revoke_personal_telegram_links(make_client):
    for renamed in (True, False):
        with make_client(config(), background_loop=False) as client:
            bound = enroll(client)
            attach(client, bound)
            registry = AccountRegistry(client.app.state.store, URL)
            client.portal.call(lambda: registry.change("Пётр", new_login="Новый" if renamed else None, apply=True))
            assert status(client).status_code == 401
        # Возвращаем имя в реестре для следующего сценария в той же временной БД.
        if renamed:
            with make_client(config(), background_loop=False) as client:
                registry = AccountRegistry(client.app.state.store, URL)
                client.portal.call(lambda: registry.change("Новый", new_login="Пётр", apply=True))


def test_upgrade_from_0009_and_roundtrip_preserve_active_principal(tmp_path):
    url = f"sqlite:///{tmp_path / 'legacy.db'}"
    upgrade(url, "0009")
    engine = create_db_engine(url)
    pid = "58de3817-1474-446d-a774-576b2399b35b"
    with engine.begin() as conn:
        conn.execute(P.insert().values(principal_id=pid, provider=PROVIDER, provider_subject=digest("Пётр"), created_at=1))
    upgrade(url)
    with engine.connect() as conn:
        assert conn.execute(select(M.c.principal_id)).scalar() == pid
    downgrade(url, "0009")
    with engine.connect() as conn:
        assert conn.execute(select(P.c.provider_subject)).scalar() == digest("Пётр")
    upgrade(url)
    repo = AnomalyRepository(engine, timedelta(minutes=1), timedelta(hours=1), timedelta(days=180))
    renewed = asyncio.run(IdentityRepository(repo).issue(PROVIDER, "Пётр", "schema-1", "new-session"))
    assert renewed.principal.principal_id == pid
    repo.dispose()


def test_cli_defaults_to_dry_run_and_does_not_print_login(store, capsys):
    repo, url = store
    original = asyncio.run(IdentityRepository(repo).issue(PROVIDER, "Пётр", "schema-1", "session"))
    args = ["--db-url", url, "--autograph-url", URL, "retire", "--login", "Пётр"]
    assert main(args) == 0
    assert asyncio.run(IdentityRepository(repo).authenticate(original.credential, "session", "schema-1", PROVIDER)) == original.principal
    assert main([*args, "--apply"]) == 0
    with pytest.raises(IdentityRejected):
        asyncio.run(IdentityRepository(repo).authenticate(original.credential, "session", "schema-1", PROVIDER))
    assert "Пётр" not in capsys.readouterr().out


def test_recycled_login_roundtrip_cannot_collide_with_retired_principal(store):
    repo, url = store
    async def prepare():
        identity = IdentityRepository(repo)
        old = await identity.issue(PROVIDER, "Пётр", "schema-1", "session-old")
        await AccountRegistry(repo, URL).change("Пётр", apply=True)
        active = await identity.issue(PROVIDER, "Пётр", "schema-1", "session-active")
        return old, active
    old, active = asyncio.run(prepare())
    downgrade(url, "0009")
    upgrade(url)
    renewed = asyncio.run(IdentityRepository(repo).issue(PROVIDER, "Пётр", "schema-1", "session-renewed"))
    assert renewed.principal.principal_id == active.principal.principal_id != old.principal.principal_id
    with pytest.raises(IdentityRejected):
        asyncio.run(IdentityRepository(repo).authenticate(active.credential, "session-active", "schema-1", PROVIDER))
