import httpx

from fleet_service.main import create_app

from .conftest import AUTH, ask, test_settings
from .fakes import SCHEMA_ID, VALID_TOKEN


def test_health_without_token(make_client):
    with make_client() as client:
        assert client.get("/health").json() == {
            "status": "ok", "llmProvider": "openrouter", "llmConfigured": True, "backgroundConfigured": False, "emailConfigured": False,
            "reserveConfigured": False, "telegramConfigured": False,
            "breakers": {"llm": "closed", "autograph": "closed", "predictive": "closed"},
        }
    with make_client(test_settings(llm_model="")) as client:
        assert client.get("/health").json()["llmConfigured"] is False


def test_missing_or_malformed_token_is_401(make_client, fakes):
    with make_client() as client:
        for headers in ({}, {"X-Schema-Id": SCHEMA_ID}, {"Authorization": VALID_TOKEN, "X-Schema-Id": SCHEMA_ID},
                        {"Authorization": "Bearer ", "X-Schema-Id": SCHEMA_ID},
                        {"Authorization": f"Bearer {VALID_TOKEN}"}):
            r = client.post("/v1/chat", json=ask(), headers=headers)
            assert r.status_code == 401, headers
            assert r.headers["www-authenticate"] == "Bearer"
            assert r.json()["detail"]
    # Без токена в AutoGRAPH не ходим.
    assert fakes.state.calls["EnumSchemas"] == 0


def test_invalid_token_is_401(make_client):
    with make_client() as client:
        r = client.post("/v1/chat", json=ask(), headers={**AUTH, "Authorization": "Bearer expired"})
    assert r.status_code == 401
    assert r.json() == {"detail": "Сессия AutoGRAPH недействительна или истекла"}


def test_foreign_schema_is_403(make_client):
    with make_client() as client:
        r = client.post("/v1/chat", json=ask(), headers={**AUTH, "X-Schema-Id": "other"})
    assert r.status_code == 403


def test_autograph_down_is_503_not_401(make_client):
    with make_client() as client:
        r = client.post("/v1/chat", json=ask(), headers={**AUTH, "Authorization": "Bearer down"})
    assert r.status_code == 503
    assert "AutoGRAPH недоступен" in r.json()["detail"]


def test_autograph_unreachable_is_503():
    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    http = httpx.AsyncClient(transport=httpx.MockTransport(refuse))
    from fastapi.testclient import TestClient

    with TestClient(create_app(test_settings(), http=http), raise_server_exceptions=False) as client:
        r = client.post("/v1/chat", json=ask(), headers=AUTH)
    assert r.status_code == 503


def test_verification_is_cached(make_client, fakes):
    with make_client(test_settings(auth_cache_ttl_seconds=60)) as client:
        for _ in range(3):
            assert client.post("/v1/chat", json=ask(), headers=AUTH).status_code == 200
        # Отказ тоже кэшируется: повтор с тем же плохим токеном не ходит в AutoGRAPH.
        for _ in range(2):
            client.post("/v1/chat", json=ask(), headers={**AUTH, "Authorization": "Bearer expired"})
    assert fakes.state.calls["EnumSchemas"] == 2


def test_cache_can_be_disabled(make_client, fakes):
    with make_client(test_settings(auth_cache_ttl_seconds=0)) as client:
        for _ in range(2):
            assert client.post("/v1/chat", json=ask(), headers=AUTH).status_code == 200
    assert fakes.state.calls["EnumSchemas"] == 2


def test_unavailability_is_not_cached(make_client, fakes):
    with make_client() as client:
        for _ in range(2):
            client.post("/v1/chat", json=ask(), headers={**AUTH, "Authorization": "Bearer down"})
    assert fakes.state.calls["EnumSchemas"] == 2


def test_health_and_chat_follow_active_provider(make_client):
    # Ключа OpenRouter достаточно только в его режиме.
    settings = test_settings(llm_provider="gigachat")
    with make_client(settings) as client:
        health = client.get("/health").json()
        assert health["llmProvider"] == "gigachat" and health["llmConfigured"] is False
        r = client.post("/v1/chat", json={"messages": [{"role": "user", "content": "?"}], "utcOffsetMinutes": 300},
                        headers=AUTH)
        assert r.status_code == 503 and "не настроен" in r.json()["detail"] and "OpenRouter" not in r.json()["detail"]
    with make_client(test_settings(llm_provider="gigachat", gigachat_credentials="giga-secret")) as client:
        assert client.get("/health").json()["llmConfigured"] is True
    with make_client(test_settings(llm_provider="gigachat", gigachat_credentials="giga-secret", gigachat_model=" ")) as client:
        assert client.get("/health").json()["llmConfigured"] is False


def test_status_shows_provider_and_model(make_client):
    def status(**overrides):
        settings = test_settings(admin_token="admin", llm_provider="gigachat", gigachat_credentials="giga-secret",
                                 gigachat_fallback_models="GigaChat-2-Max", **overrides)
        with make_client(settings) as client:
            return client.get("/v1/status", headers={"Authorization": "Bearer admin"}).json()["llm"]

    llm = status(llm_proxy_url="http://llm_proxy:4000")
    assert llm["provider"] == "gigachat" and llm["model"] == "GigaChat-2-Pro"
    assert llm["fallbackModels"] == ["GigaChat-2-Max"]
    # Без прокси шлюз запасные модели не перебирает: показывать их нельзя.
    assert status()["fallbackModels"] == []
