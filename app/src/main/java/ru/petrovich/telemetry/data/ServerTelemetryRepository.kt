package ru.petrovich.telemetry.data

import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDateTime

/**
 * Телеметрия со стенда fleet_service (`GET /v1/vehicles`, `GET /v1/telemetry`): стенд сам ходит в AutoGRAPH и
 * сворачивает данные тем же алгоритмом, что [TripTablesMapper]. Ответ — [VehicleTelemetry] в том же виде.
 */
class ServerTelemetryRepository(
    private val stand: StandClient,
    private val utcOffsetMinutes: () -> Int = StandRequests::utcOffsetMinutes,
) : TelemetryRepository {

    override suspend fun vehicles(): List<Vehicle> =
        StandRequests.decode(ListSerializer(Vehicle.serializer()), stand.get("v1/vehicles"))

    override suspend fun telemetry(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): VehicleTelemetry {
        val (f, t) = StandRequests.period(from, to)
        val query = mapOf(
            "vehicleId" to vehicle.id,
            "from" to StandRequests.format(f),
            "to" to StandRequests.format(t),
            "utcOffsetMinutes" to utcOffsetMinutes().toString(),
        )
        val text = stand.get("v1/telemetry", query, readTimeoutSeconds = StandRequests.LONG_TIMEOUT_SECONDS)
        // Машина — та, что выбрана в приложении: название и группа на экранах не меняются.
        return StandRequests.decode(VehicleTelemetry.serializer(), text).copy(vehicle = vehicle)
    }
}
