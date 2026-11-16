package ru.petrovich.telemetry.anomaly

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandRequests
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.LocalDateTime

/**
 * Проверка одной машины за период — шов [AnomalyScanner] между устройством и стендом. Отправлять на стенд
 * телеметрию, только что полученную со стенда, нельзя, поэтому стенд проверяет по машине и периоду сам.
 */
interface VehicleChecker {
    suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly>

    /**
     * Лента событий парка одним запросом — для источников, которые отдают готовые аномалии целиком
     * (лента событий API), без перебора машин приложения. null — источник умеет только по машинам,
     * работаем через [check], как раньше.
     */
    suspend fun checkAll(from: LocalDateTime, to: LocalDateTime): List<Anomaly>? = null
}

/** Как раньше: телеметрия из [TelemetryRepository] (AutoGRAPH, стенд или демо) и детектор на устройстве. */
class LocalVehicleChecker(
    private val repository: TelemetryRepository,
    private val detector: AnomalyDetector,
) : VehicleChecker {
    override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> =
        detector.detect(repository.telemetry(vehicle, from, to))
}

/** Тело `POST /v1/anomalies/check` стенда (ml/fleet_service, schemas/anomalies.py). */
@Serializable
data class CheckRequest(val vehicleId: String, val from: String, val to: String, val utcOffsetMinutes: Int)

@Serializable
data class AnalyticsStatus(val status: String, val modelVersion: String? = null, val detail: String? = null, val anomaliesFound: Int = 0)

@Serializable
data class CheckAnalytics(val predictive: AnalyticsStatus, val antifraud: AnalyticsStatus)

@Serializable
data class CheckResponse(
    val vehicleId: String,
    val anomalies: List<Anomaly>,
    val rulesAnomalies: Int,
    val analytics: CheckAnalytics,
    /** false — проверка только по правилам: модели аналитики не загружены или недоступны. */
    val modelsReady: Boolean,
)

/** Аномалии считает стенд: правила (тот же алгоритм и формат id) и аналитика predictive_antifraud. */
class ServerVehicleChecker(
    private val stand: StandClient,
    private val utcOffsetMinutes: () -> Int = StandRequests::utcOffsetMinutes,
    private val log: (String) -> Unit = {},
) : VehicleChecker {
    override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> =
        checkFull(vehicle, from, to).anomalies

    suspend fun checkFull(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): CheckResponse {
        val body = ApiFactory.json.encodeToString(CheckRequest.serializer(), request(vehicle, from, to))
        val text = stand.post("v1/anomalies/check", body, readTimeoutSeconds = StandRequests.LONG_TIMEOUT_SECONDS)
        val response = StandRequests.decode(CheckResponse.serializer(), text)
        if (!response.modelsReady) {
            log("${vehicle.name}: проверка только по правилам — " + listOf(response.analytics.predictive, response.analytics.antifraud)
                .joinToString("; ") { it.detail ?: it.status })
        }
        // Название машины — как в приложении (лента и уведомления совпадают с выбором машины и проверкой на устройстве).
        return response.copy(anomalies = response.anomalies.map { it.copy(vehicleName = vehicle.name) })
    }

    /** Период без секунд, как у телеметрии: проверка попадает в тот же кэш телеметрии стенда. */
    fun request(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): CheckRequest {
        val (f, t) = StandRequests.period(from, to)
        return CheckRequest(vehicle.id, StandRequests.format(f), StandRequests.format(t), utcOffsetMinutes())
    }
}

/**
 * Выбирает проверку на каждый вызов по настройкам: проверка с сохранением на стенде ([AppSettings.anomalyStoreOnServer],
 * [stored]), API детекции ([AppSettings.anomaliesViaApi], [api]), проверка на стенде ([AppSettings.anomaliesOnServer])
 * или устройство, как раньше.
 * API недоступен (сеть, 5xx, отказ авторизации) — проверка автоматически уходит в старый путь без API:
 * пороговые правила на устройстве или стенд по переключателю. Повторных обращений к упавшему API нет
 * [API_RETRY_PAUSE]: иначе сверка по машинам уперлась бы в те же таймауты. О сбое и восстановлении
 * сообщает [onApiDegraded] — отметка для интерфейса (резервная схема), только при смене состояния.
 * Режим сравнения ([compareAllowed] — только отладочная сборка, и [AppSettings.anomalyCompare]; не при хранилище на
 * стенде — там другие id): считаются оба варианта, расхождения пишутся в [log], пользователю возвращается результат
 * по переключателю. На API не распространяется: там свои id и свой источник.
 */
