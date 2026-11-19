"""P0: отдельное подтверждение личного Telegram-чата; ни старый pairing, ни user_name не дают identity."""

from concurrent.futures import ThreadPoolExecutor

import pytest
from sqlalchemy import select, update

from fleet_service.autograph.errors import AutoGraphUnavailable
from fleet_service.background.crypto import generate_key
from fleet_service.store.db import (
    identity_credentials as C, identity_telegram_codes as Q, identity_telegram_links as L,
    principal_scopes as S, principals as P,
)
from fleet_service.store.repository import now_ms

from .conftest import AUTH, test_settings
from .test_identity_memory import enroll, headers
from .test_telegram import grant

KEY = generate_key()
BOT = {"Authorization": "Bearer identity-bot-fake"}
CHAT = "1001"


def config(**overrides):
    values = dict(identity_enabled=True, identity_account_lifecycle="managed", access_encryption_key=KEY, telegram_bot_username="fake_bot",
                  telegram_service_token="identity-bot-fake")
    values.update(overrides)
    return test_settings(**values)


def peer(chat_id=CHAT, **overrides):
    return {**BOT, "X-Telegram-Chat-Id": chat_id, "X-Telegram-User-Id": chat_id,
            "X-Telegram-Chat-Type": "private", **overrides}


def code(client, chat_id=CHAT):
    result = client.post("/v2/identity/telegram/pair-code", headers=BOT,
                         json={"chatId": chat_id, "telegramUserId": chat_id, "chatType": "private"})
    assert result.status_code == 200, result.text
    assert result.headers["cache-control"] == "no-store"
    return result.json()["code"]


def pair(client, bound, value, **body):
    return client.post("/v2/identity/telegram/pair", headers=headers(bound), json={"code": value, "consent": True, **body})


def attach(client, bound, chat_id=CHAT):
    result = pair(client, bound, code(client, chat_id))
    assert result.status_code == 200, result.text
    return result.json()


def status(client, h=None):
    return client.get("/v2/identity/telegram/me", headers=h or peer())


def test_private_chat_bound_to_verified_principal_survives_restart(make_client):
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        linked = attach(client, bound)
        assert linked["chatId"] == CHAT and linked["expiresAt"] == bound["expiresAt"]
        assert status(client).json() == {"principalId": bound["principalId"], "tenantId": "schema-1"}
        assert client.get("/v2/identity/telegram", headers=headers(bound)).json()["links"] == [linked]
        with client.app.state.store._engine.connect() as conn:
            raw = repr([dict(r) for table in (Q, L) for r in conn.execute(select(table)).mappings()])
        assert bound["credential"] not in raw and bound["autographSession"] not in raw
    with make_client(config(), background_loop=False) as client:
        assert status(client).json()["principalId"] == bound["principalId"]


def test_code_is_single_use_expires_and_is_only_stored_as_hash(make_client):
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        value = code(client)
        with client.app.state.store._engine.connect() as conn:
            assert value not in repr(conn.execute(select(Q)).all())
        assert pair(client, bound, value).status_code == 200
        assert pair(client, bound, value).status_code == 404
        value = code(client)
        with client.app.state.store._engine.begin() as conn:
            conn.execute(update(Q).values(expires_at=now_ms() - 1))
        assert pair(client, bound, value).status_code == 404


def test_new_code_invalidates_previous_code(make_client):
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        first, second = code(client), code(client)
        assert pair(client, bound, first).status_code == 404
        assert pair(client, bound, second).status_code == 200


@pytest.mark.parametrize("body,expected", [
    ({"chatId": "-1001", "telegramUserId": "1001", "chatType": "private"}, 422),
    ({"chatId": "1001", "telegramUserId": "1001", "chatType": "group"}, 422),
    ({"chatId": "1001", "telegramUserId": "1002", "chatType": "private"}, 403),
])
def test_groups_or_other_users_cannot_create_personal_code(make_client, body, expected):
    with make_client(config(), background_loop=False) as client:
        assert client.post("/v2/identity/telegram/pair-code", headers=BOT, json=body).status_code == expected
        assert status(client, peer(**{"X-Telegram-Chat-Type": "group"})).status_code == 403


