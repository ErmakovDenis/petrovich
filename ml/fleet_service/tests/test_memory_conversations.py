"""P4: HTTP, настоящая SQLite, FakeLLM, конкурентные запросы и восстановление без дублей."""

import asyncio
import json
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest
from sqlalchemy import select, update

from fleet_service.agent.llm import LLMRetryable
from fleet_service.agent.tools import Tool, ToolRegistry
from fleet_service.memory.conversations import ConversationRepository
from fleet_service.memory.repository import MemoryRepository
from fleet_service.schemas.conversations import DurableChatRequest
from fleet_service.store.db import (
    conversations as C,
    conversation_messages as M,
    conversation_turns as T,
    conversation_tool_calls as J,
    memory_outbox as O,
)

from .conftest import AUTH, FakeLLM, ask, test_settings, text, tool_call
from .test_identity_memory import enroll, headers
from .test_memory_preferences import style_payload


def config(**overrides):
    return test_settings(
        identity_enabled=True,
        identity_account_lifecycle="managed",
        chat_v2_enabled=True,
        chat_burst=30,
        **overrides,
    )


def question(cid=None, identifier="message-1", version=0, content="Что с парком?", **kwargs):
    return {
        "conversationId": cid,
        "clientMessageId": identifier,
        "expectedVersion": version,
        "content": content,
        "utcOffsetMinutes": 300,
        **kwargs,
    }


def post(client, bound, body):
    return client.post("/v2/chat", headers=headers(bound), json=body)


def create(client, bound):
    response = client.post("/v2/conversations", headers=headers(bound), json={"channel": "android"})
    assert response.status_code == 200, response.text
    return response.json()["id"]


def test_restart_replay_and_two_devices_share_owned_history(make_client):
    llm = FakeLLM(text("Первый ответ"), text("Второй ответ"))
    with make_client(config(), llm=llm) as client:
        a, other_device = enroll(client), enroll(client)
        body = question()
        first = post(client, a, body)
        assert first.status_code == 200, first.text
        assert first.headers["cache-control"] == "no-store"
        result = first.json()
        assert result["state"] == "completed" and result["version"] == 1
        cid = result["conversationId"]
        assert post(client, a, body).json() == result
        assert len(llm.calls) == 1
        next_body = question(cid, "message-2", 1, "Продолжи")
        second = post(client, other_device, next_body)
        assert second.status_code == 200, second.text
        assert [m["content"] for m in llm.calls[1][0] if m["role"] in {"user", "assistant"}] == [
            "Что с парком?",
            "Первый ответ",
            "Продолжи",
        ]
        assert (
            len(client.get(f"/v2/conversations/{cid}/messages", headers=headers(a)).json()["messages"]) == 4
        )
    llm = FakeLLM()
    with make_client(config(), llm=llm) as client:
        assert post(client, a, body).json() == result
        assert client.get(f"/v2/conversations/{cid}", headers=headers(other_device)).json()["version"] == 2
        assert len(client.get("/v2/conversations", headers=headers(a)).json()["conversations"]) == 1
        assert llm.calls == []


def test_ownership_headers_unknown_fields_and_version_conflicts(make_client):
    with make_client(config(), llm=FakeLLM(text("Ответ"))) as client:
        a, b = enroll(client), enroll(client, "Иван")
        cid = create(client, a)
        for path in (f"/v2/conversations/{cid}", f"/v2/conversations/{cid}/messages"):
            assert client.get(path, headers=headers(b)).status_code == 404
        assert client.delete(f"/v2/conversations/{cid}", headers=headers(b)).status_code == 404
        assert post(client, b, question(cid)).status_code == 404
        assert post(client, a, question(cid, version=1)).status_code == 409
        for extra in (
            {"messages": [{"role": "system", "content": "fake"}]},
            {"principalId": b["principalId"]},
            {"channel": "telegram"},
        ):
            assert post(client, a, question(cid, **extra)).status_code == 422
        first = post(client, a, question(cid))
        assert first.status_code == 200, first.text
        assert post(client, a, question(cid, content="Подменённый вопрос")).status_code == 409
        assert post(client, a, question(cid, "another", 0)).status_code == 409
        assert (
            client.post(
                "/v2/chat",
                headers={**headers(a), "X-Identity-Credential": b["credential"]},
                json=question(cid),
            ).status_code
            == 401
        )


