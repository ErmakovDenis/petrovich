"""P2: настоящий tool loop с FakeLLM, персональная изоляция и свежие права без внешних сервисов."""

import asyncio
import json
import logging
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace

import pytest
from sqlalchemy import insert, select
from sqlalchemy.exc import IntegrityError

from fleet_service.agent.tools import ToolContext
from fleet_service.autograph.errors import AutoGraphUnavailable
from fleet_service.main import build_tools
from fleet_service.memory.aliases import ALIASES, alias_intent
from fleet_service.memory.repository import MemoryConflict, MemoryRepository
from fleet_service.memory.resolver import VehicleAmbiguous, VehicleResolver
from fleet_service.schemas.contract import Vehicle
from fleet_service.store.db import (
    conversation_messages as M,
    conversation_summaries as S,
    memory_alias_operations as AO,
    memory_audit as A,
    memory_candidates as K,
    memory_outbox as O,
    user_entity_aliases as E,
)
from fleet_service.store.migrate import downgrade, upgrade

from .conftest import AUTH, FakeLLM, ask, test_settings, text, tool_call
from .test_identity_memory import enroll, headers
from .test_memory_storage import open_store


def settings(**overrides):
    return test_settings(
        identity_enabled=True, identity_account_lifecycle="managed", memory_aliases_enabled=True, **overrides
    )


def context(client, bound, message=""):
    identity = client.app.state.identity

    async def check():
        return await identity.authenticate(bound["credential"], bound["autographSession"], bound["tenantId"])

    principal = client.portal.call(check)
    return ToolContext(
        bound["autographSession"],
        bound["tenantId"],
        300,
        principal=principal,
        check_principal=check,
        features=frozenset({ALIASES}),
        alias_intent=alias_intent(message),
    )


def registry(client):
    state = client.app.state
    return build_tools(state.settings, state.telemetry_service, state.check_service, state.store)


def execute(client, tools, name, args, ctx):
    return json.loads(client.portal.call(tools.execute, name, json.dumps(args), ctx))


def save_args(alias="Рыжий", vehicle="veh-1", operation="save-1"):
    return {"alias": alias, "vehicle_reference": vehicle, "client_operation_id": operation}


def tool_result(llm, call_index):
    return json.loads(next(m["content"] for m in reversed(llm.calls[call_index][0]) if m["role"] == "tool"))


def test_fake_llm_saves_uses_and_deletes_alias_after_commit(make_client):
    llm = FakeLLM(
        tool_call("s", "set_vehicle_alias", json.dumps(save_args())),
        text("Запомнил"),
        tool_call("r", "get_vehicle_summary", json.dumps({"vehicle_id": "Рыжий"})),
        text("Сводка"),
        tool_call(
            "d", "delete_vehicle_alias", json.dumps({"alias": "Рыжий", "client_operation_id": "del-1"})
        ),
        text("Забыл"),
        tool_call("r2", "get_vehicle_summary", json.dumps({"vehicle_id": "Рыжий"})),
        text("Уточните машину"),
    )
    with make_client(settings(chat_burst=10), llm=llm) as client:
        bound = enroll(client)
        repo = MemoryRepository(client.app.state.store)
        ctx = context(client, bound)
        for message in ("Запомни: Рыжий — veh-1", "Покажи Рыжий", "Забудь прозвище Рыжий", "Покажи Рыжий"):
            response = client.post("/v1/chat", json=ask(message), headers=headers(bound))
            assert response.status_code == 200, response.text
            assert response.headers["cache-control"] == "no-store"
            if message.startswith("Запомни"):
                assert client.portal.call(repo.find_alias, ctx.principal, "Рыжий")["entity_id"] == "veh-1"
        assert tool_result(llm, 1)["status"] == "saved"
        assert tool_result(llm, 3)["vehicle"]["id"] == "veh-1"
        assert tool_result(llm, 5)["status"] == "deleted"
        assert "error" in tool_result(llm, 7)
        assert client.portal.call(repo.find_alias, ctx.principal, "Рыжий") is None


