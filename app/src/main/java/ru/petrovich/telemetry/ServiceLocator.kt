package ru.petrovich.telemetry

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.petrovich.telemetry.anomaly.AnomalyDetector
import ru.petrovich.telemetry.anomaly.AnomalyNotifier
import ru.petrovich.telemetry.anomaly.AnomalyScanner
import ru.petrovich.telemetry.anomaly.AnomalyStore
import ru.petrovich.telemetry.anomaly.AnomalySync
import ru.petrovich.telemetry.anomaly.ApiAnomalyChecker
import ru.petrovich.telemetry.anomaly.ApiVehicleResolver
import ru.petrovich.telemetry.anomaly.BackgroundAccess
import ru.petrovich.telemetry.anomaly.BaselineAnomalyDetector
import ru.petrovich.telemetry.anomaly.CompositeAnomalyDetector
import ru.petrovich.telemetry.anomaly.LocalVehicleChecker
import ru.petrovich.telemetry.anomaly.MlAnomalyDetector
import ru.petrovich.telemetry.anomaly.NotificationStore
import ru.petrovich.telemetry.anomaly.ServerVehicleChecker
import ru.petrovich.telemetry.anomaly.StandAnomalies
import ru.petrovich.telemetry.anomaly.StandBackground
import ru.petrovich.telemetry.anomaly.StoredVehicleChecker
import ru.petrovich.telemetry.anomaly.SwitchingVehicleChecker
import ru.petrovich.telemetry.anomaly.VehicleChecker
import ru.petrovich.telemetry.chat.ChatAgent
import ru.petrovich.telemetry.chat.EmailActions
import ru.petrovich.telemetry.chat.StandEmails
import ru.petrovich.telemetry.chat.PetrovichAgent
import ru.petrovich.telemetry.chat.RemoteChatAgent
import ru.petrovich.telemetry.chat.DurableChatAgent
import ru.petrovich.telemetry.chat.emailContacts
import ru.petrovich.telemetry.chat.HistorySwitchingRemote
import ru.petrovich.telemetry.data.StandRequests
import ru.petrovich.telemetry.chat.SwitchingChatAgent
import ru.petrovich.telemetry.data.AssistantIdentity
import ru.petrovich.telemetry.data.StandIdentity
import ru.petrovich.telemetry.data.settings.AndroidIdentityVault
import ru.petrovich.telemetry.data.AutoGraphTelemetryRepository
import ru.petrovich.telemetry.data.DemoTelemetryRepository
import ru.petrovich.telemetry.data.ServerTelemetryRepository
import ru.petrovich.telemetry.data.ServerWidgetStatsRepository
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandTelegram
import ru.petrovich.telemetry.data.SwitchingTelemetryRepository
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.api.PetrovichAi
import ru.petrovich.telemetry.data.settings.SettingsRepository
import ru.petrovich.telemetry.ui.home.ReportsPeriodStore
import ru.petrovich.telemetry.ui.home.WidgetPeriodCache
import ru.petrovich.telemetry.ui.park.FleetCache
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.io.File

