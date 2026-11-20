"""Повторы после рестарта, серверная история и границы персонального профиля."""
import asyncio
import json
from pathlib import Path

import httpx
import pytest

from telegram_bot.personal_history import PersonalHistory
from telegram_bot.refs import Refs
from telegram_bot.stand import Stand, StandError


class FakeStand:
    _base = 'https://stand.example'

    def __init__(self):
        self.principal = 'a'
        self.calls = []
        self.mode = 'completed'
        self.latest = []
        self.on_chat = None

    async def identity_me(self, chat_id, user_id):
        assert chat_id == user_id == 1001
        return {'principalId': self.principal, 'tenantId': 'schema'}

    async def personal(self, chat_id, method, path, **kwargs):
        self.calls.append((method, path, kwargs))
        if path == '/v2/conversations':
            return {'conversations': self.latest}
        if path == '/v2/chat':
            if self.on_chat:
                self.on_chat()
            if self.mode == 'offline':
                raise StandError(None, 'Сеть недоступна')
            body = kwargs['json']
            return {'conversationId': body['conversationId'] or 'conversation-a',
                    'clientMessageId': body['clientMessageId'], 'state': self.mode, 'version': 1,
                    'reply': 'Данных нет', 'drafts': []}
        if path.endswith('/messages'):
            return {'messages': [{'role': 'assistant', 'content': 'С сервера', 'clientMessageId': 'old',
                                   'state': 'committed', 'drafts': []}]}
        if method == 'DELETE':
            return None
        return {'id': 'conversation-a', 'version': 1, 'channel': 'telegram'}


def test_restart_reuses_exact_pending_request_without_saving_text(tmp_path):
    async def run():
        path = str(tmp_path / 'bot.db')
        stand = FakeStand()
        refs = Refs(path)
        stand.mode = 'offline'
        with pytest.raises(StandError):
            await PersonalHistory(stand, refs).ask(1001, 'Секретный вопрос', 300, 'anomaly-a')
        original = stand.calls[-1][2]['json']
        assert 'Секретный вопрос' not in json.dumps(refs.conversation(1001), ensure_ascii=False)
        refs.close()
        assert 'Секретный вопрос'.encode() not in Path(path).read_bytes()
        refs = Refs(path)
        stand.mode = 'completed'
        result = await PersonalHistory(stand, refs).ask(1001, 'Секретный вопрос', 0, None)
        assert stand.calls[-1][2]['json'] == original
        assert result['reply'] == 'Данных нет'
        assert 'pending' not in refs.conversation(1001)
        refs.close()
    asyncio.run(run())


def test_processing_and_interrupted_never_create_another_request():
    async def run():
        stand, refs = FakeStand(), Refs(':memory:')
        client = PersonalHistory(stand, refs)
        stand.mode = 'processing'
        with pytest.raises(StandError) as e:
            await client.ask(1001, 'Вопрос', 300, None)
        assert e.value.status == 202
        original = stand.calls[-1][2]['json']
        with pytest.raises(StandError):
            await client.ask(1001, 'Другой вопрос', 300, None)
        assert stand.calls[-1][2]['json'] == original
        stand.mode = 'interrupted'
        with pytest.raises(StandError) as e:
            await client.ask(1001, 'Вопрос', 0, None)
        assert 'неизвестен' in e.value.detail
        assert stand.calls[-1][2]['json'] == original
        assert 'pending' not in refs.conversation(1001)
        calls = len(stand.calls)
        with pytest.raises(StandError):
            await client.ask(1001, 'Вопрос', 0, None)
        assert len(stand.calls) == calls
    asyncio.run(run())


def test_new_retains_old_conversation_but_delete_calls_server():
    async def run():
        stand, refs = FakeStand(), Refs(':memory:')
        client = PersonalHistory(stand, refs)
        await client.ask(1001, 'Вопрос', 300, None)
        client.new(1001)
        assert not any(c[0] == 'DELETE' for c in stand.calls)
        assert await client.history(1001) == []
        await client.ask(1001, 'Новый вопрос', 300, None)
        assert stand.calls[-1][2]['json']['conversationId'] is None
        await client.delete(1001)
        assert any(c[:2] == ('DELETE', '/v2/conversations/conversation-a') for c in stand.calls)
        assert await client.history(1001) == []
    asyncio.run(run())


def test_profile_switch_and_new_while_waiting_cannot_resurrect_old_conversation():
    async def run():
        stand, refs = FakeStand(), Refs(':memory:')
        client = PersonalHistory(stand, refs)
        stand.on_chat = lambda: client.new(1001)
        with pytest.raises(StandError):
            await client.ask(1001, 'Вопрос', 300, None)
        assert refs.conversation(1001)['fresh']
        stand.on_chat = lambda: setattr(stand, 'principal', 'b')
        with pytest.raises(StandError):
            await client.ask(1001, 'Вопрос', 300, None)
        assert await client.history(1001) == []
        assert 'pending' not in refs.conversation(1001)
    asyncio.run(run())


