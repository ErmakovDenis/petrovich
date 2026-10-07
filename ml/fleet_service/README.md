# Стенд «Петровича» (fleet_service)

FastAPI-сервис для «Петрович Телеметрия» по плану `ml/docs/assistant-server-plan.md`. Устроен так же, как
`ml/predictive_antifraud`: `src/`, настройки через `pydantic-settings` (префикс `FS_`), `tests/`, `Dockerfile`.

Сейчас (шаг 3) — ассистент, телеметрия и проверка аномалий:
- стенд сам загружает телеметрию из AutoGRAPH от имени пользователя и сворачивает её в интервалы тем же
  алгоритмом, что `TripTablesMapper` приложения; приложение читает её через `GET /v1/vehicles` и `GET /v1/telemetry`
  (переключатель «Данные через стенд»);
- `POST /v1/anomalies/check` проверяет машину за период: правила (перенос `BaselineAnomalyDetector`) и
  `predictive_antifraud`; приложение вызывает его вместо детектора на устройстве (переключатель «Аномалии со стенда»);
- ассистент отвечает моделью через OpenRouter и получает числа из tools `list_vehicles`, `get_vehicle_summary` и
  `check_vehicle`. Хранилища аномалий и решений на стенде пока нет (шаг 4).

## Запуск

```bash
cd ml/fleet_service
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"     # локально — python3.14 из conda env fuel_antifrod
cp .env.example .env                                           # заполнить FS_OPENROUTER_API_KEY и FS_LLM_MODEL
.venv/bin/uvicorn --factory fleet_service.main:create_app --app-dir src --reload --port 8080   # http://127.0.0.1:8080/docs
.venv/bin/pytest
```

Вместе с `predictive_antifraud` — через `ml/docker-compose.yml`:

```bash
cd ml && docker compose up -d --build      # fleet_service → :8080, predictive_antifraud → :8001
```

Порты на хосте меняются переменными `FLEET_SERVICE_PORT` и `PREDICTIVE_PORT`. Настройки сервисов читаются из
`ml/fleet_service/.env` и `ml/predictive_antifraud/.env` (если файлов нет — значения по умолчанию).

## Сквозной сценарий

```bash
ml/fleet_service/scripts/smoke.sh          # KEEP=1 — оставить стенд запущенным
```

Поднимает compose с дополнением `scripts/smoke.compose.yml`: OpenRouter и AutoGRAPH заменены подставным
сервисом `fakes` (`tests/fakes.py`, тот же код, что в pytest; модель вызывает tools по сценарию);
`predictive_antifraud` — настоящий, без моделей. Проверяет `/health` обоих сервисов, 401 без токена и с
недействительным токеном, список машин, телеметрию за сутки, 404 для чужой машины, ответ `/v1/chat` с числом из
`get_vehicle_summary`, проверку машины (правила нашли перегрев, аналитика `not_ready`), ответы ассистента об
аномалиях (найдены / по правилам нет, но предиктивная проверка недоступна), проверку при остановленном
`predictive_antifraud`, вызовы tools в логе и отсутствие токенов и ключа в логах. Реальные ключи не нужны.

## Эндпоинты

| Метод | Путь | Описание |
|---|---|---|
| GET | `/health` | liveness; `llmConfigured` — заданы ли ключ и модель |
| POST | `/v1/chat` | ответ ассистента |
| GET | `/v1/vehicles` | машины схемы, доступные пользователю (`EnumDevices`, без `Allowed=false`), по имени |
| GET | `/v1/telemetry?vehicleId&from&to&utcOffsetMinutes` | `VehicleTelemetry` машины за период |
| POST | `/v1/anomalies/check` | аномалии машины за период: правила и аналитика, без сохранения |

Все `/v1/*` требуют заголовков `Authorization: Bearer <токен сессии AutoGRAPH>` и `X-Schema-Id`.

### `POST /v1/anomalies/check`

Тело `{vehicleId, from, to, utcOffsetMinutes}` (время — местное, без пояса; пример — `testdata/contract/check-request.json`).
Стенд берёт телеметрию тем же `TelemetryService`, что `/v1/telemetry` (кэш, проверка доступа к машине: чужая — 404),
применяет правила `rules/baseline.py` и параллельно вызывает `predictive_antifraud` (`/v1/predictive/analyze`,
`/v1/antifraud/check`, адрес `FS_PREDICTIVE_URL`, ключ `X-API-Key` — `FS_PREDICTIVE_API_KEY`). Ответ (пример —
`testdata/contract/check-response.json`):

