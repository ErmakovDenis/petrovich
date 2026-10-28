"""Telegram-бот на стенде: ссылка подключения из приложения, подключение чата по коду, запросы бота к /v1/* от имени
пользователя чата по доступу фоновой проверки, подписка и выдача уведомлений."""

from datetime import UTC, datetime, timedelta
from urllib.parse import quote

import pytest

from fleet_service.api.deps import NOT_LINKED
from fleet_service.background.crypto import generate_key
from fleet_service.telegram.sessions import NO_ACCESS

from .conftest import AUTH, ask, test_settings
from .fakes import LOGIN_PASSWORD, OTHER_TOKEN, SCHEMA_ID, VALID_TOKEN

KEY = generate_key()
BOT_TOKEN = "bot-secret"
BOT = {"Authorization": f"Bearer {BOT_TOKEN}"}
DEVICE = "device-aaaaaaaaaaaa"
OTHER_DEVICE = "device-bbbbbbbbbbbb"
OFFSET = 300
CHAT = "1001"
# Окно фоновой проверки — 06:00–09:00 16.09 по местному времени (UTC+5): перегрев veh-2 в 06:30, 07:30 и 08:30.
SCAN_UTC = datetime(2026, 9, 16, 4, 0)


def chat(chat_id: str = CHAT) -> dict:
    return {**BOT, "X-Telegram-Chat-Id": chat_id}


def settings(**overrides):
    # Без кэша проверки сессии: истёкший токен виден сразу.
    values = {"access_encryption_key": KEY, "telegram_bot_username": "petrovich_test_bot",
              "telegram_service_token": BOT_TOKEN, "auth_cache_ttl_seconds": 0}
    values.update(overrides)
    return test_settings(**values)


@pytest.fixture
def client(make_client):
    with make_client(settings(), background_loop=False) as c:
        yield c


def as_user(name: str | None = "Пётр", headers=AUTH) -> dict:
    return {**headers, **({"X-User-Name": quote(name)} if name else {})}


def grant(client, device=DEVICE, name="Пётр", headers=AUTH, password=None) -> None:
    body = {"deviceId": device, "utcOffsetMinutes": OFFSET, "savePassword": password is not None}
    if password is not None:
        body["password"] = password
    r = client.post("/v1/background/access", json=body, headers=as_user(name, headers))
    assert r.status_code == 200, r.text


def link(client, device=DEVICE, name="Пётр", headers=AUTH):
    return client.post("/v1/telegram/link", json={"deviceId": device, "schemaName": "Колонна", "utcOffsetMinutes": OFFSET},
                       headers=as_user(name, headers))


def connect(client, chat_id=CHAT, **kwargs) -> dict:
    r = link(client, **kwargs)
    assert r.status_code == 200, r.text
    code = r.json()["url"].split("?start=")[1]
    r = client.post("/v1/telegram/bot/connect", json={"chatId": chat_id, "code": code}, headers=BOT)
    assert r.status_code == 200, r.text
    return r.json()


def scan(client, now=SCAN_UTC) -> None:
    background = client.app.state.background
    background.clock = lambda: now
    client.portal.call(background.run_once)


def at_local_hour(hour: int) -> int:
    """Сегодня в [hour]:00 по местному времени пользователя, epoch millis."""
    day = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    return int((day + timedelta(hours=hour) - timedelta(minutes=OFFSET)).timestamp() * 1000)


def outbox(client, local_hour: int = 12) -> dict:
    client.app.state.telegram_notifier.clock = lambda: at_local_hour(local_hour)
    r = client.post("/v1/telegram/bot/outbox", headers=BOT)
    assert r.status_code == 200, r.text
    return r.json()


def subscribe(client, chat_id=CHAT, **changes) -> dict:
    current = client.get("/v1/telegram/me", headers=chat(chat_id)).json()["subscription"]
    r = client.put("/v1/telegram/me/subscription", json={**current, **changes}, headers=chat(chat_id))
    assert r.status_code == 200, r.text
    return r.json()


# --- подключение ---


def test_bot_is_off_without_settings(make_client):
    with make_client(settings(telegram_service_token=None), background_loop=False) as client:
        grant(client)
        assert link(client).status_code == 503
        assert client.post("/v1/telegram/bot/outbox", headers=BOT).status_code == 503
    with make_client(settings(), background_loop=False) as client:
        assert client.get("/health").json()["telegramConfigured"] is True


