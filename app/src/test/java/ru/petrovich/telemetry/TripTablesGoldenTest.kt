package ru.petrovich.telemetry

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.api.RParameter
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.round
import kotlin.random.Random

/**
 * Эталоны `testdata/golden/trip-tables/`: ответы GetTripTables и VehicleTelemetry, которую строит [TripTablesMapper]
 * приложения. Python-порт стенда (ml/fleet_service/tests/test_golden.py) обязан получить то же самое.
 *
 * Тест сверяет эталоны с текущим кодом и падает при расхождении. После намеренного изменения разбора или свёртки:
 * `UPDATE_GOLDEN=1 ./gradlew testDebugUnitTest --tests "ru.petrovich.telemetry.TripTablesGoldenTest"`,
 * затем — тот же алгоритм в ml/fleet_service/src/fleet_service/telemetry/.
 */
class TripTablesGoldenTest {
    private val dir = File(TripTablesGolden.repoRoot(), "testdata/golden/trip-tables")
    private val update = System.getenv("UPDATE_GOLDEN") == "1"
    private val day = LocalDateTime.of(2026, 9, 16, 0, 0)

    private class Case(
        val name: String,
        val vehicle: Vehicle,
        val from: LocalDateTime,
        val to: LocalDateTime,
        val bucket: Duration?,
        val parameters: List<RParameter>,
        val inputs: List<String>,
    )

    private val cases: List<Case> by lazy {
        val faw = Vehicle("dev-1", "FAW №1", "Колонна 1")
        val ural = Vehicle("dev-2", "Урал NEXT А001АА")
        listOf(
            Case("sample", Vehicle("v1", "FAW №1"), day.withHour(10), day.withHour(10).plusMinutes(4), Duration.ofMinutes(1),
                TripTablesGolden.SAMPLE_PARAMS, listOf(TripTablesGolden.SAMPLE)),
            Case("values-before-dt", Vehicle("v1", "FAW №1"), day.withHour(10), day.withHour(10).plusMinutes(2),
                Duration.ofMinutes(1), TripTablesGolden.VALUES_BEFORE_DT_PARAMS, listOf(TripTablesGolden.VALUES_BEFORE_DT)),
            // 6 ч, начало с секундами: сетка 1 мин от усечённого начала.
            day.withHour(8).withSecond(30).let { from ->
                Case("synthetic-6h", ural, from, from.plusHours(6), null, standardParams,
                    listOf(synthetic(ural.id, 1, from, from.plusHours(6), stepSeconds = 30)))
            },
            // 12 ч двумя частями по 6 ч, как грузит AutoGraphTelemetryRepository: сетка 2 мин.
            day.withHour(6).let { from ->
                Case("synthetic-12h-two-chunks", faw, from, from.plusHours(12), null, standardParams.filter { it.name != "BattaryVOLTAGE" },
                    listOf(
                        synthetic(faw.id, 2, from, from.plusHours(6), stepSeconds = 40),
                        synthetic(faw.id, 3, from.plusHours(6), from.plusHours(12), stepSeconds = 40),
                    ))
            },
            // 3 и 7 дней: сетки 5 и 15 мин (точки реже, чтобы эталон оставался небольшим).
            day.minusDays(3).let { from ->
                Case("synthetic-3d", ural, from, day, null, standardParams,
                    listOf(synthetic(ural.id, 4, from, day, stepSeconds = 600)))
            },
            day.minusDays(7).let { from ->
                Case("synthetic-7d", faw, from, day, null, standardParams,
                    listOf(synthetic(faw.id, 5, from, day, stepSeconds = 900)))
            },
            // Нестандартный прибор: известных имён нет — разделы по ключевым словам, напряжение — MEAN_NONZERO.
            day.withHour(12).let { from ->
                Case("keyword-classification", Vehicle("dev-3", "Газель"), from, from.plusHours(1), null, keywordParams,
                    listOf(synthetic("dev-3", 6, from, from.plusHours(1), stepSeconds = 20, columns = keywordColumns)))
            },
        )
    }

    @Test
    fun goldenFilesMatchKotlinMapper() {
        if (update) dir.mkdirs()
        val stale = mutableListOf<String>()
        cases.forEach { case ->
            val inputNames = case.inputs.indices.map { "${case.name}.input-${it + 1}.json" }
            case.inputs.zip(inputNames).forEach { (text, name) -> check(File(dir, name), text, stale) }
            val expected = TripTablesGolden.build(
                case.vehicle, case.from, case.to, case.bucket, case.parameters, inputNames.map { File(dir, it) },
            )
            assertTrue("${case.name}: пустой эталон", expected.tables.isNotEmpty())
            val json = TripTablesGolden.caseJson(
                case.vehicle, case.from, case.to, case.bucket, case.parameters, inputNames, expected,
            )
            check(File(dir, "${case.name}.case.json"), TripTablesGolden.format(json) + "\n", stale)
        }
        assertEquals(
            "Эталоны устарели — UPDATE_GOLDEN=1 ./gradlew testDebugUnitTest --tests \"ru.petrovich.telemetry.TripTablesGoldenTest\"",
            emptyList<String>(), stale,
        )
    }

