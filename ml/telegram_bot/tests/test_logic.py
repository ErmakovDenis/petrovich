"""Сценарии бота по макетам без Telegram и без стенда: подставной стенд в памяти."""

import asyncio
from datetime import UTC, datetime

import pytest

from telegram_bot import texts as T
from telegram_bot.logic import Petrovich, next_limit, next_quiet, toggle_kind, toggle_vehicle
from telegram_bot.refs import Refs
from telegram_bot.stand import StandError

CHAT = 1001
OFFSET = 300
# 16.09.2026 12:00 по местному времени (UTC+5).
NOW_UTC = datetime(2026, 9, 16, 7, 0)
ANOMALY_ID = "rule|overheat|veh-2|TemperatureCOOL|2026-09-16T06:40Z"


def anomaly(**extra) -> dict:
    return {"id": ANOMALY_ID, "vehicleId": "veh-2", "vehicleName": "Scania R450 К417АУ96", "severity": "CRITICAL",
            "title": "Перегрев двигателя", "eventTime": "2026-09-16T11:40",
            "description": "Температура охлаждающей жидкости 111,7 °C (норма до 100 °C)", **extra}


DAILY = {"chatId": str(CHAT), "since": "2026-09-15T12:00", "vehicles": 10, "total": 3, "critical": 3, "open": 2,
         "groups": [
             {"title": "Резкое падение уровня топлива", "count": 2, "items": [
                 {"id": ANOMALY_ID, "vehicleName": "КАМАЗ 43118", "severity": "CRITICAL",
                  "eventTime": "2026-09-16T00:55", "resolved": False},
                 {"id": "drop-2", "vehicleName": "Volvo FH 500", "severity": "CRITICAL",
                  "eventTime": "2026-09-15T20:05", "resolved": True}]},
             {"title": "Перегрев двигателя", "count": 7, "items": [
                 {"id": "rule|overheat|veh-3|T|2026-09-15T10:40Z", "vehicleName": "Scania R450", "severity": "CRITICAL",
                  "eventTime": "2026-09-15T15:40", "resolved": False}]}]}
WEEKLY = {"chatId": str(CHAT), "weekFrom": "2026-09-07", "weekTo": "2026-09-13", "total": 36, "previousTotal": 33,
          "kinds": [{"name": "Перегрев двигателя", "count": 18}, {"name": "Напряжение вне нормы", "count": 11}],
          "confirmed": 0, "falseAlarms": 0, "open": 36,
          "topVehicles": [{"name": "Scania R450 К417АУ96", "count": 9, "detail": "Перегрев двигателя"}],
          "vehicles": 10, "quietVehicles": 3}


