"""Персональные команды используют отдельный API и недоступны в группах."""

import asyncio
from datetime import datetime

import httpx

from telegram_bot.logic import Petrovich
from telegram_bot.refs import Refs
from telegram_bot.stand import Stand, StandError


class IdentityStand:
    def __init__(self):
        self.calls = []
        self.confirmed = False

    async def identity_code(self, chat_id, user_id):
        self.calls.append(("code", chat_id, user_id))
        return {"code": "ABCD2345", "expiresAt": 2_000_000_000_000}

    async def identity_me(self, chat_id, user_id):
        self.calls.append(("me", chat_id, user_id))
        if not self.confirmed:
            raise StandError(401, "Подтвердите личный профиль в приложении")
        return {"principalId": "private-principal", "tenantId": "schema-1"}

    async def identity_unlink(self, chat_id, user_id):
        self.calls.append(("unlink", chat_id, user_id))
        self.confirmed = False


def logic(stand):
    return Petrovich(stand, Refs(":memory:"), clock=lambda: datetime(2026, 11, 20))


def test_group_or_other_user_cannot_start_any_personal_operation():
    async def run():
        stand = IdentityStand()
        bot = logic(stand)
        for chat, user in [(-1001, 1001), (1001, None), (1001, 1002)]:
            for action in ("code", "status", "unlink"):
                [reply] = await bot.identity(chat, user, action)
                assert "только в личном чате" in reply.text
        assert stand.calls == []
    asyncio.run(run())


def test_personal_code_is_distinct_from_old_schema_pairing():
    async def run():
        stand = IdentityStand()
        [reply] = await logic(stand).identity(1001, 1001)
        assert "ABCD2345" in reply.text and "Личный профиль" in reply.text
        assert stand.calls == [("code", 1001, 1001)]
    asyncio.run(run())


def test_status_and_unlink_do_not_start_old_pairing_or_llm():
    async def run():
        stand = IdentityStand()
        bot = logic(stand)
        [reply] = await bot.identity(1001, 1001, "status")
        assert "Подтвердите" in reply.text
        stand.confirmed = True
        [reply] = await bot.identity(1001, 1001, "status")
        assert "private-principal" not in reply.text
        [reply] = await bot.identity(1001, 1001, "unlink")
        assert "отключён" in reply.text and not stand.confirmed
        assert [c[0] for c in stand.calls] == ["me", "me", "unlink"]
    asyncio.run(run())


def test_stand_sends_verified_private_peer_only_for_personal_endpoints():
    async def run():
        calls = []
        async def handle(request):
            calls.append(request)
            if request.url.path.endswith("pair-code"):
                return httpx.Response(200, json={"code": "ABCD2345", "expiresAt": 2_000_000_000_000})
            return httpx.Response(204) if request.method == "DELETE" else httpx.Response(200, json={})
        http = httpx.AsyncClient(transport=httpx.MockTransport(handle))
        stand = Stand("http://fakes", "fake-bot-key", 1, http)
        await stand.identity_code(1001, 1001)
        await stand.identity_me(1001, 1001)
        await stand.identity_unlink(1001, 1001)
        assert [r.url.path for r in calls] == ["/v2/identity/telegram/pair-code", "/v2/identity/telegram/me", "/v2/identity/telegram/me"]
        for r in calls[1:]:
            assert r.headers["X-Telegram-Chat-Type"] == "private"
            assert r.headers["X-Telegram-User-Id"] == r.headers["X-Telegram-Chat-Id"] == "1001"
            assert r.headers["Authorization"] == "Bearer fake-bot-key"
        await stand.aclose()
    asyncio.run(run())


def test_aiogram_adapter_uses_actual_private_actor_and_rejects_group_context(monkeypatch):
    from aiogram import Bot, Dispatcher
    from aiogram.client.session.base import BaseSession
    from aiogram.types import Chat, Message, Update, User
    from telegram_bot import bot as adapter
    from telegram_bot.logic import Reply

    class NoNetwork(BaseSession):
        async def close(self):
            pass
        async def make_request(self, bot, method, timeout=None):
            raise AssertionError("Реальная сеть запрещена в проверке адаптера")
        async def stream_content(self, *args, **kwargs):
            if False:
                yield b""

    class Logic:
        def __init__(self):
            self.calls = []
        def seen(self, chat_id, title):
            pass
        async def identity(self, chat_id, user_id, action):
            self.calls.append((chat_id, user_id, action))
            return [Reply("Тестовый ответ")]

    async def send(bot, chat_id, reply):
        assert reply.text == "Тестовый ответ"
    monkeypatch.setattr(adapter, "send", send)

    async def run():
        for command, chat_id, chat_type, expected_actor in [
            ("/identity", 1001, "private", 1001),
            ("/identity_status", 1001, "private", 1001),
            ("/forget_identity", -1001, "group", None),
        ]:
            logic = Logic()
            dispatcher = Dispatcher()
            dispatcher.include_router(adapter.build_router(logic))
            bot = Bot("123456:" + "a" * 35, session=NoNetwork())
            message = Message(message_id=1, date=datetime(2026, 11, 20), chat=Chat(id=chat_id, type=chat_type),
                              from_user=User(id=1001, is_bot=False, first_name="P0"), text=command)
            await dispatcher.feed_update(bot, Update(update_id=1, message=message))
            expected_action = {"/identity": "code", "/identity_status": "status", "/forget_identity": "unlink"}[command]
            assert logic.calls == [(chat_id, expected_actor, expected_action)]
            await bot.session.close()
    asyncio.run(run())
