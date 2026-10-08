package ru.petrovich.telemetry

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.petrovich.telemetry.anomaly.AnomalyDetector
import ru.petrovich.telemetry.anomaly.AnomalyNotifier
import ru.petrovich.telemetry.anomaly.AnomalyScanner
import ru.petrovich.telemetry.anomaly.AnomalyStore
import ru.petrovich.telemetry.anomaly.AnomalySync
import ru.petrovich.telemetry.anomaly.BaselineAnomalyDetector
import ru.petrovich.telemetry.anomaly.CompositeAnomalyDetector
import ru.petrovich.telemetry.anomaly.LocalVehicleChecker
import ru.petrovich.telemetry.anomaly.MlAnomalyDetector
import ru.petrovich.telemetry.anomaly.ServerVehicleChecker
import ru.petrovich.telemetry.anomaly.StandAnomalies
import ru.petrovich.telemetry.anomaly.StoredVehicleChecker
import ru.petrovich.telemetry.anomaly.SwitchingVehicleChecker
import ru.petrovich.telemetry.anomaly.VehicleChecker
import ru.petrovich.telemetry.chat.ChatAgent
import ru.petrovich.telemetry.chat.RemoteChatAgent
import ru.petrovich.telemetry.chat.StubChatAgent
import ru.petrovich.telemetry.chat.SwitchingChatAgent
import ru.petrovich.telemetry.data.AutoGraphTelemetryRepository
import ru.petrovich.telemetry.data.DemoTelemetryRepository
import ru.petrovich.telemetry.data.ServerTelemetryRepository
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.SwitchingTelemetryRepository
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.SettingsRepository
import java.io.File

/** Простейший DI-контейнер. */
object ServiceLocator {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val settings by lazy { SettingsRepository(appContext) }

    val autoGraph by lazy { AutoGraphTelemetryRepository(ApiFactory.create(), settings) }

    /** Доступ к стенду от имени пользователя: токен сессии AutoGRAPH, не логин и пароль. */
    private val stand by lazy {
        StandClient(
            serverUrl = { settings.current().serverUrl },
            session = { rejectedToken -> autoGraph.standSession(rejectedToken) },
        )
    }

    // Переключатель «Данные через стенд»: выключен — AutoGRAPH напрямую, как раньше.
    val telemetry: TelemetryRepository by lazy {
        SwitchingTelemetryRepository({ settings.current() }, DemoTelemetryRepository(), autoGraph, ServerTelemetryRepository(stand))
    }

    val mlDetector by lazy { MlAnomalyDetector(appContext) }

    /** Когда ML-модель будет готова, базовые правила можно убрать из списка. */
    val anomalyDetector: AnomalyDetector by lazy {
        CompositeAnomalyDetector(listOf(mlDetector, BaselineAnomalyDetector()))
    }

    val anomalyStore by lazy { AnomalyStore(appContext) }

    private val standAnomalies by lazy { StandAnomalies(stand) }

    // Переключатель «Хранить аномалии на стенде»: выключен — лента и решения только на устройстве, как раньше.
    val anomalySync by lazy {
        AnomalySync({ settings.current() }, anomalyStore, standAnomalies, File(appContext.filesDir, "anomaly-import-report.json"))
    }

    val notifier by lazy { AnomalyNotifier(appContext) }

    // Переключатель «Аномалии со стенда»: выключен — детектор на устройстве, как раньше. Режим сравнения — только debug.
    val vehicleChecker: VehicleChecker by lazy {
        SwitchingVehicleChecker(
            settings = { settings.current() },
            local = LocalVehicleChecker(telemetry, anomalyDetector),
            server = ServerVehicleChecker(stand, log = { Log.i("AnomalyCheck", it) }),
            compareAllowed = BuildConfig.DEBUG,
            log = { Log.w("AnomalyCompare", it) },
            stored = StoredVehicleChecker(standAnomalies),
        )
    }

    val anomalyScanner by lazy { AnomalyScanner(telemetry, vehicleChecker, anomalyStore, notifier, settings, anomalySync) }

    /** Источник данных сменился (демо ↔ API, другая схема): старые аномалии относятся к другим машинам. */
    suspend fun onDataSourceChanged() {
        anomalyStore.clear()
        settings.update { it.copy(lastScanAt = 0) }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Ассистент на стенде; тот же доступ к стенду, что у телеметрии. */
    val remoteChatAgent by lazy {
        // При хранилище на стенде чат из карточки передаёт только id аномалии: стенд берёт её с решением из хранилища.
        RemoteChatAgent(stand, anomalyIdOnly = { settings.current().anomalyStoreOnServer })
    }

    // Переключатель «Ассистент через стенд»: выключен — заглушка, как раньше.
    val chatAgent: ChatAgent by lazy { SwitchingChatAgent(settings.settings, StubChatAgent(), remoteChatAgent, appScope) }
}
