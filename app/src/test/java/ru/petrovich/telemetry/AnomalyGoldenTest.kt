package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.petrovich.telemetry.anomaly.BaselineAnomalyDetector
import ru.petrovich.telemetry.data.AutoGraphParameters as P
import ru.petrovich.telemetry.data.CategoryTable
import ru.petrovich.telemetry.data.DemoTelemetryRepository
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.ParameterColumn
import ru.petrovich.telemetry.data.ParameterInfo
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.data.api.ApiFactory
import java.io.File
import java.time.LocalDateTime

/**
 * Эталоны `testdata/golden/anomalies/`: VehicleTelemetry и аномалии, которые находит [BaselineAnomalyDetector].
 * Python-перенос правил на стенде (ml/fleet_service/tests/test_rules_golden.py) обязан найти те же аномалии:
 * id, тип, важность, время события (и значение).
 *
 * Тест сверяет эталоны с кодом и падает при расхождении. После намеренного изменения правил:
 * `UPDATE_GOLDEN=1 ./gradlew testDebugUnitTest --tests "ru.petrovich.telemetry.AnomalyGoldenTest"`,
 * затем — те же изменения в ml/fleet_service/src/fleet_service/rules/.
 */
class AnomalyGoldenTest {
    private val root = File(TripTablesGolden.repoRoot(), "testdata/golden")
    private val dir = File(root, "anomalies")
    private val update = System.getenv("UPDATE_GOLDEN") == "1"
    private val detector = BaselineAnomalyDetector()

    /** Телеметрия случая: встроенная или ссылка на эталон шага 2 (`trip-tables/<имя>.case.json`, поле expected). */
    private class Case(val name: String, val telemetry: VehicleTelemetry, val reference: String? = null)

    private suspend fun cases(): List<Case> {
        val demo = DemoTelemetryRepository()
        val to = LocalDateTime.of(2026, 9, 16, 18, 0) // как в AnomalyDetectionTest
        val vehicles = demo.vehicles()
        // demo-0 — без аномалий; 1 — слив, 2 — просадка напряжения, 3 — перегрев.
        val demoCases = listOf(0, 1, 2, 3).map { i ->
            Case("demo-$i", demo.telemetry(vehicles[i], to.minusDays(3), to))
        }
        val tripCases = File(root, "trip-tables").listFiles { f -> f.name.endsWith(".case.json") }.orEmpty().sorted().map { f ->
            val expected = (ApiFactory.json.parseToJsonElement(f.readText()) as JsonObject).getValue("expected")
            Case(f.name.removeSuffix(".case.json"), ApiFactory.json.decodeFromJsonElement(VehicleTelemetry.serializer(), expected),
                reference = "trip-tables/${f.name}")
        }
        val synthetic = listOf(
            Case("rules-ural", rulesUral()), Case("rules-12v", rules12v()), Case("rules-can-ignition", rulesCanIgnitionWithoutRpm()),
        )
        return demoCases + synthetic + tripCases
    }

    @Test
    fun goldenFilesMatchKotlinRules() = runTest {
        if (update) dir.mkdirs()
        val stale = mutableListOf<String>()
        var total = 0
        cases().forEach { case ->
            val anomalies = detector.detect(case.telemetry)
            total += anomalies.size
            val json = buildJsonObject {
                put("telemetry", case.reference?.let { JsonPrimitive(it) }
                    ?: ApiFactory.json.encodeToJsonElement(VehicleTelemetry.serializer(), case.telemetry))
                put("expected", JsonArray(anomalies.map(TripTablesGolden::anomalyJson)))
            }
            val file = File(dir, "${case.name}.case.json")
            val text = TripTablesGolden.format(json) + "\n"
            if (update) file.writeText(text) else if (!file.exists() || file.readText() != text) stale += file.name
        }
        assertTrue("эталоны без аномалий проверяют немного", total > 20)
        assertEquals(
            "Эталоны устарели — UPDATE_GOLDEN=1 ./gradlew testDebugUnitTest --tests \"ru.petrovich.telemetry.AnomalyGoldenTest\"",
            emptyList<String>(), stale,
        )
    }

