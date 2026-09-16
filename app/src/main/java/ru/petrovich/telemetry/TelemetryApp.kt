package ru.petrovich.telemetry

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.anomaly.AnomalyWorker

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
    }
}
