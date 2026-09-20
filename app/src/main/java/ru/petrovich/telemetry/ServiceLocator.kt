package ru.petrovich.telemetry

import android.content.Context
import ru.petrovich.telemetry.anomaly.AnomalyDetector
import ru.petrovich.telemetry.anomaly.AnomalyNotifier
import ru.petrovich.telemetry.anomaly.AnomalyScanner
import ru.petrovich.telemetry.anomaly.AnomalyStore
import ru.petrovich.telemetry.anomaly.BaselineAnomalyDetector
import ru.petrovich.telemetry.anomaly.CompositeAnomalyDetector
import ru.petrovich.telemetry.anomaly.MlAnomalyDetector
import ru.petrovich.telemetry.chat.ChatAgent
import ru.petrovich.telemetry.chat.StubChatAgent
import ru.petrovich.telemetry.data.AutoGraphTelemetryRepository
import ru.petrovich.telemetry.data.DemoTelemetryRepository
import ru.petrovich.telemetry.data.SwitchingTelemetryRepository
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.SettingsRepository

/** Простейший DI-контейнер. */
object ServiceLocator {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val settings by lazy { SettingsRepository(appContext) }

    val autoGraph by lazy { AutoGraphTelemetryRepository(ApiFactory.create(), settings) }

    val telemetry: TelemetryRepository by lazy {
        SwitchingTelemetryRepository(settings, DemoTelemetryRepository(), autoGraph)
    }

    val mlDetector by lazy { MlAnomalyDetector(appContext) }

    /** Когда ML-модель будет готова, базовые правила можно убрать из списка. */
    val anomalyDetector: AnomalyDetector by lazy {
        CompositeAnomalyDetector(listOf(mlDetector, BaselineAnomalyDetector()))
    }

    val anomalyStore by lazy { AnomalyStore(appContext) }

    val notifier by lazy { AnomalyNotifier(appContext) }

    val anomalyScanner by lazy { AnomalyScanner(telemetry, anomalyDetector, anomalyStore, notifier, settings) }

    /** Источник данных сменился (демо ↔ API, другая схема): старые аномалии относятся к другим машинам. */
    suspend fun onDataSourceChanged() {
        anomalyStore.clear()
        settings.update { it.copy(lastScanAt = 0) }
    }

    // Точка подключения ИИ-агента.
    val chatAgent: ChatAgent by lazy { StubChatAgent() }
}
