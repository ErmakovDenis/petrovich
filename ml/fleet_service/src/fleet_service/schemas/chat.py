"""Запрос и ответ `POST /v1/chat`. Приложение: chat/RemoteChatAgent.kt."""

from typing import Literal

from pydantic import Field

from .contract import Anomaly, CamelModel


class ChatTurn(CamelModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1)


class ChatRequest(CamelModel):
    # История диалога по порядку; последнее сообщение — вопрос пользователя.
    messages: list[ChatTurn] = Field(min_length=1)
    # Смещение пояса пользователя от UTC в минутах (то же, что UTCOffset при Login в AutoGRAPH).
    utc_offset_minutes: int = Field(ge=-14 * 60, le=14 * 60)
    # Аномалия, из карточки которой открыт чат.
    anomaly: Anomaly | None = None


class ChatResponse(CamelModel):
    reply: str
