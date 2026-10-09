package ru.petrovich.telemetry

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.anomaly.AnomalyWorker
import ru.petrovich.telemetry.util.runCatchingCancellable

class TelemetryApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        ServiceLocator.notifier.createChannel()

        appScope.launch {
            ServiceLocator.settings.settings.map { it.backgroundChecks }.distinctUntilChanged().collect { enabled ->
                if (enabled) AnomalyWorker.schedule(this@TelemetryApp) else AnomalyWorker.cancel(this@TelemetryApp)
            }
        }
        // Доступ стенда для фоновой проверки — по настройкам: включили — выдать, выключили (в том числе демо-режим,
        // «Фоновая проверка», хранилище на стенде) — отозвать; сменили учётную запись, схему, адрес стенда или согласие
        // на пароль — обновить. При запуске — обновить токен. Нет сети — повторит AnomalyWorker.
        appScope.launch {
            ServiceLocator.settings.settings
                .map { listOf(it.backgroundOnServer, it.userName, it.schemaId, it.standPasswordConsent, it.serverUrl) }
                .distinctUntilChanged()
                .collect { runCatchingCancellable { ServiceLocator.backgroundAccess.sync() } }
        }
    }
}
