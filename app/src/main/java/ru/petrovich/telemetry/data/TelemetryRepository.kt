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

/** Выбирает источник данных (демо или реальный API) в момент каждого вызова по текущим настройкам. */
class SwitchingTelemetryRepository(
    private val settings: ru.petrovich.telemetry.data.settings.SettingsRepository,
    private val demo: TelemetryRepository,
    private val remote: TelemetryRepository,
) : TelemetryRepository {
    private suspend fun current() = if (settings.current().demoMode) demo else remote

    override suspend fun vehicles() = current().vehicles()

    override suspend fun telemetry(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime) =
        current().telemetry(vehicle, from, to)

    override suspend fun route(vehicle: Vehicle, day: LocalDate) = current().route(vehicle, day)
}