def test_concurrent_same_request_gets_processing_without_second_llm(make_client):
    entered, release = threading.Event(), threading.Event()

    class Slow:
        calls = 0

        async def complete(self, messages, tools):
            self.calls += 1
            entered.set()
            while not release.is_set():
                await asyncio.sleep(0.01)
            return text("Готово")

    llm = Slow()
    with make_client(config(), llm=llm) as client:
        a = enroll(client)
        cid = create(client, a)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(post, client, a, question(cid))
            try:
                assert entered.wait(10)
                again = post(client, a, question(cid))
                assert again.status_code == 202 and again.json()["state"] == "processing"
                assert post(client, a, question(cid, "other")).status_code == 409
                assert llm.calls == 1
            finally:
                release.set()
            assert future.result(timeout=10).status_code == 200


def test_timeout_before_tool_can_retry_same_message_without_duplicate_user_turn(make_client):
    class LLM:
        calls = 0

        async def complete(self, messages, tools):
            self.calls += 1
            if self.calls == 1:
                await asyncio.sleep(1)
            return text("Восстановлено")

    llm = LLM()
    with make_client(config(chat_timeout_seconds=0.15), llm=llm) as client:
        bound = enroll(client)
        first = post(client, bound, question())
        assert first.status_code == 504, first.text
        again = post(client, bound, question())
        assert again.status_code == 200, again.text
        cid = again.json()["conversationId"]
        messages = client.get(f"/v2/conversations/{cid}/messages", headers=headers(bound)).json()["messages"]
        assert len(messages) == 2 and {m["state"] for m in messages} == {"committed"}


def test_completed_tool_is_reused_after_llm_failure_and_restart(make_client):
    seen = []

    async def read(args, ctx):
        seen.append(args)
        return {"number": 17}

    tools = ToolRegistry()
    tools.register(Tool("read", "Чтение", {"type": "object", "properties": {}}, read))
    llm = FakeLLM(tool_call("read-1", "read", "{}"), LLMRetryable("synthetic failure"))
    with make_client(config(), llm=llm, tools=tools) as client:
        bound = enroll(client)
        assert post(client, bound, question()).status_code == 503
    llm = FakeLLM(tool_call("read-again", "read", "{}"), text("Ответ 17"))
    with make_client(config(), llm=llm, tools=tools) as client:
        result = post(client, bound, question())
        assert result.status_code == 200, result.text
        assert seen == [{}]
        assert any(m["role"] == "tool" and "17" in m["content"] for m in llm.calls[0][0])


def test_uncertain_started_tool_is_never_automatically_repeated(make_client):
    entered = []

    async def uncertain(args, ctx):
        entered.append(1)
        raise asyncio.CancelledError()

    tools = ToolRegistry()
    tools.register(Tool("uncertain", "Действие", {"type": "object"}, uncertain, mutates=True))
    llm = FakeLLM(tool_call("one", "uncertain", "{}"))
    with make_client(config(), llm=llm, tools=tools) as client:
        bound = enroll(client)
        # Эмулируем потерю процесса на промежутке started → done через сам репозиторий.
        repo = ConversationRepository(client.app.state.store)
        principal = client.portal.call(
            client.app.state.identity.authenticate,
            bound["credential"],
            bound["autographSession"],
            bound["tenantId"],
        )
        turn, _ = client.portal.call(
            repo.claim,
            principal,
            DurableChatRequest.model_validate(question()),
            "android",
            {"veh-1", "veh-2"},
            10000,
        )
        client.portal.call(repo.reserve_tool, principal, turn["id"], turn["lease_token"], "uncertain", {})
        with client.app.state.store._engine.begin() as conn:
            conn.execute(update(T).values(lease_until=0))
        response = post(client, bound, question())
        assert response.status_code == 409 and response.json()["state"] == "interrupted"
        assert entered == [] and llm.calls == []
        assert post(client, bound, question()).json()["state"] == "interrupted"


