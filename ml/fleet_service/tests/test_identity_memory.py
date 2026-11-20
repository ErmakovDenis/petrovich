"""P0 памяти: подтверждённый аккаунт, границы доступа, отзыв и миграции без внешних сервисов."""

import asyncio
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from urllib.parse import quote

import pytest
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy import inspect, select, update

from fleet_service.autograph.errors import AutoGraphUnavailable
from fleet_service.identity.principal import IdentityRejected
from fleet_service.identity.repository import CREDENTIAL_TTL_MS, IdentityRepository
from fleet_service.identity.service import IdentityService
from fleet_service.store.db import (
    create_db_engine, identity_credentials as C, metadata, principal_scopes as S, principals as P,
)
from fleet_service.store.migrate import downgrade, upgrade
from fleet_service.store.repository import AnomalyRepository

from .conftest import AUTH, FakeLLM, ask, test_settings, text
from .fakes import LOGIN_PASSWORD, OTHER_TOKEN, SCHEMA_ID


def bind(client, name="Пётр", **overrides):
    return client.post("/v2/identity/bind", json={
        "userName": name, "password": LOGIN_PASSWORD, "schemaId": SCHEMA_ID,
        "utcOffsetMinutes": 300, "consent": True, **overrides,
    })


def enroll(client, name="Пётр"):
    result = bind(client, name)
    assert result.status_code == 200, result.text
    assert result.headers["cache-control"] == "no-store"
    return result.json()


def headers(bound):
    return {
        "Authorization": "Bearer " + bound["autographSession"],
        "X-Schema-Id": bound["tenantId"], "X-Identity-Credential": bound["credential"],
    }


def enabled(**overrides):
    return test_settings(identity_enabled=True, identity_account_lifecycle="managed", **overrides)


def test_identity_disabled_and_old_chat_compatible(make_client, fakes):
    with make_client(llm=FakeLLM(text("Готово"))) as client:
        assert bind(client).status_code == 503
        assert client.get("/v2/identity/me", headers=AUTH).status_code == 503
        result = client.post("/v1/chat", headers=AUTH, json=ask())
        assert result.status_code == 200, result.text
    assert fakes.state.calls["Login"] == 0


def test_principal_survives_login_rotation_and_restart(make_client):
    with make_client(enabled()) as client:
        first = enroll(client)
        second = enroll(client)
        assert first["principalId"] == second["principalId"]
        assert first["credential"] != second["credential"]
        assert first["autographSession"] != second["autographSession"]
        assert client.get("/v2/identity/me", headers=headers(first)).status_code == 200
    with make_client(enabled()) as client:
        assert client.get("/v2/identity/me", headers=headers(first)).json()["principalId"] == first["principalId"]
        assert enroll(client)["principalId"] == first["principalId"]


def test_users_headers_sessions_and_tenants_are_isolated(make_client):
    with make_client(enabled()) as client:
        a, b = enroll(client), enroll(client, "Иван")
        assert a["principalId"] != b["principalId"]
        spoof = {**headers(a), "X-User-Name": quote("Иван")}
        assert client.get("/v2/identity/me", headers=spoof).json()["principalId"] == a["principalId"]
        attacks = [
            {**headers(a), "Authorization": headers(b)["Authorization"]},
            {**headers(a), "Authorization": "Bearer " + OTHER_TOKEN},
            {**headers(a), "X-Identity-Credential": b["credential"]},
            {**headers(a), "X-Schema-Id": "schema-2"},
            {**AUTH, "X-User-Name": quote("Пётр"), "X-Identity-Credential": "pvi_forged"},
        ]
        for attack in attacks:
            result = client.get("/v2/identity/me", headers=attack)
            assert result.status_code == 401, result.text
            assert a["principalId"] not in result.text and b["principalId"] not in result.text


@pytest.mark.parametrize("overrides,code", [
    ({"password": "wrong"}, 401), ({"schemaId": "schema-2"}, 403),
    ({"consent": False}, 422), ({"consent": 1}, 422), ({"consent": None}, 422),
    ({"password": ""}, 422), ({"userName": " Пётр"}, 422),
    ({"schemaId": " "}, 422), ({"principalId": "someone-else"}, 422),
])
def test_bind_rejects_unverified_input(make_client, overrides, code):
    with make_client(enabled()) as client:
        result = bind(client, **overrides)
        assert result.status_code == code, result.text
        with client.app.state.store._engine.connect() as conn:
            assert conn.execute(select(P)).all() == []


def test_revocation_is_durable_and_only_revokes_presented_credential(make_client):
    with make_client(enabled()) as client:
        a, b = enroll(client), enroll(client)
        assert client.post("/v2/identity/revoke", headers=headers(a)).status_code == 204
        assert client.get("/v2/identity/me", headers=headers(a)).status_code == 401
        assert client.get("/v2/identity/me", headers=headers(b)).status_code == 200
    with make_client(enabled()) as client:
        assert client.get("/v2/identity/me", headers=headers(a)).status_code == 401
        assert client.get("/v2/identity/me", headers=headers(b)).status_code == 200


