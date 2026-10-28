"""Поведение бота без Telegram: команды, кнопки и текст пользователя → ответы (текст и кнопки). Адаптер aiogram
(bot.py) только доставляет ответы; так сценарии проверяются тестами без сети.

Состояние разговора (история для ассистента, идёт ли разбор события, ждём ли причину ложной тревоги) — в памяти
бота: перезапуск начинает разговор заново. Подключение, подписка и решения — на стенде.

callback_data кнопок: `<действие>:<ключ>[:<аргумент>]`, ключ — короткий ключ id аномалии или черновика (refs.py).
Кнопки на карточке меняют саму карточку; кнопки под ответом в разборе (суффикс `n`) присылают карточку новым
сообщением, чтобы не стереть ответ.
"""

import logging
from contextlib import suppress
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime

from . import texts as T
from .refs import Refs
from .stand import Stand, StandError

log = logging.getLogger(__name__)

PAGE = 8
PREVIOUS_MAX = 2000
LIMITS = [3, 5, 10, None]
QUIET = [(22, 7), (23, 7), (None, None)]
# Время доклада по кругу в /settings (None — выключен) и день и время сводки; точнее — в приложении.
DAILY_TIMES = ["07:00", "08:00", "09:00", "09:30", "10:00", None]
WEEKLY = [(1, "09:30"), (5, "17:00"), None]


@dataclass(frozen=True)
class Button:
    text: str
    data: str


@dataclass
class Reply:
    text: str
    buttons: list[list[Button]] | None = None
    # Показать главное меню под полем ввода (кнопки «Что у меня не так?», «Проблемы», «Настройки»).
    menu: bool = False
    # Изменить сообщение, на котором нажата кнопка, а не присылать новое.
    edit: bool = False


@dataclass
class Answer:
    """Итог нажатия кнопки: ответы и короткое всплывающее уведомление."""

    replies: list[Reply] = field(default_factory=list)
    toast: str | None = None
    alert: bool = False


@dataclass
class ChatState:
    history: list[dict] = field(default_factory=list)
    # Разбор события: id аномалии, о которой спрашивают ассистента.
    review: str | None = None
    # Ждём причину ложной тревоги текстом («Другое»): id аномалии.
    pending_reason: str | None = None
    offset: int | None = None
    # Как чат называется в Telegram (@имя или имя) — стенд показывает его в приложении.
    title: str | None = None
    # Что показывать в карточке (настройки чата на стенде); None — ещё не знаем.
    card: dict | None = None
    # «Разобрать по порядку» из утреннего доклада: id неразобранных событий доклада.
    queue: list[str] = field(default_factory=list)


def utc_now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


# --- подписка: чистые функции ---


def toggle_vehicle(sub: dict, all_ids: list[str], vehicle_id: str) -> dict:
    """Выбор машины; все выбраны — None (и новые машины тоже)."""
    chosen = list(all_ids) if sub.get("vehicleIds") is None else list(sub["vehicleIds"])
    chosen = [v for v in chosen if v != vehicle_id] if vehicle_id in chosen else chosen + [vehicle_id]
    return {**sub, "vehicleIds": None if set(all_ids) <= set(chosen) else [v for v in all_ids if v in chosen]}


def toggle_kind(sub: dict, kind: str) -> dict:
    """Включить или выключить событие (mutedKinds — выключенные)."""
    muted = list(sub.get("mutedKinds") or [])
    muted = [k for k in muted if k != kind] if kind in muted else muted + [kind]
    return {**sub, "mutedKinds": muted}


def next_limit(sub: dict) -> dict:
    current = sub.get("dailyLimit")
    i = LIMITS.index(current) if current in LIMITS else -1
    return {**sub, "dailyLimit": LIMITS[(i + 1) % len(LIMITS)]}


def next_daily(sub: dict) -> dict:
    """Время доклада по кругу; после последнего — выключен."""
    r = sub.get("reports") or {}
    current = r.get("dailyTime") if r.get("daily", True) else None
    i = DAILY_TIMES.index(current) if current in DAILY_TIMES else -1
    nxt = DAILY_TIMES[(i + 1) % len(DAILY_TIMES)]
    return {**sub, "reports": {**r, "daily": nxt is not None, **({"dailyTime": nxt} if nxt else {})}}


