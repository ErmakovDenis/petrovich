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
import ru.petrovich.telemetry.ui.common.TimeRange
import ru.petrovich.telemetry.ui.common.plural
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** Машина считается «едущей», если скорость выше этого порога — иначе стоит (в т.ч. холостой ход). */
private const val IDLE_SPEED_KMH = 1.0

/** Разрыв в опросе шире этого — пропуск данных, а не честный интервал движения/простоя. */
private val MAX_GAP: Duration = Duration.ofHours(2)

/** Минимальный подъём уровня, с которого начинаем заправку (не шум ДУТ). */
private const val REFUEL_THRESHOLD_L = 1.0

/**
 * Пауза между соседними подъёмами внутри одной заправки.
 * ДУТ часто пишет заливку несколькими точками — без склейки «1 заправка в AG» превращалась в 2–4 у нас.
 */
private val REFUEL_GAP: Duration = Duration.ofMinutes(30)

/** Скачок уровня между соседними точками больше бака — глюк ДУТ, не расход. */
private const val MAX_FUEL_STEP_L = 120.0

/**
 * Дребезг ДУТ: просадка и возврат почти на то же значение.
 * На «дне» (сетка 1–2 мин) такие качели накручивают расход больше, чем за «неделю» (15 мин).
 */
private val FUEL_NOISE_WINDOW: Duration = Duration.ofMinutes(45)

/** Доля возврата уровня, после которой просадку считаем шумом, а не расходом. */
private const val FUEL_NOISE_RECOVER = 0.7

/** Суточный расход машины выше этого — скорее мусор в widget_daily_stats, чем реал. */
private const val ABSURD_VEHICLE_DAY_FUEL_L = 400.0

/**
 * Парк проехал, а расход в суточных статах ≈ 0 — стенд ещё со старой/пустой формулой.
 * Таким данным для топлива нельзя верить (иначе неделя показывает «0 л»).
 */
private const val HOLLOW_FUEL_MIN_KM = 30.0

/** Свежими считаем данные не старше этого — как в [ru.petrovich.telemetry.anomaly.classifyActivity]. */
private val FRESHNESS: Duration = Duration.ofMinutes(45)

private val RuLocale = Locale("ru")

/** Период «Подробной сводки виджета» (3.15). У «Без связи» выбора нет — там всегда «сейчас». Свой период — отдельно через [TimeRange]. */
enum class WidgetPeriod(val title: String) {
    DAY("День"), WEEK("Неделя"), MONTH("Месяц"), QUARTER("3 месяца");

    /** Число суток (или часов для DAY) в текущем окне периода. */
    val bucketCount: Int get() = when (this) {
        DAY -> 24
        WEEK -> 7
        MONTH -> 30
        QUARTER -> 90
    }

    /**
     * Текущее окно периода: ровно [bucketCount] календарных суток до «сейчас»
     * (как takeLast(bucketCount) в сводке отчётов), не Duration.lastDays —
     * иначе lastDays(7) даёт 8 дат и карточка расходится с отчётом.
     */
    fun toRange(now: LocalDateTime = LocalDateTime.now()): TimeRange = when (this) {
        DAY -> TimeRange.lastHours(24, now)
        WEEK, MONTH, QUARTER -> TimeRange(
            now.toLocalDate().minusDays(bucketCount.toLong() - 1).atStartOfDay(),
            now,
        )
    }
}

data class WidgetPoint(val label: String, val value: Double)

data class WidgetSummary(
    val type: WidgetType,
    val total: Double,
    val unit: String,
    /** Изменение к прошлому периоду той же длины, % — null, если прошлый период пуст (не с чем сравнивать). */
    val changePercent: Double?,
    val points: List<WidgetPoint>,
    /**
     * Все машины с ненулевым вкладом за текущий период, по убыванию.
     * На главной берём топ‑3 / первого; в детали сводки — полный список.
     */
    val leaders: List<WidgetLeader>,
)

/**
 * Машина в списке лидеров/молчащих — id для перехода на карточку.
 * [name] — гос. номер (или имя), [subtitle] — модель без номера.
 */
data class WidgetLeader(
    val vehicleId: String,
    val name: String,
    val value: Double,
    val subtitle: String? = null,
)

/** Подставить plate из /v1/vehicles: в widget_daily_stats на стенде номера нет. */
private suspend fun enrichWidgetVehicles(
    items: List<ru.petrovich.telemetry.data.DailyVehicleStats>,
): List<ru.petrovich.telemetry.data.DailyVehicleStats> {
    if (items.isEmpty()) return items
    val byId = runCatching { ServiceLocator.telemetry.vehicles() }.getOrNull()?.associateBy { it.id }.orEmpty()
    return items.map { item ->
        val known = byId[item.vehicle.id]
        val merged = if (known != null) {
            known.copy(
                // Имя со стенда суточных статов может быть актуальнее — но plate берём из справочника машин.
                name = known.name.ifBlank { item.vehicle.name },
                group = known.group ?: item.vehicle.group,
            ).withPlateResolved()
        } else {
            item.vehicle.withPlateResolved()
        }
        if (merged == item.vehicle) item else item.copy(vehicle = merged)
    }
}