class SwitchingVehicleChecker(
    private val settings: suspend () -> AppSettings,
    private val local: VehicleChecker,
    private val server: VehicleChecker,
    private val compareAllowed: Boolean,
    private val stored: VehicleChecker? = null,
    private val api: VehicleChecker? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val onApiDegraded: (suspend (Boolean) -> Unit)? = null,
    private val log: (String) -> Unit,
) : VehicleChecker {
    /** До этого момента API не отвечал: лента и точечные проверки к нему не обращаются. */
    @Volatile
    private var apiUnavailableUntil: Long = 0L

    override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> {
        val s = settings()
        if (s.anomalyStoreOnServer && stored != null) return stored.check(vehicle, from, to)
        if (s.anomaliesViaApi && api != null && clock() >= apiUnavailableUntil) {
            val anomalies = runCatchingCancellable { api.check(vehicle, from, to) }
                .getOrElse { e -> onApiFailure(e); null }
            if (anomalies != null) {
                onApiRecovered()
                return anomalies
            }
        }
        return checkLegacy(s, vehicle, from, to)
    }

    /**
     * Ленту парка отдаёт только API; у хранилища стенда, стенда и устройства путь по машинам, как раньше.
     * API включён, но лента не пришла — тоже null: проверка идёт дальше по машинам через [check]
     * (пороговые правила или стенд), а не остаётся без результата.
     */
    override suspend fun checkAll(from: LocalDateTime, to: LocalDateTime): List<Anomaly>? {
        val s = settings()
        if (s.anomalyStoreOnServer && stored != null) return null
        if (s.anomaliesViaApi && api != null && clock() >= apiUnavailableUntil) {
            val feed = runCatchingCancellable { api.checkAll(from, to) }
                .getOrElse { e -> onApiFailure(e); null }
            if (feed != null) {
                onApiRecovered()
                return feed
            }
        }
        return null
    }

    /** Старый путь без API: стенд или устройство по переключателю ([AppSettings.anomaliesOnServer]). */
    private suspend fun checkLegacy(s: AppSettings, vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> {
        val onServer = s.anomaliesOnServer
        val compare = compareAllowed && s.anomalyCompare && s.serverUrl.isNotBlank()
        if (!compare) return (if (onServer) server else local).check(vehicle, from, to)

        // Оба варианта параллельно: время проверки машины не удваивается.
        val (fromLocal, fromServer) = coroutineScope {
            val l = async { runCatchingCancellable { local.check(vehicle, from, to) } }
            val s = async { runCatchingCancellable { server.check(vehicle, from, to) } }
            l.await() to s.await()
        }
        log(compareReport(vehicle, fromLocal, fromServer))
        return (if (onServer) fromServer else fromLocal).getOrThrow()
    }

    /** API не ответил: пауза повторных обращений и причина ухода в старый путь — в [log]. */
    private suspend fun onApiFailure(e: Throwable) {
        apiUnavailableUntil = clock() + API_RETRY_PAUSE.toMillis()
        log("API детекции аномалий недоступен (${e.message ?: e.toString()}) — проверка пороговыми правилами, как раньше")
        if (onApiDegraded != null && !settings().anomalyApiDegraded) onApiDegraded.invoke(true)
    }

    /** API снова отвечает — резервную отметку для интерфейса снимаем (если она была). */
    private suspend fun onApiRecovered() {
        if (onApiDegraded != null && settings().anomalyApiDegraded) onApiDegraded.invoke(false)
    }

    companion object {
        /** Пауза повторных обращений к упавшему API; после неё API пробуется снова. */
        val API_RETRY_PAUSE: Duration = Duration.ofMinutes(5)

        /** Расхождения по id и важности; detectedAt и описание не сравниваются. */
        fun compareReport(vehicle: Vehicle, local: Result<List<Anomaly>>, server: Result<List<Anomaly>>): String {
            val l = local.getOrNull() ?: return "сравнение ${vehicle.name}: на устройстве ошибка — ${local.exceptionOrNull()?.message}"
            val s = server.getOrNull() ?: return "сравнение ${vehicle.name}: на стенде ошибка — ${server.exceptionOrNull()?.message}"
            val byId = s.associateBy { it.id }
            val localIds = l.mapTo(HashSet()) { it.id }
            val onlyLocal = l.filter { it.id !in byId }.map { it.id }
            val onlyServer = s.filter { it.id !in localIds }.map { it.id }
            val severity = l.mapNotNull { a -> byId[a.id]?.takeIf { it.severity != a.severity }?.let { "${a.id}: ${a.severity} ≠ ${it.severity}" } }
            if (onlyLocal.isEmpty() && onlyServer.isEmpty() && severity.isEmpty()) {
                return "сравнение ${vehicle.name}: совпало (${l.size})"
            }
            return "сравнение ${vehicle.name}: РАСХОЖДЕНИЕ — только на устройстве $onlyLocal; только на стенде $onlyServer; важность $severity"
        }
    }
}
