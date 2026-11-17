package ru.petrovich.telemetry.anomaly

import kotlinx.serialization.Serializable
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandRequests
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.api.ApiFactory
import java.time.LocalDateTime

// Хранилище аномалий и решений на стенде (ml/fleet_service, schemas/store.py). Аномалия со стенда разбирается
// в [Anomaly] приложения: поля решения (resolution, falseAlarmReason, resolvedBy, resolvedAt) те же, лишние
// поля стенда (episodeEnd, lastDetectedAt) не читаются.

/** Чем стенд наполнил ленту (ml/fleet_service schemas/store.FeedSource). */
object FeedSource {
    const val API = "api"
    const val API_CACHE = "api_cache"
    const val RULES = "rules"
    const val UNAVAILABLE = "unavailable"
}

/** Ответ `GET /v1/anomalies`. */
@Serializable
data class AnomalyListResponse(
    val total: Int,
    val items: List<Anomaly>,
    /** Последняя успешная проверка с сохранением по схеме, epoch millis; null — проверок ещё не было. */
    val lastScanAt: Long? = null,
    /**
     * Источник выдачи — для дисклеймеров. Пусто, если стенд ещё не отдаёт поле
     * (старый деплой): не показываем ложный «антифрод недоступен».
     */
    val feedSource: String = "",
    /** Когда лента API Антифрод последний раз успешно обновилась, epoch millis. */
    val apiUpdatedAt: Long? = null,
)

/** Тело `POST /v1/anomalies/{id}/resolve`; [resolution] = null — вернуть в «ждут решения». */
@Serializable
data class ResolveRequest(val resolution: Resolution?, val reason: String? = null, val utcOffsetMinutes: Int)

/** Тело `POST /v1/anomalies/scan`: проверка с сохранением. */
@Serializable
data class ScanRequest(
    val vehicleIds: List<String>,
    val from: String,
    val to: String,
    val utcOffsetMinutes: Int,
    /** При недоступности API Антифрод считать аномалии общими правилами стенда. */
    val fallbackRules: Boolean = false,
)

@Serializable
data class VehicleScan(
    val vehicleId: String,
    val ok: Boolean,
    val error: String? = null,
    /** Аномалии машины, найденные проверкой, как они сохранены на стенде (id и решения стенда). */
    val anomalies: List<Anomaly> = emptyList(),
    /** Каких из них в хранилище до этой проверки не было. */
    val newIds: List<String> = emptyList(),
    val modelsReady: Boolean? = null,
)

@Serializable
data class ScanResponse(
    val from: String,
    val to: String,
    val results: List<VehicleScan>,
    val lastScanAt: Long? = null,
    /** Пусто — стенд без поля feedSource; дисклеймер не показываем. */
    val feedSource: String = "",
    val apiUpdatedAt: Long? = null,
)

/** Запись локальной истории для разового переноса на стенд (`POST /v1/anomalies/import`). */
@Serializable
data class ImportItem(
    val localId: String,
    val vehicleId: String,
    val vehicleName: String,
    val kind: String,
    val parameterName: String,
    val title: String,
    val eventTime: String,
    val resolution: Resolution? = null,
    val reason: String? = null,
)

@Serializable
data class ImportRequest(val utcOffsetMinutes: Int, val items: List<ImportItem>)

/** Решение, которому стенд не нашёл пары, и почему. */
@Serializable
data class ImportUnmatched(
    val localId: String,
    val vehicleId: String,
    val vehicleName: String,
    val title: String,
    val eventTime: String,
    val resolution: Resolution,
    val reason: String? = null,
    val why: String,
)

@Serializable
data class ImportResponse(
    /** Решений отправлено; перенесено; на стенде уже было решение (не перезаписано). */
    val decisions: Int,
    val applied: Int,
    val alreadyResolved: Int,
    val unmatched: List<ImportUnmatched>,
    /** Аномалии без решения: найдены на стенде и не найдены. */
    val restored: Int,
    val notFound: Int,
    val scanErrors: List<String> = emptyList(),
)

/** Запросы к хранилищу стенда от имени пользователя ([StandClient]: токен сессии, схема, логин). */
class StandAnomalies(
    private val stand: StandClient,
    private val utcOffsetMinutes: () -> Int = StandRequests::utcOffsetMinutes,
) {
    /** Последние [limit] аномалий машин пользователя, сначала новые. */
    suspend fun list(limit: Int): AnomalyListResponse {
        val text = stand.get("v1/anomalies", mapOf("utcOffsetMinutes" to utcOffsetMinutes().toString(), "limit" to limit.toString()))
        return StandRequests.decode(AnomalyListResponse.serializer(), text)
    }

    /** Проверка машины с сохранением; машину не проверили — [StandException] с причиной от стенда. */
    suspend fun scan(
        vehicle: Vehicle,
        from: LocalDateTime,
        to: LocalDateTime,
        fallbackRules: Boolean = false,
    ): VehicleScan {
        val body = ApiFactory.json.encodeToString(ScanRequest.serializer(), scanRequest(vehicle, from, to, fallbackRules))
        val text = stand.post("v1/anomalies/scan", body, readTimeoutSeconds = StandRequests.LONG_TIMEOUT_SECONDS)
        val response = StandRequests.decode(ScanResponse.serializer(), text)
        val result = response.results.firstOrNull { it.vehicleId == vehicle.id }
            ?: throw StandException("Стенд не вернул результат проверки машины")
        if (!result.ok) throw StandException(result.error ?: "Стенд не проверил машину")
        return result
    }

    fun scanRequest(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime, fallbackRules: Boolean = false) =
        ScanRequest(listOf(vehicle.id), StandRequests.format(from), StandRequests.format(to), utcOffsetMinutes(), fallbackRules)

    /** Решение по аномалии для всех пользователей схемы; null — вернуть в «ждут решения». */
    suspend fun resolve(id: String, resolution: Resolution?, reason: String?): Anomaly {
        val body = ApiFactory.json.encodeToString(ResolveRequest.serializer(), ResolveRequest(resolution, reason, utcOffsetMinutes()))
        return StandRequests.decode(Anomaly.serializer(), stand.post(listOf("v1", "anomalies", id, "resolve"), body))
    }

    /** Разовый перенос локальной истории: стенд проверяет периоды вокруг событий и переносит решения. */
    suspend fun importHistory(local: List<Anomaly>): ImportResponse {
        val body = ApiFactory.json.encodeToString(ImportRequest.serializer(), importRequest(local))
        val text = stand.post("v1/anomalies/import", body, readTimeoutSeconds = StandRequests.LONG_TIMEOUT_SECONDS)
        return StandRequests.decode(ImportResponse.serializer(), text)
    }

    fun importRequest(local: List<Anomaly>) = ImportRequest(
        utcOffsetMinutes = utcOffsetMinutes(),
        items = local.map {
            ImportItem(it.id, it.vehicleId, it.vehicleName, it.kind, it.parameterName, it.title, it.eventTime, it.resolution, it.falseAlarmReason)
        },
    )
}

/** Проверка с сохранением на стенде: аномалии приходят с id и решениями хранилища. */
class StoredVehicleChecker(
    private val stand: StandAnomalies,
    private val fallbackRules: suspend () -> Boolean = { false },
) : VehicleChecker {
    override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> =
        stand.scan(vehicle, from, to, fallbackRules = fallbackRules()).anomalies
}