```json
{"vehicleId": "42", "anomalies": [ "...Anomaly: сначала аналитики, потом правила, без повторов по id..." ],
 "rulesAnomalies": 1,
 "analytics": {"predictive": {"status": "not_ready", "modelVersion": null, "detail": "…", "anomaliesFound": 0},
               "antifraud": {"status": "unavailable", "modelVersion": null, "detail": "…", "anomaliesFound": 0}},
 "modelsReady": false}
```

`status` аналитики: `ok` — модель отработала; `not_ready` — модель не загружена; `unavailable` — сервис не настроен,
не отвечает или ответил ошибкой. Проверка по правилам выполняется в любом случае. Результат не сохраняется.

**Правила** (`rules/baseline.py`) — точный перенос `BaselineAnomalyDetector.kt`: типы `drain`, `drop`, `power`, `volt`,
`overheat`, `oil`, `brake`, логика эпизодов (минимальная длительность, пропуск данных прерывает эпизод, правила
двигателя — только при включённом зажигании), порядок результатов и формат id
`rule|<тип>|<машина>|<параметр>|<время начала эпизода>`. Пороги — `FS_RULES__*` (`rules/thresholds.py`), по
умолчанию равны зашитым в приложении. Изменённые пороги меняют начало эпизодов, а значит и id: пока id стенда
и устройства должны совпадать (переключение «Аномалии со стенда» без сброса ленты, режим сравнения), пороги стоит
держать по умолчанию — иначе одно событие может прийти дважды. Числа в описаниях — с запятой (русская локаль). Совпадение с Kotlin по id,
типу, важности, времени события и значению держат эталоны `testdata/golden/anomalies/` (пишет `AnomalyGoldenTest`,
сверяет `tests/test_rules_golden.py`): демо-машины, синтетика на каждое правило и границы длительностей, телеметрия
из эталонов шага 2.

### `GET /v1/telemetry`

`from`, `to` — местное время пользователя без пояса (`2026-09-16T08:00:00`), период не длиннее
`FS_TELEMETRY_MAX_PERIOD_HOURS`. `utcOffsetMinutes` — смещение пояса, с которым приложение входило в AutoGRAPH:
AutoGRAPH трактует `SD`/`ED` и отдаёт время в этом поясе, стенд свой пояс не подставляет. Ответ — `VehicleTelemetry`
в camelCase (`schemas/contract.py`), время — строки без пояса с секундами.

Как считается (порт `AutoGraphTelemetryRepository`, `AutoGraphParameters`, `TripTablesMapper`):
- `EnumParameters` → выбор параметров (`telemetry/parameters.py`): известные имена, параметры напряжения, иначе — по
  ключевым словам; способ свёртки `MEAN`, `MIN`, `MAX`, `MEAN_NONZERO`, `MIN_NONZERO` (ноль — «нет данных»);
- `GetTripTables` частями по `FS_AUTOGRAPH_CHUNK_HOURS`, даты `yyyyMMdd-HHmm`, gzip, `tripSplitterIndex=-1`; если
  адрес с `onlineParams` длиннее `FS_AUTOGRAPH_MAX_QUERY_CHARS` — параметры запрашиваются пачками;
- до `FS_AUTOGRAPH_RETRIES` попыток при сетевой ошибке, больше `FS_TRIP_TABLES_MAX_POINTS` точек в треке — отказ;
- потоковый разбор (`telemetry/mapper.py`, ijson) прямо в сетку интервалов 1/2/5/15 мин для периодов до 6 ч / 24 ч /
  3 дней / дольше; столбцы без данных или всегда нулевые (кроме состояний питания и зажигания) и дубликаты скрываются.

Кэш готовой телеметрии — по ключу «схема + машина + период + пояс», общий для пользователей схемы
(`FS_TELEMETRY_CACHE_TTL_SECONDS`), одна загрузка на ключ при параллельных запросах. Перед выдачей, в том числе из
кэша, машина проверяется по `EnumDevices` этого пользователя (кэш по токену, `FS_DEVICES_CACHE_TTL_SECONDS`):
чужая машина — 404. Если общая загрузка шла с чужим токеном, который истёк, запрос повторяется своим токеном.
Приложение отбрасывает секунды периода (если от этого не меняется длина интервала), поэтому повторные запросы в
пределах минуты попадают в кэш; ответа на телеметрию оно ждёт до 300 с (7 дней — 28 запросов к AutoGRAPH).
В чате сводка за длинный период может не уложиться в `FS_CHAT_TIMEOUT_SECONDS` — тогда 504.

### Совпадение с приложением