def test_all_vehicle_tools_share_resolver_and_list_aliases(make_client):
    with make_client(settings()) as client:
        a, b = enroll(client), enroll(client, "Иван")
        ca, cb = context(client, a), context(client, b)
        repo = MemoryRepository(client.app.state.store)
        client.portal.call(repo.set_alias, ca.principal, "Рыжий", "veh-2")
        client.portal.call(repo.set_alias, cb.principal, "Рыжий", "veh-1")
        tools = registry(client)
        period = {"from": "2026-09-16T00:00", "to": "2026-09-16T06:00", "vehicle_id": "РЫЖИЙ"}
        for ctx, expected in ((ca, "veh-2"), (cb, "veh-1")):
            for name in ("get_vehicle_summary", "check_vehicle"):
                result = execute(client, tools, name, period, ctx)
                assert result["vehicle"]["id"] == expected, result
            result = execute(client, tools, "list_anomalies", period, ctx)
            assert "error" not in result and "total" in result
            listed = execute(client, tools, "list_vehicles", {"query": "Рыжий"}, ctx)
            assert [v["id"] for v in listed["vehicles"]] == [expected]
            assert listed["vehicles"][0]["aliases"] == ["Рыжий"]
        anonymous = replace(ca, principal=None, check_principal=None, features=frozenset())
        assert "error" in execute(client, tools, "get_vehicle_summary", period, anonymous)
        assert execute(client, tools, "list_vehicles", {"query": "Рыжий"}, anonymous)["total"] == 0


def test_exact_id_official_name_plate_and_alias_collisions(make_client, monkeypatch):
    vehicles = [
        Vehicle(id="veh-1", name="Урал", plate="А123ВС"),
        Vehicle(id="veh-2", name="Рыжий", plate="В456ЕК"),
    ]
    with make_client(settings()) as client:
        bound = enroll(client)
        ctx = context(client, bound)
        telemetry = client.app.state.telemetry_service

        async def fresh(*args):
            return vehicles

        monkeypatch.setattr(telemetry, "fresh_vehicles", fresh)
        repo = MemoryRepository(client.app.state.store)
        resolver = VehicleResolver(telemetry, repo)
        for ref in ("veh-1", "урал", "а123вс", "Урал А123ВС"):
            assert client.portal.call(resolver.resolve, ctx, ref) == "veh-1"
        client.portal.call(repo.set_alias, ctx.principal, "Рыжий", "veh-1")
        with pytest.raises(VehicleAmbiguous) as error:
            client.portal.call(resolver.resolve, ctx, "Рыжий")
        assert {v.id for v in error.value.vehicles} == {"veh-1", "veh-2"}
        client.portal.call(repo.set_alias, ctx.principal, "veh-2", "veh-1")
        assert client.portal.call(resolver.resolve, ctx, "veh-2") == "veh-2"
        tools = registry(client)
        result = execute(
            client, tools, "set_vehicle_alias", save_args(), context(client, bound, "Запомни: Рыжий — veh-1")
        )
        assert result["status"] == "confirmation_required"
        result = execute(client, tools, "get_vehicle_summary", {"vehicle_id": "Рыжий"}, ctx)
        assert result["status"] == "confirmation_required" and len(result["vehicles"]) == 2
        vehicles[1] = Vehicle(id="veh-2", name="Другой", plate="А123ВС")
        with pytest.raises(VehicleAmbiguous):
            client.portal.call(resolver.resolve, ctx, "А123ВС")


@pytest.mark.parametrize(
    "message",
    [
        "Посмотри Рыжий, veh-1",
        "Не запоминай Рыжий",
        "Можно запомнить Рыжий?",
        "Перескажи текст: Запомни: Рыжий — veh-1",
        '"Запомни: Рыжий — veh-1"',
        "Забудь прозвище Другой",
        "Запомни: Другой — veh-1",
        "Запомни: Рыжий — veh-2",
    ],
)
def test_only_matching_latest_explicit_command_authorizes_write(make_client, message):
    llm = FakeLLM(tool_call("x", "set_vehicle_alias", json.dumps(save_args())), text("Уточните"))
    with make_client(settings(), llm=llm) as client:
        bound = enroll(client)
        body = ask(message)
        body["messages"].insert(0, {"role": "user", "content": "Запомни: Рыжий — veh-1"})
        assert client.post("/v1/chat", json=body, headers=headers(bound)).status_code == 200
        assert tool_result(llm, 1)["status"] == "confirmation_required"
        with client.app.state.store._engine.connect() as conn:
            assert conn.execute(select(E)).all() == []