def test_link_needs_background_access_of_this_user(client):
    assert link(client).status_code == 409
    grant(client)
    assert link(client, name=None).status_code == 422
    # Чужое устройство (другой логин) — не основание для ссылки.
    assert link(client, name="Иван", headers={**AUTH, "Authorization": f"Bearer {OTHER_TOKEN}"}).status_code == 409
    r = link(client)
    assert r.status_code == 200
    assert r.json()["url"].startswith("https://t.me/petrovich_test_bot?start=")


def test_code_connects_chat_once(client):
    grant(client)
    view = connect(client)
    assert (view["schemaId"], view["schemaName"], view["userName"], view["utcOffsetMinutes"]) == \
        (SCHEMA_ID, "Колонна", "Пётр", OFFSET)
    assert view["subscription"]["vehicleIds"] is None and view["subscription"]["warnings"] is True
    code = link(client).json()["url"].split("?start=")[1]
    assert client.post("/v1/telegram/bot/connect", json={"chatId": "2002", "code": code},
                       headers={"Authorization": "Bearer wrong"}).status_code == 401
    assert client.post("/v1/telegram/bot/connect", json={"chatId": "2002", "code": code}, headers=BOT).status_code == 200
    # Код одноразовый.
    assert client.post("/v1/telegram/bot/connect", json={"chatId": "3003", "code": code}, headers=BOT).status_code == 404


def test_expired_code_is_refused(client):
    grant(client)
    code = link(client).json()["url"].split("?start=")[1]
    links = client.app.state.telegram_links
    assert client.portal.call(lambda: links.redeem(code, CHAT, at=10**15)) is None


def test_code_is_stored_only_as_hash(client, store_db):
    grant(client)
    code = link(client).json()["url"].split("?start=")[1]
    raw = b"".join(p.read_bytes() for p in store_db.parent.glob("fleet.db*"))
    assert code.encode() not in raw


# --- запросы бота от имени пользователя ---


def test_bot_works_as_linked_user(client):
    grant(client)
    connect(client)
    r = client.get("/v1/vehicles", headers=chat())
    assert r.status_code == 200 and [v["id"] for v in r.json()] == ["veh-1", "veh-2"]
    # Не подключённый чат и чужой ключ.
    r = client.get("/v1/vehicles", headers=chat("9999"))
    assert r.status_code == 401 and r.json()["detail"] == NOT_LINKED
    assert client.get("/v1/vehicles", headers={"Authorization": f"Bearer {VALID_TOKEN}",
                                               "X-Telegram-Chat-Id": CHAT}).status_code == 401


def test_decision_from_bot_is_signed_by_linked_user(client):
    grant(client)
    connect(client)
    scan(client)
    [first, *_] = client.get("/v1/anomalies", params={"utcOffsetMinutes": OFFSET}, headers=chat()).json()["items"]
    path = f"/v1/anomalies/{quote(first['id'], safe='')}"
    # Заголовок X-User-Name от бота не действует: автор — пользователь подключения.
    r = client.post(f"{path}/resolve", json={"resolution": "FALSE_ALARM", "reason": "Уже известно",
                                             "utcOffsetMinutes": OFFSET},
                    headers={**chat(), "X-User-Name": quote("Иван")})
    assert r.status_code == 200 and r.json()["resolvedBy"] == "Пётр"
    # Решение видно в приложении.
    r = client.get(path, params={"utcOffsetMinutes": OFFSET}, headers=AUTH)
    assert r.status_code == 200 and r.json()["falseAlarmReason"] == "Уже известно"
    assert client.get("/v1/anomalies/nope", params={"utcOffsetMinutes": OFFSET}, headers=chat()).status_code == 404


def test_bot_cannot_manage_background_access_or_links(client):
    grant(client)
    connect(client)
    body = {"deviceId": OTHER_DEVICE, "utcOffsetMinutes": OFFSET, "savePassword": False}
    assert client.post("/v1/background/access", json=body, headers=chat()).status_code == 403
    assert client.post("/v1/background/access/revoke", json={"deviceId": DEVICE}, headers=chat()).status_code == 403
    assert client.post("/v1/telegram/link", json={"deviceId": DEVICE, "utcOffsetMinutes": OFFSET},
                       headers=chat()).status_code == 403


def test_chat_through_bot(client):
    grant(client)
    connect(client)
    r = client.post("/v1/chat", json=ask(), headers=chat())
    assert r.status_code == 200 and r.json()["reply"]


