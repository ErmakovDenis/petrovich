"""Отдельный контракт v2: клиент передаёт один вопрос, историю хранит сервер."""

from typing import Literal

from pydantic import ConfigDict, Field

from .contract import CamelModel
from .emails import EmailContacts, EmailDraftView


class CreateConversation(CamelModel):
    model_config = ConfigDict(extra="forbid")
    channel: Literal["android", "telegram"] = "android"


class ConversationView(CamelModel):
    id: str
    channel: str
    created_at: int
    updated_at: int
    version: int


class MessageView(CamelModel):
    id: str
    client_message_id: str
    role: Literal["user", "assistant"]
    content: str
    state: Literal["pending", "committed", "failed", "deleted"]
    created_at: int
    turn_id: str
    redacted: bool = False
    drafts: list["DurableDraft"] = Field(default_factory=list)
    unavailable_draft_ids: list[str] = Field(default_factory=list)


class DurableChatRequest(EmailContacts):
    model_config = ConfigDict(extra="forbid")
    conversation_id: str | None = Field(None, min_length=1, max_length=36)
    client_message_id: str = Field(min_length=1, max_length=128)
    expected_version: int = Field(0, ge=0, strict=True)
    content: str = Field(min_length=1, max_length=32000)
    utc_offset_minutes: int = Field(ge=-840, le=840, strict=True)
    anomaly_id: str | None = Field(None, max_length=512)
    allow_email: bool = Field(False, strict=True)


class DurableDraft(EmailDraftView):
    status: str
    sent_at: int | None = None


class DurableChatResponse(CamelModel):
    conversation_id: str
    client_message_id: str
    user_message_id: str
    assistant_message_id: str | None = None
    version: int
    state: Literal["processing", "completed", "interrupted"]
    reply: str | None = None
    drafts: list[DurableDraft] = Field(default_factory=list)
    unavailable_draft_ids: list[str] = Field(default_factory=list)


MessageView.model_rebuild()
