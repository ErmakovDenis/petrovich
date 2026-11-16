# Серверы, аналитика и бот

Дополняет корневой `AGENTS.md`.

## Окружение и команды

Пакеты используют Python >=3.11 и src-layout. Docker-образ fleet_service использует Python 3.12: изменения должны работать и на этой версии, даже если локальный Python новее.

Команды выполняются из каталога нужного пакета (`ml/fleet_service`, `ml/predictive_antifraud` или `ml/telegram_bot`) в выбранном виртуальном окружении:

```powershell
python -m pip install -e ".[dev]"
python -m pytest -q
```

Установка нужна при подготовке окружения или изменении зависимостей. Не переустанавливай пакеты перед каждой проверкой. В PowerShell окружение можно активировать через `.\.venv\Scripts\Activate.ps1` либо вызывать его Python напрямую.

Запуск fleet_service из его каталога:

```powershell
python -m uvicorn --factory fleet_service.main:create_app --app-dir src --reload --port 8080
```

Compose запускается из `ml/`: `docker compose up -d --build`. По умолчанию на хосте fleet_service — 8080, predictive_antifraud — 8001. Бот включается профилем `telegram`.

## Правила реализации

- `fleet_service` создаётся фабрикой `create_app`; сохраняй возможность подменять HTTP, LLM, настройки и фоновый цикл в тестах. Не читай реальные секреты при импорте модулей.
- Настройки: `FS_*` для стенда, `PA_*` для аналитики, `TB_*` для бота. Изменяя параметр, проверяй `config.py`, `.env.example`, Compose и тесты конфигурации. `ml/.env` предназначен для переменных Compose и отличается от `.env` каждого сервиса.
- Ключ аналитики должен совпадать: `PA_API_KEY` в predictive_antifraud и `FS_PREDICTIVE_API_KEY` в fleet_service.
- Новые tools регистрируются в `main.build_tools()`. Вызов tools проверяется через существующие тесты и eval-сценарии; модель не получает возможности самостоятельно отправить письмо.
- В текущей архитектуре scheduler живёт внутри процесса fleet_service. Не увеличивай число uvicorn workers без решения вопроса единственного фонового обхода.
- Миграции — `fleet_service/src/fleet_service/store/migrations/versions/`. Изменения схемы оформляй новой миграцией. Не удаляй том `fleet_data` и не меняй каноническую сетку или формат id как побочный эффект задачи.
- Доступ к AutoGRAPH для фоновой проверки шифруется; пароль сохраняется только после согласия пользователя. Сохраняй разграничение доступа схем, пользователей и Telegram-чатов.
- Бот получает данные через API стенда, а не напрямую из AutoGRAPH. Поведение добавляй в `logic.py`, доставку сообщений — в `bot.py`.
- GigaChat использует собственный шлюз, проверку TLS и передачу `functions_state_id`. Не заменяй его встроенным провайдером LiteLLM без отдельного обоснованного изменения.
- Сохраняй таймауты, лимиты, отмену запросов, circuit breaker и кэширование. Сбой аналитики должен оставлять доступной проверку по правилам и явно отражаться в результате.

## Выбор проверок

- Контракты: `fleet_service/tests/test_contract.py` плюс соответствующие Kotlin-тесты. Тест импортирует соседний `predictive_antifraud/src`, поэтому при контейнерной проверке нужен весь каталог `ml/`.
- Маппинг и правила: `test_mapper.py`, `test_golden.py`, `test_rules_golden.py`, `test_anomalies_check.py` плюс Android golden-тесты.
- LLM/настройки: относящиеся к изменению `test_config.py`, `test_chat_api.py`, `test_agent_loop.py`, `test_openrouter.py`, `test_llm_proxy_config.py`, `test_gigachat_gateway.py`, `test_gigachat_compose.py`.
- Telegram: pytest бота и `fleet_service/tests/test_telegram_bot_e2e.py`. Для последнего пакет telegram_bot должен быть установлен в окружение fleet_service.
- Сквозной сценарий из корня, в Bash с Docker: `bash ml/fleet_service/scripts/smoke.sh`. Использует подставные AutoGRAPH, OpenRouter, GigaChat и SMTP. Для чисто документальных изменений не требуется.
- Проверка инструментов настоящей модели: `python -m fleet_service.agent.eval_tools`; она требует настроенного провайдера и может расходовать средства, запускать только в рамках порученной проверки.

Секреты настоящих провайдеров и запросы к GigaChat по плану проверяет человек. Для обычных тестов используй `tests/fakes.py` и существующие фикстуры, не настоящее окружение.

## Общая проверка шага

Прочитай `.claude/skills/fleet-check/SKILL.md` перед общей проверкой. Из корня в совместимом Bash-окружении доступен `bash .claude/skills/fleet-check/check.sh`: Gradle-тесты, APK, pytest трёх пакетов, promtool и smoke. `--no-smoke` пропускает только smoke, а promtool всё ещё требует Docker.

Скрипт рассчитан на Linux: `source scripts/env.sh`, `local.properties`, `.venv/bin/pytest`. На Windows используй эквивалентные команды с `.venv/Scripts/python.exe` и `gradlew.bat`, а не считай Linux-скрипт готовой проверкой Windows. Для promtool бери версию образа Prometheus из текущего `ml/docker-compose.yml` и проверяй оба файла `ml/monitoring/alerts.yml` и `alerts_test.yml`.

Сохраняй итог каждой проверки и путь к логу; при ошибке прочитай лог и укажи причину. Отсутствие SDK, venv или Docker означает неполную проверку шага. Укажи недостающее окружение и способ подготовки, не устанавливай его молча. Настоящие модели, AutoGRAPH, Telegram, приложение на устройстве и уведомления требуют отдельных проверок и не подтверждаются подставными серверами.
