"""Подставные OpenRouter, GigaChat и AutoGRAPH: одно ASGI-приложение для pytest (через httpx.ASGITransport) и для
smoke.sh (отдельный контейнер `fakes`, `uvicorn fakes:app`). Реальные ключи и учётные данные не нужны.

OpenRouter `POST /openrouter/api/v1/chat/completions` принимает только ключ `test-key`. Тела запросов копятся в
`app.state.llm_requests` (`GET /openrouter/_requests`); `POST /openrouter/_failures` ставит в очередь следующие
ответы — ошибки с кодом и Retry-After или ответ запасной модели (smoke.sh проверяет повторы и переходы прокси).
Резервная модель вне OpenRouter — `POST /reserve/v1/chat/completions` (ключ `reserve-key`, тот же сценарий), журнал —
`GET /reserve/_requests`. Модель без tools честно отвечает, что данных нет; с tools ведёт себя по сценарию:
list_vehicles → get_vehicle_summary первой машины → ответ с числом из сводки (средний уровень топлива).

GigaChat — строгий, по правилам спецификации и справки. `POST /gigachat/oauth`: ключ `test-gigachat-key` в Basic, RqUID
(uuid4), scope; срок токена — `expires_at` в миллисекундах или секундах. `POST /gigachat/v1/chat/completions` (тело — поля
спецификации, лишние — 422): нет User-Agent — 403; токен неизвестен или истёк — 401; потоков больше предела — 429;
system не первым или не один — 422; результат функции не объект — 422; вызов функции с чужим functions_state_id
или без результата — 422; temperature не больше 0 — 422. Сценарий модели — тот же, один вызов функции за ответ. Управление —
`POST /gigachat/_config`, журнал — `GET /gigachat/_requests`, счётчики — `GET /gigachat/_stats`.

AutoGRAPH `/autograph/ServiceJSON/`:
- `Login?UserName=&Password=&UTCOffset=` — `Пётр` / `fake-password` → новый токен `login-token-<n>` с правами
  `valid-token`, `Иван` / `fake-password` — с правами `other-token`; иначе 401. Входы — `app.state.logins`.
- `EnumSchemas?session=` — `valid-token` и `other-token` → схема `schema-1`, `foreign-token` → `schema-2`;
  `down` → 500; токены из `app.state.expired` и остальное → 401.
- `EnumDevices` — `valid-token`: veh-1, veh-2 и veh-3 (Allowed=false); `other-token`: только veh-1;
  `foreign-token` (другая схема): veh-2 — та же машина в чужой схеме.
- `EnumParameters`, `GetTripTables` — детерминированные данные (точка в минуту): у veh-1 топливо 250 л, у veh-2 —
  180 л и перегрев с 30-й по 39-ю минуту каждого часа; обороты всегда 0, давление масла не приходит вовсе.

SMTP — `FakeSmtp`: принимает письма в память (`messages`), AUTH PLAIN/LOGIN с паролем `smtp-pass`; адреса из
`reject` получают 550 на RCPT, `fail_data` — 451 на DATA, `drop_after_data` — связь рвётся после текста письма. В контейнере smoke.sh запускается вместе с приложением,
если задан FAKES_SMTP_PORT; принятые письма — `GET /smtp/messages`.

predictive_antifraud `POST /predictive/v1/predictive/analyze` и `/predictive/v1/antifraud/check` (ключ `pa-key`):
режим `app.state.analytics` — not_ready (по умолчанию, как сервис без моделей), ready, found, down, overloaded.

С tools модель ведёт сценарий: вопрос об аномалиях/нарушениях/проверке → check_vehicle, иначе get_vehicle_summary;
«какие аномалии [дд.мм]» → list_anomalies; «что решили» → get_anomaly по id аномалии из контекста карточки.
Ответы сжимаются gzip. Число обращений — `app.state.calls`, параметры запросов GetTripTables —
`app.state.trip_requests`; машины из `app.state.trip_tables_down` получают на GetTripTables 500.
"""

import asyncio
import base64
import email
import email.policy
import json
import os
import re
import socketserver
import threading
import uuid
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import JSONResponse, Response

