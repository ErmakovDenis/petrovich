import re
from pathlib import Path

from fleet_service.config import Settings

ENV_EXAMPLE = Path(__file__).resolve().parents[1] / ".env.example"


def test_every_setting_is_documented_in_env_example():
    documented = set(re.findall(r"^(FS_[A-Z0-9_]+)=", ENV_EXAMPLE.read_text(encoding="utf-8"), re.M))
    fields = {f"FS_{name.upper()}" for name in Settings.model_fields} - {"FS_APP_NAME"}
    assert documented == fields


def test_env_example_as_is_gives_defaults():
    """Скопированный без правок .env.example (пустые значения) не ломает старт и не меняет умолчаний."""
    assert Settings(_env_file=ENV_EXAMPLE) == Settings(_env_file=None)
