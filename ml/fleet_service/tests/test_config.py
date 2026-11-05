import re
from pathlib import Path

import pytest
from pydantic import BaseModel

from fleet_service.config import Settings
from fleet_service.rules.thresholds import RuleThresholds

ENV_EXAMPLE = Path(__file__).resolve().parents[1] / ".env.example"


def _env_names() -> set[str]:
    """Все переменные настроек: вложенные модели (пороги правил) — как FS_<ПОЛЕ>__<ВЛОЖЕННОЕ>."""
    names = set()
    for name, field in Settings.model_fields.items():
        if isinstance(field.annotation, type) and issubclass(field.annotation, BaseModel):
            names |= {f"FS_{name.upper()}__{sub.upper()}" for sub in field.annotation.model_fields}
        else:
            names.add(f"FS_{name.upper()}")
    return names - {"FS_APP_NAME"}


def test_every_setting_is_documented_in_env_example():
    documented = set(re.findall(r"^(FS_[A-Z0-9_]+)=", ENV_EXAMPLE.read_text(encoding="utf-8"), re.M))
    assert documented == _env_names()


def test_env_example_as_is_gives_defaults():
    """Скопированный без правок .env.example (пустые значения) не ломает старт и не меняет умолчаний."""
    assert Settings(_env_file=ENV_EXAMPLE) == Settings(_env_file=None)


def test_gateway_timeout_must_be_below_proxy_attempt_in_gigachat_mode():
    with pytest.raises(ValueError, match="FS_GIGACHAT_TIMEOUT_SECONDS"):
        Settings(_env_file=None, llm_provider="gigachat", llm_timeout_seconds=30)
    # В режиме openrouter настройка шлюза ни на что не влияет: совет FS_LLM_TIMEOUT_SECONDS=30 ему не мешает.
    Settings(_env_file=None, llm_provider="openrouter", llm_timeout_seconds=30)
    Settings(_env_file=None, llm_provider="gigachat", llm_timeout_seconds=30, gigachat_timeout_seconds=25)


def test_model_names_are_stripped_in_both_modes():
    blank = Settings(_env_file=None, openrouter_api_key="k", llm_model="   ")
    assert blank.llm_configured is False
    assert Settings(_env_file=None, openrouter_api_key="k", llm_model=" a/b ").active_model == "a/b"


def test_rule_thresholds_from_env(monkeypatch):
    monkeypatch.setenv("FS_RULES__OVERHEAT_CELSIUS", "110")
    monkeypatch.setenv("FS_RULES__BRAKE_MIN_MINUTES", "8")
    rules = Settings(_env_file=None).rules
    assert rules.overheat_celsius == 110 and rules.brake_min_minutes == 8
    assert rules.fuel_drop_min_liters == RuleThresholds().fuel_drop_min_liters
