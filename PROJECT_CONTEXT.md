# Контекст проекта для следующих сессий

Проверено по исходникам и документации 2026-11-16. Это навигация по репозиторию; точное состояние нужно сверять с кодом.

## Состав

| Путь | Назначение и точки входа |
| --- | --- |
| `app/` | Android: Kotlin, Compose Material 3, Retrofit, kotlinx.serialization, WorkManager, DataStore. `ServiceLocator.kt` связывает локальные и серверные реализации. |
| `ml/fleet_service/` | FastAPI: AutoGRAPH, телеметрия, правила, хранилище, фоновая проверка, LLM-ассистент, письма, API Telegram. Фабрика `src/fleet_service/main.py:create_app`. |
| `ml/predictive_antifraud/` | FastAPI-сервис предиктивной аналитики и антифрода. Загрузка обученных моделей в `models/registry.py` пока является заготовкой. |
| `ml/telegram_bot/` | aiogram-бот, обращающийся к fleet_service. Поведение в `logic.py`, Telegram-адаптер в `bot.py`, клиент стенда в `stand.py`. |
| `ml/docker-compose.yml` | fleet_service, predictive_antifraud, LiteLLM-прокси, шлюз GigaChat; дополнительные сервисы через профили. |
| `ml/monitoring/` | Prometheus, правила оповещений, Grafana. |
| `testdata/contract/` | Общие JSON-примеры запросов и ответов для Kotlin и Python. |
| `testdata/golden/` | Общие эталоны агрегации телеметрии и правил аномалий. |
| `scripts/` | Установка Android-инструментов и запуск на Linux; для Windows использовать Android Studio и `gradlew.bat`. |

## Что уже реализовано

Стенд обслуживает чат, загрузку и агрегацию телеметрии, проверку и хранение аномалий, решения пользователей, фоновую проверку, подтверждаемую отправку писем и Telegram. В Android остаются демо-режим и локальные реализации, выбираемые настройками.

Модель ассистента выбирается `FS_LLM_PROVIDER`: `gigachat` или `openrouter`. В Compose запросы идут через LiteLLM, GigaChat — дополнительно через собственный шлюз. Настройки кода и `.env.example` могут иметь разные значения по умолчанию: проверять `config.py`, пример окружения и Compose вместе.

ML-сервис работает без обученных моделей и сообщает о неготовности. Наличие сервиса и успешно выполненные пороговые правила не означают, что предиктивная проверка выполнена.

## Где искать нужную логику

- Телеметрия: Android `data/TripTablesMapper.kt`, `data/AutoGraphParameters.kt`; сервер `telemetry/mapper.py`, `telemetry/parameters.py`, `telemetry/service.py`.
- Правила: Android `anomaly/BaselineAnomalyDetector.kt`; сервер `rules/baseline.py`, `rules/thresholds.py`, `rules/check.py`.
- Синхронизация аномалий: Android `anomaly/AnomalySync.kt`, `anomaly/VehicleChecker.kt`; сервер `store/repository.py`, `store/scan.py`.
- Фоновая проверка: Android `anomaly/StandBackground.kt`; сервер `background/`.
- Ассистент: Android `chat/RemoteChatAgent.kt`; сервер `agent/`, `prompts/system.md`, `llm_proxy.py`, `gigachat/`.
- Настройки: Android `data/settings/`; серверы `config.py`, `.env.example`, конфигурация Compose.

Пути Android указаны относительно `app/src/main/java/ru/petrovich/telemetry/`, пути сервера — относительно `ml/fleet_service/src/fleet_service/`.

## Документация по задаче

- Обзор и Android: [README.md](README.md).
- Аналитика и особенности датчиков: [ml/ANALYTICS.md](ml/ANALYTICS.md).
- API и запуск: README каждого сервиса в `ml/`.
- Стенд, секреты и резервные копии: [ml/DEPLOY.md](ml/DEPLOY.md).
- Проверки на настоящем окружении: [ml/MANUAL_CHECK.md](ml/MANUAL_CHECK.md).
- План переноса на сервер: [ml/docs/assistant-server-plan.md](ml/docs/assistant-server-plan.md); принятые отступления: [ml/fleet-step-4-deviations.md](ml/fleet-step-4-deviations.md).
- LLM и устойчивость: [ml/docs/gigachat-plan.md](ml/docs/gigachat-plan.md), [ml/docs/load-and-resilience.md](ml/docs/load-and-resilience.md).
- Оценка всего конвейера: [ml/docs/assistant-eval.md](ml/docs/assistant-eval.md).
- Архитектурные инструкции Claude: [CLAUDE.md](CLAUDE.md). Прочитаны после добавления пользователем 2026-11-16; сведения о статусе планов и окружении требуют сверки с кодом.
- Общая проверка: [.claude/skills/fleet-check/SKILL.md](.claude/skills/fleet-check/SKILL.md), исполняемый сценарий [.claude/skills/fleet-check/check.sh](.claude/skills/fleet-check/check.sh).
- Формат отчёта по шагу: [.claude/skills/fleet-step-report/SKILL.md](.claude/skills/fleet-step-report/SKILL.md).

В `.claude-ml/` пока нет файлов. Launcher `claude-ml.sh`, `ML.md`, настройки и hooks, описанные в CLAUDE.md, недоступны. Не считать их активными для Codex. Исторические команды Claude `/fleet-check`, `/fleet-step-report`, `/code-review` сами по себе не предоставляют инструменты Codex; доступны файлы сценариев и их инструкции.

Обнаруженные расхождения CLAUDE.md с кодом: статус «GigaChat не начат» устарел (есть `gigachat/`, Compose и тесты), а фактические шрифты в `Theme.kt` — Onest/Manrope. Пользовательский CLAUDE.md при согласовании инструкций не изменялся.

## Локальное окружение при подготовке

Пользователь работает в Windows/PowerShell. Linux-команды из README требуют адаптации. В PowerShell читать русские файлы через `Get-Content -Encoding UTF8`.

Git в окружении агента сообщил `dubious ownership`. Для чтения статуса сработала настройка только на один вызов: `git -c safe.directory=C:/Users/Knohhonk/Desktop/Petrovich/petrovich status --short`. Глобальную конфигурацию Git не меняли. Этот абсолютный путь относится только к текущей рабочей копии.

При осмотре уже существовал незакоммиченный `ml/ml_notebooks/check_data.ipynb`. Его содержимое не изучалось и не изменялось; не считать его результатом работы агента.