def test_bot_key_and_explicit_private_peer_are_required(make_client):
    with make_client(config(), background_loop=False) as client:
        assert status(client, {**peer(), "Authorization": "Bearer forged"}).status_code == 401
        assert status(client, BOT).status_code == 403
        assert status(client, peer(**{"X-Telegram-User-Id": "1002"})).status_code == 403


def test_consent_and_verified_app_session_required_for_pairing(make_client):
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        value = code(client)
        for consent in (False, 1, None):
            assert pair(client, bound, value, consent=consent).status_code == 422
        assert client.post("/v2/identity/telegram/pair", headers=AUTH,
                           json={"code": value, "consent": True}).status_code == 401
        assert pair(client, bound, value, principalId="forged").status_code == 422
        assert pair(client, bound, value).status_code == 200


def test_unverified_old_link_and_username_do_not_grant_identity(make_client):
    with make_client(config(), background_loop=False) as client:
        # Старое подключение создаётся обычными API, без персонального opt-in.
        grant(client)
        from urllib.parse import quote
        response = client.post("/v1/telegram/link", headers={**AUTH, "X-User-Name": quote("Пётр")},
                               json={"deviceId": "device-aaaaaaaaaaaa", "schemaName": "Тест", "utcOffsetMinutes": 300})
        legacy_code = response.json()["url"].split("?start=")[1]
        assert client.post("/v1/telegram/bot/connect", headers=BOT,
                           json={"chatId": CHAT, "code": legacy_code}).status_code == 200
        assert status(client, {**peer(), "X-User-Name": quote("Пётр")}).status_code == 401
        bound = enroll(client)
        assert pair(client, bound, legacy_code).status_code == 404


def test_revocation_of_source_credential_removes_telegram_access(make_client, fakes):
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        attach(client, bound)
        fakes.state.expired.add(bound["autographSession"])
        assert client.post("/v2/identity/revoke", headers=headers(bound)).status_code == 204
        assert status(client).status_code == 401
        with client.app.state.store._engine.connect() as conn:
            assert conn.execute(select(L)).all() == []


def test_other_principal_cannot_list_or_unlink_personal_chat(make_client):
    with make_client(config(), background_loop=False) as client:
        a, b = enroll(client), enroll(client, "Иван")
        attach(client, a)
        assert client.get("/v2/identity/telegram", headers=headers(b)).json() == {"links": []}
        assert client.delete(f"/v2/identity/telegram/links/{CHAT}", headers=headers(b)).status_code == 204
        assert status(client).json()["principalId"] == a["principalId"]
        assert client.delete(f"/v2/identity/telegram/links/{CHAT}", headers=headers(a)).status_code == 204
        assert status(client).status_code == 401


def test_bot_unlink_does_not_need_autograph_and_removes_pending_code(make_client, fakes):
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        attach(client, bound)
        value = code(client)
        fakes.state.expired.add(bound["autographSession"])
        assert client.delete("/v2/identity/telegram/me", headers=peer()).status_code == 204
        assert status(client).status_code == 401
        fakes.state.expired.clear()
        assert pair(client, bound, value).status_code == 404


def test_credential_expiry_and_disabled_principal_or_scope_close_access(make_client):
    for table, values in [(C, {"expires_at": now_ms() - 1}), (P, {"disabled_at": now_ms()}), (S, {"revoked_at": now_ms()})]:
        with make_client(config(), background_loop=False) as client:
            bound = enroll(client)
            attach(client, bound)
            with client.app.state.store._engine.begin() as conn:
                conn.execute(update(table).values(**values))
            assert status(client).status_code == 401
        # Следующий сценарий — тот же тестовый файл БД: снимаем блокировку principal/scope для нового входа.
        if table in (P, S):
            from fleet_service.store.db import create_db_engine
            engine = create_db_engine(config().db_url)
            with engine.begin() as conn:
                conn.execute(update(table).values(**{key: None for key in values}))
            engine.dispose()


def test_current_autograph_rights_and_outage_are_not_replaced_by_old_background_access(make_client):
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        attach(client, bound)
        async def no_access(session):
            return set()
        client.app.state.autograph_client.schema_ids = no_access
        assert status(client).status_code == 403
        async def unavailable(session):
            raise AutoGraphUnavailable()
        client.app.state.autograph_client.schema_ids = unavailable
        assert status(client).status_code == 503


