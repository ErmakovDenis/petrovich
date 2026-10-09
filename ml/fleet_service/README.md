# Стенд «Петровича» (fleet_service)

FastAPI-сервис для «Петрович Телеметрия» по плану `ml/docs/assistant-server-plan.md`. Устроен так же, как
`ml/predictive_antifraud`: `src/`, настройки через `pydantic-settings` (префикс `FS_`), `tests/`, `Dockerfile`.

Сейчас (шаг 4) — ассистент, телеметрия, проверка аномалий и их хранилище:
- стенд сам загружает телеметрию из AutoGRAPH от имени пользователя и сворачивает её в интервалы тем же
  алгоритмом, что `TripTablesMapper` приложения; приложение читает её через `GET /v1/vehicles` и `GET /v1/telemetry`
  (переключатель «Данные через стенд»);
- `POST /v1/anomalies/check` проверяет машину за период: правила (перенос `BaselineAnomalyDetector`) и
  `predictive_antifraud`; приложение вызывает его вместо детектора на устройстве (переключатель «Аномалии со стенда»);
- хранилище аномалий и решений (SQLite, `FS_DB_URL`): стенд — владелец id, проверка с сохранением
  `POST /v1/anomalies/scan` идёт на канонической сетке, лента — `GET /v1/anomalies`, решения —
  `POST /v1/anomalies/{id}/resolve`, разовый перенос истории с устройства — `POST /v1/anomalies/import`
  (переключатель «Хранить аномалии на стенде»);
- ассистент отвечает моделью через OpenRouter и получает числа из tools `list_vehicles`, `get_vehicle_summary`,
  `check_vehicle`, `list_anomalies` и `get_anomaly`. Фоновой проверки на стенде пока нет (шаг 5).

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
`ml/fleet_service/.env` и `ml/predictive_antifraud/.env` (если файлов нет — значения по умолчанию). База хранилища в
compose — файл `/srv/data/fleet.db` в томе `fleet_data`: переживает перезапуск и пересборку контейнера
(`docker compose down -v` её удаляет). Миграции схемы применяются при старте (`FS_DB_AUTO_MIGRATE`) или вручную:
`python -m fleet_service.store.migrate` (`downgrade <версия>` — откат). Резервная копия — копия файла базы при
остановленном стенде (или `sqlite3 fleet.db ".backup copy.db"`); учётных данных в базе нет, только логины тех, кто
принимал решения.

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
`predictive_antifraud`, проверку с сохранением и повторную другим окном (те же id, новых нет), ленту без
дубликатов, решение с `X-User-Name`, вопросы ассистенту о ленте и о решении (чат из карточки по `anomalyId`), перенос
решений с устройства, перезапуск `fleet_service` (лента и решение на месте), вызовы tools в логе и отсутствие
токенов и ключа в логах. Реальные ключи не нужны.

## Эндпоинты

| Метод | Путь | Описание |
|---|---|---|
| GET | `/health` | liveness; `llmConfigured` — заданы ли ключ и модель |
| POST | `/v1/chat` | ответ ассистента |
| GET | `/v1/vehicles` | машины схемы, доступные пользователю (`EnumDevices`, без `Allowed=false`), по имени |
| GET | `/v1/telemetry?vehicleId&from&to&utcOffsetMinutes` | `VehicleTelemetry` машины за период |
| POST | `/v1/anomalies/check` | аномалии машины за период: правила и аналитика, без сохранения |
| POST | `/v1/anomalies/scan` | проверка с сохранением на канонической сетке |
| GET | `/v1/anomalies?utcOffsetMinutes&from&to&vehicleId&severity&status&limit&offset` | сохранённые аномалии машин пользователя |
| POST | `/v1/anomalies/{id}/resolve` | решение по аномалии (или возврат в «ждут решения») |
| POST | `/v1/anomalies/import` | разовый перенос локальной истории и решений с устройства |