def test_schema_access_and_session_are_checked_without_positive_cache(make_client, fakes):
    with make_client(enabled(auth_cache_ttl_seconds=3600)) as client:
        a = enroll(client)
        assert client.get("/v2/identity/me", headers=headers(a)).status_code == 200
        fakes.state.expired.add(a["autographSession"])
        result = client.get("/v2/identity/me", headers=headers(a))
        assert result.status_code == 401
        assert a["credential"] not in result.text


def test_removed_schema_denies_existing_credential(make_client):
    with make_client(enabled()) as client:
        a = enroll(client)
        async def no_schemas(session):
            return set()
        client.app.state.autograph_client.schema_ids = no_schemas
        assert client.get("/v2/identity/me", headers=headers(a)).status_code == 403


def test_autograph_outage_does_not_allow_cached_identity(make_client):
    with make_client(enabled()) as client:
        a = enroll(client)
        async def unavailable(session):
            raise AutoGraphUnavailable()
        client.app.state.autograph_client.schema_ids = unavailable
        result = client.get("/v2/identity/me", headers=headers(a))
        assert result.status_code == 503


def test_telegram_link_is_not_identity(make_client):
    with make_client(enabled()) as client:
        a = enroll(client)
        h = {**headers(a), "X-Telegram-Chat-Id": "123"}
        assert client.get("/v2/identity/me", headers=h).status_code == 403
        result = client.post("/v2/identity/bind", headers=h, json={
            "userName": "Пётр", "password": LOGIN_PASSWORD, "schemaId": SCHEMA_ID,
            "utcOffsetMinutes": 300, "consent": True,
        })
        assert result.status_code == 403


def test_rate_limit_applies_before_login_and_cannot_be_reset_by_username(make_client, fakes):
    with make_client(enabled()) as client:
        for index in range(5):
            assert bind(client, name=f"unknown-{index}").status_code == 401
        result = bind(client)
        assert result.status_code == 429 and "retry-after" in result.headers
    assert fakes.state.calls["Login"] == 5


def test_secrets_not_in_logs_errors_or_database(make_client, caplog):
    with make_client(enabled()) as client:
        a = enroll(client)
        with client.app.state.store._engine.connect() as conn:
            rows = [dict(r) for table in (P, S, C) for r in conn.execute(select(table)).mappings()]
        assert bind(client, password="private-invalid-password").status_code == 401
        invalid = bind(client, password=["private-invalid-password"])
        assert invalid.status_code == 422 and "private-invalid-password" not in invalid.text
        from fleet_service.log_masking import mask_secrets
        assert a["credential"] not in mask_secrets(f"credential={a['credential']}")
    for secret in (a["credential"], a["autographSession"], LOGIN_PASSWORD, "private-invalid-password"):
        assert secret not in str(rows)
        assert secret not in caplog.text


@pytest.fixture
def identity_store(tmp_path):
    url = f"sqlite:///{tmp_path / 'identity.db'}"
    upgrade(url)
    store = AnomalyRepository(create_db_engine(url), timedelta(minutes=1), timedelta(hours=1), timedelta(days=180))
    yield store
    store.dispose()


def test_expiry_disabled_principal_and_revoked_scope(identity_store):
    clock = [1000]
    repo = IdentityRepository(identity_store, clock=lambda: clock[0])
    async def run():
        a = await repo.issue("provider", "Пётр", "schema", "session")
        assert await repo.authenticate(a.credential, "session", "schema", "provider") == a.principal
        clock[0] += CREDENTIAL_TTL_MS
        with pytest.raises(IdentityRejected):
            await repo.authenticate(a.credential, "session", "schema", "provider")
        b = await repo.issue("provider", "Пётр", "schema", "session")
        await identity_store.run(lambda conn: conn.execute(update(S).values(revoked_at=clock[0])), write=True)
        with pytest.raises(IdentityRejected):
            await repo.authenticate(b.credential, "session", "schema", "provider")
        with pytest.raises(IdentityRejected):
            await repo.issue("provider", "Пётр", "schema", "session")
        await identity_store.run(lambda conn: conn.execute(update(S).values(revoked_at=None)), write=True)
        await identity_store.run(lambda conn: conn.execute(update(P).values(disabled_at=clock[0])), write=True)
        with pytest.raises(IdentityRejected):
            await repo.authenticate(b.credential, "session", "schema", "provider")
        with pytest.raises(IdentityRejected):
            await repo.issue("provider", "Пётр", "schema", "session")
    asyncio.run(run())