    @Test
    fun syntheticCasesCoverEveryRuleAndSeverity() = runTest {
        val found = listOf(rulesUral(), rules12v(), rulesCanIgnitionWithoutRpm()).flatMap { detector.detect(it) }
        assertEquals(setOf("drain", "drop", "power", "volt", "overheat", "oil", "brake"), found.map { it.kind }.toSet())
        assertEquals(ru.petrovich.telemetry.anomaly.Severity.entries.toSet(), found.map { it.severity }.toSet())
    }


    // --- синтетические случаи на каждое правило ---

    private val start = LocalDateTime.of(2026, 9, 16, 6, 0)
    private val minutes = 6 * 60

    private fun telemetry(vehicle: Vehicle, columns: List<Pair<ParameterInfo, List<Double?>>>): VehicleTelemetry {
        val times = List(minutes + 1) { start.plusMinutes(it.toLong()) }
        val tables = columns.groupBy { it.first.category }.mapValues { (category, cols) ->
            CategoryTable(category, times, cols.map { (p, values) -> ParameterColumn(p, values) })
        }
        return VehicleTelemetry(vehicle, times.first(), times.last(), tables)
    }

    private fun series(f: (Int) -> Double?): List<Double?> = List(minutes + 1, f)

    private fun p(name: String, caption: String, unit: String?, category: MetricCategory) = ParameterInfo(name, caption, unit, category)

    /** Урал NEXT (24 В): все правила, границы длительностей, зажигание, пропуски данных. */
    private fun rulesUral(): VehicleTelemetry {
        // Зажигание выключено 120–179 мин; обороты при этом «замирают» на 1350.
        fun on(i: Int) = i !in 120..179
        val rpm = series { i -> when { !on(i) -> 1350.0; i in 300..320 -> 700.0; else -> 1300.0 + (i % 7) * 10 } }
        return telemetry(Vehicle("ural-1", "Урал NEXT А001АА"), listOf(
            p(P.FUEL_LEVEL, "Уровень топлива", "л", MetricCategory.FUEL) to series { i ->
                when {
                    i in 40..42 -> null // пропуск данных
                    i < 60 -> 300.0 - i * 0.1
                    i < 70 -> 260.0 // падение на 34 л — CRITICAL drop
                    i in 80..85 -> 230.0 // второе падение в пределах 60 мин — подавлено
                    i < 200 -> 255.0 - (i - 70) * 0.05
                    i in 200..205 -> 220.0 // падение после охлаждения
                    else -> 248.0 - (i - 200) * 0.05
                }
            },
            p(P.FUEL_DRAIN_VOLUME, "Объём слива", "л", MetricCategory.FUEL) to series { i ->
                when (i) { in 130..132 -> 42.5; 133 -> null; 134 -> 12.0; else -> 0.0 }
            },
            p(P.BATTERY_VOLTAGE, "Напряжение аккумулятора", "В", MetricCategory.POWER) to series { i ->
                when {
                    !on(i) -> 0.0 // CAN при выключенном зажигании — «нет данных»
                    i in 20..23 -> 23.0 // 4 мин — короче 5 мин, не аномалия
                    i in 30..36 -> 23.5 // WARNING
                    i in 220..227 -> 21.5 // CRITICAL
                    i in 240..246 -> 31.0 // перезаряд WARNING
                    i in 260..266 -> 32.5 // перезаряд CRITICAL
                    else -> 27.4
                }
            },
            p(P.POWER, "Питание (1 — есть)", null, MetricCategory.POWER) to series { i ->
                when (i) { 50 -> 0.0; in 90..93 -> 0.0; 359, 360 -> 0.0; else -> 1.0 }
            },
            p(P.IGNITION, "Зажигание", null, MetricCategory.POWER) to series { i -> if (on(i)) 1.0 else 0.0 },
            p(P.RPM, "Обороты", "об/мин", MetricCategory.ENGINE) to rpm,
            p(P.COOLANT_TEMP, "Температура ОЖ", "°C", MetricCategory.ENGINE) to series { i ->
                when (i) {
                    in 100..102 -> 101.5 // WARNING
                    in 110..111 -> 107.0 // CRITICAL (начало эпизода — значение 107)
                    150 -> 104.0 // 1 мин — короче 2 мин
                    else -> 88.0
                }
            },
            p(P.OIL_PRESSURE, "Давление масла", "кПа", MetricCategory.ENGINE) to series { i ->
                when {
                    !on(i) -> 0.0
                    i in 60..62 -> 85.0 // на оборотах > 800 — CRITICAL
                    i in 305..310 -> 80.0 // на холостых 700 — не аномалия
                    i in 330..332 -> 0.0 // 0 — нет данных
                    else -> 320.0
                }
            },
            p(P.BRAKE_PRESSURE_1, "Тормозной контур 1", "кПа", MetricCategory.MOTION) to series { i ->
                when {
                    i in 0..11 -> 400.0 // первые 12 мин после старта — WARNING (≥ 10 мин)
                    i in 150..175 -> 300.0 // при выключенном зажигании — не аномалия
                    i in 200..207 -> 500.0 // 8 мин — короче 10
                    else -> 800.0
                }
            },
            p(P.BRAKE_PRESSURE_2, "Тормозной контур 2", "кПа", MetricCategory.MOTION) to series { i ->
                when {
                    i in 270..285 -> if (i == 276) null else 450.0 // пропуск делит эпизод: 6 + 9 мин — не аномалия
                    i in 290..302 -> 520.0
                    else -> 780.0
                }
            },
        ))
    }

