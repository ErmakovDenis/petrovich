package ru.petrovich.telemetry.ui.common

import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.data.MetricCategory
import java.time.Duration
import java.time.LocalDateTime

/** Скорость и зажигание рядом с моментом события — честно посчитано по соседним параметрам, если они вообще пишутся. */
data class NearbySignals(val movingKmh: Double?, val ignitionOn: Boolean?)

suspend fun loadNearbySignals(a: Anomaly, at: LocalDateTime): NearbySignals? {
    val repo = ServiceLocator.telemetry
    val vehicle = repo.vehicles().firstOrNull { it.id == a.vehicleId } ?: return null
    val t = repo.telemetry(vehicle, at.minusHours(1), at.plusHours(1))
    fun valueNear(category: MetricCategory, vararg names: String): Double? {
        val table = t.tables[category] ?: return null
        if (table.timestamps.isEmpty()) return null
        val idx = table.timestamps.indices.minByOrNull { Duration.between(table.timestamps[it], at).abs() } ?: return null
        return names.firstNotNullOfOrNull { name -> table.columns.firstOrNull { c -> c.parameter.name == name }?.values?.getOrNull(idx) }
    }
    val speed = valueNear(MetricCategory.MOTION, "Speed")
    val ignition = valueNear(MetricCategory.POWER, "DIgnition", "DIgnitionCAN")
    return NearbySignals(speed, ignition?.let { it >= 0.5 })
}