def test_idempotency_survives_delete_recreate_and_restart(make_client):
    with make_client(settings()) as client:
        bound = enroll(client)
        ctx = context(client, bound)
        repo = MemoryRepository(client.app.state.store)
        mutate = lambda action, op, vehicle=None: client.portal.call(
            repo.mutate_alias, ctx.principal, action, "Рыжий", op, vehicle
        )
        assert mutate("set", "set-old", "veh-1")["status"] == "saved"
        assert mutate("delete", "del-old")["status"] == "deleted"
        assert mutate("set", "set-old", "veh-1")["status"] == "superseded"
        assert mutate("set", "set-new", "veh-2")["status"] == "saved"
        assert mutate("delete", "del-old")["status"] == "superseded"
        assert client.portal.call(repo.find_alias, ctx.principal, "Рыжий")["entity_id"] == "veh-2"
        with pytest.raises(MemoryConflict):
            mutate("set", "set-new", "veh-1")
    with make_client(settings()) as client:
        ctx = context(client, bound)
        repo = MemoryRepository(client.app.state.store)
        assert client.portal.call(repo.mutate_alias, ctx.principal, "set", "Рыжий", "set-old", "veh-1") == {
            "status": "superseded"
        }
        assert client.portal.call(repo.find_alias, ctx.principal, "Рыжий")["entity_id"] == "veh-2"


def test_removed_vehicle_never_uses_warm_cache_and_can_be_forgotten(make_client, monkeypatch):
    with make_client(settings(devices_cache_ttl_seconds=3600)) as client:
        bound = enroll(client)
        ctx = context(client, bound)
        telemetry = client.app.state.telemetry_service
        client.portal.call(telemetry.vehicles, ctx.session, ctx.schema_id)
        repo = MemoryRepository(client.app.state.store)
        client.portal.call(repo.set_alias, ctx.principal, "Скрытый", "veh-1")

        async def empty(*args):
            return []

        monkeypatch.setattr(telemetry._client, "enum_devices", empty)
        tools = registry(client)
        for name in ("get_vehicle_summary", "check_vehicle", "list_anomalies"):
            result = execute(client, tools, name, {"vehicle_id": "Скрытый"}, ctx)
            assert "error" in result and "veh-1" not in json.dumps(result)
        assert execute(client, tools, "list_vehicles", {}, ctx)["vehicles"] == []
        forget = context(client, bound, "Забудь название Скрытый")
        assert (
            execute(
                client,
                tools,
                "delete_vehicle_alias",
                {"alias": "Скрытый", "client_operation_id": "forget"},
                forget,
            )["status"]
            == "deleted"
        )


def test_rights_removed_between_resolution_and_commit_prevent_save(make_client, monkeypatch):
    with make_client(settings()) as client:
        bound = enroll(client)
        ctx = context(client, bound, "Запомни: Рыжий — veh-1")
        telemetry = client.app.state.telemetry_service
        original = telemetry.fresh_vehicles
        calls = 0

        async def lose_rights(*args):
            nonlocal calls
            calls += 1
            return await original(*args) if calls == 1 else []

        monkeypatch.setattr(telemetry, "fresh_vehicles", lose_rights)
        result = execute(client, registry(client), "set_vehicle_alias", save_args(), ctx)
        assert result["status"] == "not_found"
        with client.app.state.store._engine.connect() as conn:
            assert conn.execute(select(E)).all() == [] and conn.execute(select(AO)).all() == []


def test_credential_revoke_between_llm_and_tool_prevents_save(make_client):
    with make_client(settings()) as client:
        bound = enroll(client)
        ctx = context(client, bound, "Запомни: Рыжий — veh-1")
        assert client.post("/v2/identity/revoke", headers=headers(bound)).status_code == 204
        result = execute(client, registry(client), "set_vehicle_alias", save_args(), ctx)
        assert result["status"] == "unauthorized"
        with client.app.state.store._engine.connect() as conn:
            assert conn.execute(select(E)).all() == []