FAKE_LLM_KEY = "test-key"
FAKE_RESERVE_KEY = "reserve-key"
FAKE_GIGACHAT_KEY = "test-gigachat-key"
VALID_TOKEN = "valid-token"
OTHER_TOKEN = "other-token"
FOREIGN_TOKEN = "foreign-token"
SCHEMA_ID = "schema-1"
FOREIGN_SCHEMA_ID = "schema-2"
SCHEMAS = {VALID_TOKEN: SCHEMA_ID, OTHER_TOKEN: SCHEMA_ID, FOREIGN_TOKEN: FOREIGN_SCHEMA_ID}
NO_DATA_REPLY = "Данных о парке у меня пока нет: инструменты для телеметрии и аномалий ещё не подключены."

FUEL = {"veh-1": 250.0, "veh-2": 180.0}
OVERHEAT = {"veh-2"}

# Учётные записи для входа стенда по сохранённому паролю: логин → чьи права получает выданный токен.
LOGIN_PASSWORD = "fake-password"
LOGINS = {"Пётр": VALID_TOKEN, "Иван": OTHER_TOKEN}

FAKE_PA_KEY = "pa-key"
# Режимы подставного predictive_antifraud (app.state.analytics): not_ready — моделей нет (как настоящий сервис без
# моделей), ready — модели отработали без аномалий, found — модель нашла аномалию, down — 500, overloaded — 503
# (предел нагрузки PA_MAX_CONCURRENCY).
ANALYTICS_MODES = ("not_ready", "ready", "found", "down", "overloaded")