/** Простейший DI-контейнер. */
object ServiceLocator {
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        if (!identityMaintenanceStarted) {
            identityMaintenanceStarted = true
            appScope.launch {
                settings.settings.collectLatest {
                    while (runCatchingCancellable { assistantIdentity.cleanupIfNeeded() }.getOrDefault(true)) {
                        delay(60_000)
                    }
                }
            }
        }
    }

    private var identityMaintenanceStarted = false

    val settings by lazy { SettingsRepository(appContext) }

    val assistantIdentity by lazy {
        AssistantIdentity(
            { settings.current() }, { transform -> settings.update(transform) },
            AndroidIdentityVault(), StandIdentity(allowLocalHttp = BuildConfig.DEBUG),
        )
    }

    val autoGraph by lazy { AutoGraphTelemetryRepository(ApiFactory.create(), settings) }

    /** Доступ к стенду от имени пользователя: токен сессии AutoGRAPH, не логин и пароль. */
    private val stand by lazy {
        StandClient(
            serverUrl = { settings.current().serverUrl },
            session = { rejectedToken -> autoGraph.standSession(rejectedToken) },
        )
    }

    val widgetStats by lazy { ServerWidgetStatsRepository(stand) }

    // Переключатель «Данные через стенд»: выключен — AutoGRAPH напрямую, как раньше.
    val telemetry: TelemetryRepository by lazy {
        SwitchingTelemetryRepository({ settings.current() }, DemoTelemetryRepository(), autoGraph, ServerTelemetryRepository(stand))
    }

    /** Снимок парка для Главной и Парка: не сбрасывается при смене вкладки. */
    val fleetCache by lazy { FleetCache(telemetry, settings) }

    /** Снимки виджетов за день / неделю / месяц: не сбрасываются при смене вкладки. */
    val widgetPeriodCache by lazy { WidgetPeriodCache() }

    /** Выбор периода на «Отчётах» / карточке виджета — не сбрасывается при переходах по стеку. */
    val reportsPeriod by lazy { ReportsPeriodStore() }

    val mlDetector by lazy { MlAnomalyDetector(appContext) }

    /** Когда ML-модель будет готова, базовые правила можно убрать из списка. */
    val anomalyDetector: AnomalyDetector by lazy {
        CompositeAnomalyDetector(listOf(mlDetector, BaselineAnomalyDetector()))
    }

    val anomalyStore by lazy { AnomalyStore(appContext) }

    val notificationStore by lazy { NotificationStore(appContext) }

    private val standAnomalies by lazy { StandAnomalies(stand) }

    // Переключатель «Хранить аномалии на стенде»: выключен — лента и решения только на устройстве, как раньше.
    val anomalySync by lazy {
        AnomalySync(
            { settings.current() }, anomalyStore, standAnomalies, File(appContext.filesDir, "anomaly-import-report.json"),
            // Фоновая проверка на стенде: время последней проверки в сводке — со стенда (пока стенд не проверял —
            // остаётся прежнее).
            onStandLastScan = { at -> if (at != null) settings.update { it.copy(lastScanAt = at) } },
            onFeedState = { source, apiAt ->
                settings.update {
                    it.copy(anomalyFeedSource = source, anomalyApiUpdatedAt = apiAt ?: 0L)
                }
            },
        )
    }

    // Переключатель «Фоновая проверка на стенде»: выключен — доступ стенда отзывается, проверяет устройство, как раньше.
    val backgroundAccess by lazy {
        BackgroundAccess({ settings.current() }, { transform -> settings.update(transform) }, StandBackground(stand))
    }

    val notifier by lazy { AnomalyNotifier(appContext) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Переключатель «Детекция аномалий через API»: доступ — логин/пароль из настроек (HTTP Basic).
    // Креды кэшируем из настроек: перехватчик читает их синхронно на каждый запрос.
    val petrovichAiApi by lazy {
        val credentials = MutableStateFlow(Pair("", ""))
        appScope.launch {
            settings.settings.collect { s -> credentials.value = s.anomalyApiUser.trim() to s.anomalyApiPassword }
        }
        PetrovichAi.create(credentials = { credentials.value })
    }

    /** Сопоставление машин приложения с id API; общий на сверку, чтобы список машин API не тянуть на каждую. */
    private val apiVehicleResolver by lazy { ApiVehicleResolver(petrovichAiApi) }

    // Переключатель «Аномалии со стенда»: выключен — детектор на устройстве, как раньше. Режим сравнения — только debug.
    val vehicleChecker: VehicleChecker by lazy {
        SwitchingVehicleChecker(
            settings = { settings.current() },
            local = LocalVehicleChecker(telemetry, anomalyDetector),
            server = ServerVehicleChecker(stand, log = { Log.i("AnomalyCheck", it) }),
            compareAllowed = BuildConfig.DEBUG,
            log = { Log.w("AnomalyCompare", it) },
            stored = StoredVehicleChecker(standAnomalies, fallbackRules = { settings.current().anomalyFallbackRules }),
            api = ApiAnomalyChecker(petrovichAiApi, vehicleId = { v -> apiVehicleResolver.resolve(v) }),
            // Устаревший прямой API на устройстве: отметка для тестов; при стенде лента идёт через StoredVehicleChecker.
            onApiDegraded = { degraded -> settings.update { it.copy(anomalyApiDegraded = degraded) } },
        )
    }

    val anomalyScanner by lazy {
        AnomalyScanner(telemetry, vehicleChecker, anomalyStore, notifier, settings, notificationStore, anomalySync, backgroundAccess)
    }

    /** Источник данных сменился (схема / учётная запись): старые аномалии и снимок парка — от других машин. */
    suspend fun onDataSourceChanged() {
        anomalyStore.clear()
        anomalySync.resetNotifyMark()
        fleetCache.clear()
        widgetPeriodCache.clear()
        reportsPeriod.clear()
        settings.update {
            it.copy(
                lastScanAt = 0, fleetUpdatedAt = 0, fleetOnline = 0, fleetIdle = 0, fleetOffline = 0,
                anomalyFeedSource = "", anomalyApiUpdatedAt = 0,
            )
        }
    }

    /** Ассистент на стенде; тот же доступ к стенду, что у телеметрии. */
    private val legacyRemoteChatAgent by lazy {
        // При хранилище на стенде чат из карточки передаёт только id аномалии: стенд берёт её с решением из хранилища.
        RemoteChatAgent(
            stand,
            anomalyIdOnly = { settings.current().anomalyStoreOnServer },
            // Переключатель «Письма из чата»: выключен — ассистенту письма недоступны, как раньше.
            allowEmail = { settings.current().emailsOnServer },
            contacts = { settings.current().contacts },
        )
    }

    val durableChatAgent by lazy {
        DurableChatAgent(
            scope = { assistantIdentity.conversationScope() }, load = { assistantIdentity.loadConversation() },
            save = { owner, value -> assistantIdentity.saveConversation(owner, value) },
            call = { owner, method, path, body, query -> assistantIdentity.personalRequest(owner, method, path, body, query) },
            allowEmail = { settings.current().emailsOnServer },
            contacts = { settings.current().contacts.emailContacts() },
        )
    }
    val remoteChatAgent by lazy {
        HistorySwitchingRemote(legacyRemoteChatAgent, durableChatAgent) { settings.current().chatHistoryOnServer }
    }

    /** «Отправить» и «Отменить» у черновика письма со стенда в чате. */
    val emails: EmailActions by lazy {
        StandEmails(stand, contacts = { settings.current().contacts }, personalAction = { owner, id, action, body ->
            val response = assistantIdentity.personalRequest(owner, "POST", listOf("v2", "emails", id, action), body)
            if (response.code == 409) {
                val alreadySent = runCatchingCancellable {
                    ApiFactory.json.parseToJsonElement(response.body).jsonObject["detail"]?.jsonPrimitive?.content
                        ?.startsWith("Письмо уже отправлено") == true
                }.getOrDefault(false)
                throw ru.petrovich.telemetry.data.StandException(
                    if (alreadySent) "Письмо уже отправлено" else "Действие с письмом уже выполнено или недоступно", 409,
                )
            }
            StandRequests.decode(ru.petrovich.telemetry.chat.EmailResult.serializer(), response.body)
        })
    }

    /** Ссылка подключения Telegram-бота (раздел «Стенд» в настройках). */
    val telegram by lazy { StandTelegram(stand) }

    // Переключатель «Ассистент через стенд»: выключен — локальный движок по правилам и данным парка.
    val chatAgent: ChatAgent by lazy {
        SwitchingChatAgent(settings.settings, PetrovichAgent(), remoteChatAgent, appScope) { id ->
            anomalyStore.anomalies.value.firstOrNull { it.id == id }
        }
    }
}