private fun leaderOf(vehicle: Vehicle, id: String, value: Double) =
    WidgetLeader(id, vehicle.widgetListTitle(), value, vehicle.widgetListSubtitle())

/** Машина с расходом л/100 км заметно выше медианы парка за период. */
data class OverconsumerRow(
    val vehicleId: String,
    val name: String,
    val ratePer100Km: Double,
    /** На сколько % выше медианы парка. */
    val excessPercent: Double,
)

data class OverconsumersReport(
    /** Медиана л/100 км по парку (машины с пробегом ≥ порога); null — считать не из чего. */
    val medianRate: Double?,
    val rows: List<OverconsumerRow>,
)

/** «Без связи» — не про период, а про текущий список молчащих машин. */
data class OfflineEntry(val vehicleId: String, val name: String, val status: String)
data class OfflineSummary(val count: Int, val silent: List<OfflineEntry>)

/** Человеческое число для виджетов и доклада: «8 420» с разрядами через пробел, без дробной части. */
fun formatCount(v: Double): String = "%,d".format(RuLocale, Math.round(v)).replace(',', ' ')

fun unitFor(type: WidgetType): String = when (type) {
    WidgetType.FUEL, WidgetType.FUEL_TRUCKS, WidgetType.OVERCONSUMERS -> "л"
    WidgetType.IDLE -> "ч"
    WidgetType.MILEAGE -> "км"
    WidgetType.REFUELS -> ""
    WidgetType.OFFLINE, WidgetType.ALARMS -> ""
}

private val truckHint = Regex("камаз|\\bмаз\\b|scania|volvo fh|actros|\\bman\\b|урал|газон|грузов", RegexOption.IGNORE_CASE)

/** В АвтоГРАФ нет отдельного поля «тип ТС» — определяем грузовую машину по марке в названии/группе. */
private fun Vehicle.isTruck(): Boolean = truckHint.containsMatchIn(name) || (group?.let { truckHint.containsMatchIn(it) } == true)

private fun fuelColumn(t: CategoryTable?): ParameterColumn? =
    t?.columns?.firstOrNull { it.parameter.name in AutoGraphParameters.FUEL_LEVEL_NAMES }
private fun drainColumn(t: CategoryTable?): ParameterColumn? = t?.columns?.firstOrNull { it.parameter.name == AutoGraphParameters.FUEL_DRAIN_VOLUME }
private fun upColumn(t: CategoryTable?): ParameterColumn? = t?.columns?.firstOrNull { it.parameter.name == AutoGraphParameters.FUEL_UP_VOLUME }
private fun speedColumn(t: CategoryTable?): ParameterColumn? = t?.columns?.firstOrNull { it.parameter.name == AutoGraphParameters.SPEED }
private fun ignitionColumn(t: CategoryTable?): ParameterColumn? =
    t?.columns?.firstOrNull { it.parameter.name == AutoGraphParameters.IGNITION || it.parameter.name == AutoGraphParameters.IGNITION_CAN }

/**
 * Расход между соседними точками — только падение уровня.
 * UpVol/DnVol с Aggregation.MAX залипают: +Up раздувает сутки, −Dn обнуляет расход.
 * Скачок > [MAX_FUEL_STEP_L] отбрасываем (обрыв датчика / пустой бак → полный).
 */
private fun fuelBetween(level0: Double?, level1: Double?, drained: Double?, refueled: Double?): Double? {
    if (level0 == null || level1 == null) return null
    val drop = level0 - level1
    if (drop <= 0.0) return 0.0
    if (drop > MAX_FUEL_STEP_L) return null
    return drop
}

/**
 * Просадка — дребезг, если за [FUEL_NOISE_WINDOW] уровень вернулся ≥ [FUEL_NOISE_RECOVER] доли падения.
 * Иначе «день» на частой сетке даёт расход больше «недели».
 */
