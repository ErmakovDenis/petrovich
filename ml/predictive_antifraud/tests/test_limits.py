"""Предел нагрузки на /v1/*: быстрый отказ при полной очереди, срок ожидания, /health и /ready вне предела."""

import asyncio
import re
import threading
from pathlib import Path

import httpx
import numpy as np
import pytest

from predictive_antifraud.config import Settings
from predictive_antifraud.main import create_app
from predictive_antifraud.models.registry import ModelName

from .conftest import telemetry_payload

PAYLOAD = telemetry_payload(fuel=[300, 290, 280], ignition=[1, 1, 1], rpm=[800, 800, 800])
ENV_EXAMPLE = Path(__file__).resolve().parents[1] / ".env.example"


class GatedModel:
    """Модель, которая считает, пока тест не откроет [gate] (расчёт идёт в потоке пула)."""

    version = "gated-1"
    expected_features = None

    def __init__(self) -> None:
        self.gate = threading.Event()
        self.started = 0

    def score(self, features) -> np.ndarray:
        self.started += 1
        self.gate.wait(5)
        return np.zeros(len(features.timestamps))


def _client(registry, tmp_path, root_path: str = "", **settings):
    app = create_app(Settings(_env_file=None, models_dir=tmp_path, **settings), registry)
    transport = httpx.ASGITransport(app=app, root_path=root_path)
    return app, httpx.AsyncClient(transport=transport, base_url="http://pa" + root_path)


async def _until(condition, timeout: float = 5) -> None:
    for _ in range(int(timeout / 0.01)):
        if condition():
            return
        await asyncio.sleep(0.01)
    raise AssertionError("условие не наступило")


@pytest.mark.anyio
async def test_full_queue_is_rejected_at_once(registry, tmp_path):
    model = GatedModel()
    registry.register(ModelName.ANTIFRAUD, model)
    app, client = _client(registry, tmp_path, max_concurrency=1, queue_max=1)
    async with app.router.lifespan_context(app), client:
        limiter = app.state.limiter
        first = asyncio.create_task(client.post("/v1/antifraud/check", json=PAYLOAD))
        await _until(lambda: model.started == 1)
        second = asyncio.create_task(client.post("/v1/antifraud/check", json=PAYLOAD))
        await _until(lambda: limiter.waiting == 1)
        # Один считается, один в очереди — третий получает 503 сразу.
        third = await asyncio.wait_for(client.post("/v1/predictive/analyze", json=PAYLOAD), timeout=2)
        assert third.status_code == 503 and third.headers["retry-after"] == "5"
        assert third.json() == {"detail": "Сервис аналитики перегружен: очередь заполнена, повторите позже"}
        # /health и /ready под предел не попадают.
        assert (await client.get("/health")).status_code == 200
        assert (await client.get("/ready")).status_code == 200
        model.gate.set()
        assert [r.status_code for r in await asyncio.gather(first, second)] == [200, 200]
        assert model.started == 2 and limiter.active == 0


@pytest.mark.anyio
@pytest.mark.parametrize("settings, headers, waited", [
    ({"request_deadline_seconds": 0.1}, {}, "0.1"),
    # Стенд ждёт меньше PA_REQUEST_DEADLINE_SECONDS — дольше его остатка запрос в очереди не стоит.
    ({"request_deadline_seconds": 25}, {"X-Client-Timeout": "0.2"}, "0.2"),
])
async def test_request_waiting_longer_than_deadline_is_not_computed(registry, tmp_path, settings, headers, waited):
    model = GatedModel()
    registry.register(ModelName.ANTIFRAUD, model)
    app, client = _client(registry, tmp_path, max_concurrency=1, queue_max=5, **settings)
    async with app.router.lifespan_context(app), client:
        first = asyncio.create_task(client.post("/v1/antifraud/check", json=PAYLOAD))
        await _until(lambda: model.started == 1)
        late = await asyncio.wait_for(client.post("/v1/antifraud/check", json=PAYLOAD, headers=headers), timeout=3)
        assert late.status_code == 503
        assert late.json() == {"detail": f"Запрос простоял в очереди дольше {waited} с и не обработан"}
        model.gate.set()
        assert (await first).status_code == 200
        # Отвергнутый запрос не считался, место свободно.
        assert model.started == 1 and app.state.limiter.waiting == 0
        assert (await client.post("/v1/antifraud/check", json=PAYLOAD)).status_code == 200


@pytest.mark.anyio
async def test_limit_works_behind_a_path_prefix(registry, tmp_path):
    model = GatedModel()
    registry.register(ModelName.ANTIFRAUD, model)
    app, client = _client(registry, tmp_path, root_path="/predictive", max_concurrency=1, queue_max=0)
    async with app.router.lifespan_context(app), client:
        first = asyncio.create_task(client.post("/v1/antifraud/check", json=PAYLOAD))
        await _until(lambda: model.started == 1)
        assert (await client.post("/v1/antifraud/check", json=PAYLOAD)).status_code == 503
        model.gate.set()
        assert (await first).status_code == 200


@pytest.mark.anyio
async def test_requests_without_valid_key_do_not_take_places(registry, tmp_path):
    model = GatedModel()
    registry.register(ModelName.ANTIFRAUD, model)
    app, client = _client(registry, tmp_path, api_key="secret", max_concurrency=1, queue_max=0)
    async with app.router.lifespan_context(app), client:
        first = asyncio.create_task(client.post("/v1/antifraud/check", json=PAYLOAD, headers={"X-API-Key": "secret"}))
        await _until(lambda: model.started == 1)
        # Предел занят, но чужой запрос получает 401 от роутера, а не место в очереди и не 503.
        r = await client.post("/v1/antifraud/check", json=PAYLOAD, headers={"X-API-Key": "wrong"})
        assert r.status_code == 401
        model.gate.set()
        assert (await first).status_code == 200


def test_every_setting_is_in_env_example_with_its_default():
    documented = dict(re.findall(r"^(PA_[A-Z0-9_]+)=(.*)$", ENV_EXAMPLE.read_text(encoding="utf-8"), re.M))
    assert set(documented) == {f"PA_{name.upper()}" for name in Settings.model_fields} - {"PA_APP_NAME"}
    defaults = Settings(_env_file=None)
    for name in ("max_concurrency", "queue_max", "request_deadline_seconds"):
        assert float(documented[f"PA_{name.upper()}"]) == getattr(defaults, name), name
