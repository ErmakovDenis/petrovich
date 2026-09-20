package ru.petrovich.telemetry.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
)

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
        }
    }
}
