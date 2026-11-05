"""Шаг переключения режимов: окружение шлюза и предел чата при одном потоке GigaChat."""

import asyncio
import subprocess

import httpx
import pytest

from fleet_service.gigachat.app import create_gateway
from fleet_service.gigachat.config import gateway_env, write
from fleet_service.main import create_app

from .conftest import AUTH, ask, test_settings
from .fakes import FAKE_GIGACHAT_KEY, create_fakes
from .test_gigachat_gateway import Hosts, settings_for


def test_gateway_env_has_only_what_gateway_needs(tmp_path):
    s = test_settings(gigachat_credentials="fake-giga-secret", gigachat_ca_bundle="/srv/certs/ca.pem", smtp_password="smtp-secret",
                      predictive_api_key="pa-secret", access_encryption_key="enc-secret", llm_reserve_api_key="r-secret",
                      admin_token="admin-secret", telegram_service_token="tg-secret")
    write(tmp_path, s)
    path = tmp_path / "gigachat.env"
    assert path.stat().st_mode & 0o777 == 0o600
    text = path.read_text()
    assert "FS_GIGACHAT_CREDENTIALS='fake-giga-secret'" in text and "FS_GIGACHAT_CA_BUNDLE='/srv/certs/ca.pem'" in text
    for secret in ("smtp-secret", "pa-secret", "enc-secret", "r-secret", "admin-secret", "tg-secret", "test-key"):
        assert secret not in text
    assert all(name.startswith("FS_GIGACHAT_") or name in ("FS_LOG_LEVEL", "FS_METRICS_ENABLED") for name in gateway_env(s))


def test_gateway_env_without_key_or_certificate():
    env = gateway_env(test_settings())
    assert "FS_GIGACHAT_CREDENTIALS" not in env and "FS_GIGACHAT_CA_BUNDLE" not in env


def test_gateway_env_is_written_in_openrouter_mode_too(tmp_path):
    # Ключ передаётся шлюзу и при активном OpenRouter: проверка качества идёт до переключения.
    write(tmp_path, test_settings(llm_provider="openrouter", gigachat_credentials="fake-giga-secret"))
    assert "fake-giga-secret" in (tmp_path / "gigachat.env").read_text()


def test_key_with_special_characters_survives_sh(tmp_path):
    key = "a'b$c\"d`e\\f g"
    write(tmp_path, test_settings(gigachat_credentials=key))
    # Как в entrypoint шлюза: файл читает настоящий sh.
    shown = subprocess.run(["sh", "-c", 'set -a && . "$1/gigachat.env" && printf %s "$FS_GIGACHAT_CREDENTIALS"', "sh", str(tmp_path)],
                           capture_output=True, text=True, check=True).stdout
    assert shown == key


@pytest.mark.parametrize("provider, chat, streams, expected", [
    ("gigachat", 8, 1, 1), ("gigachat", 8, 10, 8), ("gigachat", 4, 10, 4), ("openrouter", 8, 1, 8),
])
def test_effective_chat_concurrency(provider, chat, streams, expected):
    s = test_settings(llm_provider=provider, chat_concurrency=chat, gigachat_concurrency=streams)
    assert s.effective_chat_concurrency == expected


@pytest.mark.anyio
async def test_one_stream_chats_wait_in_stand_queue_without_429():
    fakes = create_fakes()
    fakes.state.gigachat_delay = 0.1
    # Без предела чата на стороне стенда ожидание места в шлюзе при таком сроке дало бы 429.
    settings = settings_for(chat_burst=100, gigachat_timeout_seconds=0.3)
    gateway = create_gateway(settings, http=httpx.AsyncClient(transport=httpx.ASGITransport(app=fakes)))
    app = create_app(settings, http=httpx.AsyncClient(transport=Hosts(gateway, fakes)), background_loop=False)
    async with app.router.lifespan_context(app), httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://stand"
    ) as client:
        replies = await asyncio.gather(*[client.post("/v1/chat", json=ask(), headers=AUTH) for _ in range(4)])
        status = app.state.breakers["llm"].state
    assert [r.status_code for r in replies] == [200] * 4
    assert all("250.0" in r.json()["reply"] for r in replies)
    assert fakes.state.gigachat_peak == 1 and fakes.state.gigachat_rejected == 0
    assert gateway.state.metrics.rejections.labels("gigachat_no_slot")._value.get() == 0
    assert status == "closed" and FAKE_GIGACHAT_KEY not in replies[0].text
