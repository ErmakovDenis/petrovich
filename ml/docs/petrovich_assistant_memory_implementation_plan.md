# План реализации памяти ассистента «Петрович»

> **Статус на 20.11.2026:** P0 реализован и принят локально для режима управляемого реестра (`managed`).
> Рабочее включение не выполнялось: `FS_IDENTITY_ENABLED=false`, `FS_IDENTITY_ACCOUNT_LIFECYCLE=unconfigured`.
> Нативный UID AutoGRAPH не подтверждён. Без принятого оператором регламента rename/retire персональный API включать нельзя.
> P1 и последующие этапы, персональная память и её подключение к LLM ещё не реализованы.

## Приёмка P0 (20.11.2026)

- [x] Серверный Login с явным согласием, отдельные credential/сессия, свежий EnumSchemas, TTL один час.
- [x] Устойчивый UUID в управляемом реестре: rename сохраняет личность, retire и повторная выдача логина создают другую. Аудит, запрет слияния, CLI по умолчанию без записи.
- [x] Изоляция пользователей/схем/провайдеров, подмена заголовков, отзыв без живого AutoGRAPH, конкурентные операции и миграции `0009`/`0010`, включая откат после повторной выдачи логина.
- [x] Android: отдельное согласие, AES-GCM/Keystore, сохранённый отзыв, смена подключения и возврат обратно не оживляют профиль; защита от гонок согласия.
- [x] Персональный Telegram: одноразовый код из private-чата, отдельное подтверждение в приложении, ограничение исходной парой и её сроком, свежие права, отключение. Legacy pairing не даёт личность.
- [x] Проверка контракта безопасности: [решение и ограничения](assistant-memory-p0-security-review.md).
- [x] Общие JSON-контракты Python/Kotlin, тесты бота, JVM и инструментальные проверки Android.

Принятие P0 относится к указанной модели управляемых аккаунтов. Реальный порядок эксплуатации пока не подтверждён оператором; два флага закрывают API до его принятия. Проверенный нативный account ID можно подключить позднее отдельным адаптером. `X-User-Name` никогда не становится доказательством личности.

### Выполненные проверки

Из `ml/fleet_service`, в изолированном Python-окружении с fakes:

```bash
python -m pytest tests/test_identity_memory.py tests/test_identity_telegram.py tests/test_identity_accounts.py tests/test_identity_schema_ids.py tests/test_config.py tests/test_contract.py tests/test_auth.py tests/test_chat_api.py tests/test_logging.py tests/test_store.py tests/test_background.py::test_window_longer_than_telemetry_limit_is_rejected -q --tb=short
```

**146 passed**, 53,35 с; одно предупреждение Starlette об устаревающем транспорте TestClient. Из `ml/telegram_bot`: `python -m pytest -q --tb=short` — **33 passed**, 3,49 с. Включены реальный адаптер aiogram с подставной сессией и сквозной клиент Stand через ASGI.

Android, JDK 17 / SDK 35 / Gradle 8.11.1 в `/tmp/petrovich-android-check`:

```bash
bash /tmp/petrovich-android-check/run-gradle.sh testDebugUnitTest --tests 'ru.petrovich.telemetry.StandIdentityTest' --tests 'ru.petrovich.telemetry.AssistantIdentityTest' --tests 'ru.petrovich.telemetry.RemoteChatAgentTest' --tests 'ru.petrovich.telemetry.StandAnomaliesTest' --tests 'ru.petrovich.telemetry.StandTelegramTest' --console=plain -Pkotlin.compiler.execution.strategy=in-process
```

**50 JVM-тестов, 0 ошибок/пропусков**, BUILD SUCCESSFUL. Собраны `assembleDebug`, `assembleP0test`, `assembleP0testAndroidTest`. Сборка `p0test` имеет отдельный applicationId и только локальные URL; runner запрещает запуск в обычном пакете приложения.

На AOSP Android 26 x86, отдельном программном эмуляторе `petrovich-p0`, фактически выполнены четыре инструментальных запуска: шифрование настоящим Android Keystore и отсутствие открытых секретов в DataStore; отзыв без сети и запрет оживления при возврате настроек; запись профиля; чтение после принудительной остановки процесса. Каждый запуск завершился `OK (1 test)`. Аппаратное устройство/TEE и перенос между двумя устройствами не проверялись; правила исключения DataStore из backup/device-transfer проверены по XML, повреждение/потеря ключа покрыты тестами.

UI-приёмка обычного приложения `.p0test` с настоящим локальным fleet_service и подставным AutoGRAPH:
пароль без согласия не активирует кнопку; после согласия bind и проверка успешны; при имитированной недоступности identity API отключение сразу закрывает профиль и сохраняет отзыв; после восстановления доступности отзыв завершается.
Снимки: [требуется согласие](artifacts/p0/p0-consent-required.png), [привязка](artifacts/p0/p0-profile-bound.png), [ожидающий отзыв](artifacts/p0/p0-revocation-pending.png), [отзыв завершён](artifacts/p0/p0-revocation-complete.png).
В logcat этого тестового эмулятора проверены синтетический пароль, шаблоны credential и AutoGRAPH-токенов: совпадений нет. Это проверка конкретного прогона, не доказательство всех возможных способов утечки.
`git -c core.whitespace=cr-at-eol diff --check` завершился без ошибок; новые текстовые файлы проверены отдельно, семь затронутых JSON-контрактов имеют LF.

Прежние сбои трёх общих эталонов исправлены добавлением уже существующих полей моделей (`feedSource`, `apiUpdatedAt`, `plate`); JSON сохранены с LF. Тест ограничения окна теперь задаёт свой лимит явно, независимо от изменённого значения по умолчанию.

Полный набор fleet_service, Docker smoke и рабочее развёртывание не выполнялись. Все учётные записи синтетические; реальные AutoGRAPH, Telegram, SMTP и LLM не вызывались. Следующий этап — P1: хранение памяти и её изоляция по проверенным principal/scope.

## 0. Краткая постановка задачи

Петрович должен помнить **конкретного пользователя** между сессиями и устройствами: как он разговаривает, насколько подробно хочет получать ответы, какие названия использует для машин, какие вопросы уже обсуждал. Параллельно ассистент должен использовать **общую для разрешённого автопарка** историю реальных аномалий и расследований. Память не имеет права подменять данные AutoGRAPH, менять антифрод-решения или раскрывать данные другого пользователя.