class FakeStand:
    def __init__(self):
        self.linked = False
        self.codes = {"good-code"}
        self.items = {ANOMALY_ID: anomaly()}
        self.subscription = {"vehicleIds": None, "mutedKinds": [],
                             "reports": {"daily": True, "dailyTime": "09:30", "weekdaysOnly": False, "weekly": True,
                                         "weeklyDay": 1, "weeklyTime": "09:30", "whenEmpty": True},
                             "warnings": True, "info": False, "dailyLimit": 3, "quietFromHour": 22, "quietToHour": 7}
        self.chats: list[dict] = []
        self.chat_reply = {"reply": "В 11:40 температура поднялась до 111,7 °C.", "drafts": []}
        self.email_error: StandError | None = None
        self.outbox_data = {"notices": [], "messages": []}
        self.pair_titles: list[str | None] = []
        self.chat_error: StandError | None = None
        self.lists = {"subscribedOnly": False, "minSeverity": "INFO", "periodHours": None, "pageSize": 10,
                      "order": "newest"}

    def _linked(self):
        if not self.linked:
            raise StandError(401, "Чат не подключён")

    async def connect(self, chat_id, code, title=None):
        if code not in self.codes:
            raise StandError(404, "Ссылка устарела или уже использована — возьмите новую в приложении")
        self.codes.discard(code)
        self.linked = True
        return {}

    async def pair_code(self, chat_id, title=None):
        self.pair_titles.append(title)
        return {"code": "K7MX-2QPD", "expiresAt": int(NOW_UTC.replace(tzinfo=UTC).timestamp() * 1000) + 15 * 60_000}

    async def me(self, chat_id):
        self._linked()
        return {"chatId": str(chat_id), "schemaId": "s-1", "schemaName": "Колонна <1>", "userName": "ivanov",
                "utcOffsetMinutes": OFFSET, "subscription": dict(self.subscription), "linkedAt": 0,
                "vehicles": [{"id": "veh-1", "name": "КАМАЗ 43118"}, {"id": "veh-2", "name": "Scania R450"}],
                "vehiclesError": None}

    async def set_subscription(self, chat_id, sub):
        self._linked()
        self.subscription = sub
        return {"subscription": sub}

    async def unlink(self, chat_id):
        self.linked = False

    async def problems(self, chat_id, limit=None):
        self._linked()
        items = [a for a in self.items.values() if not a.get("resolution")]
        return {"total": len(items), "items": items[:limit or 10], "lists": self.lists}

    async def report(self, chat_id, kind="daily"):
        self._linked()
        return WEEKLY if kind == "weekly" else DAILY

    async def anomaly(self, chat_id, anomaly_id, offset):
        self._linked()
        return self.items[anomaly_id]

    async def resolve(self, chat_id, anomaly_id, resolution, reason, offset):
        self._linked()
        a = {**self.items[anomaly_id], "resolution": resolution,
             "falseAlarmReason": reason if resolution == "FALSE_ALARM" else None,
             "resolvedBy": "ivanov" if resolution else None,
             "resolvedAt": 1_789_540_860_000 if resolution else None}  # 16.09.2026 06:41 UTC → 11:41
        self.items[anomaly_id] = a
        return a

    async def chat(self, chat_id, messages, offset, anomaly_id=None):
        self._linked()
        if self.chat_error:
            raise self.chat_error
        self.chats.append({"messages": [dict(m) for m in messages], "anomalyId": anomaly_id})
        return self.chat_reply

    async def send_email(self, chat_id, draft_id):
        if self.email_error:
            raise self.email_error
        return {"id": draft_id, "status": "sent", "sentAt": 1_789_540_980_000}  # 11:43

    async def cancel_email(self, chat_id, draft_id):
        return {"id": draft_id, "status": "cancelled", "sentAt": None}

    async def outbox(self):
        return self.outbox_data


@pytest.fixture
def stand():
    return FakeStand()


@pytest.fixture
def bot(stand):
    return Petrovich(stand, Refs(":memory:"), clock=lambda: NOW_UTC)


def run(coro):
    return asyncio.run(coro)


def press(bot, data, text=""):
    return run(bot.callback(CHAT, data, text))


def card_key(reply) -> str:
    return reply.buttons[0][0].data.split(":")[1]


# --- подключение ---


def test_start_without_link_gives_code_for_app(bot, stand):
    bot.seen(CHAT, "@kris")
    replies = run(bot.start(CHAT))
    assert replies[0].text == T.INTRO
    assert "Код для подключения: <code>K7MX-2QPD</code>" in replies[1].text and "действует 15 минут" in replies[1].text
    assert stand.pair_titles == ["@kris"]


def test_unlinked_chat_gets_code_instead_of_answer(bot):
    [r] = run(bot.text(CHAT, "Что у меня не так?"))
    assert "K7MX-2QPD" in r.text
    assert "K7MX-2QPD" in run(bot.problems(CHAT)).text


def test_linked_from_app_greets_in_chat(bot, stand):
    stand.linked = True
    stand.outbox_data = {"notices": [], "messages": [], "linked": [str(CHAT)]}
    [(chat_id, r)] = run(bot.outbox())
    assert chat_id == CHAT and r.menu and "Вы подключены к схеме" in r.text