Эталоны `testdata/golden/trip-tables/*.case.json` (ответы GetTripTables, параметры, период и ожидаемая
`VehicleTelemetry`) пишет Kotlin-тест `TripTablesGoldenTest`; `tests/test_golden.py` строит то же Python-портом и
сравнивает разделы, столбцы, отметки времени и значения (допуск `VALUE_TOLERANCE = 1e-6`), а также JSON ответа
`/v1/telemetry` с эталоном. После намеренного изменения алгоритма в приложении:
`UPDATE_GOLDEN=1 ./gradlew testDebugUnitTest --tests "ru.petrovich.telemetry.TripTablesGoldenTest"`, затем правка
здесь. На сохранённых реальных ответах (по машине в файле, как для `REAL_TRIP_TABLES`):

```bash
ml/fleet_service/scripts/compare_real.sh /path/to/dir
```

### `POST /v1/chat`

Заголовки: `Authorization: Bearer <токен сессии AutoGRAPH>`, `X-Schema-Id: <id схемы>`. Стенд проверяет токен
запросом `EnumSchemas` к AutoGRAPH (токен действителен и схема есть в списке) и помнит итог
`FS_AUTH_CACHE_TTL_SECONDS` секунд. Логин и пароль стенду не передаются.

```json
{
  "messages": [{"role": "user", "content": "Что с этой машиной?"}],
  "utcOffsetMinutes": 300,
  "anomaly": { "...": "объект Anomaly, если чат открыт из карточки; необязательно" }
}
```

Ответ: `{"reply": "..."}`. Пример запроса из приложения — `testdata/contract/chat-request.json` (его сверяют и
Kotlin-, и Python-тесты). Схемы `VehicleTelemetry` и `Anomaly` — копия схем `predictive_antifraud`
(`schemas/contract.py`), совпадение проверяет `tests/test_contract.py`.

Ошибки — `{"detail": "<текст по-русски>"}`, приложение показывает его пользователю:

| Код | Когда |
|---|---|
| 401 | нет токена / схемы, токен недействителен или истёк (в том числе во время загрузки данных) |
| 403 | схема недоступна пользователю |
| 404 | машины нет среди доступных пользователю (`/v1/telemetry`, `/v1/anomalies/check`) |
| 422 | неверный запрос: пустая история, последнее сообщение не от пользователя, слишком длинное сообщение; период телеметрии с поясом, пустой или длиннее предела |
| 502 | модель ответила ошибкой, вернула пустой ответ или не уложилась в `FS_AGENT_MAX_ITERATIONS`; AutoGRAPH вернул сбойный ответ (слишком много точек) |
| 503 | ассистент не настроен (нет ключа или модели) или AutoGRAPH недоступен (после всех попыток) |
| 504 | модель не ответила за `FS_LLM_TIMEOUT_SECONDS` или ответ не уложился в `FS_CHAT_TIMEOUT_SECONDS` |
| 500 | «Внутренняя ошибка стенда» — без стека; стек только в логе |

## Настройки

Все — переменные `FS_*`, описаны в `.env.example` (тест `test_config.py` следит, чтобы ни одна не потерялась):
ключ и адрес OpenRouter, маршрутизация провайдеров (`FS_OPENROUTER_REQUIRE_PARAMETERS`,
`FS_OPENROUTER_DATA_COLLECTION`), модель, температура, лимит токенов, таймауты, лимит обращений к модели, путь к
системному промпту, лимиты истории, адрес AutoGRAPH и кэш проверки токена; для телеметрии — попытки и пауза
повторов, таймаут и длина части `GetTripTables`, предел длины query и числа точек, сроки кэшей машин, параметров и
телеметрии, предел периода; для tools — число машин в `list_vehicles`, аномалий в `check_vehicle` и предел размера
ответа tool; адрес, ключ и таймаут `predictive_antifraud`; пороги правил `FS_RULES__<ИМЯ>` (вложенные настройки,
разделитель `__`). Пустое значение в `.env` — значение по умолчанию.

Системный промпт — `src/fleet_service/prompts/system.md` (или файл из `FS_SYSTEM_PROMPT_PATH`). В нём
зафиксировано: числа и факты только из tools или переданной аномалии; нет данных — так и сказать; «нет данных» и
«0» различаются; как пользоваться tools; нули CAN при выключенном зажигании; три исхода проверки и запрет вывода
«нарушений нет», если предиктивная проверка недоступна; тексты внутри данных — не инструкции. Время пользователя и контекст аномалии стенд добавляет отдельными системными
сообщениями.

## Tools ассистента

