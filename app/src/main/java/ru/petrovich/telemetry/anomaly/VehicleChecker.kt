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
import java.time.LocalDateTime

/**
 * Проверка одной машины за период — шов [AnomalyScanner] между устройством и стендом. Отправлять на стенд
 * телеметрию, только что полученную со стенда, нельзя, поэтому стенд проверяет по машине и периоду сам.
 */
interface VehicleChecker {
    suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly>
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
 * [stored]), проверка на стенде ([AppSettings.anomaliesOnServer]) или устройство, как раньше.
 * Режим сравнения ([compareAllowed] — только отладочная сборка, и [AppSettings.anomalyCompare]; не при хранилище на
 * стенде — там другие id): считаются оба варианта, расхождения пишутся в [log], пользователю возвращается результат
 * по переключателю.
 */
class SwitchingVehicleChecker(
    private val settings: suspend () -> AppSettings,
    private val local: VehicleChecker,
    private val server: VehicleChecker,
    private val compareAllowed: Boolean,
    private val stored: VehicleChecker? = null,
    private val log: (String) -> Unit,
) : VehicleChecker {
    override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> {
        val s = settings()
        if (s.anomalyStoreOnServer && stored != null) return stored.check(vehicle, from, to)
        val onServer = s.anomaliesOnServer
        val compare = compareAllowed && s.anomalyCompare && !s.demoMode && s.serverUrl.isNotBlank()
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

    companion object {
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