def test_delete_during_generation_prevents_commit_and_keeps_personal_memory(make_client):
    entered, release = threading.Event(), threading.Event()

    async def wait_reply(messages):
        return text("Не должно сохраниться")

    class Slow:
        async def complete(self, messages, tools):
            entered.set()
            while not release.is_set():
                await asyncio.sleep(0.01)
            return text("Не должно сохраниться")

    with make_client(config(), llm=Slow()) as client:
        bound = enroll(client)
        cid = create(client, bound)
        principal = client.portal.call(
            client.app.state.identity.authenticate,
            bound["credential"],
            bound["autographSession"],
            bound["tenantId"],
        )
        client.portal.call(MemoryRepository(client.app.state.store).set_alias, principal, "Рыжий", "veh-1")
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(post, client, bound, question(cid))
            try:
                assert entered.wait(10)
                assert client.delete(f"/v2/conversations/{cid}", headers=headers(bound)).status_code == 204
            finally:
                release.set()
            assert future.result(timeout=10).status_code == 404
        assert client.get(f"/v2/conversations/{cid}/messages", headers=headers(bound)).status_code == 404
        assert (
            client.portal.call(MemoryRepository(client.app.state.store).find_alias, principal, "Рыжий")
            is not None
        )
        with client.app.state.store._engine.connect() as conn:
            assert all(not row.content for row in conn.execute(select(M)))
            assert conn.execute(select(T.c.state)).scalar_one() == "deleted"


def test_changed_vehicle_permissions_redact_history_and_block_cached_replay(make_client, monkeypatch):
    llm = FakeLLM(text("Старое значение 250"), text("Ответ без закрытых сведений"))
    with make_client(config(), llm=llm) as client:
        bound = enroll(client)
        first = post(client, bound, question())
        assert first.status_code == 200, first.text
        cid = first.json()["conversationId"]

        async def changed(*args):
            return []

        monkeypatch.setattr(client.app.state.telemetry_service, "fresh_vehicles", changed)
        assert post(client, bound, question()).status_code == 403
        rows = client.get(f"/v2/conversations/{cid}/messages", headers=headers(bound)).json()["messages"]
        assert all(row["redacted"] for row in rows) and "250" not in json.dumps(rows)
        second = post(client, bound, question(cid, "new", 1))
        assert second.status_code == 200, second.text
        assert "Старое значение" not in str(llm.calls[1][0])


def test_server_side_local_style_survives_prompt_trimming_and_restart(make_client):
    llm = FakeLLM(text("Хорошо"), text("Ответ"), text("Ответ"))
    with make_client(config(memory_preferences_enabled=True, chat_max_messages=1), llm=llm) as client:
        bound = enroll(client)
        first = post(client, bound, question(content="В этом чате пиши подробно"))
        assert first.status_code == 200, first.text
        cid = first.json()["conversationId"]
        assert post(client, bound, question(cid, "second", 1)).status_code == 200
        assert style_payload(llm.calls[1][0])["effective"]["response_length"] == "detailed"
        assert (
            post(client, bound, question(cid, "third", 2, "Ответь кратко: что с машиной?")).status_code == 200
        )
        assert style_payload(llm.calls[2][0])["effective"]["response_length"] == "short"
    llm = FakeLLM(text("Ответ"))
    with make_client(config(memory_preferences_enabled=True, chat_max_messages=1), llm=llm) as client:
        assert post(client, bound, question(cid, "fourth", 3)).status_code == 200
        assert style_payload(llm.calls[0][0])["effective"]["response_length"] == "detailed"


def test_bounded_context_pagination_and_secret_redaction(make_client):
    llm = FakeLLM(*[text("x" * 1000) for _ in range(5)])
    with make_client(config(chat_history_max_chars=2000), llm=llm) as client:
        bound = enroll(client)
        cid = create(client, bound)
        for i in range(5):
            content = "пароль: synthetic-password" if i == 0 else "y" * 300
            result = post(client, bound, question(cid, str(i), i, content))
            assert result.status_code == 200, result.text
            history = [m["content"] for m in llm.calls[i][0] if m["role"] in {"user", "assistant"}]
            assert sum(map(len, history)) <= 2000
        rows = client.get(
            f"/v2/conversations/{cid}/messages?limit=1&offset=0", headers=headers(bound)
        ).json()["messages"]
        assert (
            len(rows) == 1
            and "synthetic-password" not in str(rows)
            and "synthetic-password" not in str(llm.calls)
        )
        assert (
            client.get(f"/v2/conversations/{cid}/messages?limit=101", headers=headers(bound)).status_code
            == 422
        )