    /** 12-вольтовая бортсеть по ключевому слову, напряжение без зажигания — правила по оборотам. */
    private fun rules12v(): VehicleTelemetry = telemetry(Vehicle("gaz-1", "ГАЗель NEXT"), listOf(
        p("Vbat", "Напряжение бортсети", "В", MetricCategory.POWER) to series { i ->
            when { i in 10..17 -> 11.6; i in 40..47 -> 10.5; i in 70..77 -> 15.4; i in 100..107 -> 17.0; i in 130..135 -> 3.0; else -> 13.8 }
        },
        p("EngRPM", "Обороты двигателя", "об/мин", MetricCategory.ENGINE) to series { i -> if (i in 60..80) 400.0 else 1500.0 },
        p("CoolantT", "Температура охлаждающей жидкости", "°C", MetricCategory.ENGINE) to series { i -> if (i in 200..210) 103.0 else 85.0 },
        p("FuelLevelX", "Уровень топлива ДУТ", "л", MetricCategory.FUEL) to series { i -> if (i < 300) 60.0 else 30.0 },
    ))

    /** Зажигание только по CAN, оборотов нет: правила масла и тормозов не применяются, напряжение — по зажиганию. */
    private fun rulesCanIgnitionWithoutRpm(): VehicleTelemetry = telemetry(Vehicle("faw-9", "FAW №9"), listOf(
        p(P.IGNITION_CAN, "Зажигание CAN", null, MetricCategory.POWER) to series { i -> if (i in 0..99) 1.0 else 0.0 },
        p(P.BATTERY_VOLTAGE, "Напряжение аккумулятора", "В", MetricCategory.POWER) to series { i ->
            when { i in 50..60 -> 23.0; i in 150..170 -> 23.0; else -> 27.0 }
        },
        p(P.OIL_PRESSURE, "Давление масла", "кПа", MetricCategory.ENGINE) to series { 50.0 },
        p(P.BRAKE_PRESSURE_1, "Тормозной контур 1", "кПа", MetricCategory.MOTION) to series { 300.0 },
    ))
}
