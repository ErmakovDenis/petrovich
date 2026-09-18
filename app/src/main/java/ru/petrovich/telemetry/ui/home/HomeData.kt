package ru.petrovich.telemetry.ui.home

import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.common.plural
import ru.petrovich.telemetry.ui.common.time
import java.time.LocalDate

enum class BriefTone { RED, YELLOW, GREEN }

/** Порядок в списках: сначала ждущие решения, затем срочные, затем свежие. */
val AnomalyOrder: Comparator<Anomaly> =
    compareByDescending<Anomaly> { it.isNew }
        .thenByDescending { it.severity }
        .thenByDescending { it.eventTime }

/** Цифры для сводки и виджетов — всё считается из сохранённых аномалий. */
class HomeData(anomalies: List<Anomaly>, val vehicleCount: Int?, today: LocalDate = LocalDate.now()) {
    val pending: List<Anomaly> = anomalies.filter { it.isNew }.sortedWith(AnomalyOrder)
    val urgent: List<Anomaly> = pending.filter { it.severity == Severity.CRITICAL }
    val pendingCount = pending.size
    val urgentCount = urgent.size

    /** Аномалии за 7 дней (включая сегодня), без ложных тревог. */
    private val week: List<Anomaly> = anomalies.filter { a ->
        a.resolution != Resolution.FALSE_ALARM && a.time?.toLocalDate()?.let { !it.isBefore(today.minusDays(6)) && !it.isAfter(today) } == true
    }
    val weekTotal = week.size
    val perDay: List<Int> = (6 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        week.count { it.time?.toLocalDate() == day }
    }
    val firstDay: LocalDate = today.minusDays(6)
    val today: LocalDate = today

    val byCategory: List<Pair<MetricCategory, Int>> =
        MetricCategory.entries.map { c -> c to week.count { it.category == c } }
    val topVehicles: List<Pair<String, Int>> =
        week.groupingBy { it.vehicleName }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key to it.value }
    val fuelWeek: Int = week.count { it.category == MetricCategory.FUEL }
    val vehiclesWithIssues: Int = pending.map { it.vehicleId }.distinct().size

    val tone: BriefTone = when {
        pendingCount == 0 -> BriefTone.GREEN
        urgentCount > 0 -> BriefTone.RED
        else -> BriefTone.YELLOW
    }

    val verdict: String = when (tone) {
        BriefTone.GREEN -> "Всё в порядке"
        BriefTone.RED -> "$urgentCount ${plural(urgentCount, "срочный случай", "срочных случая", "срочных случаев")}"
        BriefTone.YELLOW -> "Срочного нет, $pendingCount ${plural(pendingCount, "замечание", "замечания", "замечаний")}"
    }

    /** Строки доклада: (машина, что случилось). */
    val topLines: List<Pair<String, String>> = pending.take(3).map { it.vehicleName to it.title.replaceFirstChar { c -> c.lowercase() } }
    val moreCount = (pendingCount - 3).coerceAtLeast(0)

    /** Текст для озвучки. */
    fun speech(): String = buildString {
        append("Доклад Петровича. ").append(verdict).append(". ")
        if (tone == BriefTone.GREEN) {
            append(if (vehicleCount != null && vehicleCount > 0) "Все $vehicleCount ${plural(vehicleCount, "машина", "машины", "машин")} без замечаний." else "Замечаний нет.")
        } else {
            topLines.forEach { (v, t) -> append("$v: $t. ") }
            if (moreCount > 0) append("И ещё $moreCount — в отчёте.")
        }
    }
}
