import logging

from fleet_service.log_masking import configure_logging, mask_secrets

from .conftest import AUTH, ask, test_settings
from .fakes import FAKE_LLM_KEY, VALID_TOKEN


def test_mask_secrets():
    assert mask_secrets("GET /Login?UserName=ivan&Password=p%40ss&UTCOffset=300") == \
        "GET /Login?UserName=***&Password=***&UTCOffset=300"
    assert mask_secrets("EnumSchemas?session=abc-123 HTTP/1.1") == "EnumSchemas?session=*** HTTP/1.1"
    assert mask_secrets("Authorization: Bearer abc.def-123") == "Authorization: Bearer ***"
    assert mask_secrets("key sk-or-v1-0123456789abcdef") == "key sk-or-***"


def test_log_records_and_exceptions_are_masked(caplog):
    configure_logging()
    log = logging.getLogger("fleet_service.test")
    with caplog.at_level(logging.INFO):
        log.info("запрос %s", "https://ag/EnumSchemas?session=secret-token")
        try:
            raise RuntimeError("упал запрос ?Password=hunter2")
        except RuntimeError:
            log.exception("ошибка")
    assert "secret-token" not in caplog.text and "hunter2" not in caplog.text
    assert "session=***" in caplog.text and "Password=***" in caplog.text


def test_service_logs_have_no_tokens_or_keys(make_client, caplog):
    with caplog.at_level(logging.DEBUG):
        with make_client(test_settings(log_level="DEBUG")) as client:
            assert client.post("/v1/chat", json=ask(), headers=AUTH).status_code == 200
            client.post("/v1/chat", json=ask(), headers={**AUTH, "Authorization": "Bearer down"})
    # httpx пишет адрес EnumSchemas с session= в лог — он должен быть замаскирован.
    assert "EnumSchemas" in caplog.text
    assert VALID_TOKEN not in caplog.text and FAKE_LLM_KEY not in caplog.text