def test_start_with_code_greets_and_shows_menu(bot):
    [r] = run(bot.start(CHAT, "good-code"))
    assert r.menu and "Вы подключены к схеме «Колонна &lt;1&gt;» как ivanov." in r.text and "Машин: 2." in r.text
    [r, code] = run(bot.start(CHAT, "good-code"))
    assert "Ссылка устарела" in r.text and "K7MX-2QPD" in code.text


# --- карточка и решения (макет 3) ---


def test_notice_card_with_memory(bot, stand):
    stand.linked = True
    stand.outbox_data = {"notices": [{"chatId": str(CHAT), "anomaly": anomaly(), "utcOffsetMinutes": OFFSET,
                                      "previous": ["2026-09-15T15:40"]}],
                         "messages": [{"chatId": str(CHAT), "text": "И ещё 2 события ждут решения — /problems"}]}
    [(chat, card), (_, more)] = run(bot.outbox())
    assert chat == CHAT
    assert card.text.splitlines() == [
        "<b>Критично · Scania R450 К417АУ96</b>",
        "Перегрев двигателя в 11:40.",
        "Температура охлаждающей жидкости 111,7 °C (норма до 100 °C).",
        "Петрович помнит: на этой машине такое уже было 15 сентября в 15:40.",
    ]
    assert [[b.text for b in row] for row in card.buttons] == [["Разобраться с Петровичем"],
                                                                ["Подтвердить", "Ложная тревога"]]
    assert more.text == "И ещё 2 события ждут решения — /problems"


def test_false_alarm_with_reason_and_return_to_work(bot, stand):
    stand.linked = True
    key = card_key(run(bot.problems(CHAT)))
    [r] = press(bot, f"c:{key}").replies
    [reasons] = press(bot, f"fa:{key}").replies
    assert reasons.edit and [row[0].text for row in reasons.buttons[:3]] == T.FALSE_ALARM_REASONS
    answer = press(bot, reasons.buttons[2][0].data)
    [done] = answer.replies
    assert answer.toast == "Отмечено: ложная тревога" and done.edit
    assert "<b>Ложная тревога · Уже известно</b>\nОтметил: ivanov, 11:41. Решение видно всем, в том числе в приложении." \
        in done.text
    assert [b.text for b in done.buttons[1]] == ["Вернуть в работу"]
    [back] = press(bot, f"re:{key}").replies
    assert "Ложная тревога" not in back.text and stand.items[ANOMALY_ID]["resolution"] is None


def test_other_reason_is_typed(bot, stand):
    stand.linked = True
    key = card_key(run(bot.problems(CHAT)))
    [ask] = press(bot, f"fo:{key}").replies
    assert "Напишите причину" in ask.text
    [r] = run(bot.text(CHAT, "Заправка на базе"))
    assert stand.items[ANOMALY_ID]["falseAlarmReason"] == "Заправка на базе" and not r.edit
    # Следующий текст — снова вопрос ассистенту.
    run(bot.text(CHAT, "Что у меня не так?"))
    assert stand.chats[-1]["messages"][-1]["content"] == "Что у меня не так?"


def test_stale_button(bot, stand):
    stand.linked = True
    answer = press(bot, "ok:0123456789abcdef")
    assert answer.toast == T.STALE_BUTTON and answer.alert and not answer.replies


def test_refused_decision_is_shown_as_alert(bot, stand):
    key = card_key(run(bot._card(CHAT, anomaly(), OFFSET)))
    answer = press(bot, f"ok:{key}")
    assert answer.alert and answer.toast == "Чат не подключён"


# --- разбор и письмо (макеты 4, 5) ---


