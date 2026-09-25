import numpy as np

from predictive_antifraud.features.extractor import FeatureMatrix
from predictive_antifraud.models.registry import ModelName

from .conftest import telemetry_payload


class DrainModel:
    """Тестовая модель: аномальны строки, где уровень топлива упал больше чем на 20 л за отсчёт."""

    version = "test-1"
    expected_features = None

    def score(self, features: FeatureMatrix) -> np.ndarray:
        fuel = features.rows[:, features.feature_names.index("TankMainFuelLevel")]
        drop = -np.diff(fuel, prepend=fuel[0])
        return np.where(drop > 20, 0.97, 0.1)


def test_health(make_client):
    with make_client() as client:
        assert client.get("/health").json() == {"status": "ok"}


def test_not_ready_without_models(make_client):
    payload = telemetry_payload(fuel=[300, 250], ignition=[0, 0], rpm=[0, 0])
    with make_client() as client:
        assert all(not m["ready"] for m in client.get("/ready").json()["models"])
        for url in ("/v1/antifraud/check", "/v1/predictive/analyze"):
            r = client.post(url, json=payload)
            assert r.status_code == 200
            assert r.json() == {"ready": False, "modelVersion": None, "anomalies": []}


def test_antifraud_episode_has_stable_id(make_client, registry):
    registry.register(ModelName.ANTIFRAUD, DrainModel())
    # Слив на стоянке: два отсчёта подряд по −40 л — один эпизод, одна аномалия.
    payload = telemetry_payload(fuel=[300, 300, 260, 220, 220, 220], ignition=[0] * 6, rpm=[900] * 6)
    with make_client() as client:
        first = client.post("/v1/antifraud/check", json=payload).json()
        second = client.post("/v1/antifraud/check", json=payload).json()

    assert first["ready"] and first["modelVersion"] == "test-1"
    [a] = first["anomalies"]
    assert a["id"] == "ml|fraud|42|TankMainFuelLevel|2026-09-01T08:10:00"
    assert a["category"] == "FUEL" and a["severity"] == "CRITICAL" and a["vehicleId"] == "42"
    assert [x["id"] for x in second["anomalies"]] == [a["id"]]


def test_misaligned_columns_rejected(make_client):
    payload = telemetry_payload(fuel=[300, 290], ignition=[1, 1], rpm=[800, 800])
    payload["tables"]["FUEL"]["columns"][0]["values"].append(280)
    with make_client() as client:
        assert client.post("/v1/antifraud/check", json=payload).status_code == 422


def test_api_key(make_client):
    payload = telemetry_payload(fuel=[300, 290], ignition=[1, 1], rpm=[800, 800])
    with make_client(api_key="secret") as client:
        assert client.post("/v1/antifraud/check", json=payload).status_code == 401
        ok = client.post("/v1/antifraud/check", json=payload, headers={"X-API-Key": "secret"})
        assert ok.status_code == 200
        assert client.get("/health").status_code == 200
