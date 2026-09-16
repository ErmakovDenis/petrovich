package ru.petrovich.telemetry.data

import ru.petrovich.telemetry.data.api.RParameter

/**
 * Как сворачивать сырые точки (~каждые 10 с) в интервал.
 * *_NONZERO — ноль означает «нет данных» (например, напряжение по CAN при выключенном зажигании).
 */
enum class Aggregation(val zeroIsMissing: Boolean = false) {
    MEAN, MIN, MAX, MEAN_NONZERO(true), MIN_NONZERO(true)
}

data class ParameterSpec(
    val name: String,
    val category: MetricCategory,
    /** Подпись вместо короткой из AutoGRAPH («Текущая», «ОС» и т.п.). */
    val caption: String? = null,
    val aggregation: Aggregation = Aggregation.MEAN,
)

/**
 * Какие параметры AutoGRAPH показывать в разделах. Имена — стандартные для схемы AutoGRAPH
 * (проверено на реальной схеме). Порядок = порядок столбцов.
 */
object AutoGraphParameters {

    /** Стандартные имена параметров, используемые в правилах аномалий. */
    const val FUEL_LEVEL = "TankMainFuelLevel"
    const val FUEL_DRAIN_VOLUME = "TankMainFuelDnVol"
    const val FUEL_UP_VOLUME = "TankMainFuelUpVol"
    const val POWER = "Power"
    const val IGNITION = "DIgnition"
    const val IGNITION_CAN = "DIgnitionCAN"

    /** Параметры-состояния: ноль в них — значимое значение (выключено), а не отсутствие датчика. */
    val statusParameters = setOf("Power", "DIgnition", "DIgnitionCAN")
    const val RPM = "Rotation"
    const val COOLANT_TEMP = "TemperatureCOOL"
    const val OIL_PRESSURE = "PressureOIL"
    const val BRAKE_PRESSURE_1 = "PressureVSBC1"
    const val BRAKE_PRESSURE_2 = "PressureVSBC2"
    const val SPEED = "Speed"
    const val BATTERY_VOLTAGE = "BattaryVOLTAGE" // так в схеме AutoGRAPH (sic)

    val curated: List<ParameterSpec> = listOf(
        ParameterSpec(FUEL_LEVEL, MetricCategory.FUEL, "Уровень топлива"),
        ParameterSpec("FL1", MetricCategory.FUEL, "ДУТ 1"),
        ParameterSpec("CANFinstant", MetricCategory.FUEL, "Мгновенный расход"),
        ParameterSpec("ConsumptionCAN", MetricCategory.FUEL, "Расход по CAN (накоп.)", Aggregation.MAX),
        ParameterSpec(FUEL_UP_VOLUME, MetricCategory.FUEL, "Объём заправки", Aggregation.MAX),
        ParameterSpec(FUEL_DRAIN_VOLUME, MetricCategory.FUEL, "Объём слива", Aggregation.MAX),

        ParameterSpec(BATTERY_VOLTAGE, MetricCategory.POWER, "Напряжение аккумулятора", Aggregation.MEAN_NONZERO),
        ParameterSpec(POWER, MetricCategory.POWER, "Питание (1 — есть)", Aggregation.MIN),
        ParameterSpec(IGNITION, MetricCategory.POWER, "Зажигание", Aggregation.MAX),
        ParameterSpec(IGNITION_CAN, MetricCategory.POWER, "Зажигание CAN", Aggregation.MAX),

        ParameterSpec(RPM, MetricCategory.ENGINE, "Обороты"),
        ParameterSpec(COOLANT_TEMP, MetricCategory.ENGINE, "Температура ОЖ", Aggregation.MAX),
        ParameterSpec(OIL_PRESSURE, MetricCategory.ENGINE, "Давление масла"),
        ParameterSpec("EngineLOAD", MetricCategory.ENGINE, "Нагрузка двигателя"),
        ParameterSpec("TemperatureBOOST", MetricCategory.ENGINE, "Температура наддува"),
        ParameterSpec("TemperatureOIL", MetricCategory.ENGINE, "Температура масла", Aggregation.MAX),
        ParameterSpec("GazLOAD", MetricCategory.ENGINE, "Педаль газа"),

        ParameterSpec(SPEED, MetricCategory.MOTION, "Скорость"),
        ParameterSpec("SpeedCAN", MetricCategory.MOTION, "Скорость CAN"),
        ParameterSpec(BRAKE_PRESSURE_1, MetricCategory.MOTION, "Тормозной контур 1", Aggregation.MIN_NONZERO),
        ParameterSpec(BRAKE_PRESSURE_2, MetricCategory.MOTION, "Тормозной контур 2", Aggregation.MIN_NONZERO),
    )

    private val curatedByName = curated.associateBy { it.name }

    /** Возвращаемые типы, которые не имеют смысла в таблице/графике (даты, интервалы, координаты, битовые флаги). */
    private val nonNumericReturnTypes = setOf(2, 5, 6, 12)

    private val voltageKeywords = listOf("напряж", "volt", "аккум", "батар", "vbat", "бортсет")

    /**
     * Выбирает параметры прибора для показа: сначала известные из [curated],
     * плюс любые параметры напряжения (у FAW их нет, у Урал NEXT есть BattaryVOLTAGE — появятся, если добавить датчик).
     * Если прибор настроен нестандартно и известных имён нет — классификация по ключевым словам.
     */
    fun select(available: List<RParameter>): Pair<List<ParameterInfo>, Map<String, Aggregation>> {
        val byName = available.associateBy { it.name }
        val chosen = mutableListOf<ParameterInfo>()
        val aggregation = mutableMapOf<String, Aggregation>()

        curated.forEach { spec ->
            val p = byName[spec.name] ?: return@forEach
            chosen += ParameterInfo(p.name, spec.caption ?: p.caption ?: p.name, p.unit?.takeIf { it.isNotBlank() }, spec.category)
            aggregation[p.name] = spec.aggregation
        }

        val numeric = available.filter { it.name !in curatedByName && it.returnType !in nonNumericReturnTypes }
        numeric.filter { p -> voltageKeywords.any { it in "${p.name} ${p.caption}".lowercase() } }.forEach { p ->
            chosen += ParameterInfo(p.name, p.caption ?: p.name, p.unit?.takeIf { it.isNotBlank() }, MetricCategory.POWER)
            aggregation[p.name] = Aggregation.MEAN_NONZERO
        }

        if (chosen.none { it.category != MetricCategory.POWER }) {
            numeric.forEach { p ->
                if (chosen.any { it.name == p.name }) return@forEach
                val category = MetricCategory.classify(p.name, p.caption, p.alias, p.groupName) ?: return@forEach
                if (chosen.count { it.category == category } >= 6) return@forEach
                chosen += ParameterInfo(p.name, p.caption ?: p.name, p.unit?.takeIf { it.isNotBlank() }, category)
                aggregation[p.name] = Aggregation.MEAN
            }
        }
        return chosen to aggregation
    }
}
