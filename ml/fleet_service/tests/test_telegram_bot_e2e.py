"""Сквозной сценарий бота: логика ml/telegram_bot и её клиент стенда против настоящего стенда (подставной AutoGRAPH).
Пропускается, если пакет бота не установлен (pip install -e ml/telegram_bot)."""

from datetime import datetime

import httpx
import pytest

from .test_telegram import BOT_TOKEN, CHAT, as_user, client, grant, link, scan  # noqa: F401 — client — фикстура

pytest.importorskip("telegram_bot")
from telegram_bot.logic import Petrovich  # noqa: E402
from telegram_bot.refs import Refs  # noqa: E402
from telegram_bot.stand import Stand  # noqa: E402

CHAT_ID = int(CHAT)


def make_bot(client) -> Petrovich:
    http = httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app))
    stand = Stand("http://stand", BOT_TOKEN, 30, http=http)
    return Petrovich(stand, Refs(":memory:"), clock=lambda: datetime(2026, 9, 16, 4, 0))


def test_bot_end_to_end(client):  # noqa: F811
    bot = make_bot(client)
    call = client.portal.call

    # Не подключён — бот присылает код для приложения.
    replies = call(bot.start, CHAT_ID, "")
    assert "Код для подключения" in replies[-1].text

    grant(client)
    code = link(client).json()["url"].split("?start=")[1]
    [hello] = call(bot.start, CHAT_ID, code)
    assert "Вы подключены к схеме «Колонна» как Пётр." in hello.text and "Машин: 2." in hello.text

    # Фоновая проверка стенда нашла перегрев — карточки приходят в чат по выдаче.
    scan(client)
    client.app.state.telegram_notifier.clock = lambda: int(datetime.now().timestamp() * 1000)
    notices = call(bot.outbox)
    assert len(notices) == 3 and all(chat == CHAT_ID for chat, _ in notices)
    _, card = notices[-1]
    assert card.text.startswith("<b>Критично · ") and "Петрович помнит: на этой машине такое уже было" in card.text
    assert call(bot.outbox) == []

    # Решение с карточки: на стенде, от имени пользователя чата, видно в приложении.
    key = card.buttons[0][0].data.split(":")[1]
    answer = call(bot.callback, CHAT_ID, f"fr:{key}:1", "")
    assert answer.toast == "Отмечено: ложная тревога"
    assert "<b>Ложная тревога · Ошибка датчика</b>\nОтметил: Пётр" in answer.replies[0].text
    problems = call(bot.problems, CHAT_ID)
    assert problems.text.startswith("<b>Ждут решения: 2</b>")

    # Настройки уходят на стенд.
    call(bot.callback, CHAT_ID, "s:w", "")
    me = call(bot._stand.me, CHAT_ID)
    assert me["subscription"]["warnings"] is False

    # Разбор: вопрос ассистенту с id события.
    replies = call(bot.callback, CHAT_ID, f"rv:{key}", "").replies
    assert replies[0].text.startswith("<b>Разбор</b>") and replies[-1].buttons

    # Отключение из настроек: дальше бот снова присылает код.
    call(bot.callback, CHAT_ID, "s:offy", "")
    code = call(bot.start, CHAT_ID, "")[-1].text.split("<code>")[1].split("</code>")[0]

    # Подключение кодом из чата в приложении: бот здоровается.
    r = client.post("/v1/telegram/pair", json={"deviceId": "device-aaaaaaaaaaaa", "schemaName": "Колонна",
                                               "utcOffsetMinutes": 300, "code": code}, headers=as_user())
    assert r.status_code == 200, r.text
    [(chat_id, hello)] = call(bot.outbox)
    assert chat_id == CHAT_ID and "Вы подключены к схеме «Колонна» как Пётр." in hello.text
