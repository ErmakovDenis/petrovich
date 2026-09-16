package ru.petrovich.telemetry.anomaly

import ru.petrovich.telemetry.data.AutoGraphParameters as P
import ru.petrovich.telemetry.data.ParameterColumn
import ru.petrovich.telemetry.data.VehicleTelemetry
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Пороговые правила — временная замена ML-модели, чтобы раздел аномалий и уведомления
 * работали уже сейчас. Пороги подобраны по реальным данным (16.09.2026):
 *  - нормальное падение уровня топлива за 15 мин ≤ 10 л;
 *  - ОЖ ≤ 86 °C; давление масла на оборотах > 800 ≥ 188 кПа;
 *  - напряжение аккумулятора (24 В) на работающем двигателе: медиана 27.4, 1-й перцентиль 24.3 В;
 *    при выключенном зажигании по CAN приходит 0 — это «нет данных»;
 *  - тормозные контуры: 0 кПа — нет данных с датчика.
 * Короткие выбросы отсекаются требованием минимальной длительности.
 * Отключается в [ru.petrovich.telemetry.ServiceLocator], когда будет готова модель.
 */
class BaselineAnomalyDetector : AnomalyDetector {
    override val name = "Базовые правила"

    override suspend fun detect(telemetry: VehicleTelemetry): List<Anomaly> {
        val columns = telemetry.tables.values.flatMap { it.columns }
        val times = telemetry.tables.values.firstOrNull()?.timestamps ?: return emptyList()
        if (times.size < 2) return emptyList()
        fun col(name: String, vararg keywords: String): ParameterColumn? =
            columns.firstOrNull { it.parameter.name == name }
                ?: columns.firstOrNull { c -> keywords.any { it in "${c.parameter.name} ${c.parameter.caption}".lowercase() } }

        val ctx = Ctx(telemetry, times)
        val out = mutableListOf<Anomaly>()
        val rpm = col(P.RPM, "оборот", "rpm")
        val ignition = col(P.IGNITION) ?: col(P.IGNITION_CAN)
        // При выключенном зажигании AutoGRAPH держит последнее значение оборотов и интерполирует
        // остальные CAN-параметры (проверено на Урал 8904 и FAW №2), поэтому одних оборотов недостаточно.
        fun engineRunning(i: Int, minRpm: Double): Boolean {
            if (ignition != null && (ignition.values[i] ?: 0.0) < 0.5) return false
            return rpm == null || (rpm.values[i] ?: 0.0) > minRpm
        }

        // Топливо: слив по данным AutoGRAPH.
        col(P.FUEL_DRAIN_VOLUME)?.let { c ->
            out += ctx.episodes(c, { _, v -> v > 0 }) { i, v, _ ->
                ctx.anomaly(c, i, v, Severity.CRITICAL, "drain", "Слив топлива",
                    "AutoGRAPH зафиксировал слив ${fmt(v)} ${c.parameter.unit.orEmpty()}.")
            }
        }
        // Топливо: резкое падение уровня за 15 минут.
        col(P.FUEL_LEVEL, "уровень топлива", "fuellevel")?.let { c -> out += fuelDrop(ctx, c) }

        // Пропадание питания: короткое — информационное, дольше 2 минут — предупреждение.
        col(P.POWER)?.let { c ->
            out += ctx.episodes(c, { _, v -> v < 0.5 }, report = ReportAt.END) { i, v, duration ->
                val long = duration >= Duration.ofMinutes(2)
                // ≈ минимальная оценка: с учётом шага интервалов фактическая длительность может быть больше.
                ctx.anomaly(c, i, v, if (long) Severity.WARNING else Severity.INFO, "power", "Пропадание питания",
                    "Прибор сообщил об отключении основного питания" +
                        if (long) " (≈${duration.toMinutes()} мин)." else " (кратковременно).")
            }
        }

        // Напряжение бортсети/аккумулятора — только на работающем двигателе и дольше 5 минут.
        val voltageColumns = columns.filter { c ->
            c.parameter.name == P.BATTERY_VOLTAGE ||
                (c.parameter.name != P.POWER && listOf("напряж", "volt", "аккум", "батар", "бортсет").any { it in "${c.parameter.name} ${c.parameter.caption}".lowercase() })
        }
        voltageColumns.forEach { c ->
            val present = c.values.filterNotNull().filter { it >= 5 }.sorted()
            if (present.isEmpty()) return@forEach
            val median = present[present.size / 2]
            val (low, critical, high) = if (median > 18) Triple(24.0, 22.0, 30.5) else Triple(12.0, 11.0, 15.0)
            out += ctx.episodes(
                c, { i, v -> v >= 5 && (v < low || v > high) && engineRunning(i, 500.0) },
                minDuration = Duration.ofMinutes(5),
            ) { i, v, _ ->
                val severity = if (v < critical || v > high + 1.5) Severity.CRITICAL else Severity.WARNING
                val what = if (v < low) "ниже ${fmt(low)} В — возможна неисправность генератора или АКБ" else "выше ${fmt(high)} В — перезаряд"
                ctx.anomaly(c, i, v, severity, "volt", "Напряжение вне нормы",
                    "${c.parameter.caption}: ${fmt(v)} В на работающем двигателе, $what.")
            }
        }

        // Перегрев двигателя.
        col(P.COOLANT_TEMP, "охл", "coolant")?.let { c ->
            out += ctx.episodes(c, { _, v -> v > 100 }, minDuration = Duration.ofMinutes(2)) { i, v, _ ->
                ctx.anomaly(c, i, v, if (v > 105) Severity.CRITICAL else Severity.WARNING, "overheat", "Перегрев двигателя",
                    "Температура охлаждающей жидкости ${fmt(v)} °C (норма до 100 °C).")
            }
        }

        // Низкое давление масла на работающем двигателе (0 — нет данных с датчика).
        col(P.OIL_PRESSURE, "давление масла")?.let { oil ->
            if (rpm == null) return@let
            out += ctx.episodes(oil, { i, v -> v > 0 && v < 100 && engineRunning(i, 800.0) }, minDuration = Duration.ofMinutes(1)) { i, v, _ ->
                ctx.anomaly(oil, i, v, Severity.CRITICAL, "oil", "Низкое давление масла",
                    "Давление масла ${fmt(v)} кПа при ${fmt(rpm.values[i] ?: 0.0)} об/мин. Проверьте уровень масла.")
            }
        }
        // Низкое давление в тормозных контурах: дольше 10 минут на работающем двигателе
        // (после запуска давление набирается несколько минут).
        if (rpm != null) {
            listOfNotNull(col(P.BRAKE_PRESSURE_1), col(P.BRAKE_PRESSURE_2)).forEach { brake ->
                out += ctx.episodes(brake, { i, v -> v < 550 && engineRunning(i, 600.0) }, minDuration = Duration.ofMinutes(10)) { i, v, _ ->
                    ctx.anomaly(brake, i, v, Severity.WARNING, "brake", "Низкое давление в тормозной системе",
                        "${brake.parameter.caption}: ${fmt(v)} кПа при работающем двигателе дольше 10 минут (обычно 600–1050 кПа).")
                }
            }
        }
        return out
    }

