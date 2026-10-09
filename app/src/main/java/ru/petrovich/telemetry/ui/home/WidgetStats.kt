package ru.petrovich.telemetry.ui.home

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.AutoGraphParameters
import ru.petrovich.telemetry.data.CategoryTable
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.ParameterColumn
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.ui.common.plural
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Машина считается «едущей», если скорость выше этого порога — иначе стоит (в т.ч. холостой ход). */
private const val IDLE_SPEED_KMH = 1.0

/** Разрыв в опросе шире этого — пропуск данных, а не честный интервал движения/простоя. */
private val MAX_GAP: Duration = Duration.ofHours(2)

/** Заправка — разовое значение датчика заметно больше нуля, не шум. */
private const val REFUEL_THRESHOLD_L = 1.0

/** Свежими считаем данные не старше этого — как в [ru.petrovich.telemetry.anomaly.classifyActivity]. */
private val FRESHNESS: Duration = Duration.ofMinutes(45)

private val RuLocale = Locale("ru")

/** Период «Подробной сводки виджета» (3.15). У «Без связи» выбора нет — там всегда «сейчас». */
enum class WidgetPeriod(val title: String) { DAY("День"), WEEK("Неделя"), MONTH("Месяц") }

data class WidgetPoint(val label: String, val value: Double)

data class WidgetSummary(
    val type: WidgetType,
    val total: Double,
    val unit: String,
    /** Изменение к прошлому периоду той же длины, % — null, если прошлый период пуст (не с чем сравнивать). */
    val changePercent: Double?,
    val points: List<WidgetPoint>,
    /** Машины — больше всего вклада за текущий период. */
    val leaders: List<WidgetLeader>,
)

/** Машина в списке лидеров/молчащих — хранит id отдельно от имени, иначе переход на карточку машины (которая ищет по id) не найдёт её. */
data class WidgetLeader(val vehicleId: String, val name: String, val value: Double)

/** «Без связи» — не про период, а про текущий список молчащих машин. */
data class OfflineEntry(val vehicleId: String, val name: String, val status: String)
data class OfflineSummary(val count: Int, val silent: List<OfflineEntry>)

/** Человеческое число для виджетов и доклада: «8 420» с разрядами через пробел, без дробной части. */
fun formatCount(v: Double): String = "%,d".format(RuLocale, Math.round(v)).replace(',', ' ')

fun unitFor(type: WidgetType): String = when (type) {
    WidgetType.FUEL, WidgetType.FUEL_TRUCKS -> "л"
    WidgetType.IDLE -> "ч"
    WidgetType.MILEAGE -> "км"
    WidgetType.REFUELS -> ""
    WidgetType.OFFLINE -> ""
}

private val truckHint = Regex("камаз|\\bмаз\\b|scania|volvo fh|actros|\\bman\\b|урал|газон|грузов", RegexOption.IGNORE_CASE)

/** В АвтоГРАФ нет отдельного поля «тип ТС» — определяем грузовую машину по марке в названии/группе. */
private fun Vehicle.isTruck(): Boolean = truckHint.containsMatchIn(name) || (group?.let { truckHint.containsMatchIn(it) } == true)

private fun fuelColumn(t: CategoryTable?): ParameterColumn? = t?.columns?.firstOrNull { it.parameter.name == AutoGraphParameters.FUEL_LEVEL }
private fun drainColumn(t: CategoryTable?): ParameterColumn? = t?.columns?.firstOrNull { it.parameter.name == AutoGraphParameters.FUEL_DRAIN_VOLUME }
private fun upColumn(t: CategoryTable?): ParameterColumn? = t?.columns?.firstOrNull { it.parameter.name == AutoGraphParameters.FUEL_UP_VOLUME }
private fun speedColumn(t: CategoryTable?): ParameterColumn? = t?.columns?.firstOrNull { it.parameter.name == AutoGraphParameters.SPEED }
private fun ignitionColumn(t: CategoryTable?): ParameterColumn? =
    t?.columns?.firstOrNull { it.parameter.name == AutoGraphParameters.IGNITION || it.parameter.name == AutoGraphParameters.IGNITION_CAN }

/** Расход между соседними точками уровня: падение минус слив плюс дозаправка — честный пересчёт по датчику. */
private fun fuelBetween(level0: Double?, level1: Double?, drained: Double?, refueled: Double?): Double? {
    if (level0 == null || level1 == null) return null
    return ((level0 - level1) - (drained ?: 0.0) + (refueled ?: 0.0)).coerceAtLeast(0.0)
}

private fun bucketOf(time: LocalDateTime, start: LocalDateTime, unit: ChronoUnit): Int =
    if (unit == ChronoUnit.HOURS) Duration.between(start, time).toHours().toInt() else Duration.between(start, time).toDays().toInt()

