"""Подставные OpenRouter и AutoGRAPH: одно ASGI-приложение для pytest (через httpx.ASGITransport) и для
smoke.sh (отдельный контейнер `fakes`, `uvicorn fakes:app`). Реальные ключи и учётные данные не нужны.

OpenRouter `POST /openrouter/api/v1/chat/completions` принимает только ключ `test-key`. Тела запросов копятся в
`app.state.llm_requests`. Модель без tools честно отвечает, что данных нет; с tools ведёт себя по сценарию:
list_vehicles → get_vehicle_summary первой машины → ответ с числом из сводки (средний уровень топлива).

AutoGRAPH `/autograph/ServiceJSON/`:
- `EnumSchemas?session=` — `valid-token` и `other-token` → схема `schema-1`; `down` → 500; остальное → 401.
- `EnumDevices` — `valid-token`: veh-1, veh-2 и veh-3 (Allowed=false); `other-token`: только veh-1.
- `EnumParameters`, `GetTripTables` — детерминированные данные (точка в минуту): у veh-1 топливо 250 л, у veh-2 —
  180 л и перегрев с 30-й по 39-ю минуту каждого часа; обороты всегда 0, давление масла не приходит вовсе.

predictive_antifraud `POST /predictive/v1/predictive/analyze` и `/predictive/v1/antifraud/check` (ключ `pa-key`):
режим `app.state.analytics` — not_ready (по умолчанию, как сервис без моделей), ready, found, down.

С tools модель ведёт сценарий: вопрос об аномалиях/нарушениях/проверке → check_vehicle, иначе get_vehicle_summary. Ответы сжимаются gzip. Число обращений — `app.state.calls`,
  параметры запросов GetTripTables — `app.state.trip_requests`.
"""

import json
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import JSONResponse, Response

FAKE_LLM_KEY = "test-key"
VALID_TOKEN = "valid-token"
OTHER_TOKEN = "other-token"
SCHEMA_ID = "schema-1"
NO_DATA_REPLY = "Данных о парке у меня пока нет: инструменты для телеметрии и аномалий ещё не подключены."

FUEL = {"veh-1": 250.0, "veh-2": 180.0}
OVERHEAT = {"veh-2"}

FAKE_PA_KEY = "pa-key"
# Режимы подставного predictive_antifraud (app.state.analytics): not_ready — моделей нет (как настоящий сервис без
# моделей), ready — модели отработали без аномалий, found — модель нашла аномалию, down — 500.
ANALYTICS_MODES = ("not_ready", "ready", "found", "down")

DEVICES = {
    VALID_TOKEN: [
        {"ID": "veh-1", "ParentID": "g-1", "Name": "FAW №1", "Serial": 101, "Allowed": True},
        {"ID": "veh-2", "Name": "Урал NEXT А001АА", "Serial": 102},
        {"ID": "veh-3", "Name": "Чужой прибор", "Serial": 103, "Allowed": False},
    ],
    OTHER_TOKEN: [
        {"ID": "veh-1", "ParentID": "g-1", "Name": "FAW №1", "Serial": 101, "Allowed": True},
    ],
}

PARAMETERS = [
    {"Name": "TankMainFuelLevel", "Caption": "Уровень", "Unit": "л", "ReturnType": 4},
    {"Name": "Speed", "Caption": "Текущая", "Unit": "км/ч", "ReturnType": 4},
    {"Name": "Power", "Caption": "Питание", "ReturnType": 0},
    {"Name": "DIgnition", "Caption": "Зажигание", "ReturnType": 0},
    {"Name": "Rotation", "Caption": "Обороты", "Unit": "об/мин", "ReturnType": 4},
    {"Name": "TemperatureCOOL", "Caption": "Т ОЖ", "Unit": "°C", "ReturnType": 4},
    # Есть в EnumParameters, но значений в GetTripTables не приходит — «нет данных».
    {"Name": "PressureOIL", "Caption": "Давление масла", "Unit": "бар", "ReturnType": 4},
    {"Name": "DIgnitionOnParks", "Caption": "Накоп. МЧ ост.", "ReturnType": 6},
]