    /** Эталон, записанный Kotlin, читается обратно в те же объекты — значит, приложение прочтёт и ответ стенда. */
    @Test
    fun expectedTelemetryRoundTrips() {
        dir.listFiles { f -> f.name.endsWith(".case.json") }.orEmpty().forEach { file ->
            val case = ApiFactory.json.parseToJsonElement(file.readText()) as JsonObject
            val expected = case.getValue("expected")
            val decoded = ApiFactory.json.decodeFromJsonElement(VehicleTelemetry.serializer(), expected)
            assertEquals(file.name, expected, ApiFactory.json.encodeToJsonElement(VehicleTelemetry.serializer(), decoded))
        }
    }

    private fun check(file: File, text: String, stale: MutableList<String>) {
        if (update) {
            file.writeText(text)
        } else if (!file.exists() || file.readText() != text) {
            stale += file.name
        }
    }

    private val standardParams = listOf(
        RParameter("TankMainFuelLevel", "Уровень", unit = "л", returnType = 4),
        RParameter("FL1", "ДУТ 1 шасси", unit = "л", returnType = 4),
        RParameter("FLTankMain", "Уровень (бак)", unit = "л", returnType = 4),
        RParameter("ConsumptionCAN", "Расход CAN", unit = "л", returnType = 4),
        RParameter("TankMainFuelUpVol", "Заправка", unit = "л", returnType = 4),
        RParameter("TankMainFuelDnVol", "Слив", unit = "л", returnType = 4),
        RParameter("BattaryVOLTAGE", "Напряжение АКБ", unit = "В", returnType = 4),
        RParameter("Power", "Питание", returnType = 0),
        RParameter("DIgnition", "Зажигание", returnType = 0),
        RParameter("DIgnitionCAN", "Зажигание CAN", returnType = 0),
        RParameter("Rotation", "Обороты", unit = "об/мин", returnType = 4),
        RParameter("TemperatureCOOL", "Т ОЖ", unit = "°C", returnType = 4),
        RParameter("PressureOIL", "Давление масла", unit = "бар", returnType = 4),
        RParameter("EngineLOAD", "Нагрузка", unit = "%", returnType = 4),
        RParameter("TemperatureOIL", "Т масла", unit = "°C", returnType = 4),
        RParameter("Speed", "Текущая", unit = "км/ч", returnType = 4),
        RParameter("PressureVSBC1", "Контур 1", unit = "бар", returnType = 4),
        RParameter("PressureVSBC2", "Контур 2", unit = "бар", returnType = 4),
        RParameter("DIgnitionOnParks", "Накоп. МЧ ост.", returnType = 6),
        RParameter("LastPosition", "Местоположение", returnType = 12),
    )

    private val keywordParams = listOf(
        RParameter("LLS_A", "Уровень в баке", unit = "л", returnType = 4),
        RParameter("EngRPM", "Обороты двигателя", unit = "об/мин", returnType = 4),
        RParameter("GpsSpeed", "Скорость GPS", unit = "км/ч", returnType = 4),
        RParameter("Vbat", "Напряжение бортсети", unit = "В", returnType = 4),
        RParameter("EngHours", "Моточасы", groupName = "Двигатель", returnType = 6),
        RParameter("Misc", null, alias = "odometer", returnType = 4),
    )

    private val keywordColumns = listOf("LLS_A", "EngRPM", "GpsSpeed", "Vbat", "EngHours", "Misc")

