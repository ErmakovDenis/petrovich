package ru.petrovich.telemetry.ui.park

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.anomaly.VehicleActivity
import ru.petrovich.telemetry.anomaly.classifyActivity
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.ui.home.WidgetType
import ru.petrovich.telemetry.ui.home.totalOf
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.LocalDateTime

/**
 * То, что честно известно о машине из уже загруженной телеметрии — «в баке», расход, пробег,
 * заправки и холостой ход за период, последняя связь и состояние датчика топлива. Ничего не
 * выдумываем: если сигнала для расчёта нет (например, нет параметра оборотов), соответствующее
 * поле — null (см. правило «Только известные данные», стр. 1 макета).
 */
data class VehicleStats(
    val activity: VehicleActivity,
    val lastContact: LocalDateTime?,
    /** Текущий уровень топлива, л — последнее известное значение в периоде. */
    val fuelLevel: Double?,
    val fuelUnit: String?,
    /**
     * Расход за период, л — та же формула, что у виджета «Расход топлива»
     * (падение уровня + fallback ConsumptionCAN). Null — посчитать не из чего.
     */
    val fuelUsedL: Double?,
    /** Пробег за период, км — честно посчитан интегрированием скорости по времени между точками. */
    val mileageKm: Double?,
    /** Число заправок за период по скачкам уровня (как виджет «Заправки»). Null — нет сигнала топлива. */
    val refuels: Int?,
    /** Часы холостого хода за период (зажигание + скорость ~0). Null — нет сигнала. */
    val idleHours: Double?,
    /** Передаёт ли датчик топлива данные вообще. Null — в периоде нет ни одной точки по топливу. */
    val fuelSensorOk: Boolean?,
)

/**
 * Считает [VehicleStats] по телеметрии за [t.from]..[t.to].
 * [periodTotals] — суммы со стенда (как в сводке «По машинам»); если заданы, расход/пробег/мч берём оттуда.
 */
fun computeVehicleStats(
    t: VehicleTelemetry,
    now: LocalDateTime = LocalDateTime.now(),
    periodTotals: ru.petrovich.telemetry.ui.home.VehiclePeriodTotals? = null,
): VehicleStats {
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

    val hasSignal = lastContact != null
    // Стенд (тот же источник, что виджет) важнее локального пересчёта — иначе неделя 80 л vs 12 л.
    val fuelUsedL: Double?
    val mileageKm: Double?
    val idleHours: Double?
    val refuels: Int?
    if (periodTotals != null) {
        fuelUsedL = periodTotals.fuelL.coerceAtLeast(0.0)
        mileageKm = periodTotals.mileageKm.coerceAtLeast(0.0)
        idleHours = periodTotals.idleHours.coerceAtLeast(0.0)
        refuels = periodTotals.refuels.coerceAtLeast(0)
    } else {
        val fuelUsedRaw = t.totalOf(WidgetType.FUEL)
        val mileageRaw = t.totalOf(WidgetType.MILEAGE)
        val idleRaw = t.totalOf(WidgetType.IDLE)
        val refuelsRaw = t.totalOf(WidgetType.REFUELS)
        fuelUsedL = when {
            fuelUsedRaw > 0.01 -> fuelUsedRaw
            hasSignal && t.tables.containsKey(MetricCategory.FUEL) -> 0.0
            else -> null
        }
        mileageKm = when {
            mileageRaw > 0.01 -> mileageRaw
            hasSignal && t.tables.containsKey(MetricCategory.MOTION) -> 0.0
            else -> null
        }
        idleHours = when {
            idleRaw > 0.005 -> idleRaw
            hasSignal && t.tables.containsKey(MetricCategory.POWER) -> 0.0
            else -> null
        }
        refuels = when {
            refuelsRaw > 0.5 -> refuelsRaw.toInt()
            hasSignal && t.tables.containsKey(MetricCategory.FUEL) -> 0
            else -> null
        }
    }

    return VehicleStats(
        activity = activity,
        lastContact = lastContact,
        fuelLevel = fuelLevel,
        fuelUnit = fuelColumn?.parameter?.unit,
        fuelUsedL = fuelUsedL,
        mileageKm = mileageKm,
        refuels = refuels,
        idleHours = idleHours,
        fuelSensorOk = fuelSensorOk,
    )
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
