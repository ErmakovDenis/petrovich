import numpy as np

from predictive_antifraud.features.extractor import extract
from predictive_antifraud.schemas.telemetry import VehicleTelemetry

from .conftest import telemetry_payload


def test_forward_fill_and_observed_mask():
    t = VehicleTelemetry.model_validate(telemetry_payload(
        fuel=[None, 300, None, 290], ignition=[1, 1, 1, 1], rpm=[800, 900, 900, 850],
    ))
    f = extract(t)
    fuel = f.feature_names.index("TankMainFuelLevel")
    assert f.rows[:, fuel].tolist() == [0, 300, 300, 290]
    assert f.observed[:, fuel].tolist() == [False, True, False, True]


def test_can_parameters_masked_when_ignition_off():
    # Зажигание выключено: обороты «замерли» на 900 — это не данные, берём последнее значение при включённом.
    t = VehicleTelemetry.model_validate(telemetry_payload(
        fuel=[300, 300, 300, 300], ignition=[1, 0, 0, 1], rpm=[850, 900, 900, 700],
    ))
    f = extract(t)
    rpm = f.feature_names.index("Rotation")
    assert f.ignition_on.tolist() == [True, False, False, True]
    assert f.observed[:, rpm].tolist() == [True, False, False, True]
    assert f.rows[:, rpm].tolist() == [850, 850, 850, 700]


def test_reindex_fills_missing_battery_voltage_with_zeros():
    # BattaryVOLTAGE есть только у Урал NEXT — у FAW столбец должен появиться нулевым и ненаблюдённым.
    t = VehicleTelemetry.model_validate(telemetry_payload(fuel=[300, 299], ignition=[1, 1], rpm=[800, 800]))
    f = extract(t).reindex(["BattaryVOLTAGE", "TankMainFuelLevel"])
    assert f.feature_names == ["BattaryVOLTAGE", "TankMainFuelLevel"]
    assert np.all(f.rows[:, 0] == 0) and not f.observed[:, 0].any()
    assert f.rows[:, 1].tolist() == [300, 299]