def test_legacy_chat_and_disabled_v2_are_compatible(make_client):
    with make_client(llm=FakeLLM(text("Ответ"))) as client:
        assert client.post("/v2/chat", json=question(), headers=AUTH).status_code == 503
        assert client.post("/v1/chat", json=ask(), headers=AUTH).status_code == 200


def test_read_only_recovery_does_not_repeat_mutating_tool(make_client):
    changes = []

    async def write(args, ctx):
        changes.append(1)
        return {"status": "saved"}

    tools = ToolRegistry()
    tools.register(Tool("write", "Запись", {"type": "object"}, write, mutates=True))
    llm = FakeLLM(
        tool_call("s", "write", "{}"),
        LLMRetryable("synthetic failure"),
        tool_call("s2", "write", '{"different":true}'),
        text("Завершено"),
    )
    with make_client(config(), llm=llm, tools=tools) as client:
        bound = enroll(client)
        assert post(client, bound, question()).status_code == 503
        assert post(client, bound, question()).status_code == 200
        assert changes == [1]
        assert llm.calls[2][1] == []


def test_personal_telegram_requires_private_pair_and_does_not_merge_channels(make_client):
    from .test_identity_telegram import config as telegram_config, attach, peer

    with make_client(telegram_config(chat_v2_enabled=True), llm=FakeLLM(text("Ответ боту"))) as client:
        bound = enroll(client)
        h = peer()
        assert client.post("/v2/chat", headers=h, json=question()).status_code == 401
        attach(client, bound)
        response = client.post("/v2/chat", headers=h, json=question())
        assert response.status_code == 200, response.text
        cid = response.json()["conversationId"]
        assert client.get(f"/v2/conversations/{cid}", headers=headers(bound)).json()["channel"] == "telegram"
        assert post(client, bound, question(cid, "app-question", 1)).status_code == 409
        assert (
            client.post(
                "/v2/chat", headers={**h, "X-Telegram-Chat-Type": "group"}, json=question()
            ).status_code
            == 403
        )
        assert (
            client.post("/v2/chat", headers={**h, "X-Telegram-User-Id": "other"}, json=question()).status_code
            == 403
        )


def test_mail_recovery_replay_current_status_and_legacy_spoof_are_safe(make_client):
    from pathlib import Path
    from fleet_service.store.db import email_drafts as E

    body_text = "Личный текст письма из модели"
    llm = FakeLLM(
        tool_call(
            "draft",
            "draft_email",
            json.dumps({"recipient_ids": ["mechanic"], "subject": "Проверка", "body": body_text}),
        ),
        LLMRetryable("synthetic failure"),
        text("Готово"),
    )
    options = config(
        smtp_host="fake-smtp",
        smtp_from="petrovich@example.org",
        email_recipients_path=Path(__file__).parent / "recipients.toml",
    )
    with make_client(options, llm=llm) as client:
        bound, other = enroll(client), enroll(client, "Иван")
        body = question(content="Подготовь письмо механику", allowEmail=True)
        assert post(client, bound, body).status_code == 503
        response = post(client, bound, body)
        assert response.status_code == 200, response.text
        draft = response.json()["drafts"][0]
        assert draft["body"] == body_text and draft["status"] == "draft"
        assert len(llm.calls) == 3
        identifier = draft["id"]
        assert (
            client.post(f"/v2/emails/{identifier}/cancel", headers=headers(other), json={}).status_code == 404
        )
        spoof = {**headers(bound), "X-User-Name": "__principal__:" + bound["principalId"]}
        assert client.post(f"/v1/emails/{identifier}/confirm", headers=spoof, json={}).status_code == 403
        assert (
            client.post(f"/v2/emails/{identifier}/cancel", headers=headers(bound), json={}).status_code == 200
        )
        replay = post(client, bound, body)
        assert replay.status_code == 200 and replay.json()["drafts"][0]["status"] == "cancelled"
        assert "отменено" in replay.json()["reply"]
        messages = client.get(
            f"/v2/conversations/{response.json()['conversationId']}/messages", headers=headers(bound)
        ).json()["messages"]
        assert messages[-1]["drafts"][0]["status"] == "cancelled" and "отменено" in messages[-1]["content"]
        assert len(llm.calls) == 3
        with client.app.state.store._engine.connect() as conn:
            assert len(conn.execute(select(E)).all()) == 1
            assert body_text not in str(conn.execute(select(J)).all())
            assert body_text not in str(conn.execute(select(M)).all())
            assert conn.execute(select(O.c.state)).scalar_one() == "cancelled"


