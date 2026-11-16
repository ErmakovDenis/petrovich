package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
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
import ru.petrovich.telemetry.anomaly.ApiAnomalyChecker
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.anomaly.SwitchingVehicleChecker
import ru.petrovich.telemetry.anomaly.VehicleChecker
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.api.PetrovichAi
import ru.petrovich.telemetry.data.settings.AppSettings
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ApiAnomalyCheckerTest {
    private lateinit var server: MockWebServer
    private val vehicle = Vehicle("42", "Урал NEXT А001АА")
    private val to = LocalDateTime.of(2026, 11, 13, 18, 0)

    @Before fun start() { server = MockWebServer().apply { start() } }

    @After fun stop() = server.shutdown()

    private fun api(credentials: () -> Pair<String, String> = { "op" to "secret" }) =
        PetrovichAi.create(server.url("/").toString(), credentials, OkHttpClient())

    private fun checker() = ApiAnomalyChecker(api()) { it.id.toInt() }

    private fun anomalyJson(
        id: Int = 7,
        kind: String = "fuel_drain",
        severity: String = "high",
        ts: String = "2026-11-13T10:30:00Z",
    ) = """{"id":$id,"vehicle_id":42,"ts":"$ts","kind":"$kind","severity":"$severity",""" +
        """"description":"Слив топлива 30 л","created_at":"2026-11-13T10:35:00Z","status":"new"}"""

    private fun eventJson(
        id: Long = 27913,
        kind: String = "fuel_drain",
        severity: String = "med",
        status: String = "new",
    ) = """{"id":$id,"vehicle_id":166,"vehicle":"Камаз","organization_id":2,"organization":"Организация №1",""" +
        """"kind":"$kind","title":"$kind","severity":"$severity","status":"$status",""" +
        """"started_at":"2026-11-12T20:31:00+05:00","ended_at":"2026-11-12T23:59:00+05:00",""" +
        """"volume_l":180.75965854999998,"metric":180.75965854999998,"metric_unit":"l",""" +
        """"producer_name":"fuel_drain_rules","producer_version":"prototype-v3","is_synthetic":false}"""

    @Test
    fun eventsFeedIsPagedAndMapped() = runTest {
        server.enqueue(MockResponse().setBody("""{"total":2,"items":[${eventJson()}]}"""))
        server.enqueue(MockResponse().setBody("""{"total":2,"items":[${eventJson(id = 27898)}]}"""))
        val all = checker().checkAll(to.minusDays(1), to)

        assertEquals(2, all.size)
        val first = server.takeRequest()
        assertEquals("GET", first.method)
        assertTrue(first.path, first.path!!.startsWith("/api/v1/anomaly-events"))
        assertTrue(first.path, first.path!!.contains("date_from=") && first.path!!.contains("offset=0"))
        assertTrue(server.takeRequest().path!!.contains("offset=1"))

        val a = all.first()
        assertEquals("api|fuel_drain|27913", a.id)
        assertEquals("fuel_drain", a.kind)
        assertEquals("Камаз", a.vehicleName)
        assertEquals("166", a.vehicleId)
        assertEquals("Слив топлива", a.title)
        assertEquals(Severity.WARNING, a.severity) // med
        assertEquals(MetricCategory.FUEL, a.category)
        assertEquals(null, a.resolution)
        assertEquals(180.75965854999998, a.value!!, 0.0)
        assertTrue(a.description, a.description.contains("объём 180,8 л") || a.description.contains("объём 180.8 л"))
        // Время начала переведено из +05:00 в местное.
        assertEquals(
            OffsetDateTime.parse("2026-11-12T20:31:00+05:00").atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
            a.eventTime,
        )
    }

    @Test
    fun eventStatusMapsToResolution() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"total":2,"items":[""" +
                    eventJson(id = 1, status = "confirmed") + "," +
                    eventJson(id = 2, status = "dismissed") +
                    "]}",
            ),
        )
        val all = checker().checkAll(to, to)
        assertEquals(Resolution.CONFIRMED, all[0].resolution)
        assertEquals(Resolution.FALSE_ALARM, all[1].resolution)
    }

    @Test
    fun healthIsDecodedForTheSettingsCheck() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok","service":"petrovich"}"""))
        assertEquals("ok", api().health().status)
        assertEquals("/health", server.takeRequest().path)
    }

    @Test
    fun basicAuthIsSentAndAnomalyIsMapped() = runTest {
        server.enqueue(MockResponse().setBody("[${anomalyJson()}]"))
        val anomalies = checker().check(vehicle, to.minusHours(24), to)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/anomalies/detect/42", request.path)
        assertEquals("Basic b3A6c2VjcmV0", request.getHeader("Authorization"))

        val a = anomalies.single()
        assertEquals("api|fuel_drain|7", a.id)
        assertEquals("fuel_drain", a.kind)
        assertEquals(Severity.CRITICAL, a.severity)
        assertEquals(MetricCategory.FUEL, a.category)
        assertEquals(vehicle.id, a.vehicleId)
        assertEquals(vehicle.name, a.vehicleName)
        assertEquals("API Петрович AI", a.source)
        // Время события переведено из UTC в местное, как у остальных аномалий приложения.
        assertEquals(
            LocalDateTime.ofInstant(Instant.parse("2026-11-13T10:30:00Z"), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")),
            a.eventTime,
        )
        assertEquals(Instant.parse("2026-11-13T10:35:00Z").toEpochMilli(), a.detectedAt)
    }

    @Test
    fun severityAndEmptyListAreMapped() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "[" +
                    anomalyJson(id = 1, kind = "towing", severity = "med") + "," +
                    anomalyJson(id = 2, kind = "data_gap", severity = "low") +
                    "]",
            ),
        )
        val anomalies = checker().check(vehicle, to, to)
        assertEquals(listOf(Severity.WARNING, Severity.INFO), anomalies.map { it.severity })
        assertEquals("Слив топлива 30 л", anomalies.first().title)

        server.enqueue(MockResponse().setBody("[]"))
        assertTrue(checker().check(vehicle, to, to).isEmpty())
    }

    @Test
    fun httpErrorsBecomeReadableText() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        assertFailsWith("API аномалий не принял логин/пароль — проверьте их в настройках") { checker().check(vehicle, to, to) }
        server.enqueue(MockResponse().setResponseCode(404))
        assertFailsWith("Урал NEXT А001АА: машина не найдена в API аномалий") { checker().check(vehicle, to, to) }
        server.enqueue(MockResponse().setResponseCode(500))
        assertFailsWith("API аномалий ответил ошибкой 500") { checker().check(vehicle, to, to) }
    }

    @Test
    fun blankCredentialsStopRequestBeforeNetwork() = runTest {
        val checking = ApiAnomalyChecker(api { "" to "" }) { it.id.toInt() }
        try {
            checking.check(vehicle, to, to)
            fail("ожидалась ошибка про незаданные логин/пароль")
        } catch (e: StandException) {
            assertEquals("В настройках не заданы логин/пароль API детекции аномалий", e.message)
        }
        assertEquals(0, server.requestCount)
    }

    private class Fake(private val result: List<Anomaly>) : VehicleChecker {
        var calls = 0
        override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> {
            calls++
            return result
        }
    }

    /** Источник с готовой лентой: отвечает на checkAll, по машинам не проверяется. */
    private class FleetFake(private val result: List<Anomaly>) : VehicleChecker {
        var calls = 0
        override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> =
            error("ленту парка проверяют одним запросом")
        override suspend fun checkAll(from: LocalDateTime, to: LocalDateTime): List<Anomaly> {
            calls++
            return result
        }
    }

    @Test
    fun switchFetchesFleetEventsOnlyWhenApiEnabled() = runTest {
        val fleet = FleetFake(listOf(anomaly("api|fuel_drain|7")))
        var settings = AppSettings()
        val switching = SwitchingVehicleChecker(
            { settings }, Fake(emptyList()), Fake(emptyList()), compareAllowed = false, log = {}, api = fleet,
        )
        // API выключен — ленты парка нет, работаем по машинам, как раньше.
        assertEquals(null, switching.checkAll(to, to))
        assertEquals(0, fleet.calls)
        // Включён с кредами — лента за окно одним запросом.
        settings = AppSettings(anomalyApiEnabled = true, anomalyApiUser = "op", anomalyApiPassword = "secret")
        assertEquals(listOf("api|fuel_drain|7"), switching.checkAll(to, to)?.map { it.id })
        assertEquals(1, fleet.calls)
        // Хранилище на стенде в приоритете — лента API не используется.
        val stored = Fake(listOf(anomaly("stand|x")))
        val onStand = AppSettings(
            serverUrl = "https://stand", anomaliesViaServer = true, anomalyStoreViaServer = true,
            anomalyApiEnabled = true, anomalyApiUser = "op", anomalyApiPassword = "secret",
        )
        val switchingStored = SwitchingVehicleChecker(
            { onStand }, Fake(emptyList()), Fake(emptyList()), compareAllowed = false, stored = stored, log = {}, api = fleet,
        )
        assertEquals(null, switchingStored.checkAll(to, to))
    }

    private fun anomaly(id: String) = Anomaly(
        id = id, vehicleId = "42", vehicleName = "Урал", category = MetricCategory.ENGINE, parameterName = "p",
        parameterCaption = "p", eventTime = "2026-11-13T10:00", detectedAt = 0, severity = Severity.WARNING,
        title = "t", description = "d", source = "s",
    )

    @Test
    fun switchPrefersApiWhenEnabled() = runTest {
        var settings = AppSettings()
        val local = Fake(listOf(anomaly("rule|a")))
        val remote = Fake(listOf(anomaly("rule|b")))
        val api = Fake(listOf(anomaly("api|fuel_drain|7")))
        val switching = SwitchingVehicleChecker(
            { settings }, local, remote, compareAllowed = false, log = {}, api = api,
        )

        // Выключено — устройство, API не трогаем.
        assertEquals("rule|a", switching.check(vehicle, to, to).single().id)
        // Креды не заданы — API не действует.
        settings = AppSettings(anomalyApiEnabled = true)
        assertEquals("rule|a", switching.check(vehicle, to, to).single().id)
        // Включено с кредами — проверку делает API.
        settings = AppSettings(anomalyApiEnabled = true, anomalyApiUser = "op", anomalyApiPassword = "secret")
        assertEquals("api|fuel_drain|7", switching.check(vehicle, to, to).single().id)
        // Демо-режим: новые пути не действуют.
        settings = settings.copy(demoMode = true)
        assertEquals("rule|a", switching.check(vehicle, to, to).single().id)
        assertEquals(3, local.calls)
        assertEquals(0, remote.calls) // адрес стенда в тесте не задан — до него очередь не доходит
        assertEquals(1, api.calls)
    }

    /** API, который падает на любом обращении и считает их: лента и точечная детекция. */
    private class FailingApi : VehicleChecker {
        var feedCalls = 0
        var pointCalls = 0
        override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> {
            pointCalls++
            throw StandException("API аномалий недоступен: проверьте сеть")
        }
        override suspend fun checkAll(from: LocalDateTime, to: LocalDateTime): List<Anomaly> {
            feedCalls++
            throw StandException("API аномалий недоступен: проверьте сеть")
        }
    }

    @Test
    fun apiFailureFallsBackToThresholdRules() = runTest {
        var now = 1_000_000L
        val logs = mutableListOf<String>()
        val api = FailingApi()
        val local = Fake(listOf(anomaly("rule|a")))
        val settings = AppSettings(anomalyApiEnabled = true, anomalyApiUser = "op", anomalyApiPassword = "secret")
        val switching = SwitchingVehicleChecker(
            { settings }, local, Fake(emptyList()), compareAllowed = false, api = api,
            log = { logs += it }, clock = { now },
        )

        // Лента не пришла — сверка продолжается по машинам старым путём (пороговые правила).
        assertEquals(null, switching.checkAll(to, to))
        assertEquals(listOf("rule|a"), switching.check(vehicle, to, to).map { it.id })
        // Повторных обращений к упавшему API нет: ни к ленте, ни к точечной детекции по машинам.
        assertEquals(1, api.feedCalls)
        assertEquals(0, api.pointCalls)
        assertTrue(logs.single().contains("пороговыми правилами"))

        // После паузы API пробуется снова.
        now += SwitchingVehicleChecker.API_RETRY_PAUSE.toMillis() + 1
        assertEquals(null, switching.checkAll(to, to))
        assertEquals(2, api.feedCalls)
    }

    @Test
    fun apiPointFailureFallsBackToLocalCheck() = runTest {
        val api = FailingApi()
        val local = Fake(listOf(anomaly("rule|a")))
        val settings = AppSettings(anomalyApiEnabled = true, anomalyApiUser = "op", anomalyApiPassword = "secret")
        val switching = SwitchingVehicleChecker(
            { settings }, local, Fake(emptyList()), compareAllowed = false, api = api, log = {},
        )

        // Точечная детекция недоступна — машина проверяется пороговыми правилами на устройстве.
        assertEquals(listOf("rule|a"), switching.check(vehicle, to, to).map { it.id })
        assertEquals(1, api.pointCalls)
        // Пауза после сбоя действует и на ленту: checkAll API не дёргает.
        assertEquals(null, switching.checkAll(to, to))
        assertEquals(0, api.feedCalls)
    }

    @Test
    fun apiFailureMarksDegradedStateForTheUi() = runTest {
        var now = 1_000_000L
        var settings = AppSettings(anomalyApiEnabled = true, anomalyApiUser = "op", anomalyApiPassword = "secret")
        val marks = mutableListOf<Boolean>()
        var fail = true
        val api = object : VehicleChecker {
            override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime): List<Anomaly> =
                if (fail) throw StandException("API аномалий недоступен: проверьте сеть") else emptyList()
            override suspend fun checkAll(from: LocalDateTime, to: LocalDateTime): List<Anomaly> =
                if (fail) throw StandException("API аномалий недоступен: проверьте сеть") else emptyList()
        }
        val switching = SwitchingVehicleChecker(
            { settings }, Fake(emptyList()), Fake(emptyList()), compareAllowed = false, api = api,
            clock = { now }, onApiDegraded = { marks += it }, log = {},
        )

        // Сбой — отметка для интерфейса; проверка в паузу к API не идёт и отметку не дублирует.
        assertEquals(null, switching.checkAll(to, to))
        switching.check(vehicle, to, to)
        assertEquals(listOf(true), marks)

        // API восстановился — отметка снимается одним вызовом (после паузы повторного обращения).
        settings = settings.copy(anomalyApiDegraded = true)
        fail = false
        now += SwitchingVehicleChecker.API_RETRY_PAUSE.toMillis() + 1
        assertEquals(emptyList<Anomaly>(), switching.checkAll(to, to))
        assertEquals(listOf(true, false), marks)

        // Отметка уже снята — успешные проверки состояние не трогают.
        settings = settings.copy(anomalyApiDegraded = false)
        switching.checkAll(to, to)
        assertEquals(listOf(true, false), marks)
    }

    @Test
    fun storedCheckerKeepsPriorityOverApi() = runTest {
        val settings = AppSettings(
            serverUrl = "https://stand", anomaliesViaServer = true, anomalyStoreViaServer = true,
            anomalyApiEnabled = true, anomalyApiUser = "op", anomalyApiPassword = "secret",
        )
        val stored = Fake(listOf(anomaly("stand|x")))
        val api = Fake(listOf(anomaly("api|fuel_drain|7")))
        val switching = SwitchingVehicleChecker(
            { settings }, Fake(emptyList()), Fake(emptyList()), compareAllowed = false, stored = stored, log = {}, api = api,
        )
        assertEquals("stand|x", switching.check(vehicle, to, to).single().id)
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
