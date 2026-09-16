package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.petrovich.telemetry.anomaly.BaselineAnomalyDetector
import ru.petrovich.telemetry.anomaly.FeatureExtractor
import ru.petrovich.telemetry.data.DemoTelemetryRepository
import ru.petrovich.telemetry.data.MetricCategory
import java.time.LocalDateTime

class AnomalyDetectionTest {
    private val repo = DemoTelemetryRepository()
    private val detector = BaselineAnomalyDetector()
    private val to = LocalDateTime.of(2026, 9, 16, 18, 0)

    @Test
    fun demoDataHasAllCategoriesAndAlignedColumns() = runTest {
        val v = repo.vehicles()
        assertEquals(10, v.size)
        val t = repo.telemetry(v[0], to.minusHours(24), to)
        assertEquals(MetricCategory.entries.toSet(), t.tables.keys)
        t.tables.values.forEach { table -> table.columns.forEach { assertEquals(table.timestamps.size, it.values.size) } }
        val features = FeatureExtractor.extract(t)
        assertEquals(7, features.featureNames.size)
        assertEquals(t.tables.values.first().timestamps.size, features.rows.size)
    }

    @Test
    fun noFalsePositivesOnNormalData() = runTest {
        // FAW №1 (demo-0): в демо-данных у неё нет встроенных аномалий.
        val t = repo.telemetry(repo.vehicles()[0], to.minusDays(3), to)
        assertEquals(emptyList<String>(), detector.detect(t).map { it.title })
    }

    @Test
    fun baselineDetectorFindsInjectedAnomalies() = runTest {
        val all = repo.vehicles().flatMap { detector.detect(repo.telemetry(it, to.minusDays(3), to)) }
        all.groupBy { it.vehicleName to it.title }.forEach { (k, list) -> println("${k.first}: ${k.second} x${list.size}") }
        assertTrue("ожидались аномалии в демо-данных", all.isNotEmpty())
        assertEquals(all.size, all.distinctBy { it.id }.size)
    }
}