private fun isFuelNoiseDrop(
    timestamps: List<LocalDateTime>,
    levels: List<Double?>,
    dropIndex: Int,
    before: Double,
    after: Double,
): Boolean {
    val drop = before - after
    if (drop <= 0.0) return false
    val t0 = timestamps.getOrNull(dropIndex) ?: return false
    val deadline = t0.plus(FUEL_NOISE_WINDOW)
    val need = after + drop * FUEL_NOISE_RECOVER
    for (j in (dropIndex + 1) until timestamps.size) {
        val t = timestamps[j]
        if (t.isAfter(deadline)) break
        val v = levels.getOrNull(j) ?: continue
        if (v >= need) return true
    }
    return false
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
        WidgetType.FUEL, WidgetType.FUEL_TRUCKS, WidgetType.OVERCONSUMERS -> {
            val table = t.tables[MetricCategory.FUEL] ?: return result
            val level = fuelColumn(table)
            val drain = drainColumn(table); val up = upColumn(table)
            var fromLevel = 0.0
            if (level != null) {
                for (i in 1 until table.timestamps.size) {
                    val prev = level.values.getOrNull(i - 1)
                    val cur = level.values.getOrNull(i)
                    val v = fuelBetween(prev, cur, drain?.values?.getOrNull(i), up?.values?.getOrNull(i)) ?: continue
                    if (v <= 0.0) continue
                    val gap = Duration.between(table.timestamps[i - 1], table.timestamps[i]).seconds
                    if (gap !in 1..MAX_GAP.seconds) continue
                    if (prev != null && cur != null &&
                        isFuelNoiseDrop(table.timestamps, level.values, i, prev, cur)
                    ) {
                        continue
                    }
                    fromLevel += v
                    put(table.timestamps[i - 1], v)
                }
            }
            // Нет ДУТ / уровень плоский — прирост накопленного ConsumptionCAN (как на стенде).
            if (fromLevel <= ACTIVITY_EPS) {
                result.fill(0.0)
                val can = table.columns.firstOrNull { it.parameter.name == "ConsumptionCAN" } ?: return result
                var prev: Double? = null
                for (i in table.timestamps.indices) {
                    val v = can.values.getOrNull(i) ?: continue
                    if (prev != null && v > prev) {
                        val step = v - prev
                        if (step <= MAX_FUEL_STEP_L) put(table.timestamps[i], step)
                    }
                    prev = v
                }
            }
        }
        WidgetType.REFUELS -> {
            val table = t.tables[MetricCategory.FUEL] ?: return result
            val level = fuelColumn(table) ?: return result
            var i = 1
            while (i < table.timestamps.size) {
                val prev = level.values.getOrNull(i - 1)
                val cur = level.values.getOrNull(i)
                if (prev == null || cur == null || cur - prev <= REFUEL_THRESHOLD_L) {
                    i++
                    continue
                }
                val startLevel: Double = prev
                var peak: Double = cur
                var j = i
                while (j + 1 < table.timestamps.size) {
                    val gapSec = Duration.between(table.timestamps[j], table.timestamps[j + 1]).seconds
                    if (gapSec !in 1..REFUEL_GAP.seconds) break
                    val a: Double = level.values.getOrNull(j) ?: break
                    val b: Double = level.values.getOrNull(j + 1) ?: break
                    if (b < a - REFUEL_THRESHOLD_L) break
                    if (b > peak) peak = b
                    j++
                }
                if (peak - startLevel > REFUEL_THRESHOLD_L) put(table.timestamps[i - 1], 1.0)
                i = j + 1
            }
        }
        WidgetType.OFFLINE, WidgetType.ALARMS -> Unit // см. offline() / AlarmsWidget на Главной
    }
    return result
}

/**
 * Сумма показателя по одной машине за весь её диапазон телеметрии разом (один «бакет») — та же
 * честная арифметика, что и у виджетов «Сводки». Используется докладом (3.4) для 4 показателей за сутки.
 */
fun VehicleTelemetry.totalOf(type: WidgetType): Double = accumulate(type, this, from, ChronoUnit.DAYS, 1).sum()

private fun bucketLabel(period: WidgetPeriod?, start: LocalDateTime, unit: ChronoUnit, i: Int): String {
    val t = if (unit == ChronoUnit.HOURS) start.plusHours(i.toLong()) else start.plusDays(i.toLong())
    return when (period) {
        WidgetPeriod.DAY -> t.format(DateTimeFormatter.ofPattern("HH", RuLocale))
        WidgetPeriod.WEEK -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, RuLocale).replaceFirstChar { c -> c.uppercase() }
        WidgetPeriod.MONTH, WidgetPeriod.QUARTER -> t.format(DateTimeFormatter.ofPattern("d.MM", RuLocale))
        null -> if (unit == ChronoUnit.HOURS) {
            t.format(DateTimeFormatter.ofPattern("HH:mm", RuLocale))
        } else {
            t.format(DateTimeFormatter.ofPattern("d.MM", RuLocale))
        }
    }
}

/** Разбиение своего периода: часы, если короче 36 ч, иначе дни. */
private fun customBuckets(range: TimeRange): Pair<ChronoUnit, Int> {
    val span = range.span
    return if (span.toHours() < 36) {
        ChronoUnit.HOURS to max(1, ceil(span.toMinutes() / 60.0).toInt())
    } else {
        ChronoUnit.DAYS to max(1, ceil(span.toHours() / 24.0).toInt())
    }
}

/** Свыше ~2 месяцев столбцы по дням нечитаемы — группируем по неделям. */
private const val CHART_WEEKLY_FROM_DAYS = 61

/**
 * Доля активных дней прошлого окна относительно текущего (и минимум дней), иначе % врёт:
 * пустые сутки и обрезанная история не должны тянуть «к прошлому периоду».
 */
private const val PREVIOUS_COVERAGE = 0.8
private const val MIN_ACTIVE_DAYS = 3
private const val ACTIVITY_EPS = 0.01

private fun chartGroupDays(dayCount: Int): Int = if (dayCount >= CHART_WEEKLY_FROM_DAYS) 7 else 1