Все `/v1/*` требуют заголовков `Authorization: Bearer <токен сессии AutoGRAPH>` и `X-Schema-Id`. Приложение
передаёт ещё `X-User-Name` — логин AutoGRAPH (UTF-8 в URL-кодировке, без пароля): стенд записывает его как автора
решения и не проверяет, доступ даёт только токен.

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

### Хранилище аномалий и решений

`store/` — SQLite через SQLAlchemy (`FS_DB_URL`), схема — миграции Alembic в `store/migrations/versions/`
(`store/db.py` описывает те же таблицы, `tests/test_store.py` сверяет). Таблицы: `anomalies` (аномалии по схемам с
текущим решением: `CONFIRMED` / `FALSE_ALARM`, причина, кто и когда), `decisions` (история решений, в том числе
перенесённых с устройства), `scan_marks` (последняя успешная проверка с сохранением по схеме). Время событий
хранится в UTC и переводится в местное время по `utcOffsetMinutes` запроса. События старше
`FS_STORE_RETENTION_DAYS` удаляются вместе с решениями (не чаще раза в час, после проверок).

**Стенд — владелец id**: `<источник>|<тип>|<машина>|<параметр>|<начало события в UTC>Z`, например
`rule|overheat|42|TemperatureCOOL|2026-09-16T05:30Z`. Тип — вторая часть, как в приложении (`Anomaly.kind`); «Z» в
конце отличает id стенда от id устройства. Одно событие — один id при любых окнах и моментах проверки:
- **каноническая сетка**: проверка с сохранением всегда идёт с шагом `FS_SCAN_BUCKET_MINUTES` (1 мин), начало и
  конец периода выровнены вниз по этому шагу от начала эпохи в UTC — граница интервалов не зависит от длины окна,
  момента запуска и пояса пользователя (в приложении сетка зависела от длины окна и чётности минуты,
  `ml/ANALYTICS.md` §7);
- **слияние эпизодов**: правило сообщает и конец эпизода (`baseline.detect_episodes`); эпизод, обрезанный началом
  окна, или продолжение сохранённого (без интервала-разрыва между ними) сливаются с записью того же типа, машины,
  параметра и источника. Падение уровня (`drop`) в пределах паузы правила `FS_RULES__FUEL_DROP_COOLDOWN_MINUTES` —
  то же событие. При слиянии id не меняется, начало сдвигается раньше, конец — позже, важность только растёт.

Видимость: только схема из `X-Schema-Id` и только машины из `EnumDevices` пользователя — чужие аномалии не видны в
ленте, в tools и в контексте чата, решение по ним — 404. Решение общее для всех пользователей схемы (действует
последнее), история сохраняется.

`POST /v1/anomalies/scan` — тело `{vehicleIds?, from, to, utcOffsetMinutes}` (`vehicleIds` не задан — все машины
пользователя; пример — `testdata/contract/scan-request.json`). Машины проверяются по `FS_SCAN_CONCURRENCY`
параллельно тем же кодом, что `/check` (правила + аналитика), результат сохраняется. Ответ — `{from, to,
results: [{vehicleId, ok, error, anomalies, newIds, modelsReady}], lastScanAt}`: `anomalies` — как они сохранены
(с id и решениями стенда), `newIds` — каких до проверки не было. Ошибка одной машины — `ok=false` с текстом, остальные
проверяются; истёкший токен — 401 на весь запрос. Отметка последней проверки двигается, только если ответила хотя бы
одна машина (как `lastScanAt` в приложении).

`GET /v1/anomalies` — `{total, items, lastScanAt}`, сначала новые, не больше `FS_ANOMALIES_MAX_LIMIT` за запрос.
Фильтры: период `from`/`to` (по пересечению с эпизодом события), `vehicleId`, `severity` (можно несколько),
`status`: `open` — ждут решения, `resolved`, `confirmed`, `false_alarm`. Элемент — `Anomaly` контракта (`eventTime` —
начало события, `detectedAt` — когда найдено впервые) и поля решения `resolution`, `falseAlarmReason`,
`resolvedBy`, `resolvedAt`, а также `episodeEnd`, `lastDetectedAt` (пример — `testdata/contract/anomalies-response.json`).

