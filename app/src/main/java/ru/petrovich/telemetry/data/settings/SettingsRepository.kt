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
import java.time.DayOfWeek

/** Оформление: следовать системе или зафиксировать светлую/тёмную тему. */
enum class ThemeMode(val title: String) { SYSTEM("Системная"), LIGHT("Светлая"), DARK("Тёмная") }

/** Будни по умолчанию для утреннего доклада — Пн–Пт. */
val DefaultReportDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

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
    /** Время утреннего доклада, "HH:mm". */
    val reportTime: String = "08:00",
    /** Дни недели, в которые приходит доклад. */
    val reportDays: Set<DayOfWeek> = DefaultReportDays,
    /** Доклад можно не только читать, но и слушать (синтез речи). */
    val voiceReportEnabled: Boolean = true,
    /** Ночью (между [quietHoursStart] и [quietHoursEnd]) присылать push только для срочных случаев. */
    val quietHoursEnabled: Boolean = true,
    /** Начало тихих часов, "HH:mm". */
    val quietHoursStart: String = "22:00",
    /** Конец тихих часов, "HH:mm". */
    val quietHoursEnd: String = "07:00",
    /** Не больше 3 обычных (не срочных) push в день — срочные идут без ограничения. */
    val limitDailyPush: Boolean = true,
    /** За какой день (yyyy-MM-dd) уже отправлен доклад — чтобы не дублировать. */
    val lastReportDate: String = "",
    /** За какой день считаем обычные push, и сколько их уже ушло сегодня. */
    val pushCountDate: String = "",
    val pushCountToday: Int = 0,
    /** Сотрудники, которым Петрович может позвонить или написать. */
    val contacts: List<Contact> = emptyList(),
    /**
     * Снимок состояния парка с последней проверки: «на линии» (едет) / «стоят» (на связи, не едут) /
     * «без связи» (нет свежих данных). Считается по реальной телеметрии в [ru.petrovich.telemetry.anomaly.AnomalyScanner],
     * а не живой статус трекера — обновляется так же редко, как сама проверка.
     */
    val fleetOnline: Int = 0,
    val fleetIdle: Int = 0,
    val fleetOffline: Int = 0,
    val fleetUpdatedAt: Long = 0,
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
) {
    /** Новые пути через стенд действуют только с реальными данными и заданным адресом стенда. */
    val assistantOnServer: Boolean get() = !demoMode && assistantViaServer && serverUrl.isNotBlank()

    val telemetryOnServer: Boolean get() = !demoMode && telemetryViaServer && serverUrl.isNotBlank()

    val anomaliesOnServer: Boolean get() = !demoMode && anomaliesViaServer && serverUrl.isNotBlank()

    /** Хранилище на стенде — продолжение «Аномалий со стенда»: без него не действует. */
    val anomalyStoreOnServer: Boolean get() = anomaliesOnServer && anomalyStoreViaServer
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
        val reportTime = stringPreferencesKey("report_time")
        val reportDays = stringPreferencesKey("report_days")
        val voiceReportEnabled = booleanPreferencesKey("voice_report_enabled")
        val quietHoursEnabled = booleanPreferencesKey("quiet_hours_enabled")
        val quietHoursStart = stringPreferencesKey("quiet_hours_start")
        val quietHoursEnd = stringPreferencesKey("quiet_hours_end")
        val limitDailyPush = booleanPreferencesKey("limit_daily_push")
        val lastReportDate = stringPreferencesKey("last_report_date")
        val pushCountDate = stringPreferencesKey("push_count_date")
        val pushCountToday = intPreferencesKey("push_count_today")
        val contacts = stringPreferencesKey("contacts_json")
        val fleetOnline = intPreferencesKey("fleet_online")
        val fleetIdle = intPreferencesKey("fleet_idle")
        val fleetOffline = intPreferencesKey("fleet_offline")
        val fleetUpdatedAt = longPreferencesKey("fleet_updated_at")
        val serverUrl = stringPreferencesKey("server_url")
        val assistantViaServer = booleanPreferencesKey("assistant_via_server")
        val telemetryViaServer = booleanPreferencesKey("telemetry_via_server")
        val anomaliesViaServer = booleanPreferencesKey("anomalies_via_server")
        val anomalyCompare = booleanPreferencesKey("anomaly_compare")
        val anomalyStoreViaServer = booleanPreferencesKey("anomaly_store_via_server")
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
        reportTime = this[Keys.reportTime] ?: "08:00",
        reportDays = this[Keys.reportDays]?.let { raw ->
            raw.split(',').mapNotNull { it.trim().toIntOrNull() }.mapNotNull { n -> DayOfWeek.entries.firstOrNull { it.value == n } }.toSet()
        }?.takeIf { it.isNotEmpty() } ?: DefaultReportDays,
        voiceReportEnabled = this[Keys.voiceReportEnabled] ?: true,
        quietHoursEnabled = this[Keys.quietHoursEnabled] ?: true,
        quietHoursStart = this[Keys.quietHoursStart] ?: "22:00",
        quietHoursEnd = this[Keys.quietHoursEnd] ?: "07:00",
        limitDailyPush = this[Keys.limitDailyPush] ?: true,
        lastReportDate = this[Keys.lastReportDate].orEmpty(),
        pushCountDate = this[Keys.pushCountDate].orEmpty(),
        pushCountToday = this[Keys.pushCountToday] ?: 0,
        contacts = decodeContacts(this[Keys.contacts].orEmpty()),
        fleetOnline = this[Keys.fleetOnline] ?: 0,
        fleetIdle = this[Keys.fleetIdle] ?: 0,
        fleetOffline = this[Keys.fleetOffline] ?: 0,
        fleetUpdatedAt = this[Keys.fleetUpdatedAt] ?: 0,
        serverUrl = this[Keys.serverUrl].orEmpty(),
        assistantViaServer = this[Keys.assistantViaServer] ?: false,
        telemetryViaServer = this[Keys.telemetryViaServer] ?: false,
        anomaliesViaServer = this[Keys.anomaliesViaServer] ?: false,
        anomalyCompare = this[Keys.anomalyCompare] ?: false,
        anomalyStoreViaServer = this[Keys.anomalyStoreViaServer] ?: false,
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
            p[Keys.reportTime] = new.reportTime
            p[Keys.reportDays] = new.reportDays.joinToString(",") { it.value.toString() }
            p[Keys.voiceReportEnabled] = new.voiceReportEnabled
            p[Keys.quietHoursEnabled] = new.quietHoursEnabled
            p[Keys.quietHoursStart] = new.quietHoursStart
            p[Keys.quietHoursEnd] = new.quietHoursEnd
            p[Keys.limitDailyPush] = new.limitDailyPush
            p[Keys.lastReportDate] = new.lastReportDate
            p[Keys.pushCountDate] = new.pushCountDate
            p[Keys.pushCountToday] = new.pushCountToday
            p[Keys.contacts] = encodeContacts(new.contacts)
            p[Keys.fleetOnline] = new.fleetOnline
            p[Keys.fleetIdle] = new.fleetIdle
            p[Keys.fleetOffline] = new.fleetOffline
            p[Keys.fleetUpdatedAt] = new.fleetUpdatedAt
            p[Keys.serverUrl] = new.serverUrl
            p[Keys.assistantViaServer] = new.assistantViaServer
            p[Keys.telemetryViaServer] = new.telemetryViaServer
            p[Keys.anomaliesViaServer] = new.anomaliesViaServer
            p[Keys.anomalyCompare] = new.anomalyCompare
            p[Keys.anomalyStoreViaServer] = new.anomalyStoreViaServer
        }
    }
}
