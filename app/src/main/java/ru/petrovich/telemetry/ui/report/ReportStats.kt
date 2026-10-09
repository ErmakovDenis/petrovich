package ru.petrovich.telemetry.ui.report

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.AutoGraphParameters
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.ui.home.WidgetType
import ru.petrovich.telemetry.ui.home.totalOf
import java.time.LocalDateTime

/**
 * 4 показателя «Доклада» (3.4): топливо, пробег, холостой ход, заправки за сутки — честно посчитаны
 * из телеметрии по всему парку, тем же способом, что и виджеты «Сводки» (см. ui/home/WidgetStats.kt).
 * Показатель, для которого ни у одной машины нет нужного датчика, в результате — null: цифру не придумываем
 * (см. «1. Общие правила» макета — «Только известные данные»).
 */
data class ReportStats(
    val fuelLiters: Double?,
    val mileageKm: Double?,
    val idleHours: Double?,
    val refuels: Int?,
    /** Весь парк по АвтоГРАФ — для «N из M машин без замечаний» (3.4), а не только те, где есть телеметрия за сутки. */
    val vehicleTotal: Int,
)

suspend fun loadReportStats(from: LocalDateTime, to: LocalDateTime): ReportStats = coroutineScope {
    val repo = ServiceLocator.telemetry
    val vehicles = repo.vehicles()
    val limit = Semaphore(4)
    val telemetry = vehicles.map { v -> async { limit.withPermit { runCatching { repo.telemetry(v, from, to) }.getOrNull() } } }.awaitAll().filterNotNull()

    var fuel = 0.0; var fuelSeen = false
    var mileage = 0.0; var mileageSeen = false
    var idleHours = 0.0; var idleSeen = false
    var refuels = 0; var refuelsSeen = false

    telemetry.forEach { t ->
        val fuelTable = t.tables[MetricCategory.FUEL]
        if (fuelTable?.columns?.any { it.parameter.name == AutoGraphParameters.FUEL_LEVEL } == true) {
            fuel += t.totalOf(WidgetType.FUEL); fuelSeen = true
            refuels += t.totalOf(WidgetType.REFUELS).toInt(); refuelsSeen = true
        }
        val motionTable = t.tables[MetricCategory.MOTION]
        if (motionTable?.columns?.any { it.parameter.name == AutoGraphParameters.SPEED } == true) {
            mileage += t.totalOf(WidgetType.MILEAGE); mileageSeen = true
            val powerTable = t.tables[MetricCategory.POWER]
            val hasIgnition = powerTable?.columns?.any {
                it.parameter.name == AutoGraphParameters.IGNITION || it.parameter.name == AutoGraphParameters.IGNITION_CAN
            } == true
            if (hasIgnition) { idleHours += t.totalOf(WidgetType.IDLE); idleSeen = true }
        }
    }

    ReportStats(
        fuelLiters = if (fuelSeen) fuel else null,
        mileageKm = if (mileageSeen) mileage else null,
        idleHours = if (idleSeen) idleHours else null,
        refuels = if (refuelsSeen) refuels else null,
        vehicleTotal = vehicles.size,
    )
}
