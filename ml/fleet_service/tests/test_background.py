"""Фоновая проверка на стенде (шаг 5): доступ по токену и паролю по согласию, шифрование в базе, расписание,
одна проверка схемы на всех пользователей, отметка проверки при недоступном AutoGRAPH, отзыв доступа, одно
уведомление на пользователя."""

import logging
from datetime import datetime
from urllib.parse import quote

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from fleet_service.background import scheduler as scheduler_module
from fleet_service.background.access import KEY_CHANGED, PasswordAction
from fleet_service.background.crypto import SealBroken, SecretBox, generate_key
from fleet_service.background.scheduler import PASSWORD_REJECTED, TOKEN_EXPIRED
from fleet_service.main import create_app

from . import fakes as fakes_module
from .conftest import AUTH, test_settings
from .fakes import LOGIN_PASSWORD, OTHER_TOKEN, SCHEMA_ID, VALID_TOKEN, create_fakes

KEY = generate_key()
DEVICE_A = "device-aaaaaaaaaaaa"
DEVICE_B = "device-bbbbbbbbbbbb"
DEVICE_C = "device-cccccccccccc"
OFFSET = 300
# 16.09.2026 09:00 по местному времени пользователя (UTC+5): окно 3 ч — 06:00–09:00, перегрев veh-2 в 06:30,
# 07:30 и 08:30.
NOW_UTC = datetime(2026, 9, 16, 4, 0)
OTHER = {"Authorization": f"Bearer {OTHER_TOKEN}", "X-Schema-Id": SCHEMA_ID}


def user(name: str | None, headers=AUTH) -> dict:
    return {**headers, **({"X-User-Name": quote(name)} if name else {})}


def settings(**overrides):
    return test_settings(access_encryption_key=KEY, **overrides)


@pytest.fixture
def client(make_client):
    with make_client(settings(), background_loop=False) as c:
        yield c


def grant(client, device=DEVICE_A, headers=None, name="Пётр", offset=OFFSET, save_password=False, password=None,
          expect=200) -> dict:
    body = {"deviceId": device, "utcOffsetMinutes": offset, "savePassword": save_password}
    if password is not None:
        body["password"] = password
    r = client.post("/v1/background/access", json=body, headers=user(name, headers or AUTH))
    assert r.status_code == expect, r.text
    return r.json()


def status(client, device=DEVICE_A, headers=AUTH) -> dict:
    r = client.get("/v1/background/access", params={"deviceId": device}, headers=headers)
    assert r.status_code == 200, r.text
    return r.json()


def run(client, now=NOW_UTC) -> list:
    background = client.app.state.background
    background.clock = lambda: now
    return client.portal.call(background.run_once)


def listing(client, headers=AUTH) -> dict:
    r = client.get("/v1/anomalies", params={"utcOffsetMinutes": OFFSET}, headers=headers)
    assert r.status_code == 200, r.text
    return r.json()


def claim(client, ids, device=DEVICE_A, name="Пётр", headers=AUTH):
    return client.post("/v1/notifications/claim", json={"deviceId": device, "ids": ids}, headers=user(name, headers))


# --- доступ ---

def test_without_key_access_is_not_accepted(make_client):
    with make_client() as client:
        assert client.get("/health").json()["backgroundConfigured"] is False
        r = client.post("/v1/background/access", json={"deviceId": DEVICE_A, "utcOffsetMinutes": OFFSET},
                        headers=AUTH)
        assert r.status_code == 503 and r.json() == {"detail": "Фоновая проверка на стенде не включена"}
        s = status(client)
        assert s["enabled"] is False and s["registered"] is False
        # Отзыв и уведомления работают и без ключа.
        assert client.post("/v1/background/access/revoke", json={"deviceId": DEVICE_A}, headers=AUTH).status_code == 200
        assert claim(client, ["x"]).json() == {"ids": ["x"]}


def test_access_needs_session_and_valid_device_id(client):
    r = client.post("/v1/background/access", json={"deviceId": DEVICE_A, "utcOffsetMinutes": OFFSET})
    assert r.status_code == 401
    r = client.post("/v1/background/access", json={"deviceId": "short", "utcOffsetMinutes": OFFSET}, headers=AUTH)
    assert r.status_code == 422


