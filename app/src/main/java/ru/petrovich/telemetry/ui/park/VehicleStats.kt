package ru.petrovich.telemetry.ui.park

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.anomaly.VehicleActivity
import ru.petrovich.telemetry.anomaly.classifyActivity
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.Duration
import java.time.LocalDateTime

/**
 * То, что честно известно о машине из уже загруженной телеметрии — «в баке», пробег и холостой ход
 * за период, последняя связь и состояние датчика топлива. Ничего не выдумываем: если сигнала для
 * расчёта нет (например, нет параметра оборотов), соответствующее поле — null (см. правило
 * «Только известные данные», стр. 1 макета).
 */
data class VehicleStats(
    val activity: VehicleActivity,
    val lastContact: LocalDateTime?,
    /** Текущий уровень топлива, л — последнее известное значение в периоде. */
    val fuelLevel: Double?,
    val fuelUnit: String?,
    /** Пробег за период, км — честно посчитан интегрированием скорости по времени между точками. */
    val mileageKm: Double?,
    /** Часы холостого хода за период (двигатель работает, скорость ~0). Null — нет сигнала оборотов. */
    val idleHours: Double?,
    /** Передаёт ли датчик топлива данные вообще. Null — в периоде нет ни одной точки по топливу. */
    val fuelSensorOk: Boolean?,
)

private const val MOVING_SPEED_KMH = 3.0
private const val IDLE_RPM_THRESHOLD = 250.0

/** Считает [VehicleStats] по телеметрии за уже загруженный период [t.from]..[t.to]. */
fun computeVehicleStats(t: VehicleTelemetry, now: LocalDateTime = LocalDateTime.now()): VehicleStats {
    val activity = classifyActivity(t, now)

    val lastContact = t.tables.values
        .flatMap { table -> table.timestamps.indices.map { i -> table to i } }
        .filter { (table, i) -> table.columns.any { it.values.getOrNull(i) != null } }
        .maxOfOrNull { (table, i) -> table.timestamps[i] }

    val fuelTable = t.tables[MetricCategory.FUEL]
    val fuelColumn = fuelTable?.columns?.firstOrNull { it.parameter.name.contains("Level", ignoreCase = true) }
        ?: fuelTable?.columns?.firstOrNull()
    val fuelLevel = fuelColumn?.values?.lastOrNull { it != null }
    val fuelSensorOk = fuelTable?.columns?.isNotEmpty()?.let { hasColumns ->
        if (!hasColumns) null else fuelColumn?.values?.any { it != null } ?: false
    }

    val motionTable = t.tables[MetricCategory.MOTION]
    val speedColumn = motionTable?.columns?.firstOrNull { it.parameter.name == "Speed" } ?: motionTable?.columns?.firstOrNull()
    val mileageKm = speedColumn?.let { col -> motionTable?.let { table -> integrateMileage(table.timestamps, col.values) } }

    val engineTable = t.tables[MetricCategory.ENGINE]
    val rpmColumn = engineTable?.columns?.firstOrNull { it.parameter.name.contains("otation", ignoreCase = true) || it.parameter.name.contains("rpm", ignoreCase = true) }
    val idleHours = if (rpmColumn != null && motionTable != null && speedColumn != null) {
        integrateIdle(motionTable.timestamps, speedColumn.values, rpmColumn.values)
    } else null

    return VehicleStats(
        activity = activity,
        lastContact = lastContact,
        fuelLevel = fuelLevel,
        fuelUnit = fuelColumn?.parameter?.unit,
        mileageKm = mileageKm,
        idleHours = idleHours,
        fuelSensorOk = fuelSensorOk,
    )
}

/** Пробег = сумма (средняя скорость между соседними известными точками) × время между ними. */
private fun integrateMileage(times: List<LocalDateTime>, speeds: List<Double?>): Double? {
    if (times.size < 2) return null
    var km = 0.0
    var any = false
    for (i in 0 until times.size - 1) {
        val a = speeds.getOrNull(i) ?: continue
        val b = speeds.getOrNull(i + 1) ?: continue
        val hours = Duration.between(times[i], times[i + 1]).seconds / 3600.0
        if (hours <= 0 || hours > 1.0) continue // большой разрыв — не додумываем, что было между точками
        km += (a + b) / 2.0 * hours
        any = true
    }
    return if (any) km else null
}

/** Холостой ход = время, когда скорость < 3 км/ч, а обороты выше порога (двигатель работает). */
private fun integrateIdle(times: List<LocalDateTime>, speeds: List<Double?>, rpm: List<Double?>): Double? {
    if (times.size < 2) return null
    var hours = 0.0
    var any = false
    for (i in 0 until times.size - 1) {
        val s = speeds.getOrNull(i) ?: continue
        val r = rpm.getOrNull(i) ?: continue
        val dt = Duration.between(times[i], times[i + 1]).seconds / 3600.0
        if (dt <= 0 || dt > 1.0) continue
        any = true
        if (s < MOVING_SPEED_KMH && r > IDLE_RPM_THRESHOLD) hours += dt
    }
    return if (any) hours else null
}

fun VehicleActivity.label(): String = when (this) {
    VehicleActivity.ON_LINE -> "На линии"
    VehicleActivity.STOPPED -> "Стоит"
    VehicleActivity.OFFLINE -> "Нет связи"
}

/**
 * Единая метка статуса для «Парка» и «Карточки машины»: красная/жёлтая «Проблема»/«Внимание», если
 * есть неразобранная аномалия (честно — из [ru.petrovich.telemetry.anomaly.AnomalyStore]), иначе —
 * честный статус активности. (текст, цвет текста, цвет фона)
 */
@Composable
fun statusPill(activity: VehicleActivity, worstPendingSeverity: Severity?): Triple<String, Color, Color> {
    val c = Petrovich.colors
    return when {
        worstPendingSeverity == Severity.CRITICAL -> Triple("Проблема", c.high, c.highSoft)
        worstPendingSeverity == Severity.WARNING -> Triple("Внимание", c.med, c.medSoft)
        activity == VehicleActivity.ON_LINE -> Triple("На линии", c.ok, c.okSoft)
        activity == VehicleActivity.STOPPED -> Triple("Стоит", c.muted, c.surface2)
        else -> Triple("Нет связи", c.med, c.medSoft)
    }
}