    /**
     * Детерминированный ответ GetTripTables с особенностями реальных данных: повторы времени, точки вне периода,
     * нераспознанное время, стоянки с выключенным зажиганием (CAN-параметры — нули, обороты «замирают»),
     * числа строками с запятой, bool, null, интервалы «00:00:10», колонки с «Values» раньше «Name»,
     * второй трек с «Values» раньше «DT», дубликат ДУТ (FL1 = TankMainFuelLevel), всегда нулевые датчики.
     */
    private fun synthetic(
        deviceId: String, seed: Int, from: LocalDateTime, to: LocalDateTime, stepSeconds: Long,
        columns: List<String> = standardColumns,
    ): String {
        val rnd = Random(seed)
        val dts = mutableListOf<String>()
        val times = mutableListOf<LocalDateTime>()
        var t = from.minusMinutes(5)
        while (t < to.plusMinutes(5)) {
            times += t
            dts += when (rnd.nextInt(60)) {
                0 -> "00:00:10"
                1 -> t.format(ISO) + ".000"
                2 -> t.format(ISO) + "Z"
                else -> t.format(ISO)
            }
            if (rnd.nextInt(8) == 0) { times += t; dts += dts.last() } // повтор времени
            t = t.plusSeconds(stepSeconds + rnd.nextLong(-stepSeconds / 3, stepSeconds / 3 + 1))
        }
        val n = times.size
        // Стоянки: зажигание выключено на 1/5 точек двумя отрезками.
        val parked = BooleanArray(n) { i -> i % (n / 2 + 1) in (n / 6)..(n / 6 + n / 10) }
        var level = 280.0 + seed
        var consumption = 1000.0 * seed
        var rpm = 900.0
        val values = columns.associateWith { mutableListOf<JsonElement>() }
        fun col(name: String, v: JsonElement) { values[name]?.add(v) }
        fun num(x: Double) = JsonPrimitive(round(x * 100) / 100)
        for (i in 0 until n) {
            val on = !parked[i]
            if (on) { level -= 0.0123 + rnd.nextDouble(0.0, 0.01); consumption += 0.031; rpm = 900.0 + rnd.nextDouble(0.0, 800.0) }
            if (i == n * 3 / 4) level -= 60.0 // слив на ходу
            val fuel = num(level + rnd.nextDouble(-0.3, 0.3))
            col("TankMainFuelLevel", fuel)
            col("FL1", fuel)
            col("FLTankMain", fuel)
            col("ConsumptionCAN", if (on) num(consumption) else JsonPrimitive(0))
            col("TankMainFuelUpVol", JsonPrimitive(if (i == n / 3) 120.5 else 0.0))
            col("TankMainFuelDnVol", JsonPrimitive(0))
            col("BattaryVOLTAGE", if (on) num(27.5 + rnd.nextDouble(-0.4, 0.4)) else JsonPrimitive(0.0))
            col("Power", JsonPrimitive(if (rnd.nextInt(40) == 0) 0 else 1))
            col("DIgnition", JsonPrimitive(on))
            col("DIgnitionCAN", if (rnd.nextBoolean()) JsonPrimitive(if (on) "1" else "0") else JsonPrimitive(if (on) 1 else 0))
            col("Rotation", num(rpm)) // при выключенном зажигании обороты «замирают» на последнем значении
            col("TemperatureCOOL", if (on) num(70.0 + i * 20.0 / n + rnd.nextDouble(0.0, 3.0)) else JsonPrimitive(0))
            col("PressureOIL", when {
                !on -> JsonPrimitive(0.0)
                rnd.nextInt(5) == 0 -> JsonPrimitive("%.1f".format(java.util.Locale.ROOT, 3.5 + rnd.nextDouble(-0.5, 0.5)).replace('.', ','))
                else -> num(3.5 + rnd.nextDouble(-0.5, 0.5))
            })
            col("EngineLOAD", if (i < n / 2) num(rnd.nextDouble(10.0, 90.0)) else JsonNull)
            col("TemperatureOIL", JsonPrimitive(0.0))
            col("Speed", when {
                !on -> JsonPrimitive(0)
                rnd.nextInt(30) == 0 -> JsonPrimitive("00:00:10")
                rnd.nextInt(10) == 0 -> JsonPrimitive("${rnd.nextInt(0, 80)},5")
                else -> num(rnd.nextDouble(0.0, 85.0))
            })
            col("PressureVSBC1", if (on) num(8.0 + rnd.nextDouble(-0.3, 0.3)) else JsonPrimitive(0))
            col("PressureVSBC2", when (rnd.nextInt(20)) {
                0 -> JsonNull
                1 -> JsonObject(emptyMap())
                else -> if (on) num(7.8 + rnd.nextDouble(-0.3, 0.3)) else JsonPrimitive(0)
            })
            // Нестандартный прибор.
            col("LLS_A", fuel)
            col("EngRPM", if (on) num(rpm) else JsonPrimitive(0))
            col("GpsSpeed", if (on) num(rnd.nextDouble(0.0, 60.0)) else JsonPrimitive(0))
            col("Vbat", if (on) num(12.6 + rnd.nextDouble(-0.2, 0.2)) else JsonPrimitive(0))
            col("EngHours", JsonPrimitive("12:30:00"))
            col("Misc", JsonPrimitive(i))
        }

        fun trip(index: Int, range: IntRange, valuesFirst: Boolean): JsonObject {
            val dt = "DT" to JsonArray(range.map { JsonPrimitive(dts[it]) })
            val cols = "Values" to buildJsonArray {
                columns.forEachIndexed { c, name ->
                    val data = "Values" to JsonArray(range.map { values.getValue(name)[it] })
                    val caption = "Caption" to JsonPrimitive(name)
                    // У части колонок «Values» раньше «Name», как в реальном API.
                    add(JsonObject(if (c % 2 == 0) mapOf(data, "Name" to JsonPrimitive(name), caption) else mapOf("Name" to JsonPrimitive(name), caption, data)))
                }
            }
            val index0 = "Index" to JsonPrimitive(index)
            return JsonObject(if (valuesFirst) mapOf(index0, cols, dt) else mapOf(index0, dt, cols))
        }

        val response = buildJsonObject {
            put(deviceId, buildJsonObject {
                put("ID", deviceId)
                put("Name", "Прибор $deviceId")
                put("Trips",JsonArray(listOf(trip(0, 0 until n / 2, false), trip(1, n / 2 until n, true))))
            })
        }
        return ApiFactory.json.encodeToString(JsonElement.serializer(), response)
    }

    private val standardColumns = standardParams.map { it.name }.filter { it != "LastPosition" && it != "DIgnitionOnParks" }

    companion object {
        private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    }
}