@pytest.mark.parametrize(
    "args",
    [
        {**save_args(), "principal_id": "victim"},
        {**save_args(), "tenant_id": "victim"},
        {**save_args(), "alias": ""},
        {**save_args(), "client_operation_id": 1},
    ],
)
def test_model_cannot_inject_scope_or_invalid_arguments(make_client, args):
    with make_client(settings()) as client:
        bound = enroll(client)
        ctx = context(client, bound, "Запомни: Рыжий — veh-1")
        result = execute(client, registry(client), "set_vehicle_alias", args, ctx)
        assert result["status"] == "invalid_arguments"


def test_disabled_and_unverified_chat_keep_legacy_contract(make_client):
    for config in (test_settings(), settings()):
        llm = FakeLLM(text("Готово"))
        with make_client(config, llm=llm) as client:
            response = client.post("/v1/chat", json=ask(), headers=AUTH)
            assert response.status_code == 200
            assert all(t["function"]["name"] != "set_vehicle_alias" for t in llm.calls[0][1])
    with make_client(settings()) as client:
        for addition in ({"X-Identity-Credential": "forged"}, {"X-User-Name": "victim"}):
            response = client.post("/v1/chat", json=ask(), headers={**AUTH, **addition})
            assert response.status_code == (401 if "X-Identity-Credential" in addition else 200)


def test_memory_errors_do_not_confirm_save_or_leak_text(make_client, monkeypatch, caplog):
    private = "СекретноеПрозвище"
    with make_client(settings()) as client:
        bound = enroll(client)
        ctx = context(client, bound, f"Запомни: {private} — veh-1")

        async def fail(*args):
            raise RuntimeError(private)

        monkeypatch.setattr(MemoryRepository, "mutate_alias", fail)
        with caplog.at_level(logging.INFO):
            result = execute(client, registry(client), "set_vehicle_alias", save_args(private), ctx)
        assert result["status"] == "unavailable"
        assert private not in caplog.text and private not in json.dumps(result)


def test_autograph_outage_does_not_fall_back_to_alias_or_device_cache(make_client, monkeypatch):
    with make_client(settings()) as client:
        bound = enroll(client)
        ctx = context(client, bound)
        repo = MemoryRepository(client.app.state.store)
        client.portal.call(repo.set_alias, ctx.principal, "Рыжий", "veh-1")
        client.portal.call(client.app.state.telemetry_service.vehicles, ctx.session, ctx.schema_id)

        async def fail(*args):
            raise AutoGraphUnavailable()

        monkeypatch.setattr(client.app.state.autograph_client, "enum_devices", fail)
        result = execute(client, registry(client), "get_vehicle_summary", {"vehicle_id": "Рыжий"}, ctx)
        assert "error" in result and "veh-1" not in json.dumps(result)


def test_operation_and_alias_audit_rollback_together(make_client):
    with make_client(settings()) as client:
        ctx = context(client, enroll(client))
        store = client.app.state.store
        repo = MemoryRepository(store)
        with store._engine.begin() as conn:
            conn.exec_driver_sql(
                "CREATE TRIGGER fail_operation BEFORE INSERT ON memory_alias_operations "
                "BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END"
            )
        with pytest.raises(IntegrityError):
            client.portal.call(repo.mutate_alias, ctx.principal, "set", "Рыжий", "failed", "veh-1")
        with store._engine.connect() as conn:
            assert conn.execute(select(E)).all() == []
            assert conn.execute(select(A)).all() == []


def test_operation_keys_are_personal_and_concurrent_replay_is_atomic(make_client):
    with make_client(settings()) as client:
        ca, cb = context(client, enroll(client)), context(client, enroll(client, "Иван"))
        url = client.app.state.settings.db_url

        def work(index):
            independent = open_store(url)
            try:
                return asyncio.run(
                    MemoryRepository(independent).mutate_alias(
                        ca.principal, "set", "Рыжий", "same-key", "veh-2"
                    )
                )
            finally:
                independent.dispose()

        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(work, range(8)))
        assert results == [{"status": "saved"}] * 8
        repo = MemoryRepository(client.app.state.store)
        assert client.portal.call(repo.mutate_alias, cb.principal, "set", "Рыжий", "same-key", "veh-1") == {
            "status": "saved"
        }
        with client.app.state.store._engine.connect() as conn:
            assert len(conn.execute(select(E)).all()) == 2
            assert len(conn.execute(select(AO)).all()) == 2
            assert len(conn.execute(select(A)).all()) == 2


