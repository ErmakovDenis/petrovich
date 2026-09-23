from datetime import datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from predictive_antifraud.config import Settings
from predictive_antifraud.main import create_app
from predictive_antifraud.models.registry import ModelRegistry

START = datetime(2026, 9, 1, 8, 0)


def telemetry_payload(fuel: list[float | None], ignition: list[float], rpm: list[float]) -> dict:
    """VehicleTelemetry в JSON, как его сериализует приложение: FUEL и ENGINE с общей сеткой по 5 мин."""
    times = [(START + timedelta(minutes=5 * i)).isoformat() for i in range(len(fuel))]

    def column(name: str, caption: str, category: str, values: list) -> dict:
        return {"parameter": {"name": name, "caption": caption, "unit": None, "category": category}, "values": values}

    return {
        "vehicle": {"id": "42", "name": "Урал NEXT А001АА"},
        "from": times[0],
        "to": times[-1],
        "tables": {
            "FUEL": {"category": "FUEL", "timestamps": times,
                     "columns": [column("TankMainFuelLevel", "Уровень топлива", "FUEL", fuel)]},
            "ENGINE": {"category": "ENGINE", "timestamps": times,
                       "columns": [column("DIgnition", "Зажигание", "ENGINE", ignition),
                                   column("Rotation", "Обороты", "ENGINE", rpm)]},
        },
    }


@pytest.fixture
def registry(tmp_path) -> ModelRegistry:
    return ModelRegistry(tmp_path)


@pytest.fixture
def make_client(registry, tmp_path):
    def make(**settings) -> TestClient:
        app = create_app(Settings(models_dir=tmp_path, **settings), registry)
        return TestClient(app)

    return make