def test_expired_token_without_password_means_no_access(client, fakes):
    grant(client)
    connect(client)
    fakes.state.expired.add(VALID_TOKEN)
    r = client.get("/v1/vehicles", headers=chat())
    assert r.status_code == 401 and r.json()["detail"] == NO_ACCESS
    # Приложение вошло заново и обновило доступ — бот снова работает.
    grant(client, headers={**AUTH, "Authorization": f"Bearer {OTHER_TOKEN}"})
    assert client.get("/v1/vehicles", headers=chat()).status_code == 200


def test_stored_password_lets_bot_work_without_phone(client, fakes):
    grant(client, password=LOGIN_PASSWORD)
    connect(client)
    fakes.state.expired.add(VALID_TOKEN)
    assert client.get("/v1/vehicles", headers=chat()).status_code == 200
    assert len(fakes.state.logins) == 2


# --- подписка ---


def test_me_subscription_and_unlink(client):
    grant(client)
    connect(client)
    me = client.get("/v1/telegram/me", headers=chat()).json()
    assert [v["id"] for v in me["vehicles"]] == ["veh-1", "veh-2"] and me["vehiclesError"] is None
    sub = subscribe(client, vehicleIds=["veh-2"], info=True, dailyLimit=None, quietFromHour=None)["subscription"]
    assert (sub["vehicleIds"], sub["info"], sub["dailyLimit"], sub["quietFromHour"]) == (["veh-2"], True, None, None)
    assert client.put("/v1/telegram/me/subscription", json={"dailyLimit": 0}, headers=chat()).status_code == 422
    # Переподключение тем же пользователем сохраняет подписку.
    connect(client)
    assert client.get("/v1/telegram/me", headers=chat()).json()["subscription"]["vehicleIds"] == ["veh-2"]
    assert client.delete("/v1/telegram/me", headers=chat()).status_code == 204
    assert client.get("/v1/telegram/me", headers=chat()).status_code == 401


def test_me_without_access_still_answers(client, fakes):
    grant(client)
    connect(client)
    fakes.state.expired.add(VALID_TOKEN)
    me = client.get("/v1/telegram/me", headers=chat())
    assert me.status_code == 200 and me.json()["vehicles"] == [] and me.json()["vehiclesError"] == NO_ACCESS


def pair_code(client, chat_id=CHAT, title="@petr") -> str:
    r = client.post("/v1/telegram/bot/pair-code", json={"chatId": chat_id, "title": title}, headers=BOT)
    assert r.status_code == 200, r.text
    return r.json()["code"]


def pair(client, code, device=DEVICE, name="Пётр", headers=AUTH):
    return client.post("/v1/telegram/pair", json={"deviceId": device, "schemaName": "Колонна", "utcOffsetMinutes": OFFSET,
                                                  "code": code}, headers=as_user(name, headers))


def test_code_from_bot_connects_chat_in_app(client):
    code = pair_code(client)
    assert len(code) == 9 and code[4] == "-"
    assert pair(client, code).status_code == 409  # без доступа фоновой проверки боту не по чему работать
    grant(client)
    # Набран строчными, без дефиса, с пробелами — подходит.
    r = pair(client, f" {code.replace('-', '').lower()} ")
    assert r.status_code == 200, r.text
    view = r.json()
    assert (view["chatId"], view["userName"], view["schemaName"], view["title"]) == (CHAT, "Пётр", "Колонна", "@petr")
    # Код одноразовый; бот поздоровается в чате.
    assert pair(client, code).status_code == 404
    assert outbox(client)["linked"] == [CHAT]
    assert client.get("/v1/telegram/me", headers=chat()).status_code == 200


def test_new_code_replaces_old_one_and_bot_cannot_pair(client):
    grant(client)
    old = pair_code(client)
    new = pair_code(client)
    assert pair(client, old).status_code == 404
    assert client.post("/v1/telegram/pair", json={"deviceId": DEVICE, "utcOffsetMinutes": OFFSET, "code": new},
                       headers=chat()).status_code in (401, 403)
    assert pair(client, new).status_code == 200


