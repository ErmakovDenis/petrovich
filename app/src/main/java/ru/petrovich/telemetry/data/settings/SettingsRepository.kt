package ru.petrovich.telemetry.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val demoMode: Boolean = true,
    val userName: String = "",
    val password: String = "",
    val schemaId: String = "",
    val schemaName: String = "",
    val backgroundChecks: Boolean = true,
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
    }

    private fun Preferences.toSettings() = AppSettings(
        demoMode = this[Keys.demo] ?: true,
        userName = this[Keys.user].orEmpty(),
        password = this[Keys.password].orEmpty(),
        schemaId = this[Keys.schemaId].orEmpty(),
        schemaName = this[Keys.schemaName].orEmpty(),
        backgroundChecks = this[Keys.background] ?: true,
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
        }
    }
}
