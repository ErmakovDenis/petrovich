"""Схемы контракта стенда совпадают со схемами predictive_antifraud, запрос чата — с тем, что шлёт приложение."""

import json
import sys
from pathlib import Path

import pytest

from fleet_service.schemas import contract
from fleet_service.schemas.chat import ChatRequest

REPO = Path(__file__).resolve().parents[3]
PREDICTIVE_SRC = REPO / "ml" / "predictive_antifraud" / "src"


@pytest.fixture(scope="module")
def predictive_schemas():
    sys.path.insert(0, str(PREDICTIVE_SRC))
    try:
        from predictive_antifraud.schemas import results, telemetry
    finally:
        sys.path.remove(str(PREDICTIVE_SRC))
    return telemetry, results


@pytest.mark.parametrize("name, module", [("VehicleTelemetry", 0), ("Anomaly", 1), ("DetectionResponse", 1)])
def test_schema_matches_predictive_antifraud(predictive_schemas, name, module):
    theirs = getattr(predictive_schemas[module], name).model_json_schema(by_alias=True)
    ours = getattr(contract, name).model_json_schema(by_alias=True)
    assert ours == theirs


def test_check_request_and_response_fixtures():
    """testdata/contract/check-*.json: запрос — ровно то, что шлёт ServerVehicleChecker; ответ — ровно то, что отдаёт
    стенд (те же поля), его разбирает VehicleCheckerTest приложения."""
    from fleet_service.schemas.anomalies import CheckRequest, CheckResponse

    request = CheckRequest.model_validate(json.loads((REPO / "testdata/contract/check-request.json").read_text("utf-8")))
    assert request.vehicle_id == "42" and request.utc_offset_minutes == 300 and request.from_.hour == 18
    raw = json.loads((REPO / "testdata/contract/check-response.json").read_text(encoding="utf-8"))
    assert CheckResponse.model_validate(raw).model_dump(by_alias=True, mode="json") == raw


def test_chat_request_from_app_fixture():
    """testdata/contract/chat-request.json — ровно то, что сериализует RemoteChatAgent (Kotlin-тест сверяет то же)."""
    raw = json.loads((REPO / "testdata" / "contract" / "chat-request.json").read_text(encoding="utf-8"))
    request = ChatRequest.model_validate(raw)
    assert [t.role for t in request.messages] == ["user", "assistant", "user"]
    assert request.utc_offset_minutes == 300
    assert request.anomaly is not None and request.anomaly.severity.value == "CRITICAL"
    assert request.anomaly.score is None and request.anomaly.value == 80.0