def test_review_asks_assistant_about_the_event(bot, stand):
    stand.linked = True
    key = card_key(run(bot.problems(CHAT)))
    intro, reply = press(bot, f"rv:{key}").replies
    assert intro.text.startswith("<b>Разбор</b> · Перегрев двигателя · Scania")
    assert stand.chats[-1] == {"messages": [{"role": "user", "content": T.REVIEW_FIRST_QUESTION}],
                               "anomalyId": ANOMALY_ID}
    assert [b.text for row in reply.buttons for b in row] == ["Написать письмо", "Подтвердить", "Ложная тревога",
                                                              "Завершить разбор"]
    # Вопрос в разборе идёт с историей и id события; решение из разбора — новой карточкой, ответ не стирается.
    run(bot.text(CHAT, "А вчера?"))
    assert len(stand.chats[-1]["messages"]) == 3 and stand.chats[-1]["anomalyId"] == ANOMALY_ID
    [card] = press(bot, f"ok:{key}:n").replies
    assert not card.edit and "<b>Подтверждено</b>" in card.text
    [end] = press(bot, f"rx:{key}").replies
    assert end.menu
    run(bot.text(CHAT, "Что у меня не так?"))
    assert stand.chats[-1] == {"messages": [{"role": "user", "content": "Что у меня не так?"}], "anomalyId": None}


def test_draft_is_sent_only_by_button(bot, stand):
    stand.linked = True
    stand.chat_reply = {"reply": "Подготовил черновик.", "drafts": [{
        "id": "draft-" + "x" * 40, "subject": "Scania: перегрев", "body": "Прошу проверить <охлаждение>.",
        "expiresAt": 1_789_542_780_000,  # 12:13
        "recipients": [{"id": "mech", "name": "Сидоров", "role": "механик", "email": "mech@example.ru"}]}]}
    reply, draft = run(bot.text(CHAT, "Напиши механику"))
    assert reply.text == "Подготовил черновик."
    assert "Сидоров, механик — mech@example.ru" in draft.text and "&lt;охлаждение&gt;" in draft.text
    assert "до 12:13" in draft.text
    assert [b.text for b in draft.buttons[0]] == ["Отправить", "Отменить"]
    answer = press(bot, draft.buttons[0][0].data, "Кому …")
    assert answer.toast == "✓ Отправлено в 11:43" and answer.replies[0].edit and answer.replies[0].buttons is None
    stand.email_error = StandError(409, "Письмо уже отправлено")
    assert press(bot, draft.buttons[0][0].data).toast == "✓ Отправлено"
    stand.email_error = StandError(410, "Срок черновика истёк")
    assert press(bot, draft.buttons[0][0].data).toast == "Срок черновика истёк"


def test_failed_question_is_not_kept_in_history(bot, stand):
    stand.linked = True
    stand.chat_error = StandError(503, "Ассистент не настроен")
    [r] = run(bot.text(CHAT, "Что у меня не так?"))
    assert r.text == "Ассистент не настроен" and bot.state(CHAT).history == []


# --- настройки из приложения: карточка, повторы, списки ---


def test_card_follows_chat_settings(bot, stand):
    stand.linked = True
    card = {"description": False, "memory": False, "parameter": True, "source": True, "resolvedBy": True,
            "reviewButton": False, "resolveButtons": True}
    stand.outbox_data = {"notices": [{"chatId": str(CHAT), "utcOffsetMinutes": OFFSET, "repeats": 2, "card": card,
                                      "previous": ["2026-09-15T15:40"],
                                      "anomaly": anomaly(value=111.7, parameterCaption="Температура ОЖ",
                                                         source="Базовые правила")}],
                         "messages": []}
    [(_, r)] = run(bot.outbox())
    assert "Температура ОЖ: 111,7" in r.text and "Источник: Базовые правила" in r.text
    assert "повторялось ещё 2 раза" in r.text
    assert "Температура охлаждающей" not in r.text and "Петрович помнит" not in r.text
    assert [[b.text for b in row] for row in r.buttons] == [["Подтвердить", "Ложная тревога"]]


