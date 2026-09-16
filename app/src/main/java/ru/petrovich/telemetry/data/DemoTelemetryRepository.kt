package ru.petrovich.telemetry.data

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Синтетические данные для 10 грузовиков — чтобы приложение работало без доступа к API.
 * В данные намеренно подмешаны аномалии (слив топлива, просадка напряжения, перегрев).
 */
class DemoTelemetryRepository : TelemetryRepository {

    private val vehicles = listOf(
        "КАМАЗ 65115 А101МР66", "КАМАЗ 43118 В214ОК66", "МАЗ 6312 Е305ТХ96", "Scania R450 К417АУ96",
        "Volvo FH 500 М522СН66", "КАМАЗ 5490 Н630ВЕ196", "Mercedes Actros О748РА66", "MAN TGS Р851ЕК96",
        "Урал 4320 С963НМ66", "ГАЗон NEXT Т077ХО196",
    ).mapIndexed { i, name -> Vehicle("demo-$i", name, if (i < 5) "Колонна №1" else "Колонна №2") }

    private val step: Duration = Duration.ofMinutes(5)

    private fun param(name: String, caption: String, unit: String?, c: MetricCategory) =
        ParameterInfo(name, caption, unit, c)

    private val fuelLevel = param("TankMainFuelLevel", "Уровень топлива", "л", MetricCategory.FUEL)
    private val fuelRate = param("CANFinstant", "Мгновенный расход", "л/ч", MetricCategory.FUEL)
    private val voltage = param("PowerVoltage", "Напряжение бортсети", "В", MetricCategory.POWER)
    private val battery = param("Power", "Питание (1 — есть)", null, MetricCategory.POWER)
    private val rpm = param("Rotation", "Обороты", "об/мин", MetricCategory.ENGINE)
    private val coolant = param("TemperatureCOOL", "Температура ОЖ", "°C", MetricCategory.ENGINE)
    private val speed = param("Speed", "Скорость", "км/ч", MetricCategory.MOTION)

    override suspend fun vehicles(): List<Vehicle> = vehicles

    override suspend fun telemetry(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): VehicleTelemetry {
        val idx = vehicle.id.removePrefix("demo-").toIntOrNull() ?: 0
        val start = from.truncateToStep()
        val times = generateSequence(start) { it.plus(step) }.takeWhile { !it.isAfter(to) }.toList()

        val cols = mutableMapOf<ParameterInfo, MutableList<Double?>>()
        listOf(fuelLevel, fuelRate, voltage, battery, rpm, coolant, speed).forEach { cols[it] = mutableListOf() }

        for (t in times) {
            val epochMin = t.toEpochSecond(ZoneOffset.UTC) / 60
            val rnd = Random(idx * 1_000_003L + epochMin)
            val hour = t.hour + t.minute / 60.0
            val working = hour in 7.0..20.0 && (epochMin / 5 + idx) % 17 != 0L
            val v = if (working) (55 + 25 * sin(hour / 24 * 2 * PI * 3 + idx)).coerceAtLeast(0.0) + rnd.nextDouble(-5.0, 5.0) else 0.0
            val engineOn = working || (hour in 6.5..7.0)

            // Уровень топлива: пилообразное потребление с заправкой раз в ~сутки.
            val cycleMin = 24 * 60 + idx * 37
            val phase = ((epochMin + idx * 211) % cycleMin).toDouble() / cycleMin
            var level = 380 - 300 * phase + rnd.nextDouble(-2.0, 2.0)
            // Аномалия: слив топлива ночью у некоторых машин.
            val drainAt = (epochMin + idx * 97) % (3 * 24 * 60)
            if (idx % 3 == 1 && drainAt in 150..(3 * 24 * 60)) level -= 60
            level = max(level, 5.0)

            var volt = if (engineOn) 28.1 + rnd.nextDouble(-0.3, 0.3) else 25.2 + rnd.nextDouble(-0.2, 0.2)
            // Аномалия: просадка напряжения.
            if (idx % 4 == 2 && (epochMin + idx * 13) % (18 * 60) in 0..20) volt = 21.5 + rnd.nextDouble(-0.5, 0.5)

            var temp = if (engineOn) 86 + 6 * sin(epochMin / 40.0) + rnd.nextDouble(-1.5, 1.5) else 30.0 + rnd.nextDouble(-2.0, 2.0)
            // Аномалия: перегрев.
            if (idx % 5 == 3 && (epochMin + idx * 7) % (20 * 60) in 0..25) temp = 108 + rnd.nextDouble(0.0, 4.0)

            cols.getValue(fuelLevel) += level.round1()
            cols.getValue(fuelRate) += if (engineOn) (18 + v * 0.25 + rnd.nextDouble(-2.0, 2.0)).round1() else 0.0
            cols.getValue(voltage) += volt.round1()
            cols.getValue(battery) += 1.0
            cols.getValue(rpm) += if (engineOn) (900 + v * 18 + rnd.nextDouble(-60.0, 60.0)).round1() else 0.0
            cols.getValue(coolant) += temp.round1()
            cols.getValue(speed) += v.coerceAtLeast(0.0).round1()
        }

        val tables = cols.entries.groupBy { it.key.category }.mapValues { (c, entries) ->
            CategoryTable(c, times, entries.map { ParameterColumn(it.key, it.value) })
        }
        return VehicleTelemetry(vehicle, from, to, tables)
    }

    private fun LocalDateTime.truncateToStep(): LocalDateTime =
        withSecond(0).withNano(0).withMinute(minute - minute % 5)

    private fun Double.round1() = Math.round(this * 10) / 10.0
}
