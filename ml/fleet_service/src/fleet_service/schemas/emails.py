"""Письма: черновик в ответе чата и подтверждение / отмена (`POST /v1/emails/{draftId}/confirm`, `/cancel`).
Приложение: chat/Emails.kt."""

from typing import Literal

from pydantic import Field, field_validator

from ..mail.recipients import EMAIL
from .contract import CamelModel


class EmailContact(CamelModel):
    """Почтовый контакт из настроек телефона; телефон модели и стенду не нужен."""

    id: str = Field(min_length=1, max_length=60, pattern=r"^[A-Za-z0-9_-]+$")
    name: str = Field(min_length=1, max_length=200)
    role: str = Field(max_length=200)
    email: str = Field(min_length=1, max_length=320)

    @field_validator("name", "role", "email", mode="before")
    @classmethod
    def trim(cls, value):
        return value.strip() if isinstance(value, str) else value

    @field_validator("email")
    @classmethod
    def valid_email(cls, value: str) -> str:
        if not EMAIL.fullmatch(value) or any(ord(ch) < 32 for ch in value):
            raise ValueError("неверный адрес почтового контакта")
        return value


class EmailContacts(CamelModel):
    # None — старый клиент не передал список; [] — пользователь удалил все контакты.
    email_contacts: list[EmailContact] | None = Field(None, max_length=500)

    @field_validator("email_contacts")
    @classmethod
    def unique_ids(cls, value):
        if value is not None and len({c.id for c in value}) != len(value):
            raise ValueError("id почтовых контактов повторяются")
        return value


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
