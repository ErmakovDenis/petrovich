# Android

Дополняет корневой `AGENTS.md`. Рабочий каталог команд — корень репозитория.

## Устройство и правила

- Пакет: `ru.petrovich.telemetry`. JDK 17, compileSdk/targetSdk 35, minSdk 26. Версии зависимостей заданы в Gradle-файлах.
- DI организован через `ServiceLocator`; используй существующие интерфейсы репозиториев, детекторов и ассистента.
- В корутинах используй `runCatchingCancellable` из `util/Coroutines.kt`, чтобы не поглощать отмену. Общий экземпляр kotlinx.serialization — `ApiFactory.json`.
- UI — Compose Material 3, темы в `ui/theme/`, ресурсы в `res/`. Сохраняй светлую/тёмную темы, шрифты Onest/Manrope и русские тексты интерфейса.
- Настройки и переключатели — `data/settings/`. Проверяй производные условия серверных режимов и поведение демо-режима, а не только один флаг.
- При смене источника данных используй `ServiceLocator.onDataSourceChanged()` для сброса связанных данных. Переключение прямого доступа на стенд само по себе не означает смену автопарка.
- `AnomalyStore` при серверном хранилище служит локальным кэшем. Решения, импорт и серверные id проходят через `AnomalySync`; не создавай второй независимый источник истины.
- Изменения агрегации и правил согласовывай с Python и общими golden-фикстурами. Сохраняй обработку повторных времён, границ чанков, отключённых датчиков и зажигания.
- Секреты модели и SMTP остаются на стенде. Для сетевых настроек учитывай различия debug/release, включая network security config.

## Проверка

PowerShell, из корня репозитория:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Нужны JDK 17 и Android SDK; путь к SDK задаётся локально через Android Studio, окружение или `local.properties`.

Для точечной проверки можно передать `--tests "ru.petrovich.telemetry.TripTablesGoldenTest"` задаче `testDebugUnitTest`. При изменении контрактов проверяй соответствующие `Stand*Test`, `ServerTelemetryRepositoryTest`, `RemoteChatAgentTest` и Python `test_contract.py`; при изменении правил — `AnomalyGoldenTest` и серверные golden-тесты.

На Linux: `source scripts/env.sh`, затем `./gradlew testDebugUnitTest` и `./gradlew assembleDebug`. Не запускай установку SDK или эмулятор для задачи, которой они не нужны.

Если ожидаемое поведение агрегации или правил намеренно изменено, обновляй эталоны через Kotlin-тесты `TripTablesGoldenTest` / `AnomalyGoldenTest` с `UPDATE_GOLDEN=1`, затем проверяй обе реализации и diff фикстур. Не перезаписывай эталоны только ради устранения падения теста. В PowerShell переменная задаётся через `$env:UPDATE_GOLDEN = '1'`; после генерации убери её через `Remove-Item Env:UPDATE_GOLDEN`.

`REAL_TRIP_TABLES` позволяет дополнительно проверить сохранённые реальные ответы AutoGRAPH; `ml/fleet_service/scripts/compare_real.sh` сравнивает обработку Kotlin и Python без обращения к настоящему API. Проверки на устройстве, уведомления и настоящий AutoGRAPH отмечай отдельно от JVM-тестов. Утверждение Claude об отсутствии KVM относится к его Linux-окружению, доступность эмулятора в текущем окружении проверяй отдельно.
