"""Перенос правил совпадает с BaselineAnomalyDetector приложения на эталонах `testdata/golden/anomalies/`.

Эталоны пишет Kotlin-тест AnomalyGoldenTest: VehicleTelemetry (встроенная или ссылка на эталон шага 2) и найденные
аномалии. Сравниваются id, тип, важность, время события, параметр, заголовок и значение (допуск VALUE_TOLERANCE),
а также порядок. detectedAt и текст описания не сравниваются (время запуска и локаль).

Реальные данные: `REAL_TRIP_TABLES=… REAL_TRIP_TABLES_OUT=… pytest tests/test_rules_golden.py` (или
scripts/compare_real.sh) — эталоны пишет TripTablesMapperTest.realResponses (`*.anomalies.json`).
"""

import json
import os
from pathlib import Path

import pytest

from fleet_service.rules.baseline import detect, java_time
from fleet_service.rules.thresholds import RuleThresholds
from fleet_service.schemas.contract import VehicleTelemetry

REPO = Path(__file__).resolve().parents[3]
GOLDEN = REPO / "testdata" / "golden"
CASES = sorted((GOLDEN / "anomalies").glob("*.case.json"))

VALUE_TOLERANCE = 1e-6


def _real_cases() -> list[Path]:
    if not os.environ.get("REAL_TRIP_TABLES"):
        return []
    out = Path(os.environ.get("REAL_TRIP_TABLES_OUT") or REPO / "app" / "build" / "real-trip-tables")
    return sorted(out.glob("*.anomalies.json"))


def load(case_file: Path) -> tuple[VehicleTelemetry, list[dict]]:
    case = json.loads(case_file.read_text(encoding="utf-8"))
    telemetry = case["telemetry"]
    if isinstance(telemetry, str):
        telemetry = json.loads((GOLDEN / telemetry).read_text(encoding="utf-8"))["expected"]
    return VehicleTelemetry.model_validate(telemetry), case["expected"]


def compare(case_file: Path) -> None:
    telemetry, expected = load(case_file)
    found = detect(telemetry)
    ours = [{"id": a.id, "kind": a.id.split("|")[1], "severity": a.severity.value, "eventTime": a.event_time,
             "parameterName": a.parameter_name, "title": a.title, "source": a.source} for a in found]
    theirs = [{k: e[k] for k in ("id", "kind", "severity", "eventTime", "parameterName", "title", "source")}
              for e in expected]
    assert ours == theirs
    for a, e in zip(found, expected):
        assert (a.value is None) == (e["value"] is None)
        if a.value is not None:
            assert abs(a.value - e["value"]) <= VALUE_TOLERANCE, a.id


def test_golden_cover_every_rule():
    kinds = {e["kind"] for f in CASES for e in load(f)[1]}
    assert kinds == {"drain", "drop", "power", "volt", "overheat", "oil", "brake"}
    assert {"demo-0.case.json", "rules-ural.case.json"} <= {f.name for f in CASES}


@pytest.mark.parametrize("case_file", CASES, ids=lambda p: p.name.removesuffix(".case.json"))
def test_rules_match_kotlin(case_file):
    compare(case_file)


@pytest.mark.parametrize("case_file", _real_cases(), ids=lambda p: p.name.removesuffix(".anomalies.json"))
def test_rules_match_kotlin_on_real_data(case_file):
    compare(case_file)


def test_no_false_positives_on_demo_0():
    """Как AnomalyDetectionTest.noFalsePositivesOnNormalData."""
    telemetry, _ = load(GOLDEN / "anomalies" / "demo-0.case.json")
    assert detect(telemetry) == []


def test_thresholds_change_without_code():
    telemetry, expected = load(GOLDEN / "anomalies" / "rules-ural.case.json")
    default = {a.id for a in detect(telemetry)}
    # Порог перегрева выше 107 °C — эпизоды перегрева исчезают; остальные правила не меняются.
    stricter = {a.id for a in detect(telemetry, RuleThresholds(overheat_celsius=110))}
    assert {i for i in default if "|overheat|" in i} and not {i for i in stricter if "|overheat|" in i}
    assert stricter == {i for i in default if "|overheat|" not in i}
    # Тормоза: с порогом 8 мин находится и 8-минутный эпизод.
    looser = {a.id for a in detect(telemetry, RuleThresholds(brake_min_minutes=8))}
    assert len([i for i in looser if "|brake|" in i]) > len([i for i in default if "|brake|" in i])


def test_java_time_like_local_date_time():
    from datetime import datetime

    assert java_time(datetime(2026, 9, 16, 10, 0)) == "2026-09-16T10:00"
    assert java_time(datetime(2026, 9, 16, 10, 0, 5)) == "2026-09-16T10:00:05"
    assert java_time(datetime(2026, 9, 16, 10, 0, 0, 500_000)) == "2026-09-16T10:00:00.500"
    assert java_time(datetime(2026, 9, 16, 10, 0, 0, 123_456)) == "2026-09-16T10:00:00.123456"