def next_weekly(sub: dict) -> dict:
    r = sub.get("reports") or {}
    current = (r.get("weeklyDay"), r.get("weeklyTime")) if r.get("weekly", True) else None
    i = WEEKLY.index(current) if current in WEEKLY else -1
    nxt = WEEKLY[(i + 1) % len(WEEKLY)]
    return {**sub, "reports": {**r, "weekly": nxt is not None,
                               **({"weeklyDay": nxt[0], "weeklyTime": nxt[1]} if nxt else {})}}


def toggle_weekdays(sub: dict) -> dict:
    r = sub.get("reports") or {}
    return {**sub, "reports": {**r, "weekdaysOnly": not r.get("weekdaysOnly")}}


def next_quiet(sub: dict) -> dict:
    current = (sub.get("quietFromHour"), sub.get("quietToHour"))
    i = QUIET.index(current) if current in QUIET else -1
    a, b = QUIET[(i + 1) % len(QUIET)]
    return {**sub, "quietFromHour": a, "quietToHour": b}


# --- клавиатуры ---


def card_buttons(key: str, resolved: bool, card: dict | None = None) -> list[list[Button]] | None:
    """Кнопки карточки; какие показывать — настройки чата (card.reviewButton, card.resolveButtons)."""
    card = card or {}
    rows = []
    if card.get("reviewButton", True):
        rows.append([Button("Разобраться с Петровичем", f"rv:{key}")])
    if card.get("resolveButtons", True):
        rows.append([Button("Вернуть в работу", f"re:{key}")] if resolved else
                    [Button("Подтвердить", f"ok:{key}"), Button("Ложная тревога", f"fa:{key}")])
    return rows or None


def reason_buttons(key: str, suffix: str) -> list[list[Button]]:
    rows = [[Button(r, f"fr:{key}:{i}{suffix}")] for i, r in enumerate(T.FALSE_ALARM_REASONS)]
    return rows + [[Button("Другое", f"fo:{key}"), Button("‹ Назад", f"bk:{key}{suffix}")]]


def review_buttons(key: str) -> list[list[Button]]:
    return [[Button("Написать письмо", f"rm:{key}")],
            [Button("Подтвердить", f"ok:{key}:n"), Button("Ложная тревога", f"fa:{key}:n")],
            [Button("Завершить разбор", f"rx:{key}")]]


def settings_buttons(sub: dict) -> list[list[Button]]:
    limit = sub.get("dailyLimit")
    quiet = sub.get("quietFromHour")
    return [
        [Button("Мои машины", "s:veh:0"), Button("События", "s:ev")],
        [Button(f"Предупреждения: {'вкл' if sub.get('warnings') else 'выкл'}", "s:w"),
         Button(f"Инфо: {'вкл' if sub.get('info') else 'выкл'}", "s:i")],
        [Button(f"Лимит: {limit} в день" if limit else "Лимит: нет", "s:lim"),
         Button(f"Тихие часы: {quiet:02d}–{sub.get('quietToHour'):02d}" if quiet is not None else "Тихие часы: выкл",
                "s:q")],
        [Button(f"Доклад: {T.daily_label(sub)}", "s:rt"), Button(f"Дни: {T.days_label(sub)}", "s:rd")],
        [Button(f"Сводка: {T.weekly_label(sub, short=True)}", "s:rw")],
        [Button("Отключить бота", "s:off")],
    ]