    private fun fuelDrop(ctx: Ctx, c: ParameterColumn, window: Duration = Duration.ofMinutes(15), minDrop: Double = 25.0): List<Anomaly> {
        val result = mutableListOf<Anomaly>()
        var windowStart = 0
        var cooldownUntil: LocalDateTime? = null
        for (i in c.values.indices) {
            val v = c.values[i] ?: continue
            while (Duration.between(ctx.times[windowStart], ctx.times[i]) > window) windowStart++
            if (cooldownUntil != null && ctx.times[i].isBefore(cooldownUntil)) continue
            val peak = (windowStart until i).mapNotNull { c.values[it] }.maxOrNull() ?: continue
            if (peak - v >= minDrop) {
                result += ctx.anomaly(c, i, v, Severity.CRITICAL, "drop", "Резкое падение уровня топлива",
                    "Уровень упал с ${fmt(peak)} до ${fmt(v)} ${c.parameter.unit.orEmpty()} (−${fmt(peak - v)}) за ≤15 мин. Возможен слив.")
                cooldownUntil = ctx.times[i].plusMinutes(60)
            }
        }
        return result
    }

    private enum class ReportAt { START, END }

    private class Ctx(val t: VehicleTelemetry, val times: List<LocalDateTime>) {
        private val step: Duration = Duration.between(times[0], times[1])

        /**
         * Одна аномалия на каждую непрерывную серию интервалов, где выполняется условие,
         * если серия длится не меньше [minDuration]. Интервал без данных прерывает серию.
         * [build] получает индекс начала серии, значение в нём и длительность серии.
         */
        fun episodes(
            c: ParameterColumn,
            condition: (index: Int, value: Double) -> Boolean,
            minDuration: Duration = Duration.ZERO,
            report: ReportAt = ReportAt.START,
            build: (Int, Double, Duration) -> Anomaly,
        ): List<Anomaly> {
            val result = mutableListOf<Anomaly>()
            var runStart = -1
            var reported = false
            fun close(endExclusive: Int) {
                if (runStart >= 0 && !reported && report == ReportAt.END) {
                    // Длительность — от начала первого до начала последнего интервала серии: одиночный интервал = 0.
                val duration = step.multipliedBy((endExclusive - 1 - runStart).toLong())
                    if (duration >= minDuration) result += build(runStart, c.values[runStart]!!, duration)
                }
                runStart = -1
                reported = false
            }
            for (i in c.values.indices) {
                val v = c.values[i]
                if (v == null || !condition(i, v)) { close(i); continue }
                if (runStart < 0) runStart = i
                val duration = step.multipliedBy((i - runStart + 1).toLong())
                if (report == ReportAt.START && !reported && duration >= minDuration) {
                    result += build(runStart, c.values[runStart]!!, duration)
                    reported = true
                }
            }
            close(c.values.size)
            return result
        }

        fun anomaly(c: ParameterColumn, i: Int, value: Double, severity: Severity, kind: String, title: String, description: String) =
            Anomaly(
                id = "rule|$kind|${t.vehicle.id}|${c.parameter.name}|${times[i]}",
                vehicleId = t.vehicle.id,
                vehicleName = t.vehicle.name,
                category = c.parameter.category,
                parameterName = c.parameter.name,
                parameterCaption = c.parameter.caption,
                eventTime = times[i].toString(),
                detectedAt = System.currentTimeMillis(),
                severity = severity,
                title = title,
                description = description,
                value = value,
                source = "Базовые правила",
            )
    }

    private fun fmt(v: Double) = if (abs(v - Math.round(v)) < 0.05) Math.round(v).toString() else "%.1f".format(v)
}