def test_forget_invalidates_derived_memory_even_without_active_alias(make_client):
    with make_client(settings()) as client:
        bound = enroll(client)
        ctx = context(client, bound, "Забудь прозвище Рыжий")
        repo = MemoryRepository(client.app.state.store)
        cid = client.portal.call(repo.create_conversation, ctx.principal, "android")

        async def append():
            return await repo.append_turn(
                ctx.principal, cid, "message", "Рыжий", "Уточните", expected_version=0
            )

        client.portal.call(append)
        with client.app.state.store._engine.begin() as conn:
            mid = conn.execute(
                select(M.c.id).where(M.c.conversation_id == cid, M.c.role == "user")
            ).scalar_one()
            conn.execute(
                insert(S).values(
                    conversation_id=cid,
                    version=1,
                    summary="Рыжий — машина",
                    source_range="1:2",
                    updated_at=1000,
                )
            )
            conn.execute(
                insert(K).values(
                    id="candidate",
                    tenant_id=ctx.schema_id,
                    principal_id=ctx.principal.principal_id,
                    kind="alias",
                    dedupe_key="candidate",
                    payload_json="{}",
                    evidence_message_id=mid,
                    status="accepted",
                    expires_at=10000,
                )
            )
        assert (
            execute(
                client,
                registry(client),
                "delete_vehicle_alias",
                {"alias": "Рыжий", "client_operation_id": "forget"},
                ctx,
            )["status"]
            == "deleted"
        )
        with client.app.state.store._engine.connect() as conn:
            assert conn.execute(select(S)).all() == []
            assert conn.execute(select(K.c.status)).scalar_one() == "rejected"
            assert conn.execute(select(O.c.state)).scalar_one() == "cancelled"
            tombstone = conn.execute(select(E)).mappings().one()
            assert tombstone["normalized_alias"] == "рыжий" and tombstone["status"] == "deleted"
        assert len(client.portal.call(repo.messages, ctx.principal, cid)) == 2


def test_alias_write_arguments_are_not_logged_in_real_agent_loop(make_client, caplog):
    alias = "ЛичноеПрозвищеДляЛога"
    llm = FakeLLM(tool_call("save", "set_vehicle_alias", json.dumps(save_args(alias))), text("Готово"))
    with make_client(settings(), llm=llm) as client:
        bound = enroll(client)
        with caplog.at_level(logging.INFO):
            response = client.post("/v1/chat", json=ask(f"Запомни: {alias} — veh-1"), headers=headers(bound))
        assert response.status_code == 200 and tool_result(llm, 1)["status"] == "saved"
        assert alias not in caplog.text
        assert "set_vehicle_alias(аргументы не пишутся в лог)" in caplog.text


def test_model_cannot_confirm_a_write_without_committed_tool(make_client, monkeypatch):
    with make_client(settings(), llm=FakeLLM(text("Запомнил"))) as client:
        response = client.post("/v1/chat", json=ask("Запомни: Рыжий — veh-1"), headers=AUTH)
        assert response.status_code == 200
        assert (
            response.json()["reply"]
            == "Память не изменена: для персональных прозвищ сначала подтвердите личность."
        )
    llm = FakeLLM(text("Запомнил, всё сохранил"))
    with make_client(settings(), llm=llm) as client:
        bound = enroll(client)
        response = client.post("/v1/chat", json=ask("Запомни: Рыжий — veh-1"), headers=headers(bound))
        assert response.status_code == 200 and "Память не изменена" in response.json()["reply"]
    llm = FakeLLM(tool_call("s", "set_vehicle_alias", json.dumps(save_args())), text("Всё запомнил"))
    with make_client(settings(), llm=llm) as client:
        bound = enroll(client)

        async def fail(*args):
            raise RuntimeError("synthetic store failure")

        monkeypatch.setattr(MemoryRepository, "mutate_alias", fail)
        response = client.post("/v1/chat", json=ask("Запомни: Рыжий — veh-1"), headers=headers(bound))
        assert response.status_code == 200
        assert response.json()["reply"] == "Память недоступна; изменение не подтверждено"


