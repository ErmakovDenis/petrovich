package ru.petrovich.telemetry.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Оформление: следовать системе или зафиксировать светлую/тёмную тему. */
enum class ThemeMode(val title: String) { SYSTEM("Системная"), LIGHT("Светлая"), DARK("Тёмная") }

data class AppSettings(
    val demoMode: Boolean = false,
    val userName: String = "",
    val password: String = "",
    val schemaId: String = "",
    val schemaName: String = "",
    val backgroundChecks: Boolean = true,
    /** Пройден ли первый запуск (выбор источника данных). */
    val onboarded: Boolean = false,
    /** Раскладка виджетов сводки: `id:размер,id:размер`; пусто — набор по умолчанию. */
    val widgetLayout: String = "",
    val pushCritical: Boolean = true,
    val pushWarning: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Когда последняя проверка данных завершилась успешно (epoch millis); 0 — ещё не проверяли. */
    val lastScanAt: Long = 0,
    /** Адрес стенда fleet_service, например `https://stand.example.ru`; пусто — стенд не используется. */
    val serverUrl: String = "",
    /** Чат «Петрович» отвечает через стенд (модель на стенде), а не заглушкой. */
    val assistantViaServer: Boolean = false,
    /** Телеметрия (таблицы, графики, сводка, проверка аномалий) загружается со стенда, а не из AutoGRAPH напрямую. */
    val telemetryViaServer: Boolean = false,
    /** Аномалии при проверке считает стенд (правила и аналитика), а не детектор на устройстве. */
    val anomaliesViaServer: Boolean = false,
    /** Отладка: считать аномалии и на устройстве, и на стенде, расхождения — в лог (только debug-сборка). */
    val anomalyCompare: Boolean = false,
    /** Аномалии и решения хранятся на стенде: лента — со стенда, решения — на стенд, проверка — с сохранением. */
    val anomalyStoreViaServer: Boolean = false,
    /** Фоновая проверка на стенде: стенд проверяет сам, приложение в фоне только забирает новые аномалии. */
    val backgroundViaServer: Boolean = false,
    /** Согласие хранить пароль на стенде (зашифрованным): стенд сам входит в AutoGRAPH, когда сессия истекла. */
    val standPasswordConsent: Boolean = false,
    /** Случайный id установки приложения: им стенд различает доступ устройств и отзывает его. */
    val deviceId: String = "",
    /** Стенд получил доступ этого устройства для фоновой проверки (нужно отозвать, когда она выключится). */
    val standAccessGranted: Boolean = false,
    /** Интервал фоновой проверки на стенде, мин (из ответа стенда) — чтобы сводка поняла, что проверка отстала. */
    val standScanIntervalMinutes: Int = 15,
    /** SHA-256 пароля, который стенд не принял: тот же пароль повторно не отправляется (не блокировать учётную запись). */
    val standPasswordRejected: String = "",
    /** Письма из чата: ассистент на стенде готовит черновик, письмо уходит после «Отправить» в чате. */
    val emailViaServer: Boolean = false,
) {
    /** Новые пути через стенд действуют только с реальными данными и заданным адресом стенда. */
    val assistantOnServer: Boolean get() = !demoMode && assistantViaServer && serverUrl.isNotBlank()

    /** Письма — продолжение ассистента на стенде: без него не действуют. */
    val emailsOnServer: Boolean get() = assistantOnServer && emailViaServer

    val telemetryOnServer: Boolean get() = !demoMode && telemetryViaServer && serverUrl.isNotBlank()

    val anomaliesOnServer: Boolean get() = !demoMode && anomaliesViaServer && serverUrl.isNotBlank()

    /** Хранилище на стенде — продолжение «Аномалий со стенда»: без него не действует. */
    val anomalyStoreOnServer: Boolean get() = anomaliesOnServer && anomalyStoreViaServer

    /** Фоновая проверка на стенде — продолжение хранилища на стенде; выключенная «Фоновая проверка» выключает и её. */
    val backgroundOnServer: Boolean get() = anomalyStoreOnServer && backgroundViaServer && backgroundChecks
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val demo = booleanPreferencesKey("demo_mode")
        val user = stringPreferencesKey("user_name")
        // TODO: для продакшена хранить пароль/токен в зашифрованном виде (Android Keystore).
        val password = stringPreferencesKey("password")
        val schemaId = stringPreferencesKey("schema_id")
        val schemaName = stringPreferencesKey("schema_name")
        val background = booleanPreferencesKey("background_checks")
        val onboarded = booleanPreferencesKey("onboarded")
        val widgetLayout = stringPreferencesKey("widget_layout")
        val pushCritical = booleanPreferencesKey("push_critical")
        val pushWarning = booleanPreferencesKey("push_warning")
        val themeMode = stringPreferencesKey("theme_mode")
        val lastScanAt = longPreferencesKey("last_scan_at")
        val serverUrl = stringPreferencesKey("server_url")
        val assistantViaServer = booleanPreferencesKey("assistant_via_server")
        val telemetryViaServer = booleanPreferencesKey("telemetry_via_server")
        val anomaliesViaServer = booleanPreferencesKey("anomalies_via_server")
        val anomalyCompare = booleanPreferencesKey("anomaly_compare")
        val anomalyStoreViaServer = booleanPreferencesKey("anomaly_store_via_server")
        val backgroundViaServer = booleanPreferencesKey("background_via_server")
        val standPasswordConsent = booleanPreferencesKey("stand_password_consent")
        val deviceId = stringPreferencesKey("device_id")
        val standAccessGranted = booleanPreferencesKey("stand_access_granted")
        val standScanIntervalMinutes = intPreferencesKey("stand_scan_interval_minutes")
        val standPasswordRejected = stringPreferencesKey("stand_password_rejected")
        val emailViaServer = booleanPreferencesKey("email_via_server")
    }

    private fun Preferences.toSettings() = AppSettings(
        demoMode = this[Keys.demo] ?: false,
        userName = this[Keys.user].orEmpty(),
        password = this[Keys.password].orEmpty(),
        schemaId = this[Keys.schemaId].orEmpty(),
        schemaName = this[Keys.schemaName].orEmpty(),
        backgroundChecks = this[Keys.background] ?: true,
        onboarded = this[Keys.onboarded] ?: false,
        widgetLayout = this[Keys.widgetLayout].orEmpty(),
        pushCritical = this[Keys.pushCritical] ?: true,
        pushWarning = this[Keys.pushWarning] ?: true,
        lastScanAt = this[Keys.lastScanAt] ?: 0,
        themeMode = ThemeMode.entries.firstOrNull { it.name == this[Keys.themeMode] } ?: ThemeMode.SYSTEM,
        serverUrl = this[Keys.serverUrl].orEmpty(),
        assistantViaServer = this[Keys.assistantViaServer] ?: false,
        telemetryViaServer = this[Keys.telemetryViaServer] ?: false,
        anomaliesViaServer = this[Keys.anomaliesViaServer] ?: false,
        anomalyCompare = this[Keys.anomalyCompare] ?: false,
        anomalyStoreViaServer = this[Keys.anomalyStoreViaServer] ?: false,
        backgroundViaServer = this[Keys.backgroundViaServer] ?: false,
        standPasswordConsent = this[Keys.standPasswordConsent] ?: false,
        deviceId = this[Keys.deviceId].orEmpty(),
        standAccessGranted = this[Keys.standAccessGranted] ?: false,
        standScanIntervalMinutes = this[Keys.standScanIntervalMinutes] ?: 15,
        standPasswordRejected = this[Keys.standPasswordRejected].orEmpty(),
        emailViaServer = this[Keys.emailViaServer] ?: false,
    )

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val new = transform(p.toSettings())
            p[Keys.demo] = new.demoMode
            p[Keys.user] = new.userName
            p[Keys.password] = new.password
            p[Keys.schemaId] = new.schemaId
            p[Keys.schemaName] = new.schemaName
            p[Keys.background] = new.backgroundChecks
            p[Keys.onboarded] = new.onboarded
            p[Keys.widgetLayout] = new.widgetLayout
            p[Keys.pushCritical] = new.pushCritical
            p[Keys.pushWarning] = new.pushWarning
            p[Keys.themeMode] = new.themeMode.name
            p[Keys.lastScanAt] = new.lastScanAt
            p[Keys.serverUrl] = new.serverUrl
            p[Keys.assistantViaServer] = new.assistantViaServer
            p[Keys.telemetryViaServer] = new.telemetryViaServer
            p[Keys.anomaliesViaServer] = new.anomaliesViaServer
            p[Keys.anomalyCompare] = new.anomalyCompare
            p[Keys.anomalyStoreViaServer] = new.anomalyStoreViaServer
            p[Keys.backgroundViaServer] = new.backgroundViaServer
            p[Keys.standPasswordConsent] = new.standPasswordConsent
            p[Keys.deviceId] = new.deviceId
            p[Keys.standAccessGranted] = new.standAccessGranted
            p[Keys.standScanIntervalMinutes] = new.standScanIntervalMinutes
            p[Keys.standPasswordRejected] = new.standPasswordRejected
            p[Keys.emailViaServer] = new.emailViaServer
        }
    }
}