def test_token_access_is_stored_encrypted(client, store_db):
    s = grant(client)
    assert s["enabled"] is True and s["registered"] is True and s["tokenActive"] is True
    assert s["passwordStored"] is False and s["tokenSince"] and s["lastScanAt"] is None
    assert s["intervalMinutes"] == 15 and s["windowHours"] == 3
    raw = b"".join(p.read_bytes() for p in store_db.parent.glob("fleet.db*"))
    assert VALID_TOKEN.encode() not in raw and b"device-aaaaaaaaaaaa" in raw
    # Повторная выдача тем же токеном не сбрасывает «с какого момента стенд знает токен».
    assert grant(client)["tokenSince"] == s["tokenSince"]


def test_secret_box_binds_ciphertext_to_device_and_field():
    box = SecretBox(KEY)
    sealed = box.seal("секрет", "dev-1|token")
    assert box.open(sealed, "dev-1|token") == "секрет"
    for context in ("dev-2|token", "dev-1|password"):
        with pytest.raises(SealBroken):
            box.open(sealed, context)
    with pytest.raises(SealBroken):
        SecretBox(generate_key()).open(sealed, "dev-1|token")
    with pytest.raises(ValueError):
        SecretBox("короткий")


def test_window_longer_than_telemetry_limit_is_rejected():
    with pytest.raises(ValidationError):
        test_settings(background_window_hours=200)


# --- фоновая проверка ---

def test_background_scan_finds_anomalies_without_the_app(client, fakes):
    """Приложение выдало доступ и больше не запускалось: стенд сам проверяет, лента пополняется."""
    grant(client)
    [r] = run(client)
    assert (r.schema_id, r.vehicles, r.failed, r.new, r.marked) == (SCHEMA_ID, 2, 0, 3, True)
    items = listing(client)["items"]
    assert sorted(a["eventTime"] for a in items) == ["2026-09-16T06:30", "2026-09-16T07:30", "2026-09-16T08:30"]
    assert listing(client)["lastScanAt"] == status(client)["lastScanAt"]
    assert status(client)["lastOkAt"] and status(client)["lastError"] is None
    # Час спустя — одно новое событие, старые не дублируются.
    [r] = run(client, datetime(2026, 9, 16, 5, 0))
    assert r.new == 1 and listing(client)["total"] == 4


def test_two_accounts_of_one_schema_do_not_double_autograph_requests(make_client):
    """Схему открывают двое (Пётр на двух устройствах, Иван): каждая машина проверяется один раз. Тяжёлые
    запросы — как при одном пользователе; EnumDevices — по одному на пользователя (видимость машин разная)."""
    def calls(grants) -> dict:
        fakes = create_fakes()
        http = httpx.AsyncClient(transport=httpx.ASGITransport(app=fakes))
        with TestClient(create_app(settings(), http=http, background_loop=False)) as c:
            for device, headers, name, offset in grants:
                grant(c, device, headers, name, offset)
            fakes.state.calls.clear()
            [r] = run(c)
            assert r.vehicles == 2 and r.failed == 0
        return dict(fakes.state.calls)

    one = calls([(DEVICE_A, AUTH, "Пётр", OFFSET)])
    # Другой пояс у Ивана: без общей проверки телеметрия veh-1 грузилась бы второй раз (другой ключ кэша).
    many = calls([(DEVICE_A, AUTH, "Пётр", OFFSET), (DEVICE_C, AUTH, "пётр", OFFSET), (DEVICE_B, OTHER, "Иван", 180)])
    heavy = {k: v for k, v in one.items() if k.startswith(("GetTripTables", "EnumParameters"))}
    assert heavy and heavy == {k: v for k, v in many.items() if k.startswith(("GetTripTables", "EnumParameters"))}
    assert heavy["GetTripTables:veh-1"] == heavy["GetTripTables:veh-2"] == 1
    assert one["EnumDevices"] == 1 and many["EnumDevices"] == 2
    assert "Login" not in many


def test_unavailable_autograph_does_not_move_scan_mark(client, fakes):
    grant(client)
    run(client)
    mark = listing(client)["lastScanAt"]
    fakes.state.trip_tables_down = {"veh-1", "veh-2"}
    [r] = run(client, datetime(2026, 9, 16, 5, 0))
    assert r.vehicles == 2 and r.failed == 2 and r.marked is False
    assert listing(client)["lastScanAt"] == mark
    s = status(client)
    assert s["lastScanAt"] == mark and s["lastError"].startswith("AutoGRAPH не ответил ни по одной машине")
    # Ответила хотя бы одна машина — отметка двигается.
    fakes.state.trip_tables_down = {"veh-1"}
    [r] = run(client, datetime(2026, 9, 16, 6, 0))
    assert r.marked is True and listing(client)["lastScanAt"] > mark and status(client)["lastError"] is None