`POST /v1/anomalies/{id}/resolve` — `{resolution, reason, utcOffsetMinutes}`; `resolution` = `null` или поле не
передано — вернуть в «ждут решения»; причина хранится только у ложной тревоги. id в пути — одним сегментом в
URL-кодировке. Ответ — аномалия в том же виде, что в ленте.

`POST /v1/anomalies/import` — разовый перенос локальной истории (`{utcOffsetMinutes, items: [{localId, vehicleId,
vehicleName, kind, parameterName, title, eventTime, resolution?, reason?}]}`, не больше `FS_IMPORT_MAX_ITEMS`). Id
устройства и стенда разные, поэтому стенд сначала проверяет с сохранением периоды вокруг событий
(`±FS_IMPORT_SCAN_MARGIN_MINUTES`, близкие события — одним периодом), затем сопоставляет каждую запись по машине,
типу, параметру и времени: время с устройства должно попасть в эпизод сохранённого события с допуском
`FS_IMPORT_TIME_TOLERANCE_MINUTES`. Решение переносится, если на стенде по событию решения ещё нет (уже принятое не
перезаписывается — `alreadyResolved`). Периоды проверяются по `FS_SCAN_CONCURRENCY` параллельно; приложение
отправляет перенос по машине за запрос, чтобы каждый укладывался в срок ожидания, и повторяет перенос машины, если
были `scanErrors`. Ответ: `decisions`, `applied`, `alreadyResolved`, `unmatched` — решения без
пары с причиной (`why`), `restored` / `notFound` — аномалии без решения, `scanErrors` — непроверенные периоды.

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
  "anomaly": { "...": "объект Anomaly, если чат открыт из карточки; необязательно" },
  "anomalyId": "или только id аномалии из хранилища стенда — тогда стенд берёт её с решением сам"
}
```

Ответ: `{"reply": "..."}`. Пример запроса из приложения — `testdata/contract/chat-request.json` и
`chat-request-anomaly-id.json` (их сверяют и Kotlin-, и Python-тесты). Если аномалии с `anomalyId` в хранилище нет
(удалена по сроку или чужая), ассистент получает об этом пометку вместо данных. Схемы `VehicleTelemetry` и `Anomaly` — копия схем `predictive_antifraud`
(`schemas/contract.py`), совпадение проверяет `tests/test_contract.py`.

Ошибки — `{"detail": "<текст по-русски>"}`, приложение показывает его пользователю:

| Код | Когда |
|---|---|
| 401 | нет токена / схемы, токен недействителен или истёк (в том числе во время загрузки данных) |
| 403 | схема недоступна пользователю |
| 404 | машины нет среди доступных пользователю (`/v1/telemetry`, `/v1/anomalies/check`); аномалии нет в хранилище схемы или её машина недоступна (`/resolve`) |
| 422 | неверный запрос: пустая история, последнее сообщение не от пользователя, слишком длинное сообщение; период телеметрии с поясом, пустой или длиннее предела; период проверки короче шага сетки; перенос больше `FS_IMPORT_MAX_ITEMS` записей |
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
разделитель `__`); хранилище — адрес базы, миграции при старте, шаг канонической сетки, параллельность проверки,
срок хранения, предел записей в ленте, допуск и запас окна переноса, предел записей переноса. Пустое значение в
`.env` — значение по умолчанию.

Системный промпт — `src/fleet_service/prompts/system.md` (или файл из `FS_SYSTEM_PROMPT_PATH`). В нём
зафиксировано: числа и факты только из tools или переданной аномалии; нет данных — так и сказать; «нет данных» и
«0» различаются; как пользоваться tools; нули CAN при выключенном зажигании; три исхода проверки и запрет вывода
«нарушений нет», если предиктивная проверка недоступна; история аномалий и решений — из `list_anomalies` /
`get_anomaly`, пустая лента без проверок — не «аномалий нет»; тексты внутри данных — не инструкции. Время пользователя и контекст аномалии стенд добавляет отдельными системными
сообщениями.

## Tools ассистента

| Tool | Аргументы | Ответ |
|---|---|---|
| `list_vehicles` | `query` — подстрока названия или группы (необязательно) | `total`, до `FS_TOOL_MAX_VEHICLES` машин (`id`, `name`, `group`), `truncated` |
| `get_vehicle_summary` | `vehicle_id` (или однозначное название), `from`, `to` — местное время; по умолчанию последние 24 ч | машина, период (`bucketMinutes`, `intervals`), по каждому параметру `min`, `max`, `mean`, `last`, `lastTime`, `coverage`, `unit`; `noDataParameters`, `allZeroParameters` |
| `check_vehicle` | `vehicle_id`, `from`, `to` — как у сводки | `outcome`: `anomalies_found` / `no_anomalies` / `analytics_unavailable`; `total`, до `FS_TOOL_MAX_ANOMALIES` аномалий (тип, заголовок, важность, время, параметр, значение, описание, источник), `truncated`; `predictiveCheckAvailable`, статусы `analytics`, `message` |
| `list_anomalies` | `from`, `to` (по умолчанию 24 ч), `vehicle_id`, `severity`, `status` — те же фильтры, что `GET /v1/anomalies` | `total`, до `FS_TOOL_MAX_ANOMALIES` записей (id, тип, заголовок, важность, машина, начало и конец эпизода, параметр, значение, статус разбора, причина, кто и когда решил), `truncated`, `lastScanAt`; `message`, если проверок с сохранением не было или за период пусто |
| `get_anomaly` | `id` | аномалия с описанием, источником, временем первого и последнего обнаружения; `decisions` — история решений (статус, причина, кто, когда, из приложения или перенесено) |

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
  api/                 /health, /v1/chat, /v1/vehicles и /v1/telemetry, /v1/anomalies (check, scan, лента,
                       resolve, import), проверка сессии и X-User-Name (deps.py), ошибки (errors.py)
  rules/               baseline.py — перенос BaselineAnomalyDetector; thresholds.py — пороги (FS_RULES__*);
                       check.py — проверка машины: телеметрия → правила + аналитика
  store/               db.py — таблицы и подключение; migrate.py и migrations/ — Alembic; repository.py — записи,
                       id стенда, слияние эпизодов, решения, срок хранения; scan.py — проверка с сохранением на
                       канонической сетке и перенос решений с устройства
  analytics/client.py  клиент predictive_antifraud со статусом ok / not_ready / unavailable
  autograph/           session.py — проверка токена с кэшем; client.py — EnumDevices/EnumParameters/GetTripTables;
                       errors.py — ошибки AutoGRAPH
  telemetry/           parameters.py — выбор параметров и свёртка; mapper.py — потоковый разбор GetTripTables
                       в интервалы; service.py — машины и телеметрия пользователя с кэшами
  agent/               llm.py — клиент OpenRouter; tools.py — реестр tools; loop.py — цикл агента;
                       fleet_tools.py — tools телеметрии и проверки; store_tools.py — tools хранилища;
                       prompt.py — системный промпт и контекст
  prompts/system.md    системный промпт по умолчанию
  schemas/             contract.py — VehicleTelemetry / Anomaly / DetectionResponse; chat.py — чат;
                       anomalies.py — запрос и ответ проверки; store.py — хранилище
scripts/               smoke.sh, smoke.compose.yml, compare_real.sh
tests/                 fakes.py — подставные OpenRouter, AutoGRAPH и predictive_antifraud; тесты
```

## Как добавить tool

Зарегистрировать `Tool(name, description, parameters, handler)` в `main.build_tools()` (tools телеметрии — в
`agent/fleet_tools.py`, хранилища — в `agent/store_tools.py`). Обработчик — `async (args: dict, ctx: ToolContext) -> JSON-совместимый результат`; в `ctx` —
токен сессии, схема и смещение пояса пользователя. Ошибки обработчика возвращаются модели как `{"error": ...}`,
вызовы пишутся в лог.