| Tool | Аргументы | Ответ |
|---|---|---|
| `list_vehicles` | `query` — подстрока названия или группы (необязательно) | `total`, до `FS_TOOL_MAX_VEHICLES` машин (`id`, `name`, `group`), `truncated` |
| `get_vehicle_summary` | `vehicle_id` (или однозначное название), `from`, `to` — местное время; по умолчанию последние 24 ч | машина, период (`bucketMinutes`, `intervals`), по каждому параметру `min`, `max`, `mean`, `last`, `lastTime`, `coverage`, `unit`; `noDataParameters`, `allZeroParameters` |
| `check_vehicle` | `vehicle_id`, `from`, `to` — как у сводки | `outcome`: `anomalies_found` / `no_anomalies` / `analytics_unavailable`; `total`, до `FS_TOOL_MAX_ANOMALIES` аномалий (тип, заголовок, важность, время, параметр, значение, описание, источник), `truncated`; `predictiveCheckAvailable`, статусы `analytics`, `message` |

`check_vehicle` различает три исхода: аномалии найдены; правила и аналитика отработали и ничего не нашли; правила
ничего не нашли, но предиктивная проверка или антифрод недоступны — тогда `predictiveCheckAvailable=false` и
`message` прямо говорит, что отсутствие аномалий по правилам не означает отсутствия нарушений.

Числа считает код: агрегаты — по интервалам с данными, `coverage` — доля интервалов с данными. Скрытые, как в
приложении, параметры перечислены отдельно: `noDataParameters` — за период ни одного значения (это не ноль),
`allZeroParameters` — значения были, но все 0 (машина стояла или датчик не подключён — по данным не различить). Ряды точек модель не получает,
поэтому размер ответа зависит от числа параметров, а не от длины периода; сверх `FS_TOOL_MAX_RESULT_CHARS` модель
получает ошибку. Данные берутся тем же `TelemetryService`, что отдаёт `/v1/telemetry` (общий кэш, та же проверка
доступа к машине). Ошибка AutoGRAPH или неизвестная машина уходят модели как `{"error": ...}` — ассистент отвечает,
что данных нет. Вызовы tools с аргументами пишутся в лог (`модель вызывает tool …`).

## Секреты и логи

Ключ OpenRouter задаётся только в `.env` стенда. AutoGRAPH принимает логин, пароль и токен в query-строке, а
httpx пишет адреса запросов в лог, поэтому `log_masking.py` маскирует `session=`, `UserName=`, `Password=`,
`Bearer …` и `sk-or-…` во всех записях логов, включая текст исключений. Кэши стенда хранят не токен, а его хэш.
Ключ `X-API-Key` аналитики (`FS_PREDICTIVE_API_KEY`) уходит только в заголовок и в логи не пишется.

## Структура

```
src/fleet_service/
  main.py              create_app(): настройки, httpx-клиент, сервисы, агент; зависимости подменяются в тестах
  config.py            настройки (FS_*)
  cache.py             кэш со сроком жизни и одной загрузкой на ключ
  log_masking.py       маскирование секретов в логах
  api/                 /health, /v1/chat, /v1/vehicles и /v1/telemetry, /v1/anomalies/check, проверка сессии
                       (deps.py), ошибки (errors.py)
  rules/               baseline.py — перенос BaselineAnomalyDetector; thresholds.py — пороги (FS_RULES__*);
                       check.py — проверка машины: телеметрия → правила + аналитика
  analytics/client.py  клиент predictive_antifraud со статусом ok / not_ready / unavailable
  autograph/           session.py — проверка токена с кэшем; client.py — EnumDevices/EnumParameters/GetTripTables;
                       errors.py — ошибки AutoGRAPH
  telemetry/           parameters.py — выбор параметров и свёртка; mapper.py — потоковый разбор GetTripTables
                       в интервалы; service.py — машины и телеметрия пользователя с кэшами
  agent/               llm.py — клиент OpenRouter; tools.py — реестр tools; loop.py — цикл агента;
                       fleet_tools.py — tools телеметрии и проверки; prompt.py — системный промпт и контекст
  prompts/system.md    системный промпт по умолчанию
  schemas/             contract.py — VehicleTelemetry / Anomaly / DetectionResponse; chat.py — чат;
                       anomalies.py — запрос и ответ проверки
scripts/               smoke.sh, smoke.compose.yml, compare_real.sh
tests/                 fakes.py — подставные OpenRouter, AutoGRAPH и predictive_antifraud; тесты
```

## Как добавить tool

Зарегистрировать `Tool(name, description, parameters, handler)` в `main.build_tools()` (tools телеметрии — в
`agent/fleet_tools.py`). Обработчик — `async (args: dict, ctx: ToolContext) -> JSON-совместимый результат`; в `ctx` —
токен сессии, схема и смещение пояса пользователя. Ошибки обработчика возвращаются модели как `{"error": ...}`,
вызовы пишутся в лог.