Основные сценарии:

1. Пользователь A: «Запомни: Рыжий — Урал А123ВС» → после однозначной проверки машины ассистент сохраняет **персональный** alias на стабильный `vehicle_id`; позже «Проверь Рыжего» вызывает fleet tool именно с этим ID.
2. Пользователь B использует «Рыжий» для другого ТС. Оба пользователя получают **свою** машину; память одного не влияет на другого.
3. «Отвечай мне коротко, сначала вывод, потом цифры» → предпочтение действует в новых разговорах; «в этом диалоге подробно» меняет лишь текущий контекст.
4. «Посмотри Рыжего, А123ВС» → система может **предложить** сохранить новое прозвище. Неявное предположение без подтверждения не становится проверенным alias.
5. «Как мы разбирали этот случай раньше?» → выводится история доступных пользователю аномалий и решений с привязкой к источникам; предположения механика не выдаются за подтверждённые причины.
6. После перезапуска Android или Telegram-процесса диалог доступен с сервера. Общие пользовательские предпочтения/aliases могут применяться в обоих каналах, **только после безопасного связывания личности**; истории разговоров Android и Telegram не обязаны сливаться в одну беседу.
7. «Забудь название Рыжий» / удаление в настройках → alias перестаёт использоваться немедленно; stale summaries, очереди и индексы не восстанавливают удалённое знание.

### Целевое состояние (Definition of Done всего проекта)

- У каждого запроса к персональной памяти есть подтверждённый `principal_id`, `scope/schema_id` и проверенный набор доступных `vehicle_id`. При отсутствии надёжной идентичности чат может работать **без персональной памяти**.
- Пользовательские aliases разрешаются **детерминированно** в стабильные ID до выполнения операций над телеметрией; при неоднозначности ассистент просит уточнить, а не выбирает случайную машину.
- Предпочтения стиля применяются предсказуемо; текущая явная инструкция сильнее долговременной памяти.
- История диалогов живёт на сервере, восстанавливается после рестартов, поддерживает идемпотентность и разделение пользователей.
- Личные факты, общие сведения автопарка, факты из tools и гипотезы имеют отдельные scope, источники, статус и политику хранения.
- Пользователь может просмотреть, исправить, отключить извлечение и удалить персональную память, отдельно от удаления переписки.
- Ни alias, ни summary, ни текст прошлого расследования не являются источником новых числовых значений телеметрии или разрешением на действие.
- Старый Android/Telegram/`POST /v1/chat`, локальный режим, детекция аномалий, карточки, письма и фоновые задачи работают при выключенной новой памяти.
- Есть автоматические тесты доступа, целостности, консистентности, перезапуска, миграций, LLM extraction, prompt injection и регрессии старых функций. **Все контрольные проверки ниже пройдены.**

## 1. Что подтверждено в репозитории