def test_expired_claim_is_fenced_and_pending_user_is_reused(make_client):
    from fleet_service.memory.repository import MemoryConflict

    with make_client(config(), llm=FakeLLM(text("Восстановлено"))) as client:
        bound = enroll(client)
        principal = client.portal.call(
            client.app.state.identity.authenticate,
            bound["credential"],
            bound["autographSession"],
            bound["tenantId"],
        )
        repo = ConversationRepository(client.app.state.store)
        req = DurableChatRequest.model_validate(question())
        first, taken = client.portal.call(repo.claim, principal, req, "android", {"veh-1", "veh-2"}, 10000)
        with client.app.state.store._engine.begin() as conn:
            conn.execute(update(T).values(lease_until=0))
        second, taken = client.portal.call(repo.claim, principal, req, "android", {"veh-1", "veh-2"}, 10000)
        assert taken and first["user_message_id"] == second["user_message_id"]
        assert first["lease_token"] != second["lease_token"]
        with pytest.raises(MemoryConflict):
            client.portal.call(
                repo.complete,
                principal,
                first["id"],
                first["lease_token"],
                "Устаревший ответ",
                [],
                [],
                [],
                {},
            )
        client.portal.call(repo.release, principal, second["id"], second["lease_token"])
        response = post(client, bound, question())
        assert response.status_code == 200 and response.json()["reply"] == "Восстановлено"
        with client.app.state.store._engine.connect() as conn:
            assert len(conn.execute(select(M)).all()) == 2 and len(conn.execute(select(O)).all()) == 1


def test_reply_commit_failure_keeps_retryable_turn_without_outbox(make_client, monkeypatch):
    from sqlalchemy.exc import IntegrityError

    llm = FakeLLM(text("Ответ до сбоя"), text("Ответ после сбоя"))
    with make_client(config(), llm=llm) as client:
        bound = enroll(client)
        with client.app.state.store._engine.begin() as conn:
            conn.exec_driver_sql(
                "CREATE TRIGGER fail_outbox_v2 BEFORE INSERT ON memory_outbox "
                "BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END"
            )
        response = post(client, bound, question())
        assert response.status_code == 500
        with client.app.state.store._engine.begin() as conn:
            assert conn.execute(select(O)).all() == []
            assert len(conn.execute(select(M)).all()) == 1
            conn.exec_driver_sql("DROP TRIGGER fail_outbox_v2")
        response = post(client, bound, question())
        assert response.status_code == 200 and response.json()["reply"] == "Ответ после сбоя"


def test_v2_contract_fixtures_and_history_boundary():
    from pathlib import Path
    from fleet_service.schemas.conversations import DurableChatResponse

    root = Path(__file__).resolve().parents[3] / "testdata" / "contract"
    for name, model in (
        ("chat-v2-request.json", DurableChatRequest),
        ("chat-v2-response.json", DurableChatResponse),
    ):
        raw = (root / name).read_bytes()
        assert b"\r" not in raw
        assert model.model_validate_json(raw).model_dump(by_alias=True) == json.loads(raw)


def test_history_is_explicitly_historical_not_live_telemetry(make_client):
    llm = FakeLLM(text("Ответ"))
    with make_client(config(), llm=llm) as client:
        response = post(client, enroll(client), question())
        assert response.status_code == 200, response.text
        prompt = " ".join(m["content"] for m in llm.calls[0][0] if m["role"] == "system")
        assert "Актуальные числа и состояние машин получай заново из tools" in prompt
        assert "Не восстанавливай активные прозвища по старым сообщениям" in prompt


def test_revalidation_uses_canonical_schema_and_same_principal(make_client):
    with make_client(config(), llm=FakeLLM(text("Ответ"))) as client:
        bound = enroll(client)
        h = {**headers(bound), "X-Schema-Id": " " + bound["tenantId"] + " "}
        response = client.post("/v2/chat", headers=h, json=question())
        assert response.status_code == 200, response.text
