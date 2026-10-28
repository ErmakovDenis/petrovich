"""Адаптер aiogram: доставляет ответы Petrovich (logic.py) в Telegram и раз в TB_OUTBOX_INTERVAL_SECONDS забирает
уведомления со стенда. Long polling: входящих портов и публичного адреса боту не нужно."""

import asyncio
import logging
from contextlib import suppress

from aiogram import Bot, Dispatcher, F, Router
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramBadRequest, TelegramForbiddenError
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.types import (
    BotCommand,
    CallbackQuery,
    InlineKeyboardButton,
    InlineKeyboardMarkup,
    KeyboardButton,
    Message,
    ReplyKeyboardMarkup,
)
from aiogram.utils.chat_action import ChatActionSender

from . import texts as T
from .config import Settings
from .logic import Petrovich, Reply
from .refs import Refs
from .stand import Stand, StandError

log = logging.getLogger(__name__)

COMMANDS = [
    BotCommand(command="problems", description="Проблемы — ждут решения"),
    BotCommand(command="park", description="Парк"),
    BotCommand(command="report", description="Доклад за сутки"),
    BotCommand(command="week", description="Сводка за неделю"),
    BotCommand(command="settings", description="Настройки"),
    BotCommand(command="new", description="Новый разговор"),
    BotCommand(command="help", description="Помощь"),
]
MENU = ReplyKeyboardMarkup(
    keyboard=[[KeyboardButton(text=T.WHATS_WRONG)], [KeyboardButton(text="Проблемы"), KeyboardButton(text="Настройки")]],
    resize_keyboard=True, is_persistent=True, input_field_placeholder=T.ASK_PLACEHOLDER,
)


def markup(reply: Reply) -> InlineKeyboardMarkup | ReplyKeyboardMarkup | None:
    if reply.buttons:
        return InlineKeyboardMarkup(inline_keyboard=[
            [InlineKeyboardButton(text=b.text, callback_data=b.data) for b in row] for row in reply.buttons
        ])
    return MENU if reply.menu else None


async def send(bot: Bot, chat_id: int, reply: Reply) -> None:
    await bot.send_message(chat_id, reply.text, reply_markup=markup(reply), disable_web_page_preview=True)


def chat_title(message: Message) -> str | None:
    """Как показать чат в приложении: @имя пользователя, иначе имя (или название группы)."""
    user = message.from_user
    if message.chat.type == "private" and user is not None:
        return f"@{user.username}" if user.username else user.full_name
    return message.chat.title


def build_router(logic: Petrovich) -> Router:
    router = Router()

    @router.message.outer_middleware()
    async def remember_title(handler, message: Message, data: dict):  # noqa: ANN001 — сигнатура aiogram
        logic.seen(message.chat.id, chat_title(message))
        return await handler(message, data)

    async def deliver(message: Message, replies: list[Reply]) -> None:
        for r in replies:
            await send(message.bot, message.chat.id, r)

    @router.message(CommandStart())
    async def start(message: Message, command: CommandObject) -> None:
        await deliver(message, await logic.start(message.chat.id, command.args or ""))

    @router.message(Command("help"))
    async def help_(message: Message) -> None:
        await deliver(message, [logic.help()])

    @router.message(Command("new"))
    async def new(message: Message) -> None:
        await deliver(message, [logic.new(message.chat.id)])

    @router.message(Command("problems"))
    async def problems(message: Message) -> None:
        await deliver(message, [await logic.problems(message.chat.id)])

    @router.message(Command("park"))
    async def park(message: Message) -> None:
        await deliver(message, [await logic.park(message.chat.id)])

    @router.message(Command("report"))
    async def report(message: Message) -> None:
        await deliver(message, [await logic.report(message.chat.id)])

    @router.message(Command("week"))
    async def week(message: Message) -> None:
        await deliver(message, [await logic.report(message.chat.id, weekly=True)])

    @router.message(Command("settings"))
    async def settings(message: Message) -> None:
        await deliver(message, [await logic.settings(message.chat.id)])

    @router.message(F.voice | F.audio | F.video_note)
    async def voice(message: Message) -> None:
        await deliver(message, [Reply(T.VOICE_LATER)])

    @router.message(F.text)
    async def text(message: Message) -> None:
        # Ассистент отвечает до полутора минут — показываем «печатает…».
        async with ChatActionSender.typing(bot=message.bot, chat_id=message.chat.id):
            replies = await logic.text(message.chat.id, message.text)
        await deliver(message, replies)

    @router.callback_query(F.data)
    async def button(query: CallbackQuery) -> None:
        msg = query.message
        if msg is None or not hasattr(msg, "chat"):
            await query.answer(T.STALE_BUTTON, show_alert=True)
            return
        # Разбор и письмо — обращение к ассистенту и SMTP: сразу снимаем «часики» с кнопки.
        slow = query.data.split(":", 1)[0] in ("rv", "rm")
        if slow:
            await query.answer()
            async with ChatActionSender.typing(bot=query.bot, chat_id=msg.chat.id):
                answer = await logic.callback(msg.chat.id, query.data, msg.text or "")
            # Всплывающее уведомление уже не показать — отказ стенда приходит сообщением.
            if answer.toast and not answer.replies:
                answer.replies.append(Reply(T.h(answer.toast)))
        else:
            answer = await logic.callback(msg.chat.id, query.data, msg.text or "")
            await query.answer(answer.toast, show_alert=answer.alert)
        for r in answer.replies:
            if r.edit:
                with suppress(TelegramBadRequest):  # «message is not modified» — повторное нажатие
                    await msg.edit_text(r.text, reply_markup=markup(r), disable_web_page_preview=True)
            else:
                await send(query.bot, msg.chat.id, r)

    return router


async def outbox_loop(bot: Bot, logic: Petrovich, interval: float) -> None:
    """Уведомления со стенда. Сбой стенда или Telegram не останавливает цикл; выданное стендом не повторяется."""
    while True:
        try:
            for chat_id, reply in await logic.outbox():
                try:
                    await send(bot, chat_id, reply)
                except TelegramForbiddenError:
                    log.info("чат заблокировал бота — отключаем на стенде")
                    await logic.forget(chat_id)
                except TelegramBadRequest as e:
                    log.warning("уведомление не отправлено: %s", e.message)
        except StandError as e:
            log.warning("уведомления: стенд не ответил: %s", e.detail)
        except Exception:  # noqa: BLE001 — сбой одного прохода не останавливает уведомления
            log.exception("уведомления: проход прерван")
        await asyncio.sleep(interval)


async def run(settings: Settings) -> None:
    stand = Stand(settings.stand_url, settings.stand_token.get_secret_value(), settings.stand_timeout_seconds)
    refs = Refs(settings.data_path)
    logic = Petrovich(stand, refs, settings.history_max_messages)
    bot = Bot(settings.bot_token.get_secret_value(), default=DefaultBotProperties(parse_mode=ParseMode.HTML))
    dp = Dispatcher()
    dp.include_router(build_router(logic))
    await bot.set_my_commands(COMMANDS)
    task = asyncio.create_task(outbox_loop(bot, logic, settings.outbox_interval_seconds), name="outbox")
    try:
        await dp.start_polling(bot)
    finally:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task
        await stand.aclose()
        refs.close()
        await bot.session.close()
