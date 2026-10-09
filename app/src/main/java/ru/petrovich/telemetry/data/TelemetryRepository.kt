package ru.petrovich.telemetry.data

import java.time.LocalDate
import java.time.LocalDateTime

interface TelemetryRepository {
    suspend fun vehicles(): List<Vehicle>
    suspend fun telemetry(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): VehicleTelemetry

    /**
     * Маршрут и геозона машины за день — для схематичной карты (3.14). По умолчанию — null:
     * источник не знает местоположение. Переопределяет только [DemoTelemetryRepository];
     * настоящий АвтоГРАФ координат не отдаёт (см. комментарий у [VehicleRoute]).
     */
    suspend fun route(vehicle: Vehicle, day: LocalDate): VehicleRoute? = null
}

/**
 * Выбирает источник данных в момент каждого вызова по текущим настройкам: демо, стенд
 * ([ru.petrovich.telemetry.data.settings.AppSettings.telemetryOnServer]) или AutoGRAPH напрямую, как раньше.
 */
class SwitchingTelemetryRepository(
    private val settings: suspend () -> ru.petrovich.telemetry.data.settings.AppSettings,
    private val demo: TelemetryRepository,
    private val remote: TelemetryRepository,
    private val server: TelemetryRepository,
) : TelemetryRepository {
    private suspend fun current(): TelemetryRepository {
        val s = settings()
        return when {
            s.demoMode -> demo
            s.telemetryOnServer -> server
            else -> remote
        }
    }

    override suspend fun vehicles() = current().vehicles()

    override suspend fun telemetry(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime) =
        current().telemetry(vehicle, from, to)

    override suspend fun route(vehicle: Vehicle, day: LocalDate) = current().route(vehicle, day)
}
