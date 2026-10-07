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

    // Образцы общие с эталонами для стенда (TripTablesGolden).
    private val sample = TripTablesGolden.SAMPLE
    private val params = TripTablesGolden.SAMPLE_PARAMS

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
        val json = TripTablesGolden.VALUES_BEFORE_DT
        val (selected, aggregation) = AutoGraphParameters.select(TripTablesGolden.VALUES_BEFORE_DT_PARAMS)
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
     *
     * Заодно пишет эталоны для сверки с Python-портом стенда в REAL_TRIP_TABLES_OUT (по умолчанию
     * app/build/real-trip-tables); сверка — ml/fleet_service/scripts/compare_real.sh.
     */
    @Test
    fun realResponses() = runTest {
        val dir = System.getenv("REAL_TRIP_TABLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val out = (System.getenv("REAL_TRIP_TABLES_OUT")?.let(::File) ?: File("build/real-trip-tables")).apply { mkdirs() }
        val parameters = AutoGraphParameters.curated.map { RParameter(it.name, returnType = 4) }
        dir!!.listFiles { f -> f.extension == "json" }!!.sorted().forEach { file ->
            val first = Regex("\"DT\":\\s*\\[\"([^\"]+)\"").find(file.readText())?.groupValues?.get(1) ?: return@forEach
            val from = TripTablesMapper.parseDateTime(first)!!.withMinute(0).withSecond(0)
            val vehicle = Vehicle(file.name, file.nameWithoutExtension)
            val t = TripTablesGolden.build(vehicle, from, from.plusHours(24), null, parameters, listOf(file))
            val anomalies = BaselineAnomalyDetector().detect(t)
            println("${file.name}: rows=${t.tables.values.firstOrNull()?.timestamps?.size} " +
                "columns=${t.tables.values.flatMap { it.columns }.map { it.parameter.name }} anomalies=${anomalies.map { "${it.severity}:${it.title}@${it.eventTime}:${it.value}" }}")
            val case = TripTablesGolden.caseJson(vehicle, from, from.plusHours(24), null, parameters, listOf(file.absolutePath), t)
            File(out, "${file.nameWithoutExtension}.case.json")
                .writeText(TripTablesGolden.format(case) + "\n")
        }
    }
}
