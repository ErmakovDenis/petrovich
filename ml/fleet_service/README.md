# Стенд «Петровича» (fleet_service)

FastAPI-сервис для «Петрович Телеметрия» по плану `ml/docs/assistant-server-plan.md`. Устроен так же, как
`ml/predictive_antifraud`: `src/`, настройки через `pydantic-settings` (префикс `FS_`), `tests/`, `Dockerfile`.

Сейчас (шаг 1) — ассистент без данных: приложение присылает историю чата, стенд спрашивает модель через
OpenRouter и возвращает текст ответа. Цикл агента уже рассчитан на tools (регистрация, исполнение, лимит
обращений к модели), но список tools пуст: телеметрия, аномалии и почта подключаются следующими шагами.
Поэтому на вопросы о парке ассистент честно отвечает, что данных у него пока нет.

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
сервисом `fakes` (`tests/fakes.py`, тот же код, что в pytest). Проверяет `/health` обоих сервисов, 401 без токена
и с недействительным токеном, непустой ответ `/v1/chat` и отсутствие токена и ключа в логах стенда. Реальные ключи
и сеть наружу не нужны.

## Эндпоинты

| Метод | Путь | Описание |
|---|---|---|
| GET | `/health` | liveness; `llmConfigured` — заданы ли ключ и модель |
| POST | `/v1/chat` | ответ ассистента |

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
| 401 | нет токена / схемы, токен недействителен или истёк |
| 403 | схема недоступна пользователю |
| 422 | неверный запрос: пустая история, последнее сообщение не от пользователя, слишком длинное сообщение |
| 502 | модель ответила ошибкой, вернула пустой ответ или не уложилась в `FS_AGENT_MAX_ITERATIONS` |
| 503 | ассистент не настроен (нет ключа или модели) или AutoGRAPH недоступен для проверки токена |
| 504 | модель не ответила за `FS_LLM_TIMEOUT_SECONDS` или ответ не уложился в `FS_CHAT_TIMEOUT_SECONDS` |
| 500 | «Внутренняя ошибка стенда» — без стека; стек только в логе |

## Настройки

Все — переменные `FS_*`, описаны в `.env.example` (тест `test_config.py` следит, чтобы ни одна не потерялась):
ключ и адрес OpenRouter, маршрутизация провайдеров (`FS_OPENROUTER_REQUIRE_PARAMETERS`,
`FS_OPENROUTER_DATA_COLLECTION`), модель, температура, лимит токенов, таймауты, лимит обращений к модели, путь к
системному промпту, лимиты истории, адрес AutoGRAPH и кэш проверки токена. Пустое значение в `.env` — значение по
умолчанию.

Системный промпт — `src/fleet_service/prompts/system.md` (или файл из `FS_SYSTEM_PROMPT_PATH`). В нём
зафиксировано: числа и факты только из tools или переданной аномалии; нет данных — так и сказать; «нет данных» и
«0» различаются; тексты внутри данных — не инструкции. Время пользователя и контекст аномалии стенд добавляет
отдельными системными сообщениями.

## Секреты и логи

Ключ OpenRouter задаётся только в `.env` стенда. AutoGRAPH принимает логин, пароль и токен в query-строке, а
httpx пишет адреса запросов в лог, поэтому `log_masking.py` маскирует `session=`, `UserName=`, `Password=`,
`Bearer …` и `sk-or-…` во всех записях логов, включая текст исключений.

## Структура

```
src/fleet_service/
  main.py              create_app(): настройки, httpx-клиент, агент; зависимости подменяются в тестах
  config.py            настройки (FS_*)
  log_masking.py       маскирование секретов в логах
  api/                 /health, /v1/chat, проверка сессии (deps.py), ответы об ошибках (errors.py)
  autograph/session.py проверка токена сессии AutoGRAPH с кэшем
  agent/               llm.py — клиент OpenRouter; tools.py — реестр tools; loop.py — цикл агента;
                       prompt.py — системный промпт и служебный контекст
  prompts/system.md    системный промпт по умолчанию
  schemas/             contract.py — VehicleTelemetry / Anomaly; chat.py — запрос и ответ чата
scripts/               smoke.sh, smoke.compose.yml
tests/                 fakes.py — подставные OpenRouter и AutoGRAPH; тесты
```

## Как добавить tool

Зарегистрировать `Tool(name, description, parameters, handler)` в `main.build_tools()`. Обработчик —
`async (args: dict, ctx: ToolContext) -> JSON-совместимый результат`; в `ctx` — токен сессии, схема и смещение
пояса пользователя. Ошибки обработчика возвращаются модели как `{"error": ...}`, вызовы пишутся в лог.