DEVICES = {
    VALID_TOKEN: [
        {"ID": "veh-1", "ParentID": "g-1", "Name": "FAW №1", "Serial": 101, "Allowed": True},
        {"ID": "veh-2", "Name": "Урал NEXT А001АА", "Serial": 102},
        {"ID": "veh-3", "Name": "Чужой прибор", "Serial": 103, "Allowed": False},
    ],
    OTHER_TOKEN: [
        {"ID": "veh-1", "ParentID": "g-1", "Name": "FAW №1", "Serial": 101, "Allowed": True},
    ],
    FOREIGN_TOKEN: [
        {"ID": "veh-2", "Name": "Урал NEXT А001АА", "Serial": 102},
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


SMTP_USER = "petrovich"
SMTP_PASSWORD = "smtp-pass"


class FakeSmtp:
    """Подставной SMTP-сервер в отдельном потоке (часть протокола, которую использует smtplib без TLS)."""

    def __init__(self, host: str = "127.0.0.1", port: int = 0):
        self.messages: list[dict] = []
        self.reject: set[str] = set()
        self.fail_data = False
        # Принять текст письма и оборвать связь, не ответив 250 (письмо «могло уйти»).
        self.drop_after_data = False
        self.logins: list[str] = []
        fake = self

        class Handler(socketserver.StreamRequestHandler):
            def reply(self, text: str) -> None:
                self.wfile.write(text.encode() + b"\r\n")

            def line(self) -> str:
                return self.rfile.readline().decode("utf-8", "replace").rstrip("\r\n")

            def auth(self, user: str, password: str) -> None:
                fake.logins.append(user)
                self.reply("235 ok" if password == SMTP_PASSWORD else "535 bad credentials")

            def handle(self) -> None:
                sender, rcpts = None, []
                self.reply("220 fake ESMTP")
                while True:
                    raw = self.rfile.readline()
                    if not raw:
                        return
                    cmd = raw.decode("utf-8", "replace").rstrip("\r\n")
                    up = cmd.upper()
                    if up.startswith("EHLO"):
                        self.wfile.write(b"250-fake\r\n250-AUTH PLAIN LOGIN\r\n250-8BITMIME\r\n250 SMTPUTF8\r\n")
                    elif up.startswith("HELO") or up.startswith("NOOP"):
                        self.reply("250 ok")
                    elif up.startswith("AUTH PLAIN"):
                        token = cmd[10:].strip()
                        if not token:
                            self.reply("334 ")
                            token = self.line()
                        _, user, password = base64.b64decode(token).decode().split("\0")
                        self.auth(user, password)
                    elif up.startswith("AUTH LOGIN"):
                        self.reply("334 VXNlcm5hbWU6")
                        user = base64.b64decode(self.line()).decode()
                        self.reply("334 UGFzc3dvcmQ6")
                        self.auth(user, base64.b64decode(self.line()).decode())
                    elif up.startswith("MAIL FROM:"):
                        sender, rcpts = cmd[10:].split()[0].strip("<>"), []
                        self.reply("250 ok")
                    elif up.startswith("RCPT TO:"):
                        address = cmd[8:].split()[0].strip("<>")
                        if address in fake.reject:
                            self.reply("550 no such user")
                        else:
                            rcpts.append(address)
                            self.reply("250 ok")
                    elif up == "DATA":
                        if fake.fail_data:
                            self.reply("451 try later")
                            continue
                        self.reply("354 end with .")
                        lines = []
                        while (row := self.rfile.readline()) not in (b".\r\n", b""):
                            lines.append(row[1:] if row.startswith(b"..") else row)
                        msg = email.message_from_bytes(b"".join(lines), policy=email.policy.default)
                        fake.messages.append({"from": sender, "to": list(rcpts), "subject": msg["Subject"],
                                              "body": msg.get_content(), "headers": dict(msg.items())})
                        if fake.drop_after_data:
                            return
                        self.reply("250 queued")
                    elif up.startswith("RSET"):
                        sender, rcpts = None, []
                        self.reply("250 ok")
                    elif up.startswith("QUIT"):
                        self.reply("221 bye")
                        return
                    else:
                        self.reply("502 not implemented")

        socketserver.ThreadingTCPServer.allow_reuse_address = True
        self.server = socketserver.ThreadingTCPServer((host, port), Handler)
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]

    def start(self) -> "FakeSmtp":
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        return self

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()


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
        if "письм" in question:
            return _call("call-1", "list_recipients", {})
        if "что решили" in question:
            context = " ".join(m["content"] for m in messages if m.get("role") == "system")
            found = re.search(r'"id": "([^"]+)"', context)
            return _call("call-1", "get_anomaly", {"id": found.group(1) if found else "нет-в-контексте"})
        if "какие аномалии" in question:
            # «… 16.09» — за эти сутки 2026 года, иначе период по умолчанию.
            day = re.search(r"(\d{2})\.(\d{2})", question)
            period = {"from": f"2026-{day[2]}-{day[1]}T00:00", "to": f"2026-{day[2]}-{day[1]}T23:59"} if day else {}
            return _call("call-1", "list_anomalies", period)
        return _call("call-1", "list_vehicles", {})
    last = results[-1]
    if "error" in last:
        return {"role": "assistant", "content": f"Данных об этом у меня нет: {last['error']}"}
    if "subject" in last and "status" in last:
        names = ", ".join(r["name"] for r in last["recipients"])
        return {"role": "assistant", "content": f"Черновик письма «{last['subject']}» для {names} готов — "
                "отправьте его кнопкой в приложении."}
    if "recipients" in last:
        if not last["recipients"]:
            return {"role": "assistant", "content": last["message"]}
        mechanic = next((r for r in last["recipients"] if r["role"] == "Механик"), last["recipients"][0])
        return _call("call-2", "draft_email", {
            "recipient_ids": [mechanic["id"]], "subject": "Перегрев: Урал NEXT А001АА",
            "body": "Прошу проверить систему охлаждения: температура ОЖ поднималась до 108 °C.",
        })
    if "decisions" in last:
        a = last["anomaly"]
        text = f"{a['vehicle']['name']}, {a['title']}: {a['status']}"
        if a["falseAlarmReason"]:
            text += f" ({a['falseAlarmReason']})"
        if a["resolvedBy"]:
            text += f", решил {a['resolvedBy']}"
        return {"role": "assistant", "content": text + "."}
    if "lastScanAt" in last:
        if last.get("message") and not last["total"]:
            return {"role": "assistant", "content": last["message"]}
        titles = ", ".join(sorted({a["title"] for a in last["anomalies"]}))
        return {"role": "assistant", "content": f"В хранилище аномалий за период: {last['total']} ({titles})."}
    if "vehicles" in last:
        if not last["vehicles"]:
            return {"role": "assistant", "content": "Машин в схеме нет."}
        vehicle = next((v for v in last["vehicles"] if v["name"].split()[0].lower() in question), last["vehicles"][0])
        tool = "check_vehicle" if about_anomalies else "get_vehicle_summary"
        args = {"vehicle_id": vehicle["id"]}
        if "прошл" in question:
            # «На прошлой неделе» — закрытый период (кэш телеметрии по смыслу данных).
            args |= {"from": "2026-09-07T00:00", "to": "2026-09-14T00:00"}
        return _call("call-2", tool, args)
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


def _completion(body: dict, model: str | None) -> dict:
    """Ответ chat completions в полном формате OpenAI: его разбирает и стенд, и прокси LiteLLM."""
    message = _scripted_reply(body["messages"]) if body.get("tools") else {"role": "assistant", "content": NO_DATA_REPLY}
    return {
        "id": "gen-fake",
        "object": "chat.completion",
        "created": 1788000000,
        "model": model,
        # Как у OpenRouter: какой провайдер ответил.
        "provider": "FakeProvider",
        "choices": [{"index": 0, "finish_reason": "tool_calls" if message.get("tool_calls") else "stop",
                     "message": message}],
        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
    }


def _call(call_id: str, name: str, arguments: dict) -> dict:
    return {"role": "assistant", "content": None, "tool_calls": [
        {"id": call_id, "type": "function", "function": {"name": name, "arguments": json.dumps(arguments)}},
    ]}


def _gigachat_error(status: int, message: str) -> JSONResponse:
    return JSONResponse({"status": status, "message": message}, status_code=status)


GIGACHAT_FIELDS = {"model", "messages", "function_call", "functions", "temperature", "top_p", "stream", "max_tokens",
                   "repetition_penalty", "update_interval", "response_format"}


def _gigachat_problem(body: dict, issued: set[str], arguments_format: str) -> str | None:
    """Текст ошибки 422 по правилам спецификации GigaChat; None — запрос верный."""
    unknown = set(body) - GIGACHAT_FIELDS
    if unknown:
        return f"Invalid params: unknown fields {sorted(unknown)}"
    if not body.get("model") or not body.get("messages"):
        return "Invalid params: model and messages are required"
    if "temperature" in body and body["temperature"] <= 0:
        return "Invalid params: temperature must be in range (0, +inf)"
    messages = body["messages"]
    for i, m in enumerate(messages):
        role = m.get("role")
        if role not in ("system", "user", "assistant", "function"):
            return f"Invalid params: unknown role {role}"
        if not isinstance(m.get("content"), str):
            return "Invalid params: content must be a string"
        if role == "system" and i != 0:
            return "Invalid params: system message must be the first message"
        if role == "function":
            if set(m) != {"role", "content"}:
                return "Invalid params: function message has only content"
            try:
                result = json.loads(m["content"])
            except ValueError:
                result = None
            if not isinstance(result, dict):
                return "Invalid params: function result must be a JSON object"
        call = m.get("function_call")
        if call is not None:
            # Обязательность поля спецификация не заявляет, но чужое значение подозрительно: цикл шлюза его не выдумывает.
            if "functions_state_id" in m and m["functions_state_id"] not in issued:
                return "Invalid params: unknown functions_state_id"
            if i + 1 == len(messages) or messages[i + 1].get("role") != "function":
                return "Invalid params: every assistant function call must have a result in history"
            expected = str if arguments_format == "string" else dict
            if not isinstance(call.get("arguments"), expected):
                return "Invalid params: function_call.arguments has a wrong type"
    return None


def _gigachat_completion(body: dict, issued: set[str], arguments_format: str) -> dict:
    """Сценарий _scripted_reply в формате GigaChat: один вызов функции за ответ."""
    if body.get("functions"):
        history = [{"role": "tool", "content": m["content"]} if m["role"] == "function" else m for m in body["messages"]]
        scripted = _scripted_reply(history)
    else:
        scripted = {"role": "assistant", "content": NO_DATA_REPLY}
    message: dict = {"role": "assistant", "content": scripted.get("content") or ""}
    finish = "stop"
    if scripted.get("tool_calls"):
        fn = scripted["tool_calls"][0]["function"]
        arguments = fn["arguments"] if arguments_format == "string" else json.loads(fn["arguments"])
        state = str(uuid.uuid4())
        issued.add(state)
        message |= {"function_call": {"name": fn["name"], "arguments": arguments}, "functions_state_id": state}
        finish = "function_call"
    return {"choices": [{"message": message, "index": 0, "finish_reason": finish}], "created": 1788000000,
            "model": f"{body['model']}:2.0.28.2", "object": "chat.completion",
            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "precached_prompt_tokens": 0, "total_tokens": 15}}