def trip_tables(vehicle_id: str, sd: datetime, ed: datetime, names: list[str]) -> dict:
    """Ответ GetTripTables: точка в минуту от SD до ED включительно, только запрошенные параметры."""
    times = []
    t = sd
    while t <= ed:
        times.append(t)
        t += timedelta(minutes=1)
    series = {
        "TankMainFuelLevel": [FUEL.get(vehicle_id, 100.0)] * len(times),
        "Speed": [60 if t.minute % 10 < 5 else 0 for t in times],
        "Power": [1] * len(times),
        "DIgnition": [True] * len(times),
        "Rotation": [0] * len(times),
        # У veh-2 каждый час с 30-й по 39-ю минуту перегрев 108 °C — правило overheat (CRITICAL).
        "TemperatureCOOL": [108.0 if vehicle_id in OVERHEAT and 30 <= t.minute < 40 else 85.0 for t in times],
    }
    values = [{"Name": n, "Values": series[n]} for n in names if n in series]
    return {vehicle_id: {"ID": vehicle_id, "Trips": [
        {"Index": 0, "DT": [t.strftime("%Y-%m-%dT%H:%M:%S") for t in times], "Values": values},
    ]}}


def golden_trip_tables(app: FastAPI, case_file: Path, vehicle_id: str) -> None:
    """Отдавать для машины входы и параметры эталона testdata/golden (tests/test_golden.py)."""
    case = json.loads(case_file.read_text(encoding="utf-8"))
    app.state.parameters[vehicle_id] = case["parameters"]
    app.state.raw_trip_tables[vehicle_id] = [(case_file.parent / name).read_bytes() for name in case["inputs"]]


def _scripted_reply(messages: list[dict]) -> dict:
    """Сценарий модели с tools: list_vehicles → get_vehicle_summary (или check_vehicle, если вопрос об аномалиях)
    → ответ по результату. Машина — та, чьё первое слово названия есть в вопросе, иначе первая в списке."""
    question = next(m["content"] for m in reversed(messages) if m.get("role") == "user").lower()
    about_anomalies = any(w in question for w in ("аномал", "наруш", "провер"))
    results = [json.loads(m["content"]) for m in messages if m.get("role") == "tool"]
    if not results:
        return _call("call-1", "list_vehicles", {})
    last = results[-1]
    if "error" in last:
        return {"role": "assistant", "content": f"Данных об этом у меня нет: {last['error']}"}
    if "vehicles" in last:
        if not last["vehicles"]:
            return {"role": "assistant", "content": "Машин в схеме нет."}
        vehicle = next((v for v in last["vehicles"] if v["name"].split()[0].lower() in question), last["vehicles"][0])
        tool = "check_vehicle" if about_anomalies else "get_vehicle_summary"
        return _call("call-2", tool, {"vehicle_id": vehicle["id"]})
    if "outcome" in last:
        name = last["vehicle"]["name"]
        if last["outcome"] == "anomalies_found":
            titles = ", ".join(sorted({a["title"] for a in last["anomalies"]}))
            text = f"{name}: найдено аномалий — {last['total']} ({titles})."
        elif last["outcome"] == "no_anomalies":
            text = f"{name}: правила и аналитика нарушений не нашли."
        else:
            text = f"{name}: по правилам нарушений нет, но предиктивная проверка недоступна."
        if last["outcome"] == "anomalies_found" and not last["predictiveCheckAvailable"]:
            text += " Предиктивная проверка недоступна."
        return {"role": "assistant", "content": text}
    fuel = next((p for p in last.get("parameters", []) if p["name"] == "TankMainFuelLevel"), None)
    if fuel is None:
        return {"role": "assistant", "content": "Данных о топливе за этот период у меня нет."}
    period = last["period"]
    return {"role": "assistant", "content": f"{last['vehicle']['name']}: средний уровень топлива {fuel['mean']} "
            f"{fuel['unit']} за {period['from']} — {period['to']}."}


def _call(call_id: str, name: str, arguments: dict) -> dict:
    return {"role": "assistant", "content": None, "tool_calls": [
        {"id": call_id, "type": "function", "function": {"name": name, "arguments": json.dumps(arguments)}},
    ]}


