"""Подставные OpenRouter и AutoGRAPH: одно ASGI-приложение для pytest (через httpx.ASGITransport) и для
smoke.sh (отдельный контейнер `fakes`, `uvicorn fakes:app`). Реальные ключи и учётные данные не нужны.

- `POST /openrouter/api/v1/chat/completions` — модель без tools: принимает только ключ `test-key`, на любой
  вопрос честно отвечает, что данных нет. Тела запросов копятся в `app.state.llm_requests`.
- `GET /autograph/ServiceJSON/EnumSchemas?session=` — `valid-token` → схема `schema-1`; `down` → 500;
  остальное → 401. Число обращений — `app.state.enum_schemas_calls`.
"""

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

FAKE_LLM_KEY = "test-key"
VALID_TOKEN = "valid-token"
SCHEMA_ID = "schema-1"
NO_DATA_REPLY = "Данных о парке у меня пока нет: инструменты для телеметрии и аномалий ещё не подключены."


def create_fakes() -> FastAPI:
    app = FastAPI(title="Подставные OpenRouter и AutoGRAPH")
    app.state.llm_requests = []
    app.state.enum_schemas_calls = 0

    @app.post("/openrouter/api/v1/chat/completions")
    async def completions(request: Request) -> JSONResponse:
        if request.headers.get("authorization") != f"Bearer {FAKE_LLM_KEY}":
            return JSONResponse({"error": {"message": "No auth credentials found", "code": 401}}, status_code=401)
        body = await request.json()
        app.state.llm_requests.append(body)
        return JSONResponse({
            "id": "gen-fake",
            "model": body.get("model"),
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": NO_DATA_REPLY}}],
        })

    @app.get("/autograph/ServiceJSON/EnumSchemas")
    async def enum_schemas(session: str = "") -> JSONResponse:
        app.state.enum_schemas_calls += 1
        if session == "down":
            return JSONResponse({"Message": "Internal error"}, status_code=500)
        if session != VALID_TOKEN:
            return JSONResponse({"Message": "Unauthorized"}, status_code=401)
        return JSONResponse([{"ID": SCHEMA_ID, "Name": "Тестовая схема"}])

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    return app


app = create_fakes()
