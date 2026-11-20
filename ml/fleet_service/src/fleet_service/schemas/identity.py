from typing import Literal

from pydantic import ConfigDict, Field, SecretStr, field_validator

from .contract import CamelModel


class BindIdentityRequest(CamelModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    user_name: str = Field(min_length=1, max_length=200)
    password: SecretStr = Field(min_length=1, max_length=200)
    schema_id: str = Field(min_length=1, max_length=64)
    utc_offset_minutes: int = Field(ge=-840, le=840)
    consent: Literal[True]

    @field_validator("consent", mode="before")
    @classmethod
    def explicit_consent(cls, value):
        if value is not True:
            raise ValueError("Необходимо явное согласие пользователя")
        return value

    @field_validator("user_name", "schema_id")
    @classmethod
    def nonblank(cls, value: str) -> str:
        if value != value.strip() or not value or any(ord(c) < 32 for c in value):
            raise ValueError("Значение не должно содержать крайние пробелы или управляющие символы")
        return value


class IdentityStatus(CamelModel):
    principal_id: str
    tenant_id: str


class BindIdentityResponse(IdentityStatus):
    credential: str = Field(repr=False)
    autograph_session: str = Field(repr=False)
    expires_at: int


class TelegramIdentityPeer(CamelModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)
    chat_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    telegram_user_id: str = Field(pattern=r"^[1-9][0-9]{0,18}$")
    chat_type: Literal["private"]


class TelegramIdentityPair(CamelModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)
    code: str = Field(min_length=8, max_length=32)
    consent: Literal[True]

    @field_validator("consent", mode="before")
    @classmethod
    def explicit_consent(cls, value):
        if value is not True:
            raise ValueError("Необходимо явное согласие на персональную привязку Telegram")
        return value


class TelegramIdentityLink(CamelModel):
    chat_id: str
    expires_at: int


class TelegramIdentityLinks(CamelModel):
    links: list[TelegramIdentityLink]


class TelegramIdentityCode(CamelModel):
    code: str = Field(repr=False)
    expires_at: int