def test_provider_and_exact_account_boundaries(identity_store):
    async def run():
        repo = IdentityRepository(identity_store)
        a = await repo.issue("provider-A", "User", "schema", "session")
        b = await repo.issue("provider-B", "User", "schema", "session")
        c = await repo.issue("provider-A", "user", "schema", "session")
        assert len({i.principal.principal_id for i in (a, b, c)}) == 3
        with pytest.raises(IdentityRejected):
            await repo.authenticate(a.credential, "session", "schema", "provider-B")
    asyncio.run(run())


def test_concurrent_enrollment_has_one_principal(identity_store):
    # Разные экземпляры репозитория и разные потоки: не полагаемся лишь на asyncio.Lock одного процесса.
    engine = identity_store._engine
    def enroll_one(index):
        store = AnomalyRepository(engine, timedelta(minutes=1), timedelta(hours=1), timedelta(days=180))
        return asyncio.run(IdentityRepository(store).issue("provider", "Пётр", "schema", f"session-{index}"))
    with ThreadPoolExecutor(max_workers=4) as pool:
        issued = list(pool.map(enroll_one, range(8)))
    assert len({i.principal.principal_id for i in issued}) == 1
    assert len({i.credential for i in issued}) == 8


def test_identity_migration_preserves_existing_data_and_rolls_back(tmp_path):
    url = f"sqlite:///{tmp_path / 'migration.db'}"
    upgrade(url, "0008")
    engine = create_db_engine(url)
    with engine.begin() as conn:
        conn.exec_driver_sql("INSERT INTO scan_marks (schema_id, last_scan_at) VALUES ('schema', 123)")
    upgrade(url)
    with engine.connect() as conn:
        assert compare_metadata(MigrationContext.configure(conn), metadata) == []
        assert conn.exec_driver_sql("SELECT last_scan_at FROM scan_marks").scalar() == 123
    downgrade(url, "0008")
    assert "principals" not in inspect(engine).get_table_names()
    with engine.connect() as conn:
        assert conn.exec_driver_sql("SELECT last_scan_at FROM scan_marks").scalar() == 123
    upgrade(url)
    with engine.connect() as conn:
        assert compare_metadata(MigrationContext.configure(conn), metadata) == []
    engine.dispose()


def test_missing_credential_is_unauthorized(make_client):
    with make_client(enabled()) as client:
        assert client.get("/v2/identity/me", headers=AUTH).status_code == 401


def test_revocation_during_schema_check_is_not_accepted(identity_store):
    repo = IdentityRepository(identity_store)
    class Autograph:
        async def schema_ids(self, session):
            await repo.revoke(bound.credential)
            return {"schema"}
    service = IdentityService(repo, Autograph(), "http://fake-autograph")
    async def run():
        nonlocal bound
        from fleet_service.identity.repository import digest
        bound = await repo.issue(digest("http://fake-autograph"), "Пётр", "schema", "session")
        with pytest.raises(IdentityRejected):
            await service.authenticate(bound.credential, "session", "schema")
    bound = None
    asyncio.run(run())


def test_revocation_works_after_autograph_session_expiry_and_is_idempotent(make_client, fakes):
    with make_client(enabled()) as client:
        a = enroll(client)
        fakes.state.expired.add(a["autographSession"])
        before = fakes.state.calls["EnumSchemas"]
        assert client.post("/v2/identity/revoke", headers=headers(a)).status_code == 204
        assert client.post("/v2/identity/revoke", headers=headers(a)).status_code == 204
        assert fakes.state.calls["EnumSchemas"] == before
        with client.app.state.store._engine.connect() as conn:
            assert conn.execute(select(C.c.revoked_at)).scalar() is not None


def test_revocation_cannot_mix_credentials_sessions_or_scopes(make_client):
    with make_client(enabled()) as client:
        a, b = enroll(client), enroll(client, "Иван")
        for h in [
            {**headers(a), "Authorization": headers(b)["Authorization"]},
            {**headers(a), "X-Schema-Id": "schema-2"},
            {**headers(a), "X-Identity-Credential": "pvi_unknown"},
        ]:
            assert client.post("/v2/identity/revoke", headers=h).status_code == 204
            assert client.get("/v2/identity/me", headers=headers(a)).status_code == 200
        assert client.post("/v2/identity/revoke", headers={**headers(a), "X-Telegram-Chat-Id": "123"}).status_code == 403


def test_android_identity_contract_uses_shared_fixtures(make_client):
    import json
    from pathlib import Path
    fixtures = Path(__file__).resolve().parents[3] / "testdata" / "contract"
    body = json.loads((fixtures / "identity-bind-request.json").read_text())
    expected = json.loads((fixtures / "identity-bind-response.json").read_text())
    with make_client(enabled()) as client:
        result = client.post("/v2/identity/bind", json=body)
        assert result.status_code == 200, result.text
        actual = result.json()
        assert actual.keys() == expected.keys()
        assert actual["tenantId"] == expected["tenantId"]
        assert client.get("/v2/identity/me", headers=headers(actual)).json() == {
            "principalId": actual["principalId"], "tenantId": actual["tenantId"],
        }
