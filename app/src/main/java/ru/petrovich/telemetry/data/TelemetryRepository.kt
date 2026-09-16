package ru.petrovich.telemetry.data

import java.time.LocalDateTime

interface TelemetryRepository {
    suspend fun vehicles(): List<Vehicle>
    suspend fun telemetry(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): VehicleTelemetry
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
}