def test_expired_token_without_password_waits_for_the_app(client, fakes):
    grant(client)
    fakes.state.expired.add(VALID_TOKEN)
    [r] = run(client)
    assert r.usable == 0 and r.marked is False
    s = status(client, headers=OTHER)
    assert s["tokenActive"] is False and s["lastError"] == TOKEN_EXPIRED
    # Пока приложение не обновит доступ, стенд не обращается к AutoGRAPH от имени устройства.
    fakes.state.calls.clear()
    assert run(client) == [] and not fakes.state.calls
    # Приложение вошло заново и обновило доступ — проверки возобновились.
    assert grant(client, headers=OTHER)["tokenActive"] is True
    [r] = run(client)
    assert r.marked is True and r.vehicles == 1


def test_password_by_consent_lets_stand_log_in_itself(client, fakes, store_db, caplog):
    caplog.set_level(logging.DEBUG)
    s = grant(client, save_password=True, password=LOGIN_PASSWORD)
    assert s["passwordStored"] is True and fakes.state.logins == [{"UserName": "Пётр", "UTCOffset": OFFSET}]
    fakes.state.expired.add(VALID_TOKEN)
    [r] = run(client)
    assert r.marked is True and r.vehicles == 2 and r.new == 3
    assert len(fakes.state.logins) == 2 and fakes.state.logins[1]["UTCOffset"] == OFFSET
    s = status(client, headers=OTHER)
    assert s["tokenActive"] is True and s["passwordStored"] is True and s["lastError"] is None
    # Ни пароль, ни токены — ни в базе, ни в логах.
    raw = b"".join(p.read_bytes() for p in store_db.parent.glob("fleet.db*"))
    for secret in (LOGIN_PASSWORD, VALID_TOKEN, "login-token-1", "login-token-2"):
        assert secret.encode() not in raw
        assert secret not in caplog.text
    assert "Password=***" in caplog.text


def test_wrong_password_is_not_stored(client):
    r = client.post("/v1/background/access", headers=user("Пётр"),
                    json={"deviceId": DEVICE_A, "utcOffsetMinutes": OFFSET, "savePassword": True,
                          "password": "wrong-password-123"})
    assert r.status_code == 422 and "пароль на стенде не сохранён" in r.json()["detail"]
    assert "wrong-password-123" not in r.text
    assert status(client)["registered"] is False
    # Без логина входить нечем.
    r = client.post("/v1/background/access", headers=AUTH,
                    json={"deviceId": DEVICE_A, "utcOffsetMinutes": OFFSET, "savePassword": True,
                          "password": LOGIN_PASSWORD})
    assert r.status_code == 422


def test_password_kept_cleared_and_not_carried_to_another_account(client):
    grant(client, save_password=True, password=LOGIN_PASSWORD)
    # Приложение обновляет токен, не пересылая пароль.
    assert grant(client, save_password=True)["passwordStored"] is True
    assert grant(client, save_password=False)["passwordStored"] is False
    grant(client, save_password=True, password=LOGIN_PASSWORD)
    # Сменилась учётная запись — пароль прежней на стенде не остаётся.
    assert grant(client, headers=OTHER, name="Иван", save_password=True)["passwordStored"] is False


def test_rejected_stored_password_is_dropped(client, fakes, monkeypatch):
    grant(client, save_password=True, password=LOGIN_PASSWORD)
    monkeypatch.setattr(fakes_module, "LOGIN_PASSWORD", "сменили-пароль")
    fakes.state.expired.add(VALID_TOKEN)
    [r] = run(client)
    assert r.usable == 0
    s = status(client, headers=OTHER)
    assert s["passwordStored"] is False and s["lastError"] == PASSWORD_REJECTED
    assert run(client) == []


def test_revoke_stops_background_checks(client, fakes):
    grant(client, save_password=True, password=LOGIN_PASSWORD)
    r = client.post("/v1/background/access/revoke", json={"deviceId": DEVICE_A}, headers=AUTH)
    assert r.status_code == 200 and r.json()["registered"] is False
    fakes.state.calls.clear()
    assert run(client) == [] and not fakes.state.calls


def test_new_schema_replaces_device_access(client):
    grant(client)
    foreign = {"Authorization": "Bearer foreign-token", "X-Schema-Id": "schema-2"}
    assert grant(client, headers=foreign)["registered"] is True
    assert status(client)["registered"] is False
    [r] = run(client)
    assert r.schema_id == "schema-2"


