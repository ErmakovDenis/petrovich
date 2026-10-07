package ru.petrovich.telemetry.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import ru.petrovich.telemetry.data.api.ApiFactory
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Телеметрия со стенда fleet_service (`GET /v1/vehicles`, `GET /v1/telemetry`): стенд сам ходит в AutoGRAPH и
 * сворачивает данные тем же алгоритмом, что [TripTablesMapper]. Ответ — [VehicleTelemetry] в том же виде.
 */
class ServerTelemetryRepository(
    private val stand: StandClient,
    private val utcOffsetMinutes: () -> Int = { ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60 },
) : TelemetryRepository {

    override suspend fun vehicles(): List<Vehicle> =
        decode(ListSerializer(Vehicle.serializer()), stand.get("v1/vehicles"))

    override suspend fun telemetry(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): VehicleTelemetry {
        val (f, t) = period(from, to)
        val query = mapOf(
            "vehicleId" to vehicle.id,
            "from" to f.format(QUERY_TIME),
            "to" to t.format(QUERY_TIME),
            "utcOffsetMinutes" to utcOffsetMinutes().toString(),
        )
        val text = stand.get("v1/telemetry", query, readTimeoutSeconds = TELEMETRY_TIMEOUT_SECONDS)
        // Машина — та, что выбрана в приложении: название и группа на экранах не меняются.
        return decode(VehicleTelemetry.serializer(), text).copy(vehicle = vehicle)
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T =
        try {
            ApiFactory.json.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) {
            // SerializationException — наследник IllegalArgumentException.
            throw StandException("Стенд вернул данные в неожиданном формате")
        } catch (e: DateTimeException) {
            // Время не в виде местного без пояса (LocalDateTimeSerializer).
            throw StandException("Стенд вернул данные в неожиданном формате")
        }

    companion object {
        private val QUERY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

        /** Стенд отвечает, когда загрузит весь период: 7 дней — 28 частей по 6 ч, каждая — запрос к AutoGRAPH. */
        private const val TELEMETRY_TIMEOUT_SECONDS = 300L

        /**
         * Период запроса без секунд: одинаковые периоды попадают в кэш стенда, а сетка интервалов не меняется (начало
         * и так усекается до минуты, AutoGRAPH принимает время с точностью до минуты). Если усечение меняет длину
         * интервала ([TripTablesMapper.bucketFor]: начало и конец с разными секундами на границе 6 ч / 24 ч / 3 дней),
         * период уходит как есть.
         */
        internal fun period(from: LocalDateTime, to: LocalDateTime): Pair<LocalDateTime, LocalDateTime> {
            val f = from.truncatedTo(ChronoUnit.MINUTES)
            val t = to.truncatedTo(ChronoUnit.MINUTES)
            val same = TripTablesMapper.bucketFor(Duration.between(f, t)) == TripTablesMapper.bucketFor(Duration.between(from, to))
            return if (same) f to t else from to to
        }
    }
}
