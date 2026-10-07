package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.CheckRequest
import ru.petrovich.telemetry.anomaly.ServerVehicleChecker
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.anomaly.SwitchingVehicleChecker
import ru.petrovich.telemetry.anomaly.VehicleChecker
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandSession
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.AppSettings
import java.io.File
import java.time.LocalDateTime

class VehicleCheckerTest {
    private lateinit var server: MockWebServer
    private val vehicle = Vehicle("42", "Урал NEXT А001АА")
    private val to = LocalDateTime.of(2026, 9, 16, 18, 0)
    private val logged = mutableListOf<String>()

    @Before fun start() { server = MockWebServer().apply { start() } }

    @After fun stop() = server.shutdown()

    private fun checker() = ServerVehicleChecker(
        StandClient({ server.url("/").toString() }, { StandSession("token", "schema-1") }, OkHttpClient()),
        utcOffsetMinutes = { 300 },
        log = { logged += it },
    )

    private fun fixture(name: String) = File(TripTablesGolden.repoRoot(), "testdata/contract/$name").readText()

    /** Тот же JSON проверяет tests/test_contract.py стенда. */
    @Test
    fun requestMatchesSharedContractFixture() {
        val request = checker().request(vehicle, to.minusHours(24), to)
        assertEquals(
            Json.parseToJsonElement(fixture("check-request.json")),
            ApiFactory.json.encodeToJsonElement(CheckRequest.serializer(), request),
        )
    }

    @Test
    fun standResponseIsDecodedAndAnalyticsStatusLogged() = runTest {
        server.enqueue(MockResponse().setBody(fixture("check-response.json")))
        val anomalies = checker().check(vehicle, to.minusHours(24), to)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/anomalies/check", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        val a = anomalies.single()
        assertEquals("rule|overheat|42|TemperatureCOOL|2026-09-16T10:30", a.id)
        assertEquals("overheat", a.kind)
        assertEquals(Severity.CRITICAL, a.severity)
        assertEquals(MetricCategory.ENGINE, a.category)
        assertEquals(108.0, a.value!!, 0.0)
        // Решения и отметки — только в приложении: со стенда аномалия приходит неразобранной.
        assertEquals(null, a.resolution)
        assertTrue(logged.single().contains("предиктивная аналитика: модель не загружена"))
    }

    @Test
    fun periodIsTruncatedAndVehicleNameIsTakenFromApp() = runTest {
        server.enqueue(MockResponse().setBody(fixture("check-response.json")))
        val now = to.withSecond(42).withNano(7)
        val renamed = Vehicle("42", "Урал (как в приложении)")
        val anomalies = checker().check(renamed, now.minusHours(3), now)
        // Секунды отброшены, как у телеметрии: проверка попадает в кэш телеметрии стенда.
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).toString()
        assertTrue(body, body.contains("\"from\":\"2026-09-16T15:00:00\"") && body.contains("\"to\":\"2026-09-16T18:00:00\""))
        assertEquals("Урал (как в приложении)", anomalies.single().vehicleName)
    }

    @Test
    fun standErrorsAreShownAsText() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"AutoGRAPH недоступен: нет ответа после 3 попыток"}"""))
        assertFailsWith("AutoGRAPH недоступен: нет ответа после 3 попыток") { checker().check(vehicle, to.minusHours(3), to) }
        server.enqueue(MockResponse().setBody("""{"anomalies":"нет"}"""))
        assertFailsWith("Стенд вернул данные в неожиданном формате") { checker().check(vehicle, to.minusHours(3), to) }
    }

    private fun anomaly(id: String, severity: Severity = Severity.WARNING) = Anomaly(
        id = id, vehicleId = "42", vehicleName = "Урал", category = MetricCategory.ENGINE, parameterName = "p",
        parameterCaption = "p", eventTime = "2026-09-16T10:00", detectedAt = 0, severity = severity, title = "t",
        description = "d", source = "s",
    )

    private class Fake(private val result: () -> List<Anomaly>) : VehicleChecker {
        var calls = 0
        override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> {
            calls++
            return result()
        }
    }

    @Test
    fun switchFollowsSettings() = runTest {
        var settings = AppSettings()
        val local = Fake { listOf(anomaly("rule|a")) }
        val remote = Fake { listOf(anomaly("rule|b")) }
        val switching = SwitchingVehicleChecker({ settings }, local, remote, compareAllowed = true, log = { logged += it })

        assertEquals("rule|a", switching.check(vehicle, to, to).single().id)
        settings = AppSettings(anomaliesViaServer = true) // без адреса стенда — устройство
        assertEquals("rule|a", switching.check(vehicle, to, to).single().id)
        settings = settings.copy(serverUrl = "https://stand")
        assertEquals("rule|b", switching.check(vehicle, to, to).single().id)
        settings = settings.copy(demoMode = true) // демо-режим: новые пути не действуют
        assertEquals("rule|a", switching.check(vehicle, to, to).single().id)
        assertEquals(3 to 1, local.calls to remote.calls)
        assertTrue(logged.isEmpty())
    }

    @Test
    fun compareModeRunsBothLogsDifferencesAndReturnsChosen() = runTest {
        val local = Fake { listOf(anomaly("rule|same"), anomaly("rule|sev", Severity.WARNING), anomaly("rule|local")) }
        val remote = Fake { listOf(anomaly("rule|same"), anomaly("rule|sev", Severity.CRITICAL), anomaly("ml|x")) }
        var settings = AppSettings(serverUrl = "https://stand", anomalyCompare = true)
        val switching = SwitchingVehicleChecker({ settings }, local, remote, compareAllowed = true, log = { logged += it })

        assertEquals(listOf("rule|same", "rule|sev", "rule|local"), switching.check(vehicle, to, to).map { it.id })
        settings = settings.copy(anomaliesViaServer = true)
        assertEquals(listOf("rule|same", "rule|sev", "ml|x"), switching.check(vehicle, to, to).map { it.id })
        assertEquals(2 to 2, local.calls to remote.calls)
        assertEquals(2, logged.size)
        assertEquals(
            "сравнение Урал NEXT А001АА: РАСХОЖДЕНИЕ — только на устройстве [rule|local]; только на стенде [ml|x]; " +
                "важность [rule|sev: WARNING ≠ CRITICAL]",
            logged[0],
        )
        // Отладка выключена в release-сборке.
        logged.clear()
        val release = SwitchingVehicleChecker({ settings }, local, remote, compareAllowed = false, log = { logged += it })
        release.check(vehicle, to, to)
        assertTrue(logged.isEmpty())
    }

    @Test
    fun compareModeFailureOfOtherSideIsOnlyLogged() = runTest {
        val local = Fake { listOf(anomaly("rule|a")) }
        val remote = Fake { throw StandException("Стенд недоступен") }
        val settings = AppSettings(serverUrl = "https://stand", anomalyCompare = true)
        val switching = SwitchingVehicleChecker({ settings }, local, remote, compareAllowed = true, log = { logged += it })
        assertEquals("rule|a", switching.check(vehicle, to, to).single().id)
        assertEquals("сравнение Урал NEXT А001АА: на стенде ошибка — Стенд недоступен", logged.single())
        // Выбран стенд, и он недоступен — ошибка видна пользователю, как без сравнения.
        val onServer = SwitchingVehicleChecker({ settings.copy(anomaliesViaServer = true) }, local, remote, true) {}
        assertFailsWith("Стенд недоступен") { onServer.check(vehicle, to, to) }
    }

    private suspend fun assertFailsWith(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("ожидалась ошибка «$message»")
        } catch (e: StandException) {
            assertEquals(message, e.message)
        }
    }
}