class Petrovich:
    def __init__(self, stand: Stand, refs: Refs, history_max: int = 20, clock: Callable[[], datetime] = utc_now):
        self._stand = stand
        self._refs = refs
        self._history_max = history_max
        self._clock = clock
        self._states: dict[int, ChatState] = {}
        # «Петрович помнит» из уведомления — чтобы не терять строку при перерисовке карточки.
        self._previous: dict[str, list[str]] = {}

    def state(self, chat_id: int) -> ChatState:
        return self._states.setdefault(chat_id, ChatState())

    def seen(self, chat_id: int, title: str | None) -> None:
        """Название чата из входящего сообщения."""
        if title:
            self.state(chat_id).title = title[:200]

    async def _unlinked(self, chat_id: int) -> Reply:
        """Чат не подключён: код для ввода в приложении."""
        try:
            data = await self._stand.pair_code(chat_id, self.state(chat_id).title)
        except StandError as e:
            return Reply(f"{T.h(e.detail)}\n\n{T.HOW_TO_CONNECT}")
        now_ms = int(self._clock().replace(tzinfo=UTC).timestamp() * 1000)
        return Reply(T.pair_code(data["code"], max(1, round((data["expiresAt"] - now_ms) / 60_000))))

    async def _failed(self, chat_id: int, e: StandError, edit: bool = False) -> Reply:
        """Отказ стенда ответом в чат; чат не подключён (401) — код для приложения."""
        if e.status == 401:
            return await self._unlinked(chat_id)
        return Reply(T.h(e.detail), edit=edit)

    async def _me(self, chat_id: int) -> dict:
        me = await self._stand.me(chat_id)
        st = self.state(chat_id)
        st.offset = me["utcOffsetMinutes"]
        st.card = me["subscription"].get("card") or {}
        return me

    async def _offset(self, chat_id: int) -> int:
        st = self.state(chat_id)
        if st.offset is None:
            await self._me(chat_id)
        return st.offset

    async def _card(self, chat_id: int, a: dict, offset: int, edit: bool = False, repeats: int = 0) -> Reply:
        """Карточка события по настройкам чата: какие строки и кнопки показывать."""
        st = self.state(chat_id)
        if st.card is None:
            with suppress(StandError):  # настройки не получены — карточка по умолчанию
                await self._me(chat_id)
        card = st.card or {}
        key = self._refs.key(a["id"])
        text = T.card(a, offset, self._previous.get(key), self._clock(), card, repeats)
        return Reply(text, card_buttons(key, bool(a.get("resolution")), card), edit=edit)

    # --- команды ---

    async def start(self, chat_id: int, payload: str = "") -> list[Reply]:
        if payload:
            try:
                await self._stand.connect(chat_id, payload.strip(), self.state(chat_id).title)
            except StandError as e:
                if e.status == 404:
                    return [Reply(T.h(e.detail)), await self._unlinked(chat_id)]
                return [Reply(T.h(e.detail))]
            title = self.state(chat_id).title
            self._states.pop(chat_id, None)
            self.seen(chat_id, title)
        try:
            me = await self._me(chat_id)
        except StandError as e:
            if e.status == 401 and not payload:
                return [Reply(T.INTRO), await self._unlinked(chat_id)]
            return [Reply(T.h(e.detail))]
        return [Reply(T.greeting(me), menu=True)]

    def help(self) -> Reply:
        return Reply(T.HELP, menu=True)

    def new(self, chat_id: int) -> Reply:
        st = self.state(chat_id)
        st.history.clear()
        st.review = st.pending_reason = None
        return Reply(T.NEW_CHAT, menu=True)

    async def problems(self, chat_id: int) -> Reply:
        try:
            offset = await self._offset(chat_id)
            data = await self._stand.problems(chat_id)
        except StandError as e:
            return await self._failed(chat_id, e)
        today = T.local_now(offset, self._clock()).date()
        rows = [[Button(T.problem_button(a, today)[:64], f"c:{self._refs.key(a['id'])}")] for a in data["items"]]
        return Reply(T.problems(data["total"], len(data["items"]), data.get("lists")), rows or None)

    async def park(self, chat_id: int) -> Reply:
        try:
            me = await self._me(chat_id)
            data = await self._stand.problems(chat_id, limit=500)
        except StandError as e:
            return await self._failed(chat_id, e)
        return Reply(T.park(me, data["items"], data["total"], data.get("lists")))

    async def report(self, chat_id: int, weekly: bool = False) -> Reply:
        """/report — доклад за сутки сейчас, /week — сводка за прошлую неделю."""
        try:
            offset = await self._offset(chat_id)
            data = await self._stand.report(chat_id, "weekly" if weekly else "daily")
        except StandError as e:
            return await self._failed(chat_id, e)
        return self._weekly(data) if weekly else self._daily(chat_id, data, offset)

    def _daily(self, chat_id: int, data: dict, offset: int) -> Reply:
        """Утренний доклад (макет 7); «Разобрать по порядку» — неразобранные события доклада."""
        queue = [e["id"] for g in data["groups"] for e in g["items"] if not e["resolved"]]
        self.state(chat_id).queue = queue
        buttons = [[Button("Разобрать по порядку", "rq:0")]] if queue else []
        buttons.append([Button("Открыть ленту", "pl")])
        return Reply(T.daily_report(data, offset, self._clock()), buttons)

    @staticmethod
    def _weekly(data: dict) -> Reply:
        """Недельная сводка (макет 8)."""
        return Reply(T.weekly_report(data), [[Button("Незакрытые события", "pl")]] if data["open"] else None)

    async def settings(self, chat_id: int, edit: bool = False) -> Reply:
        try:
            me = await self._me(chat_id)
        except StandError as e:
            return await self._failed(chat_id, e, edit=edit)
        return Reply(T.settings(me), settings_buttons(me["subscription"]), edit=edit)

    # --- текст пользователя ---

    async def text(self, chat_id: int, text: str) -> list[Reply]:
        st = self.state(chat_id)
        text = text.strip()
        if not text:
            return []
        if st.pending_reason is not None:
            anomaly_id, st.pending_reason = st.pending_reason, None
            answer = await self._resolve(chat_id, anomaly_id, "FALSE_ALARM", text[:500], new_message=True)
            return answer.replies or [Reply(T.h(answer.toast or ""))]
        aliases = {"проблемы": self.problems, "настройки": self.settings, "парк": self.park}
        if text.lower() in aliases:
            return [await aliases[text.lower()](chat_id)]
        if text.lower() == "помощь":
            return [self.help()]
        return await self._ask(chat_id, text)

    async def _ask(self, chat_id: int, question: str) -> list[Reply]:
        st = self.state(chat_id)
        st.history.append({"role": "user", "content": question})
        try:
            offset = await self._offset(chat_id)
            resp = await self._stand.chat(chat_id, st.history[-self._history_max:], offset, st.review)
        except StandError as e:
            st.history.pop()
            return [await self._failed(chat_id, e)]
        st.history.append({"role": "assistant", "content": resp["reply"]})
        del st.history[:-self._history_max]
        replies = [Reply(T.h(part)) for part in T.split(resp["reply"])]
        if st.review is not None:
            replies[-1].buttons = review_buttons(self._refs.key(st.review))
        for d in resp.get("drafts") or []:
            key = self._refs.key(d["id"])
            replies.append(Reply(T.draft(d, offset), [[Button("Отправить", f"ms:{key}"),
                                                      Button("Отменить", f"mc:{key}")]]))
        return replies

    # --- кнопки ---

    async def callback(self, chat_id: int, data: str, message_text: str = "") -> Answer:
        action, _, rest = data.partition(":")
        if action == "s":
            return await self._settings_action(chat_id, rest)
        if action == "pl":
            return Answer([await self.problems(chat_id)])
        if action == "rq":
            return await self._next_in_queue(chat_id, rest)
        key, _, arg = rest.partition(":")
        value = self._refs.value(key)
        if value is None:
            return Answer(toast=T.STALE_BUTTON, alert=True)
        new_message = arg.endswith("n")
        arg = arg.removesuffix("n").rstrip(":")
        st = self.state(chat_id)
        try:
            if action == "c":
                offset = await self._offset(chat_id)
                return Answer([await self._card(chat_id, await self._stand.anomaly(chat_id, value, offset), offset)])
            if action == "ok":
                return await self._resolve(chat_id, value, "CONFIRMED", None, new_message)
            if action == "re":
                return await self._resolve(chat_id, value, None, None, new_message)
            if action == "fr":
                return await self._resolve(chat_id, value, "FALSE_ALARM", T.FALSE_ALARM_REASONS[int(arg)], new_message)
            if action == "fa":
                if new_message:
                    return Answer([Reply("Почему это ложная тревога?", reason_buttons(key, ":n"))])
                offset = await self._offset(chat_id)
                card = await self._card(chat_id, await self._stand.anomaly(chat_id, value, offset), offset, edit=True)
                card.text += "\n\nПочему это ложная тревога?"
                card.buttons = reason_buttons(key, "")
                return Answer([card])
            if action == "fo":
                st.pending_reason = value
                return Answer([Reply("Напишите причину одной фразой — отмечу ложную тревогу.")])
            if action == "bk":
                offset = await self._offset(chat_id)
                a = await self._stand.anomaly(chat_id, value, offset)
                return Answer([await self._card(chat_id, a, offset, edit=not new_message)])
            if action == "rv":
                a = await self._stand.anomaly(chat_id, value, await self._offset(chat_id))
                st.review, st.pending_reason = value, None
                st.history.clear()
                return Answer([Reply(T.review_intro(a)), *await self._ask(chat_id, T.REVIEW_FIRST_QUESTION)])
            if action == "rm":
                st.review = value
                return Answer(await self._ask(chat_id, T.REVIEW_EMAIL_REQUEST))
            if action == "rx":
                st.review = None
                st.history.clear()
                return Answer([Reply("Разбор завершён. Решение по событию можно принять на карточке или в /problems.",
                                     menu=True)])
            if action in ("ms", "mc"):
                return await self._email(chat_id, value, send=action == "ms", message_text=message_text)
        except StandError as e:
            return Answer(toast=e.detail[:200], alert=True)
        except (ValueError, IndexError):
            pass
        return Answer(toast=T.STALE_BUTTON, alert=True)

    async def _next_in_queue(self, chat_id: int, index: str) -> Answer:
        """Следующее событие из утреннего доклада — новой карточкой, с кнопкой «Следующее»."""
        queue = self.state(chat_id).queue
        i = int(index) if index.isdigit() else -1
        if not 0 <= i < len(queue):
            return Answer(toast=T.STALE_BUTTON, alert=True)
        try:
            offset = await self._offset(chat_id)
            a = await self._stand.anomaly(chat_id, queue[i], offset)
        except StandError as e:
            return Answer(toast=e.detail[:200], alert=True)
        card = await self._card(chat_id, a, offset)
        nav = Button(f"Следующее › ({i + 2} из {len(queue)})", f"rq:{i + 1}") if i + 1 < len(queue) else \
            Button("Это последнее · открыть ленту", "pl")
        card.buttons = (card.buttons or []) + [[nav]]
        return Answer([card])

    async def _resolve(self, chat_id: int, anomaly_id: str, resolution: str | None, reason: str | None,
                       new_message: bool) -> Answer:
        try:
            offset = await self._offset(chat_id)
            a = await self._stand.resolve(chat_id, anomaly_id, resolution, reason, offset)
        except StandError as e:
            return Answer(toast=e.detail[:200], alert=True)
        toast = {"CONFIRMED": "Подтверждено", "FALSE_ALARM": "Отмечено: ложная тревога"}.get(resolution or "",
                                                                                              "Вернул в работу")
        return Answer([await self._card(chat_id, a, offset, edit=not new_message)], toast=toast)

    async def _email(self, chat_id: int, draft_id: str, send: bool, message_text: str) -> Answer:
        try:
            result = await (self._stand.send_email if send else self._stand.cancel_email)(chat_id, draft_id)
        except StandError as e:
            if e.status == 409 and e.detail.startswith("Письмо уже отправлено"):
                result = {"status": "sent", "sentAt": None}
            else:
                return Answer(toast=e.detail[:200], alert=True)
        offset = await self._offset(chat_id)
        if result["status"] == "sent":
            at = f" в {T.ms_local(result['sentAt'], offset):%H:%M}" if result.get("sentAt") else ""
            status = f"✓ Отправлено{at}"
        else:
            status = "Черновик отменён"
        body = T.h(message_text) if message_text else ""
        return Answer([Reply(f"{body}\n\n<b>{status}</b>".strip(), edit=True)], toast=status)

    async def _settings_action(self, chat_id: int, rest: str) -> Answer:
        op, _, arg = rest.partition(":")
        try:
            if op == "home":
                return Answer([await self.settings(chat_id, edit=True)])
            if op == "off":
                return Answer([Reply("Отключить бота от схемы? Уведомления и вопросы перестанут работать, пока вы снова "
                                     "не подключите его из приложения.",
                                     [[Button("Да, отключить", "s:offy"), Button("Отмена", "s:home")]], edit=True)])
            if op == "offy":
                await self._stand.unlink(chat_id)
                self._states.pop(chat_id, None)
                return Answer([Reply("Бот отключён. Чтобы подключиться снова, отправьте /start.", edit=True)])
            me = await self._me(chat_id)
            sub = me["subscription"]
            vehicles = me.get("vehicles") or []
            if op == "veh":
                return Answer([self._vehicles_view(sub, vehicles, int(arg or 0))])
            if op == "ev":
                return Answer([self._events_view(sub)])
            if op == "vt":
                page, _, idx = arg.partition(":")
                sub = toggle_vehicle(sub, [v["id"] for v in vehicles], vehicles[int(idx)]["id"])
                await self._stand.set_subscription(chat_id, sub)
                return Answer([self._vehicles_view(sub, vehicles, int(page))])
            if op == "va":
                sub = {**sub, "vehicleIds": None}
                await self._stand.set_subscription(chat_id, sub)
                return Answer([self._vehicles_view(sub, vehicles, 0)])
            if op == "et" and arg in T.KINDS:
                sub = toggle_kind(sub, arg)
                await self._stand.set_subscription(chat_id, sub)
                return Answer([self._events_view(sub)])
            changes = {"w": lambda s: {**s, "warnings": not s.get("warnings")},
                       "i": lambda s: {**s, "info": not s.get("info")},
                       "lim": next_limit, "q": next_quiet, "rt": next_daily, "rd": toggle_weekdays,
                       "rw": next_weekly}
            if op in changes:
                me["subscription"] = (await self._stand.set_subscription(chat_id, changes[op](sub)))["subscription"]
                return Answer([Reply(T.settings(me), settings_buttons(me["subscription"]), edit=True)])
        except StandError as e:
            return Answer(toast=e.detail[:200], alert=True)
        except (ValueError, IndexError):
            pass
        return Answer(toast=T.STALE_BUTTON, alert=True)

    @staticmethod
    def _vehicles_view(sub: dict, vehicles: list[dict], page: int) -> Reply:
        chosen = sub.get("vehicleIds")
        pages = max(1, (len(vehicles) + PAGE - 1) // PAGE)
        page = min(max(page, 0), pages - 1)
        rows = [[Button(f"{'✓' if chosen is None or v['id'] in chosen else '·'} {v['name']}"[:64],
                        f"s:vt:{page}:{i}")]
                for i, v in enumerate(vehicles) if page * PAGE <= i < (page + 1) * PAGE]
        nav = []
        if page > 0:
            nav.append(Button("◀", f"s:veh:{page - 1}"))
        if page < pages - 1:
            nav.append(Button("▶", f"s:veh:{page + 1}"))
        rows += [nav] if nav else []
        rows.append([Button("Все машины", "s:va"), Button("Готово", "s:home")])
        text = (f"<b>Мои машины</b>: {T.vehicles_label(sub, vehicles)}\n"
                "Отметьте машины, о которых присылать события. Страница "
                f"{page + 1} из {pages}.")
        return Reply(text, rows, edit=True)

    @staticmethod
    def _events_view(sub: dict) -> Reply:
        muted = sub.get("mutedKinds") or []
        rows = [[Button(f"{'·' if k in muted else '✓'} {name}"[:64], f"s:et:{k}")] for k, name in T.KINDS.items()]
        rows.append([Button("Готово", "s:home")])
        text = (f"<b>События</b>: {T.events_label(sub)}\n"
                "Выключенное событие не приходит совсем, даже критичное, и не показывается в /problems и /park. "
                "То же — в приложении: Настройки → Telegram → «События».")
        return Reply(text, rows, edit=True)

    # --- уведомления ---

    async def outbox(self) -> list[tuple[int, Reply]]:
        """Что отправить в чаты: карточки новых событий и служебные сообщения со стенда."""
        data = await self._stand.outbox()
        out: list[tuple[int, Reply]] = []
        for n in data.get("notices") or []:
            chat_id = int(n["chatId"])
            self.state(chat_id).offset = n["utcOffsetMinutes"]
            key = self._refs.key(n["anomaly"]["id"])
            if n.get("previous"):
                self._previous[key] = n["previous"]
                while len(self._previous) > PREVIOUS_MAX:
                    del self._previous[next(iter(self._previous))]
            if n.get("card") is not None:
                self.state(chat_id).card = n["card"]
            out.append((chat_id, await self._card(chat_id, n["anomaly"], n["utcOffsetMinutes"],
                                                  repeats=n.get("repeats") or 0)))
        for m in data.get("messages") or []:
            out.append((int(m["chatId"]), Reply(T.h(m["text"]))))
        for d in data.get("daily") or []:
            chat_id = int(d["chatId"])
            try:
                out.append((chat_id, self._daily(chat_id, d, await self._offset(chat_id))))
            except StandError as e:
                log.warning("доклад не отправлен: %s", e.detail)
        for w in data.get("weekly") or []:
            out.append((int(w["chatId"]), self._weekly(w)))
        # Подключён из приложения по коду: здороваемся, как после /start по ссылке.
        for c in data.get("linked") or []:
            chat_id = int(c)
            title = self.state(chat_id).title
            self._states.pop(chat_id, None)
            self.seen(chat_id, title)
            try:
                out.append((chat_id, Reply(T.greeting(await self._me(chat_id)), menu=True)))
            except StandError as e:
                log.warning("приветствие после подключения не отправлено: %s", e.detail)
        return out

    async def forget(self, chat_id: int) -> None:
        """Пользователь заблокировал бота: отключить чат на стенде."""
        self._states.pop(chat_id, None)
        try:
            await self._stand.unlink(chat_id)
        except StandError as e:
            log.warning("не удалось отключить чат, заблокировавший бота: %s", e.detail)

