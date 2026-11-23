package ru.petrovich.telemetry.anomaly

import ru.petrovich.telemetry.data.AutoGraphParameters as P
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.ParameterColumn
import ru.petrovich.telemetry.data.ParameterInfo
import ru.petrovich.telemetry.data.VehicleTelemetry
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Пороговые правила — временная замена ML-модели, чтобы раздел аномалий и уведомления
 * работали уже сейчас. Пороги подобраны по реальным данным (16.09.2026) и совпадают с
 * `RuleThresholds` стенда (FS_RULES__*). Виды как у API 1 курса: fuel_drain, towing, data_gap*
 * плюс drop/power/volt/overheat/oil/brake.
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
        val speed = col(P.SPEED) ?: col(P.SPEED_CAN)
        val gpsSpeed = col(P.GPS_SPEED)
        val power = col(P.POWER)

        // Топливо: слив по данным AutoGRAPH → kind как у API 1 курса.
        col(P.FUEL_DRAIN_VOLUME)?.let { c ->
            out += ctx.episodes(c, { _, v -> v > 0 }) { i, v, _ ->
                ctx.anomaly(c, i, v, Severity.CRITICAL, "fuel_drain", "Слив топлива",
                    "AutoGRAPH зафиксировал слив ${fmt(v)} ${c.parameter.unit.orEmpty()}.")
            }
        }

        // Буксировка / скорость без движения / пропуски — те же kinds, что у API 1 курса.
        if (speed != null) {
            out += towing(ctx, speed, ignition, rpm)
            if (gpsSpeed != null) out += speedWithoutMovement(ctx, speed, gpsSpeed)
        }
        out += gaps(ctx, columns, power, gpsSpeed)
        return out
    }

    private fun towing(
        ctx: Ctx,
        speed: ParameterColumn,
        ignition: ParameterColumn?,
        rpm: ParameterColumn?,
    ): List<Anomaly> {
        if (ignition == null && rpm == null) return emptyList()
        return ctx.episodes(
            speed,
            { i, v ->
                if (v < TOWING_MIN_SPEED) {
                    false
                } else {
                    val ignOff = ignition != null && (ignition.values[i] ?: 0.0) < 0.5
                    val rpmLow = rpm != null && (rpm.values[i] ?: 0.0) <= TOWING_MAX_RPM
                    ignOff || rpmLow
                }
            },
            minDuration = Duration.ofMinutes(TOWING_MIN_MINUTES.toLong()),
        ) { i, v, d ->
            ctx.anomaly(
                speed, i, v, Severity.WARNING, "towing", "Буксировка",
                "Скорость ${fmt(v)} км/ч ≈${d.toMinutes()} мин при выключенном двигателе — возможна буксировка.",
            )
        }
    }

    private fun speedWithoutMovement(ctx: Ctx, speed: ParameterColumn, gpsSpeed: ParameterColumn): List<Anomaly> {
        // Спидометр ≥ порога, GpsSpeed ≈ 0 — скорость без перемещения по GPS.
        val gpsMax = SPEED_WITHOUT_MAX_KM * 10 // 0.1 → 1 км/ч
        return ctx.episodes(
            speed,
            { i, v ->
                if (v < SPEED_WITHOUT_MIN_KMH) false
                else {
                    val g = gpsSpeed.values[i]
                    g != null && g <= gpsMax
                }
            },
            minDuration = Duration.ofMinutes(SPEED_WITHOUT_MIN_MINUTES.toLong()),
        ) { i, v, d ->
            ctx.anomaly(
                speed, i, v, Severity.WARNING, "speed_without_movement", "Скорость без движения",
                "Спидометр ${fmt(v)} км/ч ≈${d.toMinutes()} мин при GpsSpeed ≈0 — проверьте датчик скорости.",
            )
        }
    }

    private fun gaps(
        ctx: Ctx,
        columns: List<ParameterColumn>,
        power: ParameterColumn?,
        gpsSpeed: ParameterColumn?,
    ): List<Anomaly> {
        if (columns.isEmpty()) return emptyList()
        val n = ctx.times.size
        val others = columns.filter { it !== power }
        val allNull = BooleanArray(n) { i -> columns.all { it.values[i] == null } }
        val othersNull = BooleanArray(n) { i -> others.isEmpty() || others.all { it.values[i] == null } }
        val powerOn = BooleanArray(n) { i ->
            power != null && power.values[i] != null && power.values[i]!! >= 0.5
        }
        val anyOther = BooleanArray(n) { i -> others.any { it.values[i] != null } }
        val claimed = BooleanArray(n)
        val result = mutableListOf<Anomaly>()
        val telemetryCol = ParameterColumn(
            ParameterInfo("telemetry", "Телеметрия", null, MetricCategory.POWER),
            List(n) { null },
        )

        fun durationOk(start: Int, endExclusive: Int, minutes: Int): Boolean =
            ctx.step.multipliedBy((endExclusive - start).toLong()) >= Duration.ofMinutes(minutes.toLong())

        fun emit(kind: String, title: String, description: String, start: Int, endExclusive: Int, col: ParameterColumn, value: Double? = null) {
            for (i in start until endExclusive) claimed[i] = true
            result += ctx.anomaly(col, start, value, Severity.WARNING, kind, title, description)
        }

        fun runs(mask: BooleanArray): List<Pair<Int, Int>> {
            val out = mutableListOf<Pair<Int, Int>>()
            var start = -1
            for (i in mask.indices) {
                if (mask[i]) {
                    if (start < 0) start = i
                } else if (start >= 0) {
                    out += start to i
                    start = -1
                }
            }
            if (start >= 0) out += start to mask.size
            return out
        }

        fun hasDataBefore(start: Int) = (0 until start).any { !allNull[it] }
        fun hasDataAfter(end: Int) = (end until n).any { !allNull[it] }

        // Внутренний разрыв — раньше all_channels, чтобы не перекрывать telemetry_gap.
        for ((start, end) in runs(allNull)) {
            if (start == 0 || end >= n) continue
            if (!hasDataBefore(start) || !hasDataAfter(end)) continue
            if (durationOk(start, end, GAP_TELEMETRY_MIN_MINUTES)) {
                val mins = ctx.step.multipliedBy((end - start).toLong()).toMinutes()
                emit(
                    "telemetry_gap", "Разрыв телеметрии",
                    "Пауза в телеметрии ≈$mins мин при данных до и после.", start, end, telemetryCol,
                )
            }
        }

        for ((start, end) in runs(BooleanArray(n) { i -> allNull[i] && !claimed[i] })) {
            if (durationOk(start, end, GAP_ALL_CHANNELS_MIN_MINUTES)) {
                val mins = ctx.step.multipliedBy((end - start).toLong()).toMinutes()
                emit(
                    "data_gap_all_channels_unavailable", "Машина не на связи",
                    "Нет данных ни по одному каналу ≈$mins мин.", start, end, telemetryCol,
                )
            }
        }

        val signalMask = BooleanArray(n) { i -> powerOn[i] && othersNull[i] && !claimed[i] }
        for ((start, end) in runs(signalMask)) {
            if (durationOk(start, end, GAP_SIGNAL_MIN_MINUTES)) {
                val mins = ctx.step.multipliedBy((end - start).toLong()).toMinutes()
                emit(
                    "data_gap_signal_unavailable", "Нет сигнала",
                    "Питание есть, остальные каналы молчат ≈$mins мин.", start, end, power ?: telemetryCol, 1.0,
                )
            }
        }

        if (gpsSpeed != null) {
            val gpsMask = BooleanArray(n) { i -> gpsSpeed.values[i] == null && anyOther[i] && !claimed[i] }
            for ((start, end) in runs(gpsMask)) {
                if (durationOk(start, end, GAP_GPS_MIN_MINUTES)) {
                    val mins = ctx.step.multipliedBy((end - start).toLong()).toMinutes()
                    emit(
                        "data_gap_gps_unavailable", "Нет GPS",
                        "Скорость GPS не передавалась ≈$mins мин при других данных.", start, end, gpsSpeed,
                    )
                }
            }
        }

        for (c in columns) {
            if (c === power) continue
            val mask = BooleanArray(n) { i ->
                c.values[i] == null && columns.any { it !== c && it.values[i] != null } && !claimed[i]
            }
            for ((start, end) in runs(mask)) {
                if (durationOk(start, end, GAP_CHANNEL_MIN_MINUTES)) {
                    val mins = ctx.step.multipliedBy((end - start).toLong()).toMinutes()
                    emit(
                        "data_gap_channel_unavailable", "Нет данных с датчика",
                        "${c.parameter.caption} молчит ≈$mins мин при других данных.", start, end, c,
                    )
                }
            }
        }

        val partialMask = BooleanArray(n) { i ->
            if (claimed[i]) return@BooleanArray false
            val nulls = columns.count { it.values[i] == null }
            val lives = columns.size - nulls
            lives > 0 && nulls > 0 && nulls >= maxOf(1, columns.size / 2)
        }
        for ((start, end) in runs(partialMask)) {
            if (durationOk(start, end, GAP_PARTIAL_MIN_MINUTES)) {
                val mins = ctx.step.multipliedBy((end - start).toLong()).toMinutes()
                emit(
                    "data_gap", "Пропуск данных",
                    "Часть каналов без данных ≈$mins мин.", start, end, telemetryCol,
                )
            }
        }

        return result.sortedWith(compareBy({ GAP_RANK[it.kind] ?: 99 }, { it.eventTime }, { it.id }))
    }

    private enum class ReportAt { START, END }

    private class Ctx(val t: VehicleTelemetry, val times: List<LocalDateTime>) {
        val step: Duration = Duration.between(times[0], times[1])

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
                    val duration = step.multipliedBy((endExclusive - 1 - runStart).toLong())
                    if (duration >= minDuration) result += build(runStart, c.values[runStart]!!, duration)
                }
                runStart = -1
                reported = false
            }
            for (i in c.values.indices) {
                val v = c.values[i]
                if (v == null || !condition(i, v)) {
                    close(i)
                    continue
                }
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

        fun anomaly(
            c: ParameterColumn,
            i: Int,
            value: Double?,
            severity: Severity,
            kind: String,
            title: String,
            description: String,
        ) = Anomaly(
            id = "rule|$kind|${t.vehicle.id}|${c.parameter.name}|${times[i]}",
            vehicleId = t.vehicle.id,
            vehicleName = t.vehicle.label,
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

    companion object {
        private const val TOWING_MIN_SPEED = 5.0
        private const val TOWING_MAX_RPM = 400.0
        private const val TOWING_MIN_MINUTES = 3
        private const val SPEED_WITHOUT_MIN_KMH = 3.0
        private const val SPEED_WITHOUT_MAX_KM = 0.1
        private const val SPEED_WITHOUT_MIN_MINUTES = 5
        private const val GAP_ALL_CHANNELS_MIN_MINUTES = 10
        private const val GAP_SIGNAL_MIN_MINUTES = 10
        private const val GAP_TELEMETRY_MIN_MINUTES = 60
        private const val GAP_GPS_MIN_MINUTES = 15
        private const val GAP_CHANNEL_MIN_MINUTES = 30
        private const val GAP_PARTIAL_MIN_MINUTES = 15
        private val GAP_RANK = mapOf(
            "telemetry_gap" to 0,
            "data_gap_all_channels_unavailable" to 1,
            "data_gap_signal_unavailable" to 2,
            "data_gap_gps_unavailable" to 3,
            "data_gap_channel_unavailable" to 4,
            "data_gap" to 5,
        )
    }
}
