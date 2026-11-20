"""Серверная история: локально только pointer и отпечаток незавершённого вопроса."""

import asyncio
import hashlib
import json
from uuid import uuid4

from .refs import Refs
from .stand import Stand, StandError


def fingerprint(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


class PersonalHistory:
    def __init__(self, stand: Stand, refs: Refs):
        self.stand, self.refs = stand, refs
        self.locks: dict[int, asyncio.Lock] = {}

    def lock(self, chat_id: int) -> asyncio.Lock:
        return self.locks.setdefault(chat_id, asyncio.Lock())

    async def _state(self, chat_id: int) -> dict:
        if chat_id <= 0:
            raise StandError(403, "Личная история доступна только в личном чате")
        actor = await self.stand.identity_me(chat_id, chat_id)
        scope = fingerprint(json.dumps([self.stand._base, actor['principalId'], actor['tenantId']]))
        state = self.refs.conversation(chat_id)
        if state.get('scope') != scope:
            state = {'scope': scope, 'version': 0, 'fresh': bool(not state.get('scope') and state.get('fresh'))}
        if not state.get('id') and not state.get('fresh') and not state.get('pending'):
            data = await self.stand.personal(chat_id, 'GET', '/v2/conversations')
            latest = next((c for c in data['conversations'] if c['channel'] == 'telegram'), None)
            if latest:
                state.update(id=latest['id'], version=latest['version'])
        self.refs.save_conversation(chat_id, state)
        return state

    def new(self, chat_id: int) -> None:
        # Scope будет заново проверен перед следующим запросом. Старый разговор не удаляется.
        state = self.refs.conversation(chat_id)
        self.refs.save_conversation(chat_id, {'scope': state.get('scope'), 'fresh': True, 'version': 0})

    async def ask(self, chat_id: int, question: str, offset: int, anomaly_id: str | None) -> dict:
        async with self.lock(chat_id):
            state = await self._state(chat_id)
            if state.get('interruptedHash') == fingerprint(question):
                raise StandError(409, 'Результат действия неизвестен. Сначала проверьте его отдельным вопросом')
            pending = state.get('pending')
            if pending and pending['hash'] != fingerprint(question):
                raise StandError(409, 'Повторите незавершённый вопрос или начните новый разговор: /new')
            if not pending:
                pending = {'clientMessageId': str(uuid4()), 'conversationId': state.get('id'),
                           'expectedVersion': state['version'], 'utcOffsetMinutes': offset,
                           'anomalyId': anomaly_id, 'allowEmail': True, 'hash': fingerprint(question)}
                state['pending'] = pending
                self.refs.save_conversation(chat_id, state)
            body = {k: v for k, v in pending.items() if k != 'hash'} | {'content': question}
            try:
                result = await self.stand.personal(chat_id, 'POST', '/v2/chat', json=body, allow_interrupted=True)
            except StandError as e:
                if e.status == 409 and self.refs.conversation(chat_id) == state:
                    # Запрос с конфликтующей версией не принят сервером; обновляем версию для следующего явного retry.
                    if state.get('id'):
                        meta = await self.stand.personal(chat_id, 'GET', f"/v2/conversations/{state['id']}")
                        state['version'] = meta['version']
                    state.pop('pending', None)
                    self.refs.save_conversation(chat_id, state)
                raise
            if result['clientMessageId'] != pending['clientMessageId'] or (
                state.get('id') and result['conversationId'] != state['id']
            ):
                raise StandError(None, 'Стенд вернул ответ другого запроса')
            if self.refs.conversation(chat_id) != state:
                raise StandError(409, 'Разговор изменён. Откройте /history или задайте новый вопрос')
            actor = await self.stand.identity_me(chat_id, chat_id)
            scope = fingerprint(json.dumps([self.stand._base, actor['principalId'], actor['tenantId']]))
            if scope != state['scope']:
                raise StandError(403, 'Личный профиль изменён. Повторите вопрос')
            state['id'] = result['conversationId']
            if result['state'] == 'processing':
                self.refs.save_conversation(chat_id, state)
                raise StandError(202, 'Вопрос ещё обрабатывается. Повторите тот же вопрос чуть позже')
            if result['state'] not in ('completed', 'interrupted'):
                raise StandError(None, 'Стенд вернул незавершённый ответ')
            state.update(version=result['version'], fresh=False)
            state.pop('pending', None)
            state.pop('interruptedHash', None)
            if result['state'] == 'interrupted':
                state['interruptedHash'] = pending['hash']
            self.refs.save_conversation(chat_id, state)
            if result['state'] == 'interrupted':
                raise StandError(409, 'Результат начатого действия неизвестен. Проверьте его перед новым вопросом')
            return result

    async def history(self, chat_id: int) -> list[dict]:
        async with self.lock(chat_id):
            state = await self._state(chat_id)
            if not state.get('id'):
                return []
            meta = await self.stand.personal(chat_id, 'GET', f"/v2/conversations/{state['id']}")
            state['version'] = meta['version']
            rows = []
            offset = max(0, meta['version'] * 2 - 50)
            while True:
                page = (await self.stand.personal(chat_id, 'GET', f"/v2/conversations/{state['id']}/messages",
                                                 params={'limit': 100, 'offset': offset}))['messages']
                rows = (rows + page)[-50:]
                offset += len(page)
                if len(page) < 100:
                    break
            pending = state.get('pending')
            if pending and any(m['clientMessageId'] == pending['clientMessageId'] and m['role'] == 'assistant'
                               and m['state'] == 'committed' for m in rows):
                state.pop('pending', None)
            actor = await self.stand.identity_me(chat_id, chat_id)
            scope = fingerprint(json.dumps([self.stand._base, actor['principalId'], actor['tenantId']]))
            if scope != state['scope'] or self.refs.conversation(chat_id).get('id') != state['id']:
                raise StandError(409, 'Личный профиль или разговор изменён. Откройте /history заново')
            self.refs.save_conversation(chat_id, state)
            return rows

    async def delete(self, chat_id: int) -> None:
        async with self.lock(chat_id):
            state = await self._state(chat_id)
            if state.get('id'):
                await self.stand.personal(chat_id, 'DELETE', f"/v2/conversations/{state['id']}")
            self.new(chat_id)