/** Раскладывает одну машину по бакетам (часы/дни) для одного показателя — без повторных запросов к серверу. */
private fun accumulate(type: WidgetType, t: VehicleTelemetry, bucketStart: LocalDateTime, unit: ChronoUnit, count: Int): DoubleArray {
    val result = DoubleArray(count)
    fun put(time: LocalDateTime, value: Double) {
        val b = bucketOf(time, bucketStart, unit)
        if (b in 0 until count) result[b] += value
    }
    when (type) {
        WidgetType.MILEAGE -> {
            val table = t.tables[MetricCategory.MOTION] ?: return result
            val speed = speedColumn(table) ?: return result
            for (i in 0 until table.timestamps.size - 1) {
                val v0 = speed.values.getOrNull(i) ?: continue
                val v1 = speed.values.getOrNull(i + 1) ?: continue
                val secs = Duration.between(table.timestamps[i], table.timestamps[i + 1]).seconds
                if (secs !in 1..MAX_GAP.seconds) continue
                put(table.timestamps[i], (v0 + v1) / 2.0 * secs / 3600.0)
            }
        }
        WidgetType.IDLE -> {
            val motion = t.tables[MetricCategory.MOTION] ?: return result
            val speed = speedColumn(motion) ?: return result
            val ignition = ignitionColumn(t.tables[MetricCategory.POWER]) ?: return result
            for (i in 0 until motion.timestamps.size - 1) {
                val v = speed.values.getOrNull(i) ?: continue
                val ign = ignition.values.getOrNull(i) ?: continue
                val secs = Duration.between(motion.timestamps[i], motion.timestamps[i + 1]).seconds
                if (secs !in 1..MAX_GAP.seconds) continue
                if (ign >= 0.5 && v < IDLE_SPEED_KMH) put(motion.timestamps[i], secs / 3600.0)
            }
        }
        WidgetType.FUEL, WidgetType.FUEL_TRUCKS -> {
            val table = t.tables[MetricCategory.FUEL] ?: return result
            val level = fuelColumn(table) ?: return result
            val drain = drainColumn(table); val up = upColumn(table)
            for (i in 1 until table.timestamps.size) {
                val v = fuelBetween(level.values.getOrNull(i - 1), level.values.getOrNull(i), drain?.values?.getOrNull(i), up?.values?.getOrNull(i)) ?: continue
                put(table.timestamps[i - 1], v)
            }
        }
        WidgetType.REFUELS -> {
            val table = t.tables[MetricCategory.FUEL] ?: return result
            val up = upColumn(table) ?: return result
            table.timestamps.forEachIndexed { i, time -> if ((up.values.getOrNull(i) ?: 0.0) > REFUEL_THRESHOLD_L) put(time, 1.0) }
        }
        WidgetType.OFFLINE -> Unit // отдельная логика, см. offline()
    }
    return result
}

/**
 * Сумма показателя по одной машине за весь её диапазон телеметрии разом (один «бакет») — та же
 * честная арифметика, что и у виджетов «Сводки». Используется докладом (3.4) для 4 показателей за сутки.
 */
fun VehicleTelemetry.totalOf(type: WidgetType): Double = accumulate(type, this, from, ChronoUnit.DAYS, 1).sum()

private fun bucketLabel(period: WidgetPeriod, start: LocalDateTime, unit: ChronoUnit, i: Int): String {
    val t = if (unit == ChronoUnit.HOURS) start.plusHours(i.toLong()) else start.plusDays(i.toLong())
    return when (period) {
        WidgetPeriod.DAY -> t.format(DateTimeFormatter.ofPattern("HH", RuLocale))
        WidgetPeriod.WEEK -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, RuLocale).replaceFirstChar { c -> c.uppercase() }
        WidgetPeriod.MONTH -> t.format(DateTimeFormatter.ofPattern("d.MM", RuLocale))
    }
}

private fun buildSummary(
    type: WidgetType,
    data: List<Pair<Vehicle, VehicleTelemetry?>>,
    bucketStart: LocalDateTime,
    unit: ChronoUnit,
    bucketCount: Int,
    label: (Int) -> String,
): WidgetSummary {
    val fleet = DoubleArray(bucketCount * 2)
    // Ключ — id машины (не имя): имена могут повторяться, а id нужен для перехода на карточку машины.
    val perVehicle = mutableMapOf<String, Pair<String, Double>>()
    data.forEach { (v, t) ->
        if (t == null) return@forEach
        val series = accumulate(type, t, bucketStart, unit, bucketCount * 2)
        series.forEachIndexed { i, value -> fleet[i] += value }
        val current = series.copyOfRange(bucketCount, bucketCount * 2).sum()
        if (current > 0.0) perVehicle[v.id] = v.name to ((perVehicle[v.id]?.second ?: 0.0) + current)
    }
    val currentTotal = fleet.copyOfRange(bucketCount, bucketCount * 2).sum()
    val prevTotal = fleet.copyOfRange(0, bucketCount).sum()
    val change = if (prevTotal > 0.5) (currentTotal - prevTotal) / prevTotal * 100.0 else null
    val points = (bucketCount until bucketCount * 2).map { i -> WidgetPoint(label(i - bucketCount), fleet[i]) }
    val leaders = perVehicle.entries.sortedByDescending { it.value.second }.take(3)
        .map { (id, nameValue) -> WidgetLeader(id, nameValue.first, nameValue.second) }
    return WidgetSummary(type, currentTotal, unitFor(type), change, points, leaders)
}