| Область | Текущее поведение | Источник |
|---|---|---|
| Серверный чат | `POST /v1/chat`; клиент присылает `messages`, сервер обрезает историю до `FS_CHAT_MAX_MESSAGES`, вызывает `build_messages(...)` и `Agent.run(...)` | [`api/chat.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/api/chat.py), [`schemas/chat.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/schemas/chat.py) |
| Агент | Цикл модели и tool calls; `ToolContext` содержит `session`, `schema_id`, UTC offset, необязательное имя и capabilities | [`agent/loop.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/agent/loop.py), [`agent/tools.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/agent/tools.py) |
| Идентичность | `require_session` проверяет AutoGRAPH token и доступ к схеме, но **не извлекает подтверждённый аккаунт пользователя**. `X-User-Name` отдельно не проверяется. Хэш сессии используется как rate-limit key, не как долговременный `user_id` | [`api/deps.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/api/deps.py), [`autograph/session.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/autograph/session.py) |
| Поиск ТС | `find_vehicle_id` поддерживает ID, точное имя, госномер, label среди видимых машин; при отсутствии однозначного совпадения — отказ | [`agent/fleet_tools.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/agent/fleet_tools.py) |
| Аномалии | SQLAlchemy Core + Alembic + SQLite, таблицы `anomalies`, `decisions`; идентификатор события и история решений уже существуют | [`store/db.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/store/db.py), [`store/repository.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/store/repository.py) |
| Миграции и тесты | `store/migrations/versions`, `store/migrate.py`; `tests/test_store.py` сверяет модели и миграции, `tests/conftest.py` содержит подставные сервисы и FakeLLM | [`store/migrate.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/store/migrate.py), [`tests/test_store.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/tests/test_store.py) |
| Android | `RemoteChatAgent` шлёт полный массив сообщений в `/v1/chat`; `ChatViewModel` хранит историю в `MutableStateFlow`, «Очистить чат» сбрасывает локальные сообщения | [`chat/RemoteChatAgent.kt`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/app/src/main/java/ru/petrovich/telemetry/chat/RemoteChatAgent.kt), [`ui/chat/ChatScreen.kt`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/app/src/main/java/ru/petrovich/telemetry/ui/chat/ChatScreen.kt) |
| Telegram | У бота есть привязка чата к схеме и текущему имени пользователя, но история `ChatState.history` живёт в памяти процесса; после рестарта теряется. Команда `/new` начинает новый разговор | [`telegram/links.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/telegram/links.py), [`telegram_bot/logic.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/telegram_bot/src/telegram_bot/logic.py) |
| Ограничения LLM | Системный prompt требует получать все числа из tools, отличать отсутствие данных от нуля и не выполнять инструкции из текстовых данных | [`prompts/system.md`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/prompts/system.md) |

**Критические уточнения относительно первоначальной идеи:**

- Нельзя брать `user_id = X-User-Name` или `SHA256(AutoGRAPH session)`: первое может быть подменено, второе меняется при перевходе и не доказывает идентичность между устройствами.
- Наличие у Telegram связи `chat_id → schema_id/user_name` само по себе **не означает**, что имя проверено как персональный идентификатор. Cross-channel memory включать после отдельной проверки связывания личности.
- Не нужно заново хранить каждую аномалию как «факт памяти»: `anomalies/decisions` уже являются источником истины. Создавать лишь индексы/производные эпизоды с ссылкой на источник и возможностью его обновления.
- Исходный `ChatViewModel.clear()` сегодня очищает только отображаемую переписку. В новом интерфейсе требуется явно разделить «Новый разговор», «Удалить разговор» и «Удалить память».

## 2. Границы памяти и модель доверия

| Слой | Scope | Что хранить | Что НЕ хранить |
|---|---|---|---|
| **Working / Conversation** | `tenant_id + principal_id + conversation_id` | Последние сообщения, состояние беседы, summary обсуждённого | Бессрочные «проверенные» факты из неподтверждённого текста |
| **Personal Entity Aliases** | `tenant_id + principal_id + entity_type` | «Рыжий» → конкретный `vehicle_id`, источник, подтверждение | Прозвища как глобальные названия ТС; «id по похожему смыслу» |
| **Personal Preferences** | `tenant_id + principal_id` | Формат, краткость, терминология, язык, согласие на извлечение | Предполагаемые личные характеристики, медицинские и иные чувствительные выводы |
| **Operational / Episodic** | `tenant_id + vehicle_id / anomaly_id` | Связи с аномалией, подтверждённые решения, заметки расследования | Утверждение «слив доказан», если есть лишь `CONFIRMED` без установленной причины |
| **Procedural** (позднее) | утверждённый scope организации | Проверенные инструкции с версией и автором | Автоматически созданные правила управления автомобилем/детектором |

**Приоритет источников при ответе:** актуальная телеметрия и проверенный fleet tool → подтверждённые операционные данные → явные пользовательские aliases/предпочтения → непроверенные заметки (с маркировкой «гипотеза») → текущий текст разговора. Память не заменяет текущие данные.

**Приоритет инструкций общения:** ограничения безопасности и приложения → явная инструкция текущего пользователя → его подтверждённые долгосрочные предпочтения → стиль по умолчанию. Текст извлечённой памяти — **данные**, не новая system/developer instruction.

**Граница доступа:** каждый поиск, просмотр, изменение и извлечение памяти выполняется в рамках проверенного principal и tenant; все `vehicle_id`, полученные из памяти, **повторно** сопоставляются с актуальным списком доступных машин из AutoGRAPH. Недоступность AutoGRAPH не означает разрешение доступа из старого кеша.

## 3. Целевая серверная архитектура

```mermaid
flowchart TD
    A[Android / Telegram] --> B[Verified principal + schema access]
    B --> C[Conversation service]
    C --> D[Memory retrieval]
    D --> E[Prompt builder / Agent]
    E --> F[Vehicle resolver]
    F --> G[Existing fleet tools + AutoGRAPH]
    E --> H[Existing anomaly/decision repository]
    E --> I[Reply + citations to evidence]
    I --> J[Persist turn + durable outbox]
    J --> K[Extractor + deduplication]
    K --> L[Candidate memories / approval]
    L --> M[(Personal memory tables)]
    D --> M
    D --> H
```

**Рекомендация:** модуль внутри `ml/fleet_service`, без отдельного микросервиса, LangChain/GraphRAG и векторной БД в MVP. Использовать текущий SQLAlchemy Core, Alembic, тестовую инфраструктуру и репозиторный паттерн. Сервис `Agent` не должен напрямую знать SQL: чтение памяти, resolver и запись выполнять через отдельные application services. Учитывать, что сервер запускается одним процессом, но все критичные операции должны быть корректны после рестарта и при одновременных запросах.

Предлагаемые новые модули:

```text
ml/fleet_service/src/fleet_service/
  identity/
    principal.py           # модель доверенного пользователя / связывание каналов
    service.py             # серверная проверка личности и scopes
  memory/
    schemas.py             # типизированные DTO и статусы
    repository.py          # операции с таблицами, транзакции
    service.py             # orchestration, чтение/запись
    aliases.py             # нормализация и детерминированное разрешение
    preferences.py         # слияние предпочтений и scope
    retriever.py           # SQL/FTS retrieval и бюджеты контекста
    extractor.py           # structured extraction, без прямого подтверждения
    worker.py              # обработчик durable outbox
    policy.py              # TTL, согласие, удаление, источники
  api/
    identity.py            # новый endpoint подтверждения личности
    memory.py              # посмотреть/исправить/удалить
    conversations.py       # разговоры и сообщения
    chat_v2.py             # новый контракт server-side чата
  agent/
    memory_tools.py        # только узкие tools, без общего unrestricted save
  store/
    db.py                  # описания новых таблиц
    migrations/versions/   # новые Alembic revisions
ml/fleet_service/tests/
  test_identity_memory.py
  test_memory_aliases.py
  test_memory_api.py
  test_memory_extraction.py
  test_memory_conversations.py
  test_memory_security.py
  test_memory_regression.py
```

Пути выше — целевая структура. Модули identity, API/DTO идентичности и тесты P0 уже добавлены; остальные модули памяти ещё предстоит реализовать.

## 4. Обязательное решение №1 — идентичность пользователя (P0, блокирующий этап)

### Проблема

`require_session` подтверждает право **токена** видеть схему, но не возвращает долговременный identity; `X-User-Name` клиенту разрешено подставлять произвольно. Использовать его как ключ памяти или ключ права `DELETE /memory` нельзя.

### Выбранное для плана направление

Ввести `Principal`, создаваемый **только сервером** на основании проверенной учётной записи. Предпочтительный вариант зависит от возможностей AutoGRAPH:

1. **Проверить существование надёжного AutoGRAPH endpoint идентификации** для валидированной сессии (стабильный user/account ID). Если он есть — получать ID с сервера AutoGRAPH и фиксировать провайдера идентичности.
2. **Если такого endpoint нет** — добавить отдельный `identity/bind` на стенде: после явного согласия пользователя проверять username+password через серверный AutoGRAPH Login и права на схему, создавать стабильный principal, выдавать собственный короткоживущий/обновляемый защищённый credential для персональных операций. Пароль не сохранять для памяти и не логировать. Credential хранить безопасно на устройстве, поддержать revoke; текущее право на схему проверять независимо. Точный контракт требует security review. Нельзя считать простое совпадение client-side username достаточным доказательством.
3. Telegram привязывать к **этому principal**, проверяя одноразовую пару в уже аутентифицированной сессии приложения; переиспользовать механику pairing только после проверки, что она аутентифицирует тот же аккаунт, а не просто схему/подписку. Групповые чаты не используют персональную память участников по умолчанию.

```python
# Новый внутренний тип, пример контракта:
@dataclass(frozen=True)
class Principal:
    tenant_id: str             # первоначально scope схемы AutoGRAPH
    principal_id: str          # стабильный ID, НЕ произвольный заголовок
    source: str                # autograph_verified / stand_bound
    verified: bool
    allowed_schema_ids: frozenset[str]
```

**Критерии P0:** перезаход и смена AutoGRAPH token не меняют `principal_id`; подмена `X-User-Name` не меняет память; разные пользователи одной схемы не читают друг друга; неподтверждённая привязка Telegram не открывает память; после отзыва credential персональный API закрыт; в логах нет токенов/паролей. Пока P0 не готов — **FS_MEMORY_ENABLED=false** для персональной памяти.

## 5. Данные и миграции (P1)

Реализовать таблицы через `store/db.py` + **новую Alembic revision**, не через `metadata.create_all()` в production. Время — UTC (в стиле существующего `store/db.py`). Ниже логическая схема, а не готовый Alembic-файл:

| Таблица | Поля / ключи | Ограничения |
|---|---|---|
| `principals` | `principal_id`, `provider`, `provider_subject`, `created_at`, `disabled_at` | Уникальная пара `(provider, provider_subject)`, идентичность не из client headers |
| `principal_scopes` | `principal_id`, `tenant_id`, `linked_at`, `revoked_at` | Уникальная активная привязка и повторная проверка актуальных прав |
| `conversations` | `id`, `tenant_id`, `principal_id`, `channel`, `created_at`, `updated_at`, `version`, `deleted_at` | Чтение/запись только владельца; `channel=android/telegram` |
| `conversation_messages` | `id`, `conversation_id`, `client_message_id`, `role`, `content`, `state`, `created_at`, `turn_id` | UNIQUE `(conversation_id, client_message_id)` для user turns; длина/объём ограничены |
| `conversation_summaries` | `conversation_id`, `version`, `summary`, `source_range`, `updated_at` | Перегенерация при удалении, проверка версии и источников |
| `user_entity_aliases` | `id`, `tenant_id`, `principal_id`, `entity_type`, `entity_id`, `alias`, `normalized_alias`, `status`, `source_message_id`, `created_at`, `updated_at`, `deleted_at` | UNIQUE активного `(tenant_id, principal_id, entity_type, normalized_alias)`; entity_id — стабильный ID |
| `user_preferences` | `id`, `tenant_id`, `principal_id`, `key`, `value_json`, `source_type`, `status`, `updated_at`, `deleted_at` | Предопределённые ключи; JSON валидировать, не хранить произвольный prompt |
| `investigation_notes` | `id`, `tenant_id`, `anomaly_id`, `vehicle_id`, `author_id`, `note`, `status`, `created_at`, `updated_at` | Ссылка на существующую аномалию, provenance, append-only audit изменений |
| `memory_candidates` | `id`, `tenant_id`, `principal_id`, `kind`, `payload_json`, `evidence_message_id`, `status`, `expires_at` | `pending/accepted/rejected/expired`; дедупликация |
| `memory_outbox` | `id`, `event_type`, `aggregate_id`, `payload_ref`, `state`, `attempts`, `next_run_at` | Durable post-turn extraction; повторная обработка идемпотентна |
| `memory_audit` | `id`, `principal_id`, `action`, `target_type`, `target_id`, `at`, `reason` | Без содержания удалённой памяти и секретов |

Примечания к дизайну:

- Для SQLite частичный уникальный индекс по `deleted_at IS NULL`/активному статусу реализовать явно в миграции и SQLAlchemy metadata; не полагаться на `UNIQUE` по nullable-колонкам.
- Имена aliases нормализовать осторожно: Unicode NFKC, `casefold`, trim/collapse spaces; не делать расплывчатый fuzzy match регистрационных номеров. Сохранять исходное отображаемое имя отдельно.
- Разрешать несколько разных aliases для одного ТС, но **один alias → не более одного активного ТС** в одном личном scope. При смене привязки — подтверждённая замена + аудит.
- `tenant_id` в MVP можно связать с `schema_id` AutoGRAPH; подтвердить в проекте, соответствует ли схема организационной границе. Не склеивать сведения разных организаций по совпадающим `vehicle_id`.
- Для расследований не создавать дубликаты `decisions` как самостоятельную правду. Потребитель обращается к canonical anomaly/decision store и соединяет `investigation_notes` по ссылке.
- Для удаления: мягкая пометка + tombstone/запрет репопуляции, очистка кеша/индекса/кандидатов/summary; физическая очистка в соответствии с утверждённым retention policy. Срок и политика восстановления из backup подлежат согласованию.

**Критерии P1:** upgrade на пустой и заполненной БД; сравнение metadata↔миграции; миграция `downgrade` на тестовой копии (либо документированная необратимость); после рестарта записи сохраняются; уникальность и idempotency соблюдены; старые таблицы и `AnomalyRepository` не ломаются.

## 6. Персональные названия машин — MVP/P2

### Чтение: deterministic entity resolution

Вынести общий `VehicleResolver` и интегрировать его в **все** fleet/store tools, принимающие `vehicle_id` (не только `get_vehicle_summary`). Последовательность:

1. Получить проверенный principal и актуальный набор `visible_vehicle_ids` от `TelemetryService.vehicles(session, schema_id)`.
2. Сначала проверить **точный допустимый ID**, затем точное имя/госномер/label из доступных ТС (существующее поведение). Для текстового прозвища искать активные aliases текущего principal в **текущей схеме**.
3. Если совпадение единственно и ID всё ещё видим — вернуть канонический `vehicle_id`; alias показать в ответе вместе с формальным label/plate по необходимости.
4. Если пользовательский alias конфликтует с официальным именем/номером другой машины или совпадение неоднозначно — предложить конкретные варианты и попросить подтверждение. Не угадывать.
5. Если Alias есть, но права на машину отозваны/она удалена — отказать в доступе; можно предложить удалить устаревшее прозвище, но **не показывать закрытую телеметрию**.
6. После успешного resolution tools получают только реальный ID, а не «Рыжий». При отсутствии подтверждённого principal действует старый точный resolver без персональных прозвищ.

**Запись alias:**

- Добавить узкий tool `set_vehicle_alias` (`alias`, `vehicle_reference`, `client_operation_id`) и `delete_vehicle_alias` (`alias`, `client_operation_id`). Аргументы не могут содержать произвольный `principal_id` или `tenant_id`: scope берётся из проверенного `ToolContext`. Права на фактический `vehicle_id` проверяются сервером в момент commit. Вызов записи разрешён только при явном пользовательском намерении в текущем запросе; операция идемпотентна. Для неполного/двусмысленного запроса tool возвращает `confirmation_required`, а не записывает факт.
- Явное «Запомни, Рыжий — машина А123ВС»: backend подтверждает однозначность и доступ, затем сохраняет alias через ограниченный tool; отвечает «запомнил» **только после успешного commit**.
- «А123ВС — наш Рыжий» без явной команды: LLM extraction создаёт **candidate**, UI спрашивает «Сохранить название?»; без подтверждения alias не используется как установленный факт.
- «Рыжий теперь — В456ОР»: требует явной замены связи с подтверждением старой/новой машины.
- «Забудь Рыжего»: удаляет персональный alias (не машину), инвалидирует кеши и summary/кандидаты, где связь могла пережить удаление. Создать suppression/tombstone, чтобы старые сообщения и повторная extraction не создавали связь снова. Если текст старой беседы остался, он может исторически содержать слово «Рыжий»: это **не** право использовать его как активную привязку. Для полного удаления самого упоминания нужно отдельно удалить/очистить соответствующую переписку.
- Полностью неизвестное имя → уточняющий вопрос, **не** произвольная fuzzy-идентификация.

**Критерии P2:** tool записи и удаления реально вызван в end-to-end сценариях с FakeLLM; подтверждение только после commit; 100% точность на детерминированных тестах известных aliases; изоляция A/B; корректность смены ID, удаления и потери прав; все tools используют один resolver; старый поиск по plate/name не регрессирует.

## 7. Стиль общения — P3

Ввести версионированные **типизированные** preferences, например:

```json
{
  "response_length": "concise",
  "response_format": "conclusion_then_facts",
  "technical_detail": "standard",
  "language": "ru",
  "preferred_terms": {"fuel_anomaly": "проблема с топливом"},
  "auto_extract_enabled": false
}
```

Предпочтения языка/длины/формата не должны менять измеренные факты, значения, метрики, безопасность и текст обязательных предупреждений. `preferred_terms` влияет только на словесное описание, а не на типы аномалий и SQL-фильтры.

**Правила обновления:**

- Явное долговременное указание («всегда пиши коротко») → проверка допустимого ключа и сохранение; отвечать после commit.
- Явное локальное указание («в этом чате распиши подробно») → override в conversation, не заменяет долговременное preference.
- Наблюдаемый стиль без указания → только кандидат с evidence/предложением; в MVP автозапись привычек **отключена**.
- Просмотр/редактирование в интерфейсе имеет приоритет над догадками extractor; при конфликте показывать источник и дату.

**Критерии P3:** после нового диалога preference работает; локальный override не меняет профиль; настройка «не извлекать из переписки» полностью выключает extraction; формат не отменяет disclaimer и не меняет чисел.

## 8. Серверная история разговоров — P4

### Контракт API

Рекомендую **не ломать** текущий `/v1/chat` и ввести отдельный контракт `/v2/chat` (возможен другой versioned path по соглашениям проекта). Пример:

```json
POST /v2/chat
{
  "conversationId": "conv_...",
  "clientMessageId": "uuid-generated-on-client",
  "expectedVersion": 7,
  "content": "Проверь Рыжего",
  "utcOffsetMinutes": 300,
  "anomalyId": null,
  "allowEmail": false
}
```

```json
200 OK
{
  "conversationId": "conv_...",
  "userMessageId": "msg_...",
  "assistantMessageId": "msg_...",
  "version": 8,
  "reply": "...",
  "drafts": []
}
```

Если диалога ещё нет, endpoint создаёт его в собственном scope пользователя. `conversationId` **никогда** не является полномочием доступа. Для всех операций проверять собственника и scope. Новые endpoint'ы: `GET /v2/conversations`, `GET /v2/conversations/{id}/messages` с пагинацией, `POST /v2/conversations` (новый чат), `DELETE /v2/conversations/{id}` (удалить), `GET/PATCH/DELETE /v2/me/memory` (отдельные типы), `POST /v2/me/memory/candidates/{id}/accept|reject`.

Правила обработки turn:

1. Проверить principal, права, лимиты; загрузить разговор и его server-side summary/history (не доверять переданному клиентом контексту как истории).
2. Записать user turn с уникальным `clientMessageId` и статусом `pending` в транзакции; конкурентные turn'ы в одном разговоре сериализовать либо возвращать `409` по `expectedVersion`.
3. Выполнить retrieval и LLM/tool loop с существующими timeout/rate/feature flags. Не удерживать SQL-транзакцию во время сетевого вызова LLM.
4. Записать assistant turn и completed state, вместе с outbox event; только затем сообщить клиенту об успешном сохранении. При истечении времени/ошибке вернуть корректный статус и сохранить возможность retry/recovery без двойного turn.
5. Повтор с тем же `(conversationId, clientMessageId)` возвращает прежний результат или состояние обработки; **не делает второй tool call**, не дублирует письма или кандидатов.
6. Compact старые сообщения в summary с бюджетом токенов и ссылками на диапазоны; не помещать в prompt всю историю. Для side effects (`draft_email`) сохранить существующую серверную модель подтверждения; пользователь должен видеть актуальный статус черновика.

**Миграция клиентов:** Android `RemoteChatAgent.kt` переключается на новый контракт под feature flag и хранит только идентификатор текущей беседы; `ChatViewModel` загружает историю с сервера, `clear()` = **новый разговор**, отдельный UI «Удалить разговор». Telegram заменяет `ChatState.history` серверными conversation ID, `/new` создаёт новый conversation; технические состояния Telegram review/pending_reason остаются отдельно, пока не будут явно перенесены. Клиенты с прежним API работают как раньше, без бесшовной персональной истории. Не делать автоматический импорт клиентских сообщений без deduplication и подтверждённого владельца.

**Критерии P4:** рестарт сервера, Android и Telegram не стирает историю; два устройства видят свой conversation после обновления; восстановление после 504/retry не плодит дубли; одновременные сообщения не перепутаны; очистка чата не удаляет aliases; удалённый чат не просачивается в summary.

## 9. История расследований — P5

Использовать существующие `anomalies` и `decisions`, чтение через ограниченный по `schema_id` и visible vehicles `AnomalyRepository`. Добавить только `investigation_notes` с источником, датой, автором и статусом:

- `observation` — наблюдение;
- `hypothesis` — возможная причина;
- `verified_reason` — причина, подтверждённая отдельным доказательством/ответственным;
- `action` — выполненное действие и подтверждение выполнения.

Новые инструменты (узкие, с контролируемыми аргументами): `get_vehicle_history`, `search_investigations`, `get_investigation`, `add_investigation_note` (последний только с проверенными полномочиями и явным запросом/подтверждением, не свободная автозапись). Класс `resolution=CONFIRMED` означает только статус, определённый существующим API; **не** автоматически доказанный слив.

MVP retrieval: `tenant_id + visible_vehicle_ids + vehicle_id + kind + time range + status`, с ограничением размера и ссылками на конкретные `anomaly_id`/`decision_id`. SQLite FTS5 позднее при большом количестве текстовых заметок; embeddings/pgvector — **после** измеримого улучшения на evaluation dataset.

**Критерии P5:** ассистент корректно отличает прошлое от текущего, решение от причины, гипотезу от факта; исправление/отмена решения обновляет выдачу; удалённые или недоступные события не раскрываются; поиск не меняет детектор.

## 10. Извлечение и поддержание памяти — P6

- Извлекать **строго типизированные кандидаты** (`alias`, `preference`, `investigation_note`) через LLM с JSON Schema/Pydantic и ссылками на исходные user messages. Валидация разрешённого типа и фактов о сущностях — в коде, **не** через уверенность модели.
- Использовать уже сохранённые committed messages через `memory_outbox`, а не best-effort `asyncio.create_task()` без durable state. Idempotency key по `event_id + memory_kind + normalized_key`; retries с backoff, capped attempts и dead-letter статусом.
- Для косвенных связей `candidate → confirmation → accepted`. Никогда не сохранять по словам, которые модель придумала; предусмотреть явное согласие пользователя на автопредложение памяти.
- Объединять повторы и противоречия; более новая запись не перезаписывает подтверждённую без policy. Поддержать `superseded`, `rejected`, `deleted` и журнал происхождения.
- После удаления проверять outbox/candidate, чтобы старое событие не воссоздало alias. Обработчик должен учитывать tombstone и актуальную настройку consent **на момент обработки**.
- Не передавать в память tokens, passwords, email body, secrets и чувствительные сведения из произвольного диалога. Модельные вызовы соответствуют настройкам обработки данных поставщика LLM.

**Критерии P6:** повторная обработка события не дублирует память; конфликт требует подтверждения; удаление не самовосстанавливается; отказ LLM extractor не ломает основной чат; из текстов данных и старых сообщений не исполняются инструкции.

## 11. Prompt и безопасность tools

Обновить [`agent/prompt.py`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/agent/prompt.py) и [`prompts/system.md`](https://raw.githubusercontent.com/ErmakovDenis/petrovich/main/ml/fleet_service/src/fleet_service/prompts/system.md):

1. Блок `User preferences` — только валидированные ключи и значения, лимит длины.
2. Блок `Relevant personal aliases` — короткий список только по видимым ТС.
3. Блок `Conversation summary` — непроверенные прошлые высказывания, явно помеченные как контекст.
4. Блок `Operational evidence` — ссылки на проверенные события и версии данных; источники обязательны в формулировках о расследовании.
5. Заранее сохранённые описания/aliases/summary трактуются как **непроверенный текст данных**, а не как команды. Безопасность и доступ проверяет backend, не prompt.

Изменение `ToolContext`: передавать trusted `Principal`/`principal_id` из server-side dependency, а не принимать `principal_id` из аргументов модели. Привязка к vehicle выполняется в tools на сервере. Модель не получает универсальную возможность менять SQL или принимать окончательные решения по антифроду. Для нового memory tool выставить `log_arguments=False`, если аргументы содержат текст сообщений/заметок.

## 12. Порядок внедрения, gates и rollback

| Шаг | Результат PR | Что должно пройти перед merge | Откат |
|---|---|---|---|
| **P0. Identity** (блокер) | Надёжный principal; подтверждённая привязка Android/Telegram | Cross-user и header-spoof тесты, session rotation/revoke, no secrets | Не включать личную память |
| **P1. Storage** | SQLAlchemy Core + Alembic tables, repositories | upgrade/compare_metadata/restart/конкурентность/backup | Выключить память; схема остаётся до контролируемого downgrade |
| **P2. Aliases** (**первый полезный MVP**) | Resolver + CRUD alias + явное «запомни» | A/B изоляция, collision, stale permission, все fleet tools | `FS_MEMORY_ALIASES_ENABLED=false` |
| **P3. Preferences** | Валидированные настройки ответа + временные overrides | Test precedence, сохранение между разговорами, 0 влияния на числовые факты | `FS_MEMORY_PREFERENCES_ENABLED=false` |
| **P4. Conversations** | `/v2/chat`, серверные turns/summaries, Android и Telegram adapters | Restart/retry/concurrency + совместимость `/v1/chat` и email drafts | Клиенты обратно на v1, без потери старых данных |
| **P5. Investigations** | Поиск предыдущих случаев/заметки/источники | Источники/видимость/различие причины и статуса | Выключить episodic retrieval/tools |
| **P6. Extraction + UX** | Candidates, outbox, экран памяти, удаление/экспорт | Injection, dedup, deletion, UX e2e, performance | `FS_MEMORY_EXTRACTION_ENABLED=false` |

Рекомендуемый порядок **не менять**: P2/P3/P4 нельзя безопасно выпускать для нескольких реальных пользователей до P0. Можно отдельно реализовывать миграции, mocks и UI, не включая feature.

Настройки (предлагаемые): `FS_MEMORY_ENABLED` (default `false`), `FS_MEMORY_ALIASES_ENABLED`, `FS_MEMORY_PREFERENCES_ENABLED`, `FS_MEMORY_CONVERSATIONS_ENABLED`, `FS_MEMORY_INVESTIGATIONS_ENABLED`, `FS_MEMORY_EXTRACTION_ENABLED` (все default `false`), лимиты результатов и prompt token budget, retention. Feature flags должны иметь server-side precedence и не включаться самими клиентскими запросами. Отдельный soft rollout сначала на тестовый scope.

## 13. Матрица обязательных проверок

| ID | Тип | Сценарий | Ожидаемый результат |
|---|---|---|---|
| SEC-01 | Auth | A и B в одной схеме, одинаковый alias «Рыжий» на разные ТС | Строгая изоляция, правильный ID для каждого |
| SEC-02 | Auth | A подменяет `X-User-Name` на B | Не получает и не изменяет память B |
| SEC-03 | Auth | AutoGRAPH token истёк/схема отозвана | 401/403 или отказ данных, **без** обхода через память |
| SEC-04 | Auth | Alias указывает на машину, которую больше не видно | Tool не раскрывает телеметрию |
| SEC-05 | Auth | Подмена `conversationId`/`messageId` другого пользователя | 403/404 без утечки содержимого |
| SEC-06 | Auth | Telegram linked/unlinked, другой chat_id или групповой чат | Нет доступа к чужой персональной памяти |
| SEC-07 | Input | Prompt injection в alias, заметке, summary или названии ТС | Инструкция игнорируется, нет опасного вызова tools |
| AL-01 | Unit | «Запомни Рыжий — А123ВС», уникальный видимый plate | Alias сохраняется на ID и применяется после новой сессии |
| AL-02 | Unit | Одинаковое прозвище в разных организациях | Нет пересечения scopes |
| AL-03 | Unit | Один alias соответствует нескольким объектам/конфликтует с официальным именем | Уточнение, не произвольный выбор |
| AL-04 | Unit | Перемена alias / удаление / переподключение ТС | Прежняя связь не используется, есть аудит |
| AL-05 | Integration | `get_vehicle_summary`, `check_vehicle`, `list_anomalies` с прозвищем | Разрешается один и тот же canonical ID |
| PREF-01 | Unit | «Всегда кратко» → новая беседа | Предпочтение сохранено |
| PREF-02 | Unit | «В этой беседе подробно» | Только локальный override |
| PREF-03 | Regression | Стиль короткий, а телеметрия отсутствует | Не придумывает число и не скрывает предупреждение |
| CHAT-01 | Integration | Новый conversation, несколько turns, рестарт backend/client | История восстановлена |
| CHAT-02 | Integration | Одинаковый `clientMessageId` повторён после timeout | Один user turn, один assistant turn, нет повторных side effects |
| CHAT-03 | Concurrency | Два одновременных turn в одной беседе | Сериализация или 409; порядок сохранён |
| CHAT-04 | Regression | Старый `POST /v1/chat` и Android old mode | Контракт и поведение неизменны |
| CHAT-05 | Regression | Письмо: draft → confirm/cancel, затем повторный chat | Не создаёт повторной отправки, статус соответствует серверу |
| HIST-01 | Integration | `CONFIRMED` без verified cause | Не называется доказанным сливом |
| HIST-02 | Integration | Решение отозвано или anomaly merged | Retrieval отражает актуальную историю |
| MEM-01 | Unit | Из двух диалогов одинаковый candidate | Один ожидающий кандидат, проверенный source |
| MEM-02 | Integration | Удаление alias при pending outbox | Alias не воскресает после воркера/рестарта |
| MEM-03 | Privacy | Удаление беседы, затем поиск/summary | Удалённый текст не используется и не возвращается |
| MEM-04 | Reliability | LLM для extractor недоступна | Главный чат работает, outbox повторяет позже |
| MEM-05 | Privacy | Запрет auto-extraction, последующие сообщения | Новые кандидаты не появляются |
| DB-01 | Migration | upgrade заполненной SQLite БД, rollback сценарий | Старые аномалии/решения/письма целы |
| DB-02 | Migration | `compare_metadata`, индексы, уникальность | Схема кода соответствует migration head |
| OPS-01 | Load | Параллельные запросы/потеря сети/длинная история | Лимиты, токены и таймауты соблюдены; нет блокировки БД на весь LLM call |
| OPS-02 | Security | Логи и API errors | Нет session token, password, raw memory contents и персональных секретов |

### Evaluation dataset и предварительные quality gates

Собрать отдельный **ручной golden dataset** без приватных продакшен-секретов: минимум 50 диалоговых кейсов, в том числе 20 сценариев прозвищ (неоднозначные/смена/удаление/два пользователя), 15 сценариев предпочтений, 15 эпизодических запросов; включить negative cases и несколько сессий одного человека.

В CI прогонять deterministic tests с FakeLLM и fake AutoGRAPH; LLM-sensitive задачи дополнить фиксированным набором вызовов моделей в отдельном контролируемом evaluation job. Численные ориентиры **как критерии приёмки**, а не как уже достигнутые результаты:

- **0** успешных cross-user, cross-tenant, cross-vehicle access violations во всех security fixtures.
- **100%** корректного разрешения заранее подтверждённых однозначных aliases и отказов в неоднозначных кейсах.
- **≥95% precision** предложений об извлечении предпочтений/aliases на ручной выборке (при наличии достаточного числа положительных и отрицательных примеров); все автосохранённые записи имеют валидное evidence и проходят серверную проверку.
- **≥90% Recall@5** для поиска нужных инцидентов на ручном наборе, когда будет введён episodic retrieval; SQL baseline сравнивать с FTS/semantic retrieval.
- No regression: `pytest` fleet_service, существующие golden-тесты телеметрии/детектора, Android unit/instrumented tests и Telegram tests проходят.
- Контролировать p50/p95 overhead memory retrieval, размер prompt, токены на summarization/extraction, долю ошибок outbox, duplicate rate, retention и нагрузку на SQLite. Числовой latency budget утвердить по baseline замерам **до** подключения памяти, не назначать произвольно.

### Проверки командами (после реализации)

```bash
# Backend: из корня ml/fleet_service
python -m pip install -e '.[dev]'
python -m pytest
python -m pytest tests/test_store.py tests/test_memory_aliases.py tests/test_memory_security.py -q

# На отдельной ТЕСТОВОЙ БД, а не на production
python -m fleet_service.store.migrate upgrade
# Прогон downgrade/upgrade — только на копии БД и с выбранной конкретной ревизией

# End-to-end smoke: после запуска сервисов из корня репозитория
ml/fleet_service/scripts/smoke.sh
```

Команды для новых `test_memory_*.py` будут работоспособны **после создания** соответствующих файлов. Для Android/Telegram запускать существующие тестовые команды, сверив их с актуальным проектом; не утверждать, что тесты уже прошли. В отдельном ручном плане проверить два реальных аккаунта одной схемы, два устройства, перезапуск бота, отсутствие сети, восстановление из резервной копии, переход на новый/старый клиент.

## 14. Пользовательский интерфейс

Android: `Настройки → Память Петровича` с вкладками «Как я называю машины», «Как со мной общаться», «История диалогов» и настройкой «Предлагать запоминать из бесед». Для каждой записи: alias/значение, привязанная машина (официальное имя + plate), источник/дата, изменить/забыть. Отдельные действия: «Начать новый разговор», «Удалить разговор», «Удалить мои персональные воспоминания». **Не** обещать уничтожение всех резервных копий немедленно; показать политику хранения. Для Telegram — минимальные команды просмотра/забывания и ссылка на настройки приложения, без раскрытия лишних данных в группах.

Во время обучения: компактное подтверждение «Сохранить ‘Рыжий’ как название Урал А123ВС?» с явными действиями **Сохранить / Не сохранять**. Не делать скрытую запись по каждому упоминанию и не показывать «запомнил» до фиксации транзакции.

## 15. Ограничения безопасности, privacy и эксплуатации

- Требуется согласованная политика retention для conversations, кандидатов, алиасов, аудита и backups. Разделить «выключить извлечение», «удалить разговор», «забыть конкретный факт», «удалить весь профиль». Проверить права на экспорт и удаление.
- Не записывать сырые body писем, пароли, токены, номер телефона сотрудника без обоснованной необходимости и подтверждённого доступа. Scrub/redact в логах, trace, prompt-кешах и error messages.
- Текущие fallback-модели и LLM routing в проекте могут менять поставщика; требования по передаче персональной памяти каждой модели/провайдеру и consent должны быть явно согласованы.
- Prompt injection: текст расследования, alias, summary и ответ стороннего инструмента может быть злонамеренным. Backend проверяет scope/ACL/разрешённые аргументы **после** работы модели.
- Синхронизация и недоступность memory store: без памяти обычные fleet tools и существующий `/v1/chat` продолжают работать. Если пользователь просит **сохранить** память, ошибка БД должна быть показана явно; нельзя ложно подтверждать успех.
- Ветки отката: feature flags / versioned API. Не проводить тяжёлую миграцию всей истории без отдельной политики и подтверждённой идентичности владельца.
- SQLite достаточно для MVP при текущей архитектуре одного server process и аккуратных коротких транзакциях. Если нагрузка вырастет, планировать переход на PostgreSQL по метрикам, а не ради наличия pgvector.

## 16. Порядок работы coding agent над реализацией

Каждый этап выполнять отдельным PR с явным gate. В PR обязательно:

1. Список изменённых файлов и новых миграций.
2. Краткая спецификация API/DTO и совместимости.
3. Security/authorization boundary: откуда взят principal, как проверены schema и visible vehicles.
4. Изолированные unit tests, интеграционные тесты с FakeLLM/AutoGRAPH и regressions.
5. Результаты конкретно выполненных команд (pass/fail), а не заявления «должно работать».
6. Ручные сценарии и скриншоты/протокол UI, если менялся клиент.
7. Новые настройки `.env.example`, README/DEPLOY, процедуры backup/rollback.
8. Критерии приёмки текущего этапа в виде чек-листа; переход к следующему только после их выполнения.

**Рекомендуемый первый PR:** исследование/реализация P0: как AutoGRAPH доказывает конкретный аккаунт; server-side identity contract и security tests. **Первый функциональный релиз после P0/P1:** P2 (пользовательские названия машин) + минимальный экран управления ими. Не начинать с embeddings, summary LLM или «умного» extraction.

## 17. Самопроверка этого плана: факты, допущения, незакрытые решения

### Подтверждено проверкой источников

- В репозитории действительно есть `POST /v1/chat`, `Agent.run`, `ToolRegistry`, server-side anomaly store, SQLAlchemy/Alembic, локальная Android chat history и in-memory Telegram history.
- Identity gap реален: текущая проверка AutoGRAPH даёт доступ к схеме, **но** не возвращает стабильный account ID; `X-User-Name` явно указан в коде как непроверенный.
- `find_vehicle_id` уже делает точное сопоставление видимых ID/имен/номеров. Нужно **расширить** существующее разрешение, а не создавать второй независимый поиск без ACL.
- Системный prompt требует все числа получать из инструментов и не выполнять команды из строк данных; новая память должна сохранять эти инварианты.
- Миграции при старте, SQLite WAL, тесты на структуру БД и mock-зависимости существуют.

### Требует проверки перед реализацией

- Предоставляет ли установленная версия AutoGRAPH надёжный `whoami`/account ID по токену; если нет, согласовать безопасный identity enrollment и миграцию клиента.
- Является ли `schema_id` корректным tenant boundary для всех пользователей и организаций.
- Какие требования к срокам хранения, удалению из backup и передаче личных данных LLM-провайдерам.
- Нужно ли разрешать одну и ту же персональную память на Android и Telegram **автоматически** или только после отдельного opt-in.
- Какие роли могут добавлять подтверждённую причину расследования; сейчас `CONFIRMED` само по себе не является причинной меткой.
- Окончательные URL API, feature flag naming, формат secure storage на Android и UX подтверждения — предлагаемые решения, а не текущий контракт.

### Что этот документ НЕ заявляет

- Что новая память уже реализована или протестирована в живом приложении.
- Что какой-либо AutoGRAPH endpoint для идентификации точно существует.
- Что `X-User-Name`, Telegram `user_name` или session hash сейчас подходят для безопасной персональной памяти.
- Что embeddings или vector database требуются для MVP.
- Что `CONFIRMED` в антифроде доказывает факт мошенничества или причину аномалии.

---

**Итог:** главный результат — Петрович надёжно узнаёт **своего** пользователя, понимает **его** названия машин и стиль общения, помнит доступный контекст разговоров и извлекает подтверждённые факты из истории парка. Центральные приоритеты: `verified identity → aliases → preferences → conversations → investigations → automatic extraction`, с независимой проверкой и возможностью отключить каждый слой.