def test_problems_say_which_filter_is_on(bot, stand):
    stand.linked = True
    stand.lists = {**stand.lists, "subscribedOnly": True, "minSeverity": "WARNING", "periodHours": 24,
                   "order": "severity"}
    r = run(bot.problems(CHAT))
    assert "Фильтр: только мои машины, от предупреждений, за сутки, сначала критичные." in r.text
    stand.items = {}
    assert run(bot.problems(CHAT)).text.startswith("Всё в порядке")


# --- утренний доклад и недельная сводка (макеты 7, 8) ---


def test_daily_report_text_and_walk_through(bot, stand):
    stand.linked = True
    r = run(bot.report(CHAT))
    assert r.text.splitlines()[:2] == [
        "<b>Доклад за сутки</b>",  # 12:00 — уже не «доброе утро»
        "С 12:00 15 сентября по 10 машинам найдено 3 события — все критичные; ждут решения 2.",
    ]
    assert "<b>Резкое падение уровня топлива — 2:</b>\n• КАМАЗ 43118, 00:55\n• Volvo FH 500, 15.09 в 20:05 ✓" in r.text
    assert "• Scania R450, 15.09 в 15:40\n• и ещё 6" in r.text
    assert [b.text for row in r.buttons for b in row] == ["Разобрать по порядку", "Открыть ленту"]
    # По порядку — только неразобранные: КАМАЗ, затем Scania (её карточку стенд отдаёт по id).
    stand.items["rule|overheat|veh-3|T|2026-09-15T10:40Z"] = anomaly(id="rule|overheat|veh-3|T|2026-09-15T10:40Z")
    [first] = press(bot, "rq:0").replies
    assert first.buttons[-1][0].text == "Следующее › (2 из 2)" and not first.edit
    [last] = press(bot, "rq:1").replies
    assert last.buttons[-1][0].data == "pl"
    assert press(bot, "rq:5").alert
    assert press(bot, "pl").replies[0].text.startswith("<b>Ждут решения")


def test_reports_come_with_outbox(bot, stand):
    stand.linked = True
    stand.outbox_data = {"notices": [], "messages": [], "daily": [DAILY], "weekly": [WEEKLY]}
    [(_, daily), (_, weekly)] = run(bot.outbox())
    assert "Доклад за сутки" in daily.text
    assert weekly.text.splitlines()[:4] == [
        "<b>Сводка за неделю 7–13 сентября</b>",
        "Событий: 36 (на прошлой неделе 33).",
        "Перегрев двигателя — 18, напряжение вне нормы — 11.",
        "Решений за неделю не принимали: ждут все 36.",
    ]
    assert "1. Scania R450 К417АУ96 — 9, перегрев двигателя." in weekly.text and "Без событий: 3 машины из 10." in weekly.text
    assert weekly.buttons[0][0].text == "Незакрытые события"


def test_report_settings_in_bot(bot, stand):
    stand.linked = True
    r = run(bot.settings(CHAT))
    assert "Доклад: 09:30, каждый день" in r.text and "Недельная сводка: понедельник, 09:30" in r.text
    press(bot, "s:rt")
    assert stand.subscription["reports"]["dailyTime"] == "10:00"
    press(bot, "s:rt")
    assert stand.subscription["reports"]["daily"] is False
    press(bot, "s:rd")
    assert stand.subscription["reports"]["weekdaysOnly"] is True
    [r] = press(bot, "s:rw").replies
    assert (stand.subscription["reports"]["weeklyDay"], stand.subscription["reports"]["weeklyTime"]) == (5, "17:00")
    assert "Доклад: выкл, по будням" in r.text and "Недельная сводка: пятница, 17:00" in r.text


# --- списки и настройки (макет 11) ---