/** День с хоть какой-то телеметрией парка (не «пустая» строка нулей). */
private fun ru.petrovich.telemetry.data.DailyVehicleStats.hasActivity(): Boolean =
    fuel > ACTIVITY_EPS || mileage > ACTIVITY_EPS || idle > ACTIVITY_EPS || refuels > 0

/**
 * Суммы метрики по календарным дням, только где была активность.
 * Пустые дни в среднее и в % не входят.
 */
private fun activeDayTotals(
    items: List<ru.petrovich.telemetry.data.DailyVehicleStats>,
    value: (ru.petrovich.telemetry.data.DailyVehicleStats) -> Double,
): List<Double> = items.groupBy { it.day }.mapNotNull { (_, rows) ->
    if (rows.none { it.hasActivity() }) null else rows.sumOf(value)
}

/**
 * % по среднему за активный день: (avgNow − avgPrev) / avgPrev.
 * null — мало непустых дней в прошлом или не с чем сравнивать.
 */
private fun changePercentByActiveDays(currentActive: List<Double>, previousActive: List<Double>): Double? {
    if (currentActive.isEmpty() || previousActive.isEmpty()) return null
    val minPrev = max(MIN_ACTIVE_DAYS, kotlin.math.ceil(currentActive.size * PREVIOUS_COVERAGE).toInt())
    if (previousActive.size < minPrev) return null
    val prevAvg = previousActive.average()
    val curAvg = currentActive.average()
    if (prevAvg <= ACTIVITY_EPS) return null
    return (curAvg - prevAvg) / prevAvg * 100.0
}

/** % по суммам периодов — для дневного режима (часы): среднее по часам врёт при разном числе активных часов. */
private fun changePercentByTotals(currentTotal: Double, previousTotal: Double): Double? {
    if (previousTotal <= ACTIVITY_EPS) return null
    return (currentTotal - previousTotal) / previousTotal * 100.0
}

/** Склеивает дневные точки в группы (для 3 месяцев / длинного своего периода). */
private fun groupChartPoints(points: List<WidgetPoint>, groupDays: Int): List<WidgetPoint> {
    if (groupDays <= 1 || points.size <= groupDays) return points
    return points.chunked(groupDays).map { chunk ->
        val label = if (chunk.size == 1) chunk.first().label else "${chunk.first().label}–${chunk.last().label}"
        WidgetPoint(label, chunk.sumOf { it.value })
    }
}

private fun chartPoints(unit: ChronoUnit, dayOrHourCount: Int, points: List<WidgetPoint>): List<WidgetPoint> =
    if (unit == ChronoUnit.DAYS) groupChartPoints(points, chartGroupDays(dayOrHourCount)) else points

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
    val perVehicle = mutableMapOf<String, Pair<Vehicle, Double>>()
    data.forEach { (v, t) ->
        if (t == null) return@forEach
        val series = accumulate(type, t, bucketStart, unit, bucketCount * 2)
        series.forEachIndexed { i, value -> fleet[i] += value }
        val current = series.copyOfRange(bucketCount, bucketCount * 2).sum()
        if (current > 0.0) {
            val prev = perVehicle[v.id]?.second ?: 0.0
            perVehicle[v.id] = v.withPlateResolved() to (prev + current)
        }
    }
    val currentTotal = fleet.copyOfRange(bucketCount, bucketCount * 2).sum()
    val previousTotal = fleet.copyOfRange(0, bucketCount).sum()
    val currentActive = fleet.copyOfRange(bucketCount, bucketCount * 2).filter { it > ACTIVITY_EPS }
    val previousActive = fleet.copyOfRange(0, bucketCount).filter { it > ACTIVITY_EPS }
    // День (часы): сравниваем итоги суток, иначе среднее по «активным» часам даёт −63% при другом числе часов.
    val change = if (unit == ChronoUnit.HOURS) {
        changePercentByTotals(currentTotal, previousTotal)
    } else {
        changePercentByActiveDays(currentActive, previousActive)
    }
    val points = chartPoints(
        unit,
        bucketCount,
        (bucketCount until bucketCount * 2).map { i -> WidgetPoint(label(i - bucketCount), fleet[i]) },
    )
    val leaders = perVehicle.entries.sortedByDescending { it.value.second }
        .map { (id, pair) -> leaderOf(pair.first, id, pair.second) }
    return WidgetSummary(type, currentTotal, unitFor(type), change, points, leaders)
}

/** Суммы за период из widget_daily_stats — те же цифры, что в списке «По машинам» у сводки. */
data class VehiclePeriodTotals(
    val fuelL: Double,
    val mileageKm: Double,
    val idleHours: Double,
    val refuels: Int,
)

/**
 * Расход/пробег/простой/заправки машины за календарные сутки [range] со стенда.
 * null — стенд не годится (как у виджета), считайте по телеметрии.
 */
