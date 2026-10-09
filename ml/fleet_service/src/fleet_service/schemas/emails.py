"""Письма: черновик в ответе чата и подтверждение / отмена (`POST /v1/emails/{draftId}/confirm`, `/cancel`).
Приложение: chat/Emails.kt."""

from typing import Literal

from .contract import CamelModel


class EmailRecipientView(CamelModel):
    id: str
    name: str
    role: str
    # Адрес — пользователю, чтобы видел, куда уйдёт письмо (модели адреса не показываются).
    email: str


class EmailDraftView(CamelModel):
    id: str
    recipients: list[EmailRecipientView]
    subject: str
    body: str
    # До какого момента черновик можно подтвердить, epoch millis.
    expires_at: int


class EmailResult(CamelModel):
    id: str
    # sent — отправлено; cancelled — отменено.
    status: Literal["sent", "cancelled"]
    sent_at: int | None = None