def create_fakes() -> FastAPI:
    app = FastAPI(title="Подставные OpenRouter и AutoGRAPH")
    # AutoGRAPH отдаёт GetTripTables в gzip — клиент стенда должен это принимать.
    app.add_middleware(GZipMiddleware, minimum_size=500)
    app.state.llm_requests = []
    app.state.calls = Counter()
    app.state.trip_requests = []
    app.state.parameters = {}
    app.state.raw_trip_tables = {}
    app.state.analytics = "not_ready"
    app.state.analytics_requests = []

    @app.post("/predictive/v1/{service}/{action}")
    async def analytics(service: str, action: str, request: Request) -> JSONResponse:
        if f"{service}/{action}" not in ("predictive/analyze", "antifraud/check"):
            return JSONResponse({"detail": "Not Found"}, status_code=404)
        if request.headers.get("x-api-key") != FAKE_PA_KEY:
            return JSONResponse({"detail": "Неверный или отсутствующий X-API-Key"}, status_code=401)
        body = await request.json()
        app.state.analytics_requests.append((service, body))
        mode = app.state.analytics
        if mode == "down":
            return JSONResponse({"detail": "Internal Server Error"}, status_code=500)
        if mode == "not_ready":
            return JSONResponse({"ready": False, "modelVersion": None, "anomalies": []})
        anomalies = []
        if mode == "found" and service == "antifraud":
            vid, first = body["vehicle"]["id"], next(iter(body["tables"].values()))["timestamps"][0]
            anomalies.append({
                "id": f"ml|fraud|{vid}|TankMainFuelLevel|{first}", "vehicleId": vid,
                "vehicleName": body["vehicle"]["name"], "category": "FUEL", "parameterName": "TankMainFuelLevel",
                "parameterCaption": "Уровень топлива", "eventTime": first, "detectedAt": 1788000000000,
                "severity": "WARNING", "title": "Антифрод: Уровень топлива",
                "description": "Модель оценила состояние как аномальное (score=0.91)", "value": 250.0, "score": 0.91,
                "source": "Антифрод (ML)",
            })
        return JSONResponse({"ready": True, "modelVersion": "fake-1", "anomalies": anomalies})

    @app.post("/openrouter/api/v1/chat/completions")
    async def completions(request: Request) -> JSONResponse:
        if request.headers.get("authorization") != f"Bearer {FAKE_LLM_KEY}":
            return JSONResponse({"error": {"message": "No auth credentials found", "code": 401}}, status_code=401)
        body = await request.json()
        app.state.llm_requests.append(body)
        message = _scripted_reply(body["messages"]) if body.get("tools") else \
            {"role": "assistant", "content": NO_DATA_REPLY}
        return JSONResponse({
            "id": "gen-fake",
            "model": body.get("model"),
            "choices": [{"index": 0, "finish_reason": "tool_calls" if message.get("tool_calls") else "stop",
                         "message": message}],
        })

    def check(session: str) -> JSONResponse | None:
        if session == "down":
            return JSONResponse({"Message": "Internal error"}, status_code=500)
        if session not in DEVICES:
            return JSONResponse({"Message": "Unauthorized"}, status_code=401)
        return None

    @app.get("/autograph/ServiceJSON/EnumSchemas")
    async def enum_schemas(session: str = "") -> JSONResponse:
        app.state.calls["EnumSchemas"] += 1
        return check(session) or JSONResponse([{"ID": SCHEMA_ID, "Name": "Тестовая схема"}])

    @app.get("/autograph/ServiceJSON/EnumDevices")
    async def enum_devices(session: str = "", schemaID: str = "") -> JSONResponse:
        app.state.calls["EnumDevices"] += 1
        return check(session) or JSONResponse({
            "Groups": [{"ID": "g-1", "Name": "Колонна 1"}],
            "Items": DEVICES[session] if schemaID == SCHEMA_ID else [],
        })

    @app.get("/autograph/ServiceJSON/EnumParameters")
    async def enum_parameters(session: str = "", IDs: str = "") -> JSONResponse:
        app.state.calls["EnumParameters"] += 1
        return check(session) or JSONResponse(
            {IDs: {"OnlineParams": app.state.parameters.get(IDs, PARAMETERS), "FinalParams": []}}
        )

    @app.get("/autograph/ServiceJSON/GetTripTables")
    async def get_trip_tables(request: Request, session: str = "", IDs: str = "", SD: str = "", ED: str = "",
                              onlineParams: str = "") -> Response:
        app.state.calls["GetTripTables"] += 1
        app.state.trip_requests.append({**request.query_params, "urlLength": len(str(request.url))})
        if (error := check(session)) is not None:
            return error
        raw = app.state.raw_trip_tables.get(IDs)
        if raw is not None:
            return Response(raw[(app.state.calls["GetTripTables"] - 1) % len(raw)], media_type="application/json")
        sd, ed = datetime.strptime(SD, "%Y%m%d-%H%M"), datetime.strptime(ED, "%Y%m%d-%H%M")
        return JSONResponse(trip_tables(IDs, sd, ed, onlineParams.split(",")))

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    return app


app = create_fakes()