def _install_gigachat(app: FastAPI) -> None:
    state = app.state
    state.gigachat_requests = []
    state.gigachat_failures = []
    state.gigachat_issued = set()
    state.gigachat_tokens = set()
    state.gigachat_oauth = []
    state.gigachat_user_agents = []
    state.gigachat_active = 0
    state.gigachat_peak = 0
    state.gigachat_rejected = 0
    # Предел потоков (PERS — 1), задержка ответа, с, срок токена, с, единицы expires_at и вид arguments.
    state.gigachat_max_streams = 1
    state.gigachat_delay = 0.0
    state.gigachat_token_lifetime = 1800
    state.gigachat_expires_unit = "ms"
    state.gigachat_arguments = "object"

    @app.post("/gigachat/oauth")
    async def gigachat_oauth(request: Request) -> JSONResponse:
        form = (await request.body()).decode()
        state.gigachat_oauth.append({"scope": form, "rquid": request.headers.get("rquid")})
        if request.headers.get("authorization") != f"Basic {FAKE_GIGACHAT_KEY}":
            return JSONResponse({"code": 6, "message": "credentials doesn't match db data"}, status_code=401)
        try:
            uuid.UUID(request.headers.get("rquid", ""), version=4)
        except ValueError:
            return _gigachat_error(400, "Bad request: RqUID")
        if not form.startswith("scope=GIGACHAT_API_"):
            return _gigachat_error(400, "Bad request: scope")
        token = f"giga-token-{len(state.gigachat_oauth)}"
        state.gigachat_tokens.add(token)
        expires = datetime.now().timestamp() + state.gigachat_token_lifetime
        value = int(expires * 1000) if state.gigachat_expires_unit == "ms" else int(expires)
        return JSONResponse({"access_token": token, "expires_at": value})

    @app.post("/gigachat/v1/chat/completions")
    async def gigachat_completions(request: Request) -> JSONResponse:
        agent = request.headers.get("user-agent")
        state.gigachat_user_agents.append(agent)
        if not agent:
            return _gigachat_error(403, "Forbidden")
        token = request.headers.get("authorization", "").removeprefix("Bearer ")
        if token not in state.gigachat_tokens:
            return _gigachat_error(401, "Token has expired")
        if state.gigachat_active >= state.gigachat_max_streams:
            state.gigachat_rejected += 1
            return _gigachat_error(429, "Too many requests")
        state.gigachat_active += 1
        state.gigachat_peak = max(state.gigachat_peak, state.gigachat_active)
        try:
            body = await request.json()
            state.gigachat_requests.append(body)
            if state.gigachat_delay:
                await asyncio.sleep(state.gigachat_delay)
            if state.gigachat_failures:
                failure = state.gigachat_failures.pop(0)
                if "status" in failure:
                    headers = {"Retry-After": str(failure["retryAfter"])} if "retryAfter" in failure else None
                    return JSONResponse({"status": failure["status"], "message": failure.get("message", "Error")},
                                        status_code=failure["status"], headers=headers)
                return JSONResponse({"choices": [{"message": {"role": "assistant", "content": failure.get("content", "")},
                                                  "index": 0, "finish_reason": failure["finishReason"]}],
                                     "created": 1788000000, "model": body.get("model"), "object": "chat.completion",
                                     "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})
            problem = _gigachat_problem(body, state.gigachat_issued, state.gigachat_arguments)
            if problem:
                return _gigachat_error(422, problem)
            return JSONResponse(_gigachat_completion(body, state.gigachat_issued, state.gigachat_arguments))
        finally:
            state.gigachat_active -= 1

    @app.post("/gigachat/_config")
    async def gigachat_config(request: Request) -> dict[str, str]:
        """Настройки подставного GigaChat и очередь ответов: failures — ошибки {"status": 402} и ответы
        {"finishReason": "blacklist", "content": "…"}. Журналы очищаются."""
        data = await request.json()
        state.gigachat_failures = list(data.get("failures", []))
        state.gigachat_delay = data.get("delay", 0.0)
        state.gigachat_max_streams = data.get("maxStreams", 1)
        state.gigachat_requests.clear()
        state.gigachat_user_agents.clear()
        state.gigachat_peak = 0
        state.gigachat_rejected = 0
        return {"status": "ok"}

    @app.get("/gigachat/_requests")
    async def gigachat_requests() -> list[dict]:
        return state.gigachat_requests

    @app.get("/gigachat/_stats")
    async def gigachat_stats() -> dict[str, int]:
        return {"requests": len(state.gigachat_requests), "oauth": len(state.gigachat_oauth),
                "peak": state.gigachat_peak, "rejected": state.gigachat_rejected}


def create_fakes() -> FastAPI:
    app = FastAPI(title="Подставные OpenRouter, GigaChat и AutoGRAPH")
    _install_gigachat(app)
    # AutoGRAPH отдаёт GetTripTables в gzip — клиент стенда должен это принимать.
    app.add_middleware(GZipMiddleware, minimum_size=500)
    app.state.llm_requests = []
    app.state.llm_failures = []
    app.state.reserve_requests = []
    app.state.calls = Counter()
    app.state.trip_requests = []
    app.state.parameters = {}
    app.state.raw_trip_tables = {}
    app.state.analytics = "not_ready"
    app.state.analytics_version = "fake-1"
    app.state.analytics_requests = []
    app.state.trip_tables_down = set()
    app.state.expired = set()
    app.state.issued = {}
    app.state.logins = []

    @app.get("/predictive/ready")
    async def analytics_ready() -> JSONResponse:
        """Как /ready predictive_antifraud: модели загружены в режимах ready и found (версия — app.state.analytics_version)."""
        app.state.calls["ready"] += 1
        if app.state.analytics == "down":
            return JSONResponse({"detail": "Internal Server Error"}, status_code=500)
        loaded = app.state.analytics in ("ready", "found")
        version = app.state.analytics_version if loaded else None
        return JSONResponse({"models": [{"name": n, "ready": loaded, "version": version}
                                        for n in ("predictive", "antifraud")]})

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
        if mode == "overloaded":
            return JSONResponse({"detail": "Сервис аналитики перегружен: очередь заполнена, повторите позже"},
                                status_code=503, headers={"Retry-After": "5"})
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
        return JSONResponse({"ready": True, "modelVersion": app.state.analytics_version, "anomalies": anomalies})

    @app.post("/openrouter/api/v1/chat/completions")
    async def completions(request: Request) -> JSONResponse:
        if request.headers.get("authorization") != f"Bearer {FAKE_LLM_KEY}":
            return JSONResponse({"error": {"message": "No auth credentials found", "code": 401}}, status_code=401)
        body = await request.json()
        app.state.llm_requests.append(body)
        if app.state.llm_failures:
            failure = app.state.llm_failures.pop(0)
            if "status" not in failure:
                # Ответила запасная модель из поля models.
                return JSONResponse(_completion(body, failure["model"]))
            headers = {"Retry-After": str(failure["retryAfter"])} if failure.get("retryAfter") is not None else None
            return JSONResponse({"error": {"message": failure.get("message", "Provider error"), "code": failure["status"]}},
                                status_code=failure["status"], headers=headers)
        return JSONResponse(_completion(body, body.get("model")))

    @app.post("/reserve/v1/chat/completions")
    async def reserve_completions(request: Request) -> JSONResponse:
        """Резервная модель вне OpenRouter (OpenAI-совместимый адрес, ключ reserve-key): тот же сценарий tools."""
        if request.headers.get("authorization") != f"Bearer {FAKE_RESERVE_KEY}":
            return JSONResponse({"error": {"message": "Unauthorized", "code": 401}}, status_code=401)
        body = await request.json()
        app.state.reserve_requests.append(body)
        return JSONResponse(_completion(body, body.get("model")))

    @app.get("/reserve/_requests")
    async def reserve_requests() -> list[dict]:
        return [{k: v for k, v in b.items() if k not in ("messages", "tools")} for b in app.state.reserve_requests]

    @app.post("/openrouter/_failures")
    async def set_llm_failures(request: Request) -> dict[str, int]:
        """Следующие ответы подставного OpenRouter: ошибки [{"status": 429, "retryAfter": 1}, …] или ответ запасной
        модели {"model": "x/fallback"}. Журнал запросов очищается."""
        app.state.llm_failures = list(await request.json())
        app.state.llm_requests.clear()
        app.state.reserve_requests.clear()
        return {"queued": len(app.state.llm_failures)}

    @app.get("/openrouter/_requests")
    async def llm_requests() -> list[dict]:
        """Тела запросов к подставному OpenRouter (без сообщений — они длинные)."""
        return [{k: v for k, v in b.items() if k not in ("messages", "tools")} for b in app.state.llm_requests]

    def rights(session: str) -> str:
        """Токен, чьи права у сессии: выданный входом по паролю — права учётной записи."""
        return app.state.issued.get(session, session)

    def check(session: str) -> JSONResponse | None:
        if session == "down":
            return JSONResponse({"Message": "Internal error"}, status_code=500)
        if session in app.state.expired or rights(session) not in DEVICES:
            return JSONResponse({"Message": "Unauthorized"}, status_code=401)
        return None

    @app.get("/autograph/ServiceJSON/Login")
    async def login(UserName: str = "", Password: str = "", UTCOffset: int = 0) -> JSONResponse:
        app.state.calls["Login"] += 1
        app.state.logins.append({"UserName": UserName, "UTCOffset": UTCOffset})
        if Password != LOGIN_PASSWORD or UserName not in LOGINS:
            return JSONResponse({"Message": "Unauthorized"}, status_code=401)
        token = f"login-token-{len(app.state.issued) + 1}"
        app.state.issued[token] = LOGINS[UserName]
        return JSONResponse(token)

    @app.get("/autograph/ServiceJSON/EnumSchemas")
    async def enum_schemas(session: str = "") -> JSONResponse:
        app.state.calls["EnumSchemas"] += 1
        return check(session) or JSONResponse([{"ID": SCHEMAS[rights(session)], "Name": "Тестовая схема"}])

    @app.get("/autograph/ServiceJSON/EnumDevices")
    async def enum_devices(session: str = "", schemaID: str = "") -> JSONResponse:
        app.state.calls["EnumDevices"] += 1
        if (error := check(session)) is not None:
            return error
        owner = rights(session)
        return JSONResponse({
            "Groups": [{"ID": "g-1", "Name": "Колонна 1"}],
            "Items": DEVICES[owner] if schemaID == SCHEMAS[owner] else [],
        })

    @app.get("/autograph/ServiceJSON/EnumParameters")
    async def enum_parameters(session: str = "", IDs: str = "") -> JSONResponse:
        app.state.calls["EnumParameters"] += 1
        app.state.calls[f"EnumParameters:{IDs}"] += 1
        return check(session) or JSONResponse(
            {IDs: {"OnlineParams": app.state.parameters.get(IDs, PARAMETERS), "FinalParams": []}}
        )

    @app.get("/autograph/ServiceJSON/GetTripTables")
    async def get_trip_tables(request: Request, session: str = "", IDs: str = "", SD: str = "", ED: str = "",
                              onlineParams: str = "") -> Response:
        app.state.calls["GetTripTables"] += 1
        app.state.calls[f"GetTripTables:{IDs}"] += 1
        app.state.trip_requests.append({**request.query_params, "urlLength": len(str(request.url))})
        if (error := check(session)) is not None:
            return error
        if IDs in app.state.trip_tables_down:
            return JSONResponse({"Message": "Internal error"}, status_code=500)
        raw = app.state.raw_trip_tables.get(IDs)
        if raw is not None:
            return Response(raw[(app.state.calls["GetTripTables"] - 1) % len(raw)], media_type="application/json")
        sd, ed = datetime.strptime(SD, "%Y%m%d-%H%M"), datetime.strptime(ED, "%Y%m%d-%H%M")
        return JSONResponse(trip_tables(IDs, sd, ed, onlineParams.split(",")))

    app.state.smtp = None

    @app.get("/smtp/messages")
    async def smtp_messages() -> list[dict]:
        return [{k: v for k, v in m.items() if k != "headers"} for m in (app.state.smtp.messages if app.state.smtp else [])]

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "ok"}

    return app


app = create_fakes()
# smoke.sh: подставной SMTP в том же контейнере.
if os.environ.get("FAKES_SMTP_PORT"):
    app.state.smtp = FakeSmtp("0.0.0.0", int(os.environ["FAKES_SMTP_PORT"])).start()
