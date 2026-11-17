package ru.petrovich.telemetry.anomaly

import retrofit2.HttpException
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.api.AnomalyEventOut
import ru.petrovich.telemetry.data.api.AnomalyOut
import ru.petrovich.telemetry.data.api.PetrovichAiApi
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Проверку выполняет API детекции аномалий (Petrovich AI). Основной путь — [checkAll]: готовые аномалии
 * парка берутся из ленты событий (`GET /api/v1/anomaly-events`) за окно проверки; события несут имена
 * машин сами, сопоставления с парком приложения нет. Точечная детекция [check] (`POST /api/v1/anomalies/detect`)
 * оставлена для хранилища API, куда данные попадают через ingest/sync. При включённом переключателе
 * пороговые правила на устройстве не используются.
 */
class ApiAnomalyChecker(
    private val api: PetrovichAiApi,
    /** id машины в API для точечной детекции: у него свои числовые id, не id AutoGRAPH. */
    private val vehicleId: suspend (Vehicle) -> Int = { ApiVehicleResolver(api).resolve(it) },
) : VehicleChecker {

    override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> =
        try {
            api.detect(vehicleId(vehicle)).map { it.toAnomaly(vehicle) }
        } catch (e: HttpException) {
            throw httpError(e, vehicle.name)
        } catch (e: IOException) {
            // Сюда же падает понятный текст про незаданные логин/пароль из перехватчика авторизации.
            throw StandException(e.message ?: "API аномалий недоступен: проверьте сеть")
        }

    override suspend fun checkAll(from: LocalDateTime, to: LocalDateTime): List<Anomaly> =
        try {
            val all = mutableListOf<Anomaly>()
            var offset = 0
            while (true) {
                val page = api.events(
                    dateFrom = from.toLocalDate().toString(),
                    dateTo = to.toLocalDate().toString(),
                    limit = EVENTS_PAGE,
                    offset = offset,
                )
                all += page.items.map { it.toAnomaly() }
                offset += page.items.size
                if (offset >= page.total || page.items.isEmpty() || offset >= MAX_EVENTS) break
            }
            all
        } catch (e: HttpException) {
            throw httpError(e)
        } catch (e: IOException) {
            throw StandException(e.message ?: "API аномалий недоступен: проверьте сеть")
        }

    private fun httpError(e: HttpException, vehicle: String? = null): StandException = StandException(
        when (e.code()) {
            401 -> "API аномалий не принял логин/пароль — проверьте их в настройках"
            404, 422 -> if (vehicle != null) "$vehicle: машина не найдена в API аномалий" else "API аномалий ответил ошибкой ${e.code()}"
            else -> "API аномалий ответил ошибкой ${e.code()}"
        },
        e.code(),
    )

    companion object {
        private const val EVENTS_PAGE = 200
        /** Потолок на случай огромной ленты: сверка не должна завалить приложение десятками тысяч карточек. */
        const val MAX_EVENTS = 2000
    }
}

private val ApiTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** Заголовки событий для владельца: kind/title в API технические («fuel_drain»). */
private val EventTitles = mapOf(
    "fuel_drain" to "Слив топлива",
    "towing" to "Буксировка",
    "speed_without_movement" to "Скорость без движения",
    "telemetry_gap" to "Разрыв телеметрии",
    "data_gap" to "Пропуск данных",
    "data_gap_channel_unavailable" to "Нет данных с датчика",
    "data_gap_all_channels_unavailable" to "Машина не на связи",
    "data_gap_signal_unavailable" to "Нет сигнала",
    "data_gap_gps_unavailable" to "Нет GPS",
)

/**
 * Событие ленты API → модель приложения. id стабилен (`api|<kind>|<id>`), чтобы дедупликация ленты
 * работала при повторных проверках, а тип оставался доступен через [Anomaly.kind] (фильтр выключенных типов).
 * Статус разбора API переносится в решение: confirmed → подтверждено, dismissed → ложная тревога.
 */
fun AnomalyEventOut.toAnomaly(): Anomaly {
    val start = runCatching { OffsetDateTime.parse(startedAt) }.getOrNull()
    val startLocal = start?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime()
    val end = endedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
        ?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime()
    val title = EventTitles[kind] ?: title.ifBlank { kind }
    val details = buildList {
        volumeL?.let { add("объём %.1f л".format(it)) }
        if (volumeL == null) metric?.let { add("значение %.0f %s".format(it, metricUnit.orEmpty())) }
        end?.let { add("до ${it.toLocalTime().withSecond(0)}") }
    }.joinToString(", ")
    val producer = listOfNotNull(producerName, producerVersion).joinToString(" ").ifBlank { null }
    return Anomaly(
        id = "api|$kind|$id",
        vehicleId = vehicleId.toString(),
        vehicleName = vehicle,
        category = MetricCategory.classify(kind, title) ?: MetricCategory.ENGINE,
        parameterName = kind,
        parameterCaption = kind,
        eventTime = startLocal?.format(ApiTimeFormat) ?: startedAt,
        detectedAt = start?.toInstant()?.toEpochMilli() ?: System.currentTimeMillis(),
        severity = when (severity) {
            "high" -> Severity.CRITICAL
            "med" -> Severity.WARNING
            else -> Severity.INFO
        },
        title = title,
        description = buildString {
            append(title)
            append(", начало ")
            append((startLocal ?: startedAt).toString().replace('T', ' ').substringBeforeLast(':'))
            if (details.isNotBlank()) append(" ($details)")
            producer?.let { append(". Источник: $it") }
        },
        value = volumeL ?: metric,
        source = "API Петрович AI",
        resolution = when (status) {
            "confirmed" -> Resolution.CONFIRMED
            "dismissed" -> Resolution.FALSE_ALARM
            else -> null
        },
    )
}

/**
 * Аномалия точечной детекции API → модель приложения. id стабилен (`api|<kind>|<id>`), чтобы дедупликация
 * ленты работала при повторных проверках, а тип оставался доступен через [Anomaly.kind].
 */
fun AnomalyOut.toAnomaly(vehicle: Vehicle): Anomaly {
    val event = runCatching { LocalDateTime.ofInstant(Instant.parse(ts), ZoneId.systemDefault()) }.getOrNull()
    return Anomaly(
        id = "api|$kind|$id",
        vehicleId = vehicle.id,
        vehicleName = vehicle.label,
        category = MetricCategory.classify(kind, description) ?: MetricCategory.ENGINE,
        parameterName = kind,
        parameterCaption = kind,
        eventTime = event?.format(ApiTimeFormat) ?: ts,
        detectedAt = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis()),
        severity = when (severity) {
            "high" -> Severity.CRITICAL
            "med" -> Severity.WARNING
            else -> Severity.INFO
        },
        title = description.substringBefore('.').ifBlank { kind },
        description = description,
        source = "API Петрович AI",
    )
}