def test_ciphertext_tampering_is_closed(make_client):
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        attach(client, bound)
        with client.app.state.store._engine.begin() as conn:
            conn.execute(update(L).values(chat_id="1002", telegram_user_id="1002"))
        assert status(client, peer("1002")).status_code == 401


def test_pair_code_cannot_be_redeemed_twice_concurrently(make_client):
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        value = code(client)
        with ThreadPoolExecutor(max_workers=2) as pool:
            codes = list(pool.map(lambda _: pair(client, bound, value).status_code, range(2)))
        assert sorted(codes) == [200, 404]


def test_wrong_codes_are_rate_limited(make_client):
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        for _ in range(5):
            assert pair(client, bound, "ABCDEFGH").status_code == 404
        assert pair(client, bound, "ABCDEFGH").status_code == 429


def test_personal_pairing_off_does_not_change_old_bot_api(make_client):
    with make_client(config(identity_enabled=False), background_loop=False) as client:
        assert client.post("/v2/identity/telegram/pair-code", headers=BOT,
                           json={"chatId": CHAT, "telegramUserId": CHAT, "chatType": "private"}).status_code == 503
        assert client.post("/v1/telegram/bot/pair-code", headers=BOT, json={"chatId": CHAT}).status_code == 200


def test_legacy_username_spoof_cannot_unlink_verified_personal_profile(make_client):
    from urllib.parse import quote
    from .fakes import OTHER_TOKEN
    with make_client(config(), background_loop=False) as client:
        grant(client)
        r = client.post("/v1/telegram/link", headers={**AUTH, "X-User-Name": quote("Пётр")},
                        json={"deviceId": "device-aaaaaaaaaaaa", "schemaName": "Тест", "utcOffsetMinutes": 300})
        legacy = r.json()["url"].split("?start=")[1]
        assert client.post("/v1/telegram/bot/connect", headers=BOT, json={"chatId": CHAT, "code": legacy}).status_code == 200
        bound = enroll(client)
        attach(client, bound)
        forged = {"Authorization": "Bearer " + OTHER_TOKEN, "X-Schema-Id": "schema-1", "X-User-Name": quote("Пётр")}
        assert client.post(f"/v1/telegram/chats/{CHAT}/unlink", headers=forged).status_code == 409
        assert status(client).json()["principalId"] == bound["principalId"]


def test_legacy_repair_invalidates_old_personal_link(make_client):
    from urllib.parse import quote
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        attach(client, bound)
        grant(client)
        r = client.post("/v1/telegram/link", headers={**AUTH, "X-User-Name": quote("Пётр")},
                        json={"deviceId": "device-aaaaaaaaaaaa", "schemaName": "Тест", "utcOffsetMinutes": 300})
        legacy = r.json()["url"].split("?start=")[1]
        assert client.post("/v1/telegram/bot/connect", headers=BOT, json={"chatId": CHAT, "code": legacy}).status_code == 200
        assert status(client).status_code == 401


def test_real_bot_stand_client_works_with_personal_identity_api(make_client, monkeypatch):
    from pathlib import Path
    import httpx
    monkeypatch.syspath_prepend(str(Path(__file__).resolve().parents[2] / "telegram_bot" / "src"))
    from telegram_bot.stand import Stand, StandError
    with make_client(config(), background_loop=False) as client:
        bound = enroll(client)
        async def bot_call(action):
            http = httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app))
            bot = Stand("http://stand-fake", "identity-bot-fake", 1, http)
            try:
                return await getattr(bot, action)(1001, 1001)
            finally:
                await bot.aclose()
        personal = client.portal.call(lambda: bot_call("identity_code"))
        assert pair(client, bound, personal["code"]).status_code == 200
        assert client.portal.call(lambda: bot_call("identity_me"))["principalId"] == bound["principalId"]
        client.portal.call(lambda: bot_call("identity_unlink"))
        with pytest.raises(StandError) as error:
            client.portal.call(lambda: bot_call("identity_me"))
        assert error.value.status == 401