suspend fun loadVehiclePeriodTotals(vehicleId: String, range: TimeRange): VehiclePeriodTotals? {
    val settings = ServiceLocator.settings.current()
    if (!settings.telemetryOnServer) return null
    // Короткое «сегодня» на стенде часто ещё пустое — оставляем клиентский пересчёт.
    if (range.span.toHours() < 36) return null
    val fromDay = range.from.toLocalDate()
    val toDay = range.to.toLocalDate()
    val bucketDays = max(1, java.time.temporal.ChronoUnit.DAYS.between(fromDay, toDay).toInt() + 1)
    val response = runCatching {
        ServiceLocator.widgetStats.load(fromDay.toString(), toDay.toString())
    }.getOrNull() ?: return null
    val items = response.items
    val recent = items.map { it.day }.toSortedSet().toList().takeLast(bucketDays).toSet()
    if (!serverDailyStatsUsable(items, recent, bucketDays)) return null
    val daySet = (0 until bucketDays).map { fromDay.plusDays(it.toLong()).toString() }.toSet()
    val mine = items.filter { it.vehicle.id == vehicleId && it.day in daySet }
    if (mine.isEmpty()) return null
    return VehiclePeriodTotals(
        fuelL = mine.sumOf { it.fuel },
        mileageKm = mine.sumOf { it.mileage },
        idleHours = mine.sumOf { it.idle },
        refuels = mine.sumOf { it.refuels },
    )
}

/**
 * Можно ли брать суточные статы со стенда: есть покрытие, нет фантастического расхода
 * и нет «проехали, а топлива 0» (пустой level-drop / не обновлённый стенд).
 */
private fun serverDailyStatsUsable(
    items: List<ru.petrovich.telemetry.data.DailyVehicleStats>,
    recentDays: Set<String>,
    bucketDays: Int,
): Boolean {
    if (items.isEmpty()) return false
    val recentRows = items.filter { it.day in recentDays }
    val recentFuel = recentRows.sumOf { it.fuel }
    val recentKm = recentRows.sumOf { it.mileage }
    val covered = items.map { it.vehicle.id }.toSet().size
    if (covered < 2) return false
    if (recentFuel <= 0.01 && recentKm <= 0.01) return false
    val maxVehicleDayFuel = recentRows.groupBy { it.vehicle.id to it.day }
        .values.maxOfOrNull { rows -> rows.sumOf { it.fuel } } ?: 0.0
    if (maxVehicleDayFuel > ABSURD_VEHICLE_DAY_FUEL_L) return false
    if (covered > 0 && recentFuel > covered * bucketDays * 250.0) return false
    // Пробег есть, расход обнулился — считаем на клиенте, пока стенд не пересчитает.
    if (recentKm >= HOLLOW_FUEL_MIN_KM && recentFuel <= 0.01) return false
    return true
}

