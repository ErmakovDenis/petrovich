"""Проверка с сохранением через внешний сервис «Petrovich AI» (лента событий) — та же логика, что в приложении:
лента — основной источник; при недоступности (сеть, 5xx, отказ авторизации) — обычный путь стенда (правила +
аналитика), повторного обращения к ленте в паузу FS_PETROVICH_AI_RETRY_PAUSE_SECONDS нет. Бот получает события
ленты через outbox, как события правил."""

from datetime import UTC, datetime, timedelta
from urllib.parse import quote

from fleet_service.background.crypto import generate_key

from .conftest import AUTH, test_settings
from .fakes import FAKE_AI_PASSWORD, FAKE_AI_USER, PETROVICH_AI_EVENTS

OFFSET = 300
KEY = generate_key()
BOT_TOKEN = "bot-secret"
BOT = {"Authorization": f"Bearer {BOT_TOKEN}"}
CHAT = "1001"
DEVICE = "device-aaaaaaaaaaaa"
SCAN_UTC = datetime(2026, 9, 16, 4, 0)
OVERHEAT = "rule|overheat|veh-2|TemperatureCOOL|2026-09-16T01:30Z"
# Хранилище строит канонический id из источника, типа, машины, параметра и времени (store/repository.py):
# сырой id ленты (27913) в нём не сохраняется, повторные проверки дедуплицируются по каноническому.
DRAIN = "api|fuel_drain|veh-1|fuel_drain|2026-09-16T01:10Z"
TOWING = "api|towing|veh-2|towing|2026-09-16T02:20Z"


def settings(**overrides):
    values = {"petrovich_ai_url": "http://fakes/petrovich", "petrovich_ai_user": FAKE_AI_USER,
              "petrovich_ai_password": FAKE_AI_PASSWORD}
    values.update(overrides)
    return test_settings(**values)


def feed_on(fakes) -> None:
    fakes.state.petrovich_ai = "ok"
    fakes.state.petrovich_ai_events = PETROVICH_AI_EVENTS


def scan(client, frm: str = "2026-09-16T06:00:00", to: str = "2026-09-16T12:00:00", vehicles=None) -> dict:
    body = {"from": frm, "to": to, "utcOffsetMinutes": OFFSET}
    if vehicles is not None:
        body["vehicleIds"] = list(vehicles)
    r = client.post("/v1/anomalies/scan", json=body, headers=AUTH)
    assert r.status_code == 200, r.text
    return r.json()


def listing(client, **params) -> dict:
    r = client.get("/v1/anomalies", params={"utcOffsetMinutes": OFFSET, **params}, headers=AUTH)
    assert r.status_code == 200, r.text
    return r.json()


def all_ids(data: dict) -> set[str]:
    return {a["id"] for r in data["results"] for a in r["anomalies"]}


def test_scan_uses_feed_events(fakes, make_client):
    feed_on(fakes)
    with make_client(settings()) as client:
        data = scan(client)
    by_vehicle = {r["vehicleId"]: r for r in data["results"]}
    drain = by_vehicle["veh-1"]["anomalies"][0]
    assert drain["id"] == DRAIN
    assert drain["severity"] == "CRITICAL" and drain["title"] == "Слив топлива"
    assert drain["source"] == "API Петрович AI"
    assert drain["eventTime"] == "2026-09-16T06:10" and drain["value"] == 42.5
    towing = by_vehicle["veh-2"]["anomalies"][0]
    assert towing["id"] == TOWING and towing["severity"] == "WARNING"
    assert towing["eventTime"] == "2026-09-16T07:20" and towing["vehicleId"] == "veh-2"
    # Машина ленты, которой нет в схеме, пропущена; перегрева veh-2 из AutoGRAPH нет — правила не участвовали.
    assert "api|fuel_drain|27915" not in all_ids(data) and OVERHEAT not in all_ids(data)


def test_feed_events_saved_and_deduplicated(fakes, make_client):
    feed_on(fakes)
    with make_client(settings()) as client:
        first = scan(client)
        by_vehicle = {r["vehicleId"]: r for r in first["results"]}
        assert by_vehicle["veh-1"]["newIds"] == [DRAIN]
        stored = {a["id"] for a in listing(client)["items"]}
        assert {DRAIN, TOWING} <= stored
        # Повторная проверка тех же событий — без дублей и без новых id.
        again = {r["vehicleId"]: r for r in scan(client)["results"]}
        assert again["veh-1"]["newIds"] == [] and again["veh-1"]["anomalies"][0]["id"] == DRAIN