def test_app_lists_and_configures_own_chats(client):
    grant(client)
    assert pair(client, pair_code(client)).status_code == 200
    r = client.get("/v1/telegram/chats", headers=as_user())
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["configured"] is True and data["botUrl"] == "https://t.me/petrovich_test_bot"
    assert [c["chatId"] for c in data["chats"]] == [CHAT] and [v["id"] for v in data["vehicles"]] == ["veh-1", "veh-2"]
    # Чужой пользователь своих чатов не видит и чужие не меняет.
    other = as_user("Иван", {**AUTH, "Authorization": f"Bearer {OTHER_TOKEN}"})
    assert client.get("/v1/telegram/chats", headers=other).json()["chats"] == []
    sub = {**data["chats"][0]["subscription"], "vehicleIds": ["veh-1"], "info": True, "quietFromHour": None}
    assert client.post(f"/v1/telegram/chats/{CHAT}/subscription", json=sub, headers=other).status_code == 404
    r = client.post(f"/v1/telegram/chats/{CHAT}/subscription", json=sub, headers=as_user())
    assert r.status_code == 200 and r.json()["subscription"]["vehicleIds"] == ["veh-1"]
    # То же видит бот.
    assert client.get("/v1/telegram/me", headers=chat()).json()["subscription"]["info"] is True
    # Отключение из приложения: бот сообщит в чат.
    r = client.post(f"/v1/telegram/chats/{CHAT}/unlink", headers=as_user())
    assert r.status_code == 200 and r.json()["chats"] == []
    assert client.get("/v1/telegram/me", headers=chat()).status_code == 401
    assert [m["chatId"] for m in outbox(client)["messages"]] == [CHAT]


def test_chats_when_bot_is_off(make_client):
    with make_client(settings(telegram_service_token=None), background_loop=False) as client:
        assert client.get("/v1/telegram/chats", headers=as_user()).json() == \
            {"configured": False, "botUrl": None, "chats": [], "vehicles": [], "vehiclesError": None}


# --- уведомления ---


def test_new_events_go_to_chat_once(client):
    grant(client)
    connect(client)
    assert outbox(client) == {"notices": [], "messages": [], "linked": []}
    scan(client)
    out = outbox(client)
    notices = out["notices"]
    assert [n["anomaly"]["eventTime"] for n in notices] == ["2026-09-16T06:30", "2026-09-16T07:30", "2026-09-16T08:30"]
    assert {n["chatId"] for n in notices} == {CHAT} and notices[0]["anomaly"]["severity"] == "CRITICAL"
    # «Петрович помнит»: прежние такие же события на этой машине, сначала поздние.
    assert notices[2]["previous"] == ["2026-09-16T07:30", "2026-09-16T06:30"] and notices[0]["previous"] == []
    assert outbox(client)["notices"] == []
    # Час спустя — только новое.
    scan(client, SCAN_UTC + timedelta(hours=1))
    assert [n["anomaly"]["eventTime"] for n in outbox(client)["notices"]] == ["2026-09-16T09:30"]


def test_events_found_before_connection_are_not_sent(client):
    grant(client)
    scan(client)
    connect(client)
    assert outbox(client)["notices"] == []


def test_resolved_and_unselected_vehicles_are_not_sent(client):
    grant(client)
    connect(client)
    subscribe(client, vehicleIds=["veh-1"])
    scan(client)
    assert outbox(client)["notices"] == []


def test_warnings_are_skipped_in_quiet_hours(make_client):
    # 108 °C ниже порога «критично» 110 °C — предупреждения.
    with make_client(settings(rules={"overheat_critical_celsius": 110}), background_loop=False) as client:
        grant(client)
        connect(client)
        scan(client)
        # 23:00 — тихие часы 22–07 по умолчанию: предупреждения пропущены и потом не досылаются.
        assert outbox(client, local_hour=23)["notices"] == []
        assert outbox(client, local_hour=12)["notices"] == []


def test_critical_comes_in_quiet_hours(client):
    grant(client)
    connect(client)
    scan(client)
    assert len(outbox(client, local_hour=23)["notices"]) == 3


def test_daily_limit_and_switched_off_warnings(make_client):
    with make_client(settings(rules={"overheat_critical_celsius": 110}), background_loop=False) as client:
        grant(client)
        connect(client)
        subscribe(client, dailyLimit=2)
        scan(client)
        assert len(outbox(client)["notices"]) == 2
        scan(client, SCAN_UTC + timedelta(hours=1))
        assert outbox(client)["notices"] == []
        connect(client, chat_id="2002")
        subscribe(client, chat_id="2002", warnings=False)
        scan(client, SCAN_UTC + timedelta(hours=2))
        assert [n["chatId"] for n in outbox(client)["notices"]] == []


