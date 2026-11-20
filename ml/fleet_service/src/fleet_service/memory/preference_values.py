"""Закрытый контракт P3: только оформление, без свободных инструкций и числовых фактов."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

SCHEMA_VERSION = 1
PreferenceKey = Literal[
    "response_length",
    "response_format",
    "technical_detail",
    "language",
    "preferred_terms",
    "auto_extract_enabled",
]


class PreferenceValues(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    response_length: Literal["short", "normal", "detailed"] = "normal"
    response_format: Literal["prose", "bullets", "conclusion_then_facts"] = "prose"
    technical_detail: Literal["basic", "advanced"] = "basic"
    language: Literal["ru"] = "ru"
    preferred_terms: dict[Literal["fuel_anomaly"], Literal["аномалия топлива", "проблема с топливом"]] = (
        Field(default_factory=dict)
    )
    auto_extract_enabled: bool = False


def validate_preference(key: str, value):
    # Никаких coercion: "false", 0 и произвольный prompt не становятся настройкой.
    return PreferenceValues.model_validate({key: value}).model_dump(exclude_unset=True)[key]