def test_scan_falls_back_to_rules_when_feed_down(fakes, make_client):
    feed_on(fakes)
    with make_client(settings()) as client:
        scan(client)
        fakes.state.petrovich_ai = "down"
        down = scan(client)
        ids = all_ids(down)
        assert OVERHEAT in ids and not any(i.startswith("api|") for i in ids)
        # Пауза: повторная проверка сразу после сбоя к ленте не обращается.
        calls = fakes.state.calls["ai_events"]
        scan(client)
        assert fakes.state.calls["ai_events"] == calls


def test_feed_rejects_credentials_and_falls_back(make_client):
    with make_client(settings(petrovich_ai_password="wrong")) as client:
        ids = all_ids(scan(client))
        assert OVERHEAT in ids and not any(i.startswith("api|") for i in ids)


def test_feed_disabled_keeps_rules_path(fakes, make_client):
    # Без адреса и кредов лента не используется, даже когда подставной сервис отдаёт события.
    feed_on(fakes)
    with make_client(test_settings()) as client:
        ids = all_ids(scan(client))
        assert OVERHEAT in ids and not any(i.startswith("api|") for i in ids)
        assert fakes.state.calls["ai_events"] == 0


# --- бот получает события ленты ---

def as_user(name: str | None = "Пётр", headers=AUTH) -> dict:
    return {**headers, **({"X-User-Name": quote(name)} if name else {})}


def grant(client) -> None:
    r = client.post("/v1/background/access", json={"deviceId": DEVICE, "utcOffsetMinutes": OFFSET},
                    headers=as_user())
    assert r.status_code == 200, r.text


def connect(client, chat_id: str = CHAT) -> None:
    r = client.post("/v1/telegram/link", json={"deviceId": DEVICE, "schemaName": "Колонна",
                                               "utcOffsetMinutes": OFFSET}, headers=as_user())
    assert r.status_code == 200, r.text
    code = r.json()["url"].split("?start=")[1]
    r = client.post("/v1/telegram/bot/connect", json={"chatId": chat_id, "code": code}, headers=BOT)
    assert r.status_code == 200, r.text


def background_scan(client, now=SCAN_UTC) -> None:
    background = client.app.state.background
    background.clock = lambda: now
    client.portal.call(background.run_once)


def at_local_hour(hour: int) -> int:
    """Сегодня в [hour]:00 по местному времени пользователя, epoch millis — как хелпер в test_telegram."""
    day = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    return int((day + timedelta(hours=hour) - timedelta(minutes=OFFSET)).timestamp() * 1000)


def outbox(client, local_hour: int = 12) -> dict:
    client.app.state.telegram_notifier.clock = lambda: at_local_hour(local_hour)
    r = client.post("/v1/telegram/bot/outbox", headers=BOT)
    assert r.status_code == 200, r.text
    return r.json()


def test_bot_outbox_carries_feed_events(fakes, make_client):
    feed_on(fakes)
    with make_client(settings(access_encryption_key=KEY, telegram_bot_username="petrovich_test_bot",
                              telegram_service_token=BOT_TOKEN, auth_cache_ttl_seconds=0),
                     background_loop=False) as client:
        grant(client)
        connect(client)
        assert outbox(client)["notices"] == []
        background_scan(client)
        notices = outbox(client)["notices"]
        assert {n["anomaly"]["id"] for n in notices} == {DRAIN, TOWING}
        assert {n["chatId"] for n in notices} == {CHAT}
        # Повторный outbox без новой проверки — уведомлений нет.
        assert outbox(client)["notices"] == []


def test_bot_falls_back_to_rules_when_feed_down(fakes, make_client):
    feed_on(fakes)
    with make_client(settings(access_encryption_key=KEY, telegram_bot_username="petrovich_test_bot",
                              telegram_service_token=BOT_TOKEN, auth_cache_ttl_seconds=0),
                     background_loop=False) as client:
        grant(client)
        connect(client)
        fakes.state.petrovich_ai = "down"
        background_scan(client)
        notices = outbox(client)["notices"]
        assert {n["anomaly"]["id"] for n in notices} == {
            "rule|overheat|veh-2|TemperatureCOOL|2026-09-16T01:30Z",
            "rule|overheat|veh-2|TemperatureCOOL|2026-09-16T02:30Z",
            "rule|overheat|veh-2|TemperatureCOOL|2026-09-16T03:30Z",
        }