def test_spoofed_headers_cannot_select_another_personal_scope(make_client):
    with make_client(settings(), llm=FakeLLM(text("Готово"))) as client:
        a, b = enroll(client), enroll(client, "Иван")
        attacks = [
            {**headers(a), "X-Identity-Credential": b["credential"]},
            {**headers(a), "X-Schema-Id": "schema-2"},
            {**headers(a), "X-Telegram-Chat-Id": "123"},
        ]
        for attack, status in zip(attacks, (401, 403, 503)):
            response = client.post("/v1/chat", json=ask(), headers=attack)
            assert response.status_code == status
    llm = FakeLLM(tool_call("s", "set_vehicle_alias", json.dumps(save_args())), text("Запомнил"))
    with make_client(settings(), llm=llm) as client:
        a = enroll(client)
        response = client.post(
            "/v1/chat",
            json=ask("Запомни: Рыжий — veh-1"),
            headers={**headers(a), "X-User-Name": "spoofed-user"},
        )
        assert response.status_code == 200 and response.json()["reply"] == "Прозвище сохранено."
        with client.app.state.store._engine.connect() as conn:
            assert conn.execute(select(E.c.principal_id)).scalar_one() == a["principalId"]


def test_changed_vehicle_id_does_not_retarget_personal_alias(make_client, monkeypatch):
    with make_client(settings()) as client:
        bound = enroll(client)
        ctx = context(client, bound)
        repo = MemoryRepository(client.app.state.store)
        client.portal.call(repo.set_alias, ctx.principal, "Рыжий", "veh-1")

        async def replacement(*args):
            return [Vehicle(id="veh-new", name="FAW №1")]

        monkeypatch.setattr(client.app.state.telemetry_service, "fresh_vehicles", replacement)
        result = execute(client, registry(client), "get_vehicle_summary", {"vehicle_id": "Рыжий"}, ctx)
        assert "error" in result and "veh-new" not in json.dumps(result)
        assert client.portal.call(repo.find_alias, ctx.principal, "Рыжий")["entity_id"] == "veh-1"


def test_alias_operation_migration_preserves_populated_p1_storage(tmp_path):
    from sqlalchemy import inspect
    from fleet_service.identity.repository import IdentityRepository

    url = f"sqlite:///{tmp_path / 'upgrade-p2.db'}"
    upgrade(url, "0011")
    store = open_store(url)
    try:
        principal = asyncio.run(
            IdentityRepository(store).issue("fake", "user", "schema", "session")
        ).principal
        repo = MemoryRepository(store)
        identifier = asyncio.run(repo.set_alias(principal, "Рыжий", "veh-1"))
        upgrade(url)
        assert asyncio.run(repo.mutate_alias(principal, "set", "Рыжий", "op", "veh-1")) == {"status": "saved"}
        downgrade(url, "0011")
        assert "memory_alias_operations" not in inspect(store._engine).get_table_names()
        assert asyncio.run(repo.find_alias(principal, "Рыжий"))["id"] == identifier
        upgrade(url)
        with store._engine.connect() as conn:
            assert conn.execute(select(AO)).all() == []
        assert asyncio.run(repo.find_alias(principal, "Рыжий"))["id"] == identifier
    finally:
        store.dispose()


def test_rejected_extra_call_does_not_hide_committed_save(make_client):
    first = tool_call("save", "set_vehicle_alias", json.dumps(save_args()))
    first["tool_calls"] += tool_call("extra", "set_vehicle_alias", json.dumps(save_args("Другой")))[
        "tool_calls"
    ]
    llm = FakeLLM(first, text("Память не изменена"))
    with make_client(settings(), llm=llm) as client:
        bound = enroll(client)
        response = client.post("/v1/chat", json=ask("Запомни: Рыжий — veh-1"), headers=headers(bound))
        assert response.status_code == 200 and response.json()["reply"] == "Прозвище сохранено."
        with client.app.state.store._engine.connect() as conn:
            assert conn.execute(select(E.c.alias)).scalars().all() == ["Рыжий"]