def test_too_many_events_are_summed_up(make_client):
    with make_client(settings(telegram_notify_max_per_chat=1), background_loop=False) as client:
        grant(client)
        connect(client)
        scan(client)
        out = outbox(client)
        assert len(out["notices"]) == 1
        assert out["messages"] == [{"chatId": CHAT, "text": "И ещё 2 события ждут решения — /problems"}]
        assert outbox(client) == {"notices": [], "messages": [], "linked": []}


def test_lost_access_is_reported_once(client, fakes):
    grant(client)
    connect(client)
    fakes.state.expired.add(VALID_TOKEN)
    assert outbox(client)["messages"] == [{"chatId": CHAT, "text": NO_ACCESS}]
    assert outbox(client)["messages"] == []
    # Доступ вернулся — следующая потеря снова сообщается.
    grant(client, headers={**AUTH, "Authorization": f"Bearer {OTHER_TOKEN}"})
    assert outbox(client)["messages"] == []
    fakes.state.expired.add(OTHER_TOKEN)
    assert outbox(client)["messages"] == [{"chatId": CHAT, "text": NO_ACCESS}]


# --- настройки из приложения: события, повторы, списки, карточка ---


def test_muted_kind_is_not_sent_even_if_critical(client):
    grant(client)
    connect(client)
    subscribe(client, mutedKinds=["overheat", " overheat ", ""])
    assert client.get("/v1/telegram/me", headers=chat()).json()["subscription"]["mutedKinds"] == ["overheat"]
    scan(client)
    assert outbox(client)["notices"] == []


def test_repeats_are_skipped_and_counted(client):
    grant(client)
    connect(client)
    subscribe(client, repeatHours=3, dailyLimit=None, quietFromHour=None)
    scan(client)
    [first] = outbox(client, local_hour=12)["notices"]
    assert first["anomaly"]["eventTime"] == "2026-09-16T06:30" and first["repeats"] == 0
    # Час спустя тот же перегрев — повтор внутри окна.
    scan(client, SCAN_UTC + timedelta(hours=1))
    assert outbox(client, local_hour=13)["notices"] == []
    # Окно прошло: приходит, с числом пропущенных повторов (07:30, 08:30, 09:30).
    scan(client, SCAN_UTC + timedelta(hours=2))
    [later] = outbox(client, local_hour=16)["notices"]
    assert later["anomaly"]["eventTime"] == "2026-09-16T10:30" and later["repeats"] == 3


def test_card_settings_go_with_notice(client):
    grant(client)
    connect(client)
    current = client.get("/v1/telegram/me", headers=chat()).json()["subscription"]
    subscribe(client, card={**current["card"], "memory": False, "reviewButton": False})
    scan(client)
    notices = outbox(client)["notices"]
    assert notices and all(n["card"]["reviewButton"] is False for n in notices)
    # «Петрович помнит» выключен — прежние события не ищутся.
    assert all(n["previous"] == [] for n in notices)


def test_problems_follow_list_settings(client):
    grant(client)
    connect(client)
    scan(client)
    r = client.get("/v1/telegram/me/problems", headers=chat())
    assert r.status_code == 200, r.text
    everything = r.json()
    assert everything["total"] == 3 and everything["lists"]["pageSize"] == 10
    assert client.get("/v1/telegram/me/problems", headers=as_user()).status_code == 403

    def problems(**lists):
        current = client.get("/v1/telegram/me", headers=chat()).json()["subscription"]
        subscribe(client, lists={**current["lists"], **lists})
        return client.get("/v1/telegram/me/problems", headers=chat()).json()

    critical = sum(a["severity"] == "CRITICAL" for a in everything["items"])
    assert problems(minSeverity="CRITICAL")["total"] == critical
    assert problems(minSeverity="INFO", pageSize=3, order="severity")["items"][0]["severity"] == "CRITICAL"
    # События 16.09 — старше суток.
    assert problems(periodHours=24)["total"] == 0
    subscribe(client, vehicleIds=["veh-1"])
    assert problems(periodHours=None)["total"] == 3  # все машины, что видны, — выбор машин не действует
    assert problems(subscribedOnly=True)["total"] == 0
    # Выключенное событие не показывается и без «только выбранные машины».
    subscribe(client, vehicleIds=None, mutedKinds=["overheat"])
    assert problems(subscribedOnly=False)["total"] == 0