def test_history_restores_latest_telegram_not_android():
    async def run():
        stand, refs = FakeStand(), Refs(':memory:')
        stand.latest = [{'id': 'android-other', 'channel': 'android', 'version': 10},
                        {'id': 'conversation-a', 'channel': 'telegram', 'version': 1}]
        rows = await PersonalHistory(stand, refs).history(1001)
        assert rows[0]['content'] == 'С сервера'
        assert refs.conversation(1001)['id'] == 'conversation-a'
    asyncio.run(run())


def test_transport_uses_private_pair_and_preserves_interrupted_response():
    async def run():
        def respond(request):
            assert request.url.path == '/v2/chat'
            assert request.headers['X-Telegram-Chat-Id'] == '1001'
            assert request.headers['X-Telegram-User-Id'] == '1001'
            assert request.headers['X-Telegram-Chat-Type'] == 'private'
            assert 'X-User-Name' not in request.headers
            return httpx.Response(409, json={'state': 'interrupted'})
        stand = Stand('https://stand.example', 'synthetic-service', 1, httpx.AsyncClient(transport=httpx.MockTransport(respond)))
        assert (await stand.personal(1001, 'POST', '/v2/chat', json={}, allow_interrupted=True))['state'] == 'interrupted'
        with pytest.raises(StandError):
            await stand.personal(-1001, 'POST', '/v2/chat', json={})
        await stand.aclose()
    asyncio.run(run())


def test_bot_v2_uses_server_history_and_draft_status_without_legacy_history():
    from telegram_bot.logic import Petrovich
    from telegram_bot import texts

    async def run():
        stand, refs = FakeStand(), Refs(':memory:')
        bot = Petrovich(stand, refs, server_history=True)
        bot.state(1001).offset = 300
        replies = await bot._ask(1001, 'Вопрос')
        assert replies[0].text == 'Данных нет'
        assert bot.state(1001).history == []
        assert 'messages' not in stand.calls[-1][2]['json']
        draft = {'id': 'draft-a', 'subject': 'Тема', 'body': 'Текст', 'recipients': [],
                 'expiresAt': 2000000000000, 'status': 'cancelled'}
        assert 'отменён' in texts.draft(draft, 300)
        assert 'нажмёте' not in texts.draft(draft, 300)
        old_scope = refs.conversation(1001)['scope']
        stand.principal = 'b'
        result = await bot._email(1001, f'v2mail:{old_scope}:draft-a', True, '')
        assert result.alert and 'другому профилю' in result.toast
        assert not any('/v2/emails/' in c[1] for c in stand.calls)
    asyncio.run(run())


def test_router_rejects_group_and_mismatched_private_actor(monkeypatch):
    from datetime import datetime
    from aiogram import Bot, Dispatcher
    from aiogram.client.session.base import BaseSession
    from aiogram.types import Chat, Message, Update, User
    from telegram_bot import bot as adapter

    class NoNetwork(BaseSession):
        async def close(self):
            pass
        async def make_request(self, bot, method, timeout=None):
            raise AssertionError('Сеть запрещена')
        async def stream_content(self, *args, **kwargs):
            if False:
                yield b''

    class Logic:
        server_history = True
        def seen(self, *args):
            pass
        async def text(self, *args):
            raise AssertionError('Неподтверждённый контекст не доходит до логики')
        async def conversation(self, *args, **kwargs):
            raise AssertionError('Неподтверждённый контекст не доходит до логики')
        def new(self, *args):
            raise AssertionError('Неподтверждённый контекст не доходит до логики')

    received = []
    async def send(bot, chat_id, reply):
        received.append(reply.text)
    monkeypatch.setattr(adapter, 'send', send)

    async def run():
        for text in ['Вопрос', '/history', '/new', '/delete_conversation confirm']:
            for chat_id, kind in [(-1001, 'group'), (1002, 'private')]:
                dispatcher = Dispatcher()
                dispatcher.include_router(adapter.build_router(Logic()))
                bot = Bot('123456:' + 'a' * 35, session=NoNetwork())
                message = Message(message_id=1, date=datetime(2026, 11, 20), chat=Chat(id=chat_id, type=kind),
                                  from_user=User(id=1001, is_bot=False, first_name='P4'), text=text)
                await dispatcher.feed_update(bot, Update(update_id=1, message=message))
                await bot.session.close()
        assert len(received) == 8
        assert all('только в личном чате' in text for text in received)
    asyncio.run(run())
