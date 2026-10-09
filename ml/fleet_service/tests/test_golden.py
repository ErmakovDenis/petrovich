"""Python-агрегация совпадает с Kotlin (TripTablesMapper приложения) на эталонах `testdata/golden/trip-tables/`.

Эталоны пишет Kotlin-тест TripTablesGoldenTest: входные ответы GetTripTables, параметры EnumParameters, период и
ожидаемая VehicleTelemetry. Здесь из тех же входов строится VehicleTelemetry Python-портом и сравниваются разделы,
столбцы (порядок, подписи, единицы), отметки времени и значения с допуском VALUE_TOLERANCE.

Реальные ответы: `REAL_TRIP_TABLES=<каталог> REAL_TRIP_TABLES_OUT=<эталоны Kotlin> pytest tests/test_golden.py`
(или scripts/compare_real.sh <каталог>) — эталоны пишет TripTablesMapperTest.realResponses.
"""

import json
import os
from datetime import datetime, timedelta
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from fleet_service.schemas.contract import Vehicle, VehicleTelemetry
from fleet_service.telemetry.mapper import TripTablesBuilder
from fleet_service.telemetry.parameters import RParameter, select

REPO = Path(__file__).resolve().parents[3]
GOLDEN = REPO / "testdata" / "golden" / "trip-tables"

# Значения в обоих портах округляются до 0,1 одинаково и должны совпадать точно; допуск — на случай различий
# в последнем разряде вещественной арифметики.
VALUE_TOLERANCE = 1e-6


def _real_cases() -> list[Path]:
    if not os.environ.get("REAL_TRIP_TABLES"):
        return []
    out = Path(os.environ.get("REAL_TRIP_TABLES_OUT") or REPO / "app" / "build" / "real-trip-tables")
    return sorted(out.glob("*.case.json"))


GOLDEN_CASES = sorted(GOLDEN.glob("*.case.json"))


def build(case_file: Path) -> tuple[VehicleTelemetry, VehicleTelemetry]:
    """(Python, Kotlin) для одного эталона."""
    case = json.loads(case_file.read_text(encoding="utf-8"))
    parameters, aggregation = select([RParameter.from_json(p) for p in case["parameters"]])
    bucket = timedelta(seconds=case["bucketSeconds"]) if case["bucketSeconds"] is not None else None
    builder = TripTablesBuilder(
        Vehicle.model_validate(case["vehicle"]),
        datetime.fromisoformat(case["from"]),
        datetime.fromisoformat(case["to"]),
        parameters,
        aggregation,
        bucket,
    )
    for name in case["inputs"]:
        path = Path(name) if Path(name).is_absolute() else case_file.parent / name
        reader = builder.reader()
        with path.open("rb") as f:
            # Частями, как из сети: разбор не должен зависеть от границ кусков.
            while chunk := f.read(4096):
                reader.feed(chunk)
        reader.close()
    return builder.build().telemetry, VehicleTelemetry.model_validate(case["expected"])


def assert_same(ours: VehicleTelemetry, theirs: VehicleTelemetry) -> None:
    assert ours.vehicle == theirs.vehicle
    assert (ours.from_, ours.to) == (theirs.from_, theirs.to)
    assert list(ours.tables) == list(theirs.tables), "разделы"
    for category, table in theirs.tables.items():
        mine = ours.tables[category]
        assert mine.timestamps == table.timestamps, f"{category}: отметки времени"
        assert [c.parameter for c in mine.columns] == [c.parameter for c in table.columns], f"{category}: столбцы"
        for a, b in zip(mine.columns, table.columns):
            assert len(a.values) == len(b.values)
            for i, (x, y) in enumerate(zip(a.values, b.values)):
                if x is None or y is None:
                    assert x is y, f"{a.parameter.name}[{table.timestamps[i]}]: {x} ≠ {y}"
                else:
                    assert abs(x - y) <= VALUE_TOLERANCE, f"{a.parameter.name}[{table.timestamps[i]}]: {x} ≠ {y}"


def test_golden_files_exist():
    names = {p.name for p in GOLDEN_CASES}
    assert {"sample.case.json", "synthetic-6h.case.json", "synthetic-7d.case.json"} <= names


@pytest.mark.parametrize("case_file", GOLDEN_CASES, ids=lambda p: p.name.removesuffix(".case.json"))
def test_python_matches_kotlin(case_file):
    ours, theirs = build(case_file)
    assert theirs.tables, "пустой эталон"
    assert_same(ours, theirs)


@pytest.mark.parametrize("case_file", _real_cases(), ids=lambda p: p.name.removesuffix(".case.json"))
def test_python_matches_kotlin_on_real_responses(case_file):
    ours, theirs = build(case_file)
    assert_same(ours, theirs)


def test_real_cases_are_found_when_requested():
    """REAL_TRIP_TABLES задан, а эталонов Kotlin нет — сверка не должна молча «пройти» без проверок."""
    if not os.environ.get("REAL_TRIP_TABLES"):
        pytest.skip("REAL_TRIP_TABLES не задан")
    assert _real_cases(), "нет эталонов Kotlin: сначала REAL_TRIP_TABLES=… ./gradlew testDebugUnitTest"


def test_stand_response_equals_golden_json(make_client, fakes):
    """GET /v1/telemetry отдаёт VehicleTelemetry в том же JSON, что пишет Kotlin (приложение его прочтёт)."""
    from .conftest import AUTH
    from .fakes import golden_trip_tables

    case = json.loads((GOLDEN / "synthetic-6h.case.json").read_text(encoding="utf-8"))
    golden_trip_tables(fakes, GOLDEN / "synthetic-6h.case.json", vehicle_id="veh-1")
    with make_client() as client:
        r = client.get("/v1/telemetry", headers=AUTH, params={
            "vehicleId": "veh-1", "from": case["from"], "to": case["to"], "utcOffsetMinutes": 300,
        })
    assert r.status_code == 200, r.text
    body = r.json()
    expected = case["expected"]
    # Машина — из EnumDevices стенда, остальное — как в эталоне Kotlin.
    assert body["vehicle"]["id"] == "veh-1"
    assert set(body) == {"vehicle", "from", "to", "tables"}
    assert VehicleTelemetry.model_validate(body).tables == VehicleTelemetry.model_validate(expected).tables
    for category, table in expected["tables"].items():
        mine = body["tables"][category]
        # Время — строкой без пояса, с секундами: так его пишет и разбирает приложение.
        assert mine["timestamps"] == table["timestamps"]
        assert [c["parameter"] | {"unit": c["parameter"].get("unit")} for c in mine["columns"]] == \
            [c["parameter"] | {"unit": c["parameter"].get("unit")} for c in table["columns"]]