/** Независимая загрузка для «Подробной сводки» (3.15): свой запрос под выбранный период. */
suspend fun loadWidgetSummary(type: WidgetType, period: WidgetPeriod): WidgetSummary = coroutineScope {
    val repo = ServiceLocator.telemetry
    val vehicles = repo.vehicles().let { if (type == WidgetType.FUEL_TRUCKS) it.filter(Vehicle::isTruck) else it }
    val unit = if (period == WidgetPeriod.DAY) ChronoUnit.HOURS else ChronoUnit.DAYS
    val bucketCount = when (period) { WidgetPeriod.DAY -> 24; WidgetPeriod.WEEK -> 7; WidgetPeriod.MONTH -> 30 }
    val now = LocalDateTime.now()
    val bucketStart = if (unit == ChronoUnit.HOURS) {
        now.minusHours((bucketCount * 2 - 1).toLong()).withMinute(0).withSecond(0).withNano(0)
    } else {
        now.toLocalDate().minusDays((bucketCount * 2 - 1).toLong()).atStartOfDay()
    }
    val limit = Semaphore(4)
    val data = vehicles.map { v -> async { limit.withPermit { v to runCatching { repo.telemetry(v, bucketStart, now) }.getOrNull() } } }.awaitAll()
    buildSummary(type, data, bucketStart, unit, bucketCount) { i -> bucketLabel(period, bucketStart, unit, i) }
}

/** Общие для «Главной» 14 суток телеметрии по всему парку — одна загрузка на все виджеты сразу (без повторных запросов на каждую карточку). */
data class FleetWeek(val data: List<Pair<Vehicle, VehicleTelemetry?>>, val from: LocalDateTime, val to: LocalDateTime)

suspend fun loadFleetWeek(): FleetWeek = coroutineScope {
    val repo = ServiceLocator.telemetry
    val vehicles = repo.vehicles()
    val to = LocalDateTime.now()
    val from = to.toLocalDate().minusDays(13).atStartOfDay()
    val limit = Semaphore(4)
    val data = vehicles.map { v -> async { limit.withPermit { v to runCatching { repo.telemetry(v, from, to) }.getOrNull() } } }.awaitAll()
    FleetWeek(data, from, to)
}

/** Сводка виджета за неделю из уже загруженных [FleetWeek] — без обращений к серверу. */
fun FleetWeek.summary(type: WidgetType): WidgetSummary {
    val filtered = if (type == WidgetType.FUEL_TRUCKS) data.filter { it.first.isTruck() } else data
    return buildSummary(type, filtered, from, ChronoUnit.DAYS, 7) { i ->
        from.plusDays((7 + i).toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, RuLocale).replaceFirstChar { c -> c.uppercase() }
    }
}

/** «Без связи»: честно смотрим, когда от машины в последний раз была хоть одна точка данных в загруженном окне. */
fun FleetWeek.offline(now: LocalDateTime = LocalDateTime.now()): OfflineSummary {
    val silent = data.mapNotNull { (v, t) ->
        val lastSeen = t?.tables?.values?.flatMap { table ->
            table.timestamps.filterIndexed { i, _ -> table.columns.any { c -> c.values.getOrNull(i) != null } }
        }?.maxOrNull()
        val gap = lastSeen?.let { Duration.between(it, now) }
        if (gap != null && gap <= FRESHNESS) null else Triple(v.id, v.name, gap)
    }
    val sorted = silent.sortedByDescending { it.third ?: Duration.ofDays(999) }
    return OfflineSummary(sorted.size, sorted.take(3).map { (id, name, gap) -> OfflineEntry(id, name, silentLabel(gap)) })
}

private fun silentLabel(gap: Duration?): String {
    if (gap == null) return "нет данных за 2 недели"
    val hours = gap.toHours()
    return if (hours < 1) "молчит ${gap.toMinutes()} мин" else "молчит $hours ${plural(hours.toInt(), "час", "часа", "часов")}"
}
