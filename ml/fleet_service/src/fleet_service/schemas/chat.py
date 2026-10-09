"""Запрос и ответ `POST /v1/chat`. Приложение: chat/RemoteChatAgent.kt."""

from typing import Literal

from pydantic import Field

from .contract import Anomaly, CamelModel
from .emails import EmailDraftView


class ChatTurn(CamelModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1)


class ChatRequest(CamelModel):
    # История диалога по порядку; последнее сообщение — вопрос пользователя.
    messages: list[ChatTurn] = Field(min_length=1)
    # Смещение пояса пользователя от UTC в минутах (то же, что UTCOffset при Login в AutoGRAPH).
    utc_offset_minutes: int = Field(ge=-14 * 60, le=14 * 60)
    # Аномалия, из карточки которой открыт чат: объект целиком (аномалии считает устройство) или только id
    # аномалии из хранилища стенда — тогда стенд берёт её вместе с решением из хранилища.
    anomaly: Anomaly | None = None
    anomaly_id: str | None = Field(None, max_length=512)
    # Письма из чата разрешены в приложении (переключатель «Письма из чата»): ассистенту доступны list_recipients
    # и draft_email, если письма настроены на стенде.
    allow_email: bool = False


class ChatResponse(CamelModel):
    reply: str
    # Черновики писем, подготовленные за этот ответ: приложение показывает их с кнопками «Отправить» и «Отменить».
    drafts: list[EmailDraftView] = Field(default_factory=list)