def test_problems_and_park(bot, stand):
    stand.linked = True
    r = run(bot.problems(CHAT))
    assert r.text.startswith("<b>Ждут решения: 1</b>")
    assert r.buttons[0][0].text == "‼️ Scania R450 К417АУ96 · Перегрев двигателя, 11:40"
    park = run(bot.park(CHAT))
    assert "• Scania R450 — 1" in park.text and "Без неразобранных событий: 1 из 2." in park.text
    stand.items.clear()
    assert run(bot.problems(CHAT)).text.startswith("Всё в порядке")


def test_settings_toggles_go_to_stand(bot, stand):
    stand.linked = True
    r = run(bot.settings(CHAT))
    assert "Мои машины: все (2)" in r.text and "Не больше 3 в день, кроме критичных" in r.text
    assert "Часовой пояс: UTC+5" in r.text
    [r] = press(bot, "s:w").replies
    assert stand.subscription["warnings"] is False and "Предупреждения: выкл" in r.text and r.edit
    press(bot, "s:lim")
    press(bot, "s:q")
    assert (stand.subscription["dailyLimit"], stand.subscription["quietFromHour"]) == (5, 23)
    [veh] = press(bot, "s:veh:0").replies
    assert veh.buttons[0][0].text == "✓ КАМАЗ 43118"
    [veh] = press(bot, "s:vt:0:0").replies
    assert stand.subscription["vehicleIds"] == ["veh-2"] and veh.buttons[0][0].text == "· КАМАЗ 43118"
    press(bot, "s:va")
    assert stand.subscription["vehicleIds"] is None
    [events] = press(bot, "s:ev").replies
    assert len(events.buttons) == 8 and events.buttons[2][0].text == "✓ Перегрев двигателя"
    [events] = press(bot, "s:et:overheat").replies
    assert stand.subscription["mutedKinds"] == ["overheat"] and events.buttons[2][0].text == "· Перегрев двигателя"
    assert "6 из 7, выключены: перегрев двигателя" in events.text
    [home] = press(bot, "s:home").replies
    assert "События: 6 из 7, выключены: перегрев двигателя" in home.text
    press(bot, "s:offy")
    assert stand.linked is False


def test_subscription_helpers():
    sub = {"vehicleIds": None, "mutedKinds": ["oil"], "dailyLimit": 10, "quietFromHour": 23, "quietToHour": 7}
    assert toggle_vehicle(sub, ["a", "b"], "a")["vehicleIds"] == ["b"]
    assert toggle_vehicle({**sub, "vehicleIds": ["b"]}, ["a", "b"], "a")["vehicleIds"] is None
    assert toggle_vehicle({**sub, "vehicleIds": ["b"]}, ["a", "b"], "b")["vehicleIds"] == []
    assert toggle_kind(sub, "drain")["mutedKinds"] == ["oil", "drain"] and toggle_kind(sub, "oil")["mutedKinds"] == []
    assert next_limit(sub)["dailyLimit"] is None and next_limit({"dailyLimit": None})["dailyLimit"] == 3
    assert (next_quiet(sub)["quietFromHour"], next_quiet(sub)["quietToHour"]) == (None, None)


# --- тексты ---


def test_texts():
    assert T.when("2026-09-16T11:40", datetime(2026, 9, 16).date()) == "в 11:40"
    assert T.when("2026-09-15T15:40:00", datetime(2026, 9, 16).date()) == "15 сентября в 15:40"
    assert T.utc_offset(300) == "UTC+5" and T.utc_offset(-210) == "UTC−3:30"
    assert T.split("a" * 10 + "\n" + "b" * 10, limit=15) == ["a" * 10, "b" * 10]
    assert T.split("") == []
    assert T.plural(21, "машина", "машины", "машин") == "машина" and T.plural(12, "машина", "машины", "машин") == "машин"


def test_refs_survive_restart(tmp_path):
    path = str(tmp_path / "bot.db")
    refs = Refs(path)
    key = refs.key(ANOMALY_ID)
    assert len(f"fr:{key}:2:n".encode()) <= 64
    refs.close()
    assert Refs(path).value(key) == ANOMALY_ID
