package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.petrovich.telemetry.anomaly.BaselineAnomalyDetector
import ru.petrovich.telemetry.data.AutoGraphParameters
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.TripTablesMapper
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.api.RParameter
import java.io.File
import java.time.Duration
import java.time.LocalDateTime

class TripTablesMapperTest {
    private val vehicle = Vehicle("v1", "FAW №1")

    // Формат как в ответе GetTripTables (у части колонок «Values» раньше «Name», как в реальном API): повторяющееся время, bool, строки-интервалы, 0 у неподключённых датчиков.
    private val sample = """
        {"v1":{"ID":"v1","Name":"FAW №1","Serial":1,"Trips":[{"Index":0,"SD":"2026-09-16T05:00:05Z","ED":"2026-09-16T05:02:00Z",
          "DT":["2026-09-16T10:00:05","2026-09-16T10:00:05","2026-09-16T10:00:35","2026-09-16T10:01:10","2026-09-16T10:03:10"],
          "Values":[
            {"Values":[200.0,200.0,199.0,198.0,150.0],"Name":"TankMainFuelLevel","Caption":"Уровень","Unit":"л"},
            {"Name":"FL1","Caption":"ДУТ 1 шасси","Unit":"л","Values":[200.0,200.0,199.0,198.0,150.0]},
            {"Values":[1,1,0,1,1],"Name":"Power","Caption":"Питание"},
            {"Name":"TemperatureOIL","Caption":"Температура масла","Unit":"°C","Values":[0.0,0.0,0.0,0.0,0.0]},
            {"Name":"DIgnition","Caption":"Зажигание","Values":[true,true,false,true,true]}
          ]}]}}
    """.trimIndent()

    private val params = listOf(
        RParameter("TankMainFuelLevel", "Уровень", unit = "л", returnType = 4),
        RParameter("FL1", "ДУТ 1 шасси", unit = "л", returnType = 4),
        RParameter("Power", "Питание", returnType = 0),
        RParameter("TemperatureOIL", "Температура масла", unit = "°C", returnType = 4),
        RParameter("DIgnition", "Зажигание", returnType = 0),
        RParameter("DIgnitionOnParks", "Накоп. МЧ ост.", returnType = 6),
    )

    @Test
    fun aggregatesIntoBucketsAndDropsDuplicatesAndEmptySensors() {
        val (selected, aggregation) = AutoGraphParameters.select(params)
        assertEquals(listOf("TankMainFuelLevel", "FL1", "Power", "DIgnition", "TemperatureOIL"), selected.map { it.name }.let { names ->
            AutoGraphParameters.curated.map { it.name }.filter { it in names }
        })
        val from = LocalDateTime.of(2026, 9, 16, 10, 0)
        val t = TripTablesMapper.Builder(vehicle, from, from.plusMinutes(4), selected, aggregation, Duration.ofMinutes(1))
            .apply { read(sample.reader()) }
            .build()

        val fuel = t.tables.getValue(MetricCategory.FUEL)
        assertEquals(5, fuel.timestamps.size)
        assertEquals(listOf("TankMainFuelLevel"), fuel.columns.map { it.parameter.name }) // FL1 — дубликат
        assertEquals(listOf(199.7, 198.0, null, 150.0, null), fuel.columns[0].values)

        val power = t.tables.getValue(MetricCategory.POWER).columns.first { it.parameter.name == "Power" }
        assertEquals(0.0, power.values[0]) // MIN: пропадание питания не усредняется
        assertNull(t.tables[MetricCategory.ENGINE]) // TemperatureOIL всегда 0 — датчик не подключён
    }

    @Test
    fun valuesBeforeDtAreBuffered() {
        val json = """{"v1":{"Trips":[{"Values":[{"Name":"Speed","Values":[10,"20",null,"00:00:10"]}],
            "DT":["2026-09-16T10:00:00","2026-09-16T10:00:30","2026-09-16T10:01:00","2026-09-16T10:01:30"]}]}}"""
        val (selected, aggregation) = AutoGraphParameters.select(listOf(RParameter("Speed", "Текущая", unit = "км/ч", returnType = 4)))
        val from = LocalDateTime.of(2026, 9, 16, 10, 0)
        val t = TripTablesMapper.Builder(vehicle, from, from.plusMinutes(2), selected, aggregation, Duration.ofMinutes(1))
            .apply { read(json.reader()) }.build()
        assertEquals(listOf(15.0, null, null), t.tables.getValue(MetricCategory.MOTION).columns[0].values)
    }

    @Test
    fun fastDateParsingMatchesLocalDateTime() {
        val samples = listOf(
            "2026-09-16T10:00:05", "2026-09-16T23:59:59.123", "2026-09-16T05:00:05Z",
            "2024-02-29T12:30:00", "2026-01-01T00:00:00+05:00", "1999-12-31T23:59:59",
        )
        samples.forEach { s ->
            val expected = TripTablesMapper.parseDateTime(s)!!.toEpochSecond(java.time.ZoneOffset.UTC)
            assertEquals(s, expected, TripTablesMapper.fastEpochSecond(s))
        }
        listOf("2026-02-30T10:00:00", "2026-13-01T10:00:00", "2026-09-16 10:00:00", "00:00:10", "20260916-1000")
            .forEach { assertNull(it, TripTablesMapper.fastEpochSecond(it)) }
    }

    @Test
    fun oversizedResponseIsRejected() {
        val huge = buildString {
            append("""{"v1":{"Trips":[{"DT":[""")
            repeat(TripTablesMapper.MAX_POINTS + 1) { if (it > 0) append(','); append("\"2026-09-16T10:00:00\"") }
            append("]}]}}")
        }
        val (selected, aggregation) = AutoGraphParameters.select(listOf(RParameter("Speed", returnType = 4)))
        val from = LocalDateTime.of(2026, 9, 16, 10, 0)
        val builder = TripTablesMapper.Builder(vehicle, from, from.plusHours(1), selected, aggregation)
        val error = runCatching { builder.read(huge.reader()) }.exceptionOrNull()
        assertTrue("ожидалась IOException, получено $error", error is java.io.IOException)
    }

    /**
     * Проверка на реальных ответах API (по одной машине в файле):
     * REAL_TRIP_TABLES=/path/to/dir ./gradlew testDebugUnitTest
     */
    @Test
    fun realResponses() = runTest {
        val dir = System.getenv("REAL_TRIP_TABLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val names = AutoGraphParameters.curated.map { it.name }
        val (selected, aggregation) = AutoGraphParameters.select(names.map { RParameter(it, returnType = 4) })
        dir!!.listFiles { f -> f.extension == "json" }!!.sorted().forEach { file ->
            val first = Regex("\"DT\":\\s*\\[\"([^\"]+)\"").find(file.readText())?.groupValues?.get(1) ?: return@forEach
            val from = TripTablesMapper.parseDateTime(first)!!.withMinute(0).withSecond(0)
            val t = TripTablesMapper.Builder(Vehicle(file.name, file.nameWithoutExtension), from, from.plusHours(24), selected, aggregation)
                .apply { file.bufferedReader().use { read(it) } }.build()
            val anomalies = BaselineAnomalyDetector().detect(t)
            println("${file.name}: rows=${t.tables.values.firstOrNull()?.timestamps?.size} " +
                "columns=${t.tables.values.flatMap { it.columns }.map { it.parameter.name }} anomalies=${anomalies.map { "${it.severity}:${it.title}@${it.eventTime}:${it.value}" }}")
        }
    }
}