def test_stale_access_is_purged(client):
    grant(client)
    access = client.app.state.access
    # Устройство B подтверждало доступ давно (приложение удалили) — запись удаляется при проходе.
    client.portal.call(lambda: access.upsert(DEVICE_B, SCHEMA_ID, "Иван", OFFSET, OTHER_TOKEN, PasswordAction.KEEP,
                                             at=1_000))
    [r] = run(client)
    assert r.grants == 1
    assert status(client, DEVICE_B)["registered"] is False


def test_changed_key_drops_stored_access(make_client, store_db):
    with make_client(settings(), background_loop=False) as client:
        grant(client, save_password=True, password=LOGIN_PASSWORD)
    with make_client(test_settings(access_encryption_key=generate_key()), background_loop=False) as client:
        s = status(client)
        assert s["registered"] is True and s["tokenActive"] is False and s["passwordStored"] is False
        assert s["lastError"] == KEY_CHANGED
        assert run(client) == []
        assert grant(client)["tokenActive"] is True


def test_interval_and_window_come_from_settings(make_client):
    with make_client(settings(background_interval_minutes=5, background_window_hours=6), background_loop=False) as c:
        s = grant(c)
        assert s["intervalMinutes"] == 5 and s["windowHours"] == 6
        [r] = run(c)
        # 03:00–09:00: перегрев в 03:30 … 08:30.
        assert r.new == 6


class _Stop(Exception):
    pass


def test_schedule_repeats_with_interval(monkeypatch, make_client):
    pauses = []

    async def fake_sleep(seconds):
        pauses.append(seconds)
        if len(pauses) == 2:
            raise _Stop

    with make_client(settings(background_interval_minutes=0.5), background_loop=False) as c:
        background = c.app.state.background
        runs = []

        async def counting():
            runs.append(1)
            return []

        monkeypatch.setattr(background, "run_once", counting)
        monkeypatch.setattr(scheduler_module.asyncio, "sleep", fake_sleep)
        with pytest.raises(_Stop):
            c.portal.call(background.run_forever)
    assert len(runs) == 2 and all(25 < p <= 30 for p in pauses)


def test_loop_starts_only_when_configured(make_client):
    with make_client(settings()) as c:
        assert c.get("/health").json()["backgroundConfigured"] is True
    with make_client(settings(background_enabled=False)) as c:
        assert c.get("/health").json()["backgroundConfigured"] is False
        r = c.post("/v1/background/access", json={"deviceId": DEVICE_A, "utcOffsetMinutes": OFFSET}, headers=AUTH)
        assert r.status_code == 503


# --- одно уведомление на пользователя ---

def test_one_notification_per_user_across_devices(client):
    assert claim(client, ["a", "b"]).json() == {"ids": ["a", "b"]}
    # Второе устройство того же пользователя (логин без учёта регистра) — только то, о чём ещё не уведомляли.
    assert claim(client, ["b", "c", "a"], device=DEVICE_B, name="пётр").json() == {"ids": ["c"]}
    # Другой пользователь схемы получает свои уведомления.
    assert claim(client, ["a"], device=DEVICE_C, name="Иван", headers=OTHER).json() == {"ids": ["a"]}
    # Без логина — по устройству.
    assert claim(client, ["a"], device=DEVICE_C, name=None).json() == {"ids": ["a"]}
    assert claim(client, ["a"], device=DEVICE_C, name=None).json() == {"ids": []}


def test_claim_limit(make_client):
    with make_client(settings(notification_claim_max_ids=2), background_loop=False) as c:
        assert claim(c, ["a", "b", "c"]).status_code == 422


def test_token_expired_while_vehicles_are_cached_still_relogs_in(client, fakes):
    """Кэш машин пережил токен: проход всё равно видит, что токен истёк, и входит по сохранённому паролю."""
    grant(client, save_password=True, password=LOGIN_PASSWORD)
    assert client.get("/v1/vehicles", headers=AUTH).status_code == 200
    fakes.state.expired.add(VALID_TOKEN)
    [r] = run(client)
    assert r.marked is True and r.failed == 0 and r.vehicles == 2


def test_foreign_schema_cannot_revoke_access(client):
    grant(client)
    foreign = {"Authorization": "Bearer foreign-token", "X-Schema-Id": "schema-2"}
    r = client.post("/v1/background/access/revoke", json={"deviceId": DEVICE_A}, headers=user("Чужой", foreign))
    assert r.status_code == 200 and r.json()["registered"] is False
    assert status(client)["registered"] is True
    # Тот же логин из другой схемы (устройство сменило схему) — может.
    r = client.post("/v1/background/access/revoke", json={"deviceId": DEVICE_A}, headers=user("пётр", foreign))
    assert r.status_code == 200 and status(client)["registered"] is False