/** Независимая загрузка для «Подробной сводки» (3.15): свой запрос под выбранный период. */
suspend fun loadWidgetSummary(type: WidgetType, period: WidgetPeriod): WidgetSummary = coroutineScope {
    val settings = ServiceLocator.settings.current()
    if (settings.telemetryOnServer && period != WidgetPeriod.DAY) {
        val now = LocalDateTime.now()
        val bucketCount = period.bucketCount
        val from = now.toLocalDate().minusDays((bucketCount * 2 - 1).toLong())
        val response = runCatching { ServiceLocator.widgetStats.load(from.toString(), now.toLocalDate().toString()) }.getOrNull()
        val items = enrichWidgetVehicles(response?.items.orEmpty())
        val recent = items.map { it.day }.toSortedSet().toList().takeLast(bucketCount).toSet()
        if (serverDailyStatsUsable(items, recent, bucketCount)) {
            return@coroutineScope buildDailySummary(type, period, items, from, bucketCount)
        }
    }
    val repo = ServiceLocator.telemetry
    val vehicles = repo.vehicles().let { if (type == WidgetType.FUEL_TRUCKS) it.filter(Vehicle::isTruck) else it }
    val unit = if (period == WidgetPeriod.DAY) ChronoUnit.HOURS else ChronoUnit.DAYS
    val bucketCount = period.bucketCount
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

/** Сводка за произвольный отрезок: грузим текущий + такой же прошлый для сравнения. */
suspend fun loadWidgetSummary(type: WidgetType, range: TimeRange): WidgetSummary = coroutineScope {
    val (unit, bucketCount) = customBuckets(range)
    val windowStart = range.previousOfSameLength().from
    val settings = ServiceLocator.settings.current()
    if (settings.telemetryOnServer && unit == ChronoUnit.DAYS) {
        val fromDay = windowStart.toLocalDate()
        val toDay = range.to.toLocalDate()
        val response = runCatching { ServiceLocator.widgetStats.load(fromDay.toString(), toDay.toString()) }.getOrNull()
        val items = enrichWidgetVehicles(response?.items.orEmpty())
        val recent = items.map { it.day }.toSortedSet().toList().takeLast(bucketCount).toSet()
        if (serverDailyStatsUsable(items, recent, bucketCount)) {
            return@coroutineScope buildDailySummary(type, null, items, fromDay, bucketCount)
        }
    }
    val repo = ServiceLocator.telemetry
    val vehicles = repo.vehicles().let { if (type == WidgetType.FUEL_TRUCKS) it.filter(Vehicle::isTruck) else it }
    val limit = Semaphore(4)
    val data = vehicles.map { v ->
        async { limit.withPermit { v to runCatching { repo.telemetry(v, windowStart, range.to) }.getOrNull() } }
    }.awaitAll()
    buildSummary(type, data, windowStart, unit, bucketCount) { i -> bucketLabel(null, range.from, unit, i) }
}

private fun buildDailySummary(
    type: WidgetType,
    period: WidgetPeriod?,
    items: List<ru.petrovich.telemetry.data.DailyVehicleStats>,
    from: java.time.LocalDate,
    bucketCount: Int,
): WidgetSummary {
    fun value(item: ru.petrovich.telemetry.data.DailyVehicleStats): Double = when (type) {
        WidgetType.FUEL, WidgetType.FUEL_TRUCKS, WidgetType.OVERCONSUMERS -> item.fuel
        WidgetType.IDLE -> item.idle
        WidgetType.MILEAGE -> item.mileage
        WidgetType.REFUELS -> item.refuels.toDouble()
        WidgetType.OFFLINE, WidgetType.ALARMS -> 0.0
    }
    val filtered = if (type == WidgetType.FUEL_TRUCKS) items.filter { truckHint.containsMatchIn(it.vehicle.name) } else items
    val currentFrom = from.plusDays(bucketCount.toLong())
    val previousFrom = currentFrom.minusDays(bucketCount.toLong())
    val current = filtered.filter { java.time.LocalDate.parse(it.day) >= currentFrom }
    val previous = filtered.filter {
        val day = java.time.LocalDate.parse(it.day)
        !day.isBefore(previousFrom) && day.isBefore(currentFrom)
    }
    val total = current.sumOf(::value)
    val pattern = when (period) {
        WidgetPeriod.WEEK -> "EEE"
        WidgetPeriod.DAY -> "HH"
        else -> "d.MM"
    }
    val dailyPoints = (0 until bucketCount).map { index ->
        val day = currentFrom.plusDays(index.toLong())
        WidgetPoint(
            day.format(DateTimeFormatter.ofPattern(pattern, RuLocale)),
            current.filter { java.time.LocalDate.parse(it.day) == day }.sumOf(::value),
        )
    }
    val points = chartPoints(ChronoUnit.DAYS, bucketCount, dailyPoints)
    val leaders = current.groupBy { it.vehicle.id }.map { (id, rows) ->
        leaderOf(rows.first().vehicle, id, rows.sumOf(::value))
    }.filter { it.value > 0 }.sortedByDescending { it.value }
    return WidgetSummary(
        type, total, unitFor(type),
        changePercentByActiveDays(activeDayTotals(current, ::value), activeDayTotals(previous, ::value)),
        points, leaders,
    )
}

/** Общие для «Главной» телеметрия по парку — одна загрузка на все виджеты (неделя, месяц или свой). */
data class FleetWeek(
    val data: List<Pair<Vehicle, VehicleTelemetry?>>, val from: LocalDateTime, val to: LocalDateTime,
    val serverItems: List<ru.petrovich.telemetry.data.DailyVehicleStats> = emptyList(),
    val period: WidgetPeriod? = WidgetPeriod.WEEK,
    val customRange: TimeRange? = null,
)

suspend fun loadFleetWeek(): FleetWeek = loadFleetPeriod(WidgetPeriod.WEEK)

suspend fun loadFleetPeriod(period: WidgetPeriod): FleetWeek = coroutineScope {
    require(period != WidgetPeriod.DAY) { "DAY — через loadFleetRange(period.toRange())" }
    val bucketDays = period.bucketCount
    val settings = ServiceLocator.settings.current()
    val to = LocalDateTime.now()
    val fromDay = to.toLocalDate().minusDays((bucketDays * 2 - 1).toLong())
    val from = fromDay.atStartOfDay()
    if (settings.telemetryOnServer) {
        val response = runCatching { ServiceLocator.widgetStats.load(fromDay.toString(), to.toLocalDate().toString()) }.getOrNull()
        val items = enrichWidgetVehicles(response?.items.orEmpty())
        val recent = items.map { it.day }.toSortedSet().toList().takeLast(bucketDays).toSet()
        // Фон не догнал / пустой расход при пробеге / UpVol-мусор — считаем по телеметрии.
        if (serverDailyStatsUsable(items, recent, bucketDays)) {
            return@coroutineScope FleetWeek(emptyList(), from, to, items, period)
        }
    }
    val repo = ServiceLocator.telemetry
    val vehicles = repo.vehicles()
    val limit = Semaphore(4)
    val data = vehicles.map { v -> async { limit.withPermit { v to runCatching { repo.telemetry(v, from, to) }.getOrNull() } } }.awaitAll()
    FleetWeek(data, from, to, period = period)
}

/** Парк за свой отрезок + такое же окно до него (для %). */
suspend fun loadFleetRange(range: TimeRange): FleetWeek = coroutineScope {
    val windowStart = range.previousOfSameLength().from
    val settings = ServiceLocator.settings.current()
    if (settings.telemetryOnServer && range.span.toHours() >= 36) {
        val fromDay = windowStart.toLocalDate()
        val toDay = range.to.toLocalDate()
        val response = runCatching { ServiceLocator.widgetStats.load(fromDay.toString(), toDay.toString()) }.getOrNull()
        val items = enrichWidgetVehicles(response?.items.orEmpty())
        val bucketDays = max(1, java.time.temporal.ChronoUnit.DAYS.between(range.from.toLocalDate(), toDay).toInt() + 1)
        val recent = items.map { it.day }.toSortedSet().toList().takeLast(bucketDays).toSet()
        if (serverDailyStatsUsable(items, recent, bucketDays)) {
            return@coroutineScope FleetWeek(
                emptyList(), windowStart, range.to, items, period = null, customRange = range,
            )
        }
    }
    val repo = ServiceLocator.telemetry
    val vehicles = repo.vehicles()
    val limit = Semaphore(4)
    val data = vehicles.map { v ->
        async { limit.withPermit { v to runCatching { repo.telemetry(v, windowStart, range.to) }.getOrNull() } }
    }.awaitAll()
    FleetWeek(data, windowStart, range.to, period = null, customRange = range)
}

/** Сводка виджета из уже загруженных [FleetWeek] — без обращений к серверу. */
fun FleetWeek.summary(type: WidgetType): WidgetSummary {
    val (unit, bucketCount) = when {
        customRange != null -> customBuckets(customRange)
        period != null && period != WidgetPeriod.DAY -> ChronoUnit.DAYS to period.bucketCount
        else -> ChronoUnit.DAYS to 7
    }
    if (serverItems.isNotEmpty()) {
        val days = serverItems.groupBy { it.day }.toSortedMap()
        val orderedDays = days.keys.toList()
        val currentDays = orderedDays.takeLast(bucketCount).toSet()
        val previousDays = orderedDays.dropLast(bucketCount).takeLast(bucketCount).toSet()
        fun value(item: ru.petrovich.telemetry.data.DailyVehicleStats): Double = when (type) {
            WidgetType.FUEL, WidgetType.FUEL_TRUCKS, WidgetType.OVERCONSUMERS -> item.fuel
            WidgetType.IDLE -> item.idle
            WidgetType.MILEAGE -> item.mileage
            WidgetType.REFUELS -> item.refuels.toDouble()
            WidgetType.OFFLINE, WidgetType.ALARMS -> 0.0
        }
        val filtered = if (type == WidgetType.FUEL_TRUCKS) serverItems.filter { it.vehicle.name.contains(truckHint) } else serverItems
        val dailyPoints = currentDays.sorted().map { day ->
            val label = runCatching {
                java.time.LocalDate.parse(day).format(
                    DateTimeFormatter.ofPattern(if (period == WidgetPeriod.WEEK) "EEE" else "d.MM", RuLocale),
                )
            }.getOrDefault(day.takeLast(2))
            WidgetPoint(label, filtered.filter { it.day == day }.sumOf(::value))
        }
        val points = chartPoints(unit, bucketCount, dailyPoints)
        val current = filtered.filter { it.day in currentDays }.groupBy { it.vehicle.id }
        val leaders = current.entries.map { (id, items) -> leaderOf(items.first().vehicle, id, items.sumOf(::value)) }
            .filter { it.value > 0 }.sortedByDescending { it.value }
        val currentItems = filtered.filter { it.day in currentDays }
        val previousItems = filtered.filter { it.day in previousDays }
        val total = currentItems.sumOf(::value)
        return WidgetSummary(
            type, total, unitFor(type),
            changePercentByActiveDays(activeDayTotals(currentItems, ::value), activeDayTotals(previousItems, ::value)),
            points, leaders,
        )
    }
    val filtered = if (type == WidgetType.FUEL_TRUCKS) data.filter { it.first.isTruck() } else data
    val labelStart = customRange?.from ?: from.plus(bucketCount.toLong(), unit)
    return buildSummary(type, filtered, from, unit, bucketCount) { i ->
        bucketLabel(period, labelStart, unit, i)
    }
}

/**
 * Линия «обычно» на графике — в тех же единицах, что столбцы (день или неделя).
 * Берём среднее по ненулевым столбцам текущего периода и откатываем change%;
 * пустые столбцы среднее не занижают.
 */
fun WidgetSummary.usualDaily(): Double? {
    val change = changePercent ?: return null
    val active = points.map { it.value }.filter { it > ACTIVITY_EPS }
    if (active.isEmpty()) return null
    val factor = 1.0 + change / 100.0
    if (factor <= 0.01) return null
    return active.average() / factor
}

/**
 * «Кто перерасходует»: расход л/100 км vs медиана парка за текущее окно периода.
 * В список попадают только машины ≥ [threshold] выше медианы (по умолчанию +15 %).
 * В л/100 км идут только сутки с пробегом — простой с нулевым km не раздувает rate.
 */
fun FleetWeek.overconsumers(minKm: Double = 5.0, threshold: Double = 0.15): OverconsumersReport {
    val (unit, bucketCount) = when {
        customRange != null -> customBuckets(customRange)
        period != null && period != WidgetPeriod.DAY -> ChronoUnit.DAYS to period.bucketCount
        else -> ChronoUnit.DAYS to 7
    }
    data class Acc(val id: String, val vehicle: Vehicle, var fuel: Double = 0.0, var km: Double = 0.0)
    val byVehicle = linkedMapOf<String, Acc>()

    if (serverItems.isNotEmpty()) {
        val orderedDays = serverItems.groupBy { it.day }.toSortedMap().keys.toList()
        val currentDays = orderedDays.takeLast(bucketCount).toSet()
        for (item in serverItems) {
            if (item.day !in currentDays) continue
            // Пустой день или без пробега — в л/100 км не берём.
            if (item.mileage <= ACTIVITY_EPS) continue
            val acc = byVehicle.getOrPut(item.vehicle.id) { Acc(item.vehicle.id, item.vehicle.withPlateResolved()) }
            acc.fuel += item.fuel.coerceAtLeast(0.0)
            acc.km += item.mileage
        }
    } else {
        val currentStart = customRange?.from ?: from.plus(bucketCount.toLong(), unit)
        for ((v, t) in data) {
            if (t == null) continue
            // Посуточно: только бакеты с пробегом.
            val fuelSeries = accumulate(WidgetType.FUEL, t, currentStart, unit, bucketCount)
            val kmSeries = accumulate(WidgetType.MILEAGE, t, currentStart, unit, bucketCount)
            var fuel = 0.0
            var km = 0.0
            for (i in fuelSeries.indices) {
                val dayKm = kmSeries.getOrElse(i) { 0.0 }
                if (dayKm <= ACTIVITY_EPS) continue
                fuel += fuelSeries[i].coerceAtLeast(0.0)
                km += dayKm
            }
            if (fuel > 0.0 || km > 0.0) byVehicle[v.id] = Acc(v.id, v.withPlateResolved(), fuel, km)
        }
    }

    val rates = byVehicle.values.mapNotNull { acc ->
        if (acc.km < minKm || acc.fuel <= 0.0) null
        else acc to (acc.fuel / acc.km * 100.0)
    }
    if (rates.isEmpty()) return OverconsumersReport(medianRate = null, rows = emptyList())

    val sortedRates = rates.map { it.second }.sorted()
    val median = medianOf(sortedRates)
    if (median <= 0.0) return OverconsumersReport(medianRate = null, rows = emptyList())

    val rows = rates.mapNotNull { (acc, rate) ->
        val excess = (rate - median) / median
        if (excess < threshold) null
        else OverconsumerRow(acc.id, acc.vehicle.widgetListName(), rate, excess * 100.0)
    }.sortedByDescending { it.excessPercent }

    return OverconsumersReport(medianRate = median, rows = rows)
}

private fun medianOf(sorted: List<Double>): Double {
    if (sorted.isEmpty()) return 0.0
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
}

/** «Без связи»: честно смотрим, когда от машины в последний раз была хоть одна точка данных в загруженном окне. */
fun FleetWeek.offline(now: LocalDateTime = LocalDateTime.now()): OfflineSummary {
    if (serverItems.isNotEmpty()) {
        val latest = serverItems.groupBy { it.vehicle.id }.map { (_, items) -> items.maxByOrNull { it.day }!! }
        val silent = latest.filter { item ->
            item.lastSeen == null || runCatching { Duration.between(LocalDateTime.parse(item.lastSeen), now) }.getOrNull()?.let { it > FRESHNESS } != false
        }.sortedBy { it.lastSeen ?: "" }
        return OfflineSummary(silent.size, silent.take(3).map {
            OfflineEntry(it.vehicle.id, it.vehicle.widgetListName(), if (it.lastSeen == null) "нет данных за 2 недели" else "нет свежих данных")
        })
    }
    val silent = data.mapNotNull { (v, t) ->
        val lastSeen = t?.tables?.values?.flatMap { table ->
            table.timestamps.filterIndexed { i, _ -> table.columns.any { c -> c.values.getOrNull(i) != null } }
        }?.maxOrNull()
        val gap = lastSeen?.let { Duration.between(it, now) }
        if (gap != null && gap <= FRESHNESS) null else Triple(v.id, v.withPlateResolved().widgetListName(), gap)
    }
    val sorted = silent.sortedByDescending { it.third ?: Duration.ofDays(999) }
    return OfflineSummary(sorted.size, sorted.take(3).map { (id, name, gap) -> OfflineEntry(id, name, silentLabel(gap)) })
}

private fun silentLabel(gap: Duration?): String {
    if (gap == null) return "нет данных за 2 недели"
    val hours = gap.toHours()
    return if (hours < 1) "молчит ${gap.toMinutes()} мин" else "молчит $hours ${plural(hours.toInt(), "час", "часа", "часов")}"
}
