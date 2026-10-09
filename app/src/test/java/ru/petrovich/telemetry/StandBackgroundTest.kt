package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.AnomalyListResponse
import ru.petrovich.telemetry.anomaly.AnomalyNotifications
import ru.petrovich.telemetry.anomaly.AnomalyStore
import ru.petrovich.telemetry.anomaly.AnomalySync
import ru.petrovich.telemetry.anomaly.BackgroundAccess
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.anomaly.StandAnomalies
import ru.petrovich.telemetry.anomaly.StandBackground
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandSession
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.AppSettings
import java.io.File

/** Фоновая проверка на стенде: выдача и отзыв доступа по настройкам, пароль только по согласию, одно уведомление. */
class StandBackgroundTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val deviceId = "6f1d2c3e-8a4b-4c5d-9e6f-0a1b2c3d4e5f"
    private val on = AppSettings(
        userName = "Петров", password = "secret-1", serverUrl = "https://stand", anomaliesViaServer = true,
        anomalyStoreViaServer = true, backgroundViaServer = true, deviceId = deviceId,
    )
    private var settings = on

    @Before fun start() { server = MockWebServer().apply { start() } }

    @After fun stop() = server.shutdown()

    private fun client() = StandClient({ server.url("/").toString() }, { StandSession("token", "schema-1", "Петров") }, OkHttpClient())

    private fun stand() = StandBackground(client(), utcOffsetMinutes = { 300 })

    private fun access(newId: () -> String = { error("id уже есть") }) =
        BackgroundAccess({ settings }, { settings = it(settings) }, stand(), newId)

    private fun fixture(name: String) = File(TripTablesGolden.repoRoot(), "testdata/contract/$name").readText()

    private fun ok(body: String) = MockResponse().setBody(body)

    private fun status(passwordStored: Boolean, interval: Double = 15.0) = ok(
        """{"enabled":true,"registered":true,"tokenActive":true,"passwordStored":$passwordStored,"intervalMinutes":$interval,"windowHours":3}""",
    )

    private fun anomaly(id: String, severity: Severity = Severity.CRITICAL, detectedAt: Long = 1000) = Anomaly(
        id = id, vehicleId = "42", vehicleName = "Урал NEXT А001АА", category = MetricCategory.ENGINE,
        parameterName = "TemperatureCOOL", parameterCaption = "Т ОЖ", eventTime = "2099-01-01T10:30", detectedAt = detectedAt,
        severity = severity, title = "Перегрев двигателя", description = "d", source = "Базовые правила",
    )

    @Test
    fun grantSendsFixtureRequestAndPasswordOnlyWhenStandHasNone() = runTest {
        settings = on.copy(standPasswordConsent = true)
        server.enqueue(ok(fixture("background-access-response.json")))
        val status = access().sync()!!
        val first = server.takeRequest()
        assertEquals("/v1/background/access", first.path)
        assertEquals("Bearer token", first.getHeader("Authorization"))
        assertEquals(Json.parseToJsonElement(fixture("background-access-request.json")), Json.parseToJsonElement(first.body.readUtf8()))
        assertTrue(status.passwordStored)
        assertEquals(1, server.requestCount)
        assertTrue(settings.standAccessGranted)

        // На стенде пароля нет (первое согласие, другая учётная запись) — второй запрос с паролем.
        server.enqueue(status(passwordStored = false, interval = 7.5))
        server.enqueue(status(passwordStored = true, interval = 7.5))
        access().sync()
        server.takeRequest()
        val withPassword = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("secret-1", withPassword["password"]!!.jsonPrimitive.content)
        assertEquals(8, settings.standScanIntervalMinutes)
    }

    @Test
    fun withoutConsentPasswordNeverLeavesTheDevice() = runTest {
        server.enqueue(status(passwordStored = false))
        access().sync()
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("secret-1"))
        assertEquals(false, Json.parseToJsonElement(body).jsonObject["savePassword"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun turnedOffAccessIsRevokedOnceAndNotWhenNeverGranted() = runTest {
        // Не выдавали — отзывать нечего, на стенд не ходим.
        settings = on.copy(backgroundViaServer = false)
        assertNull(access().sync())
        assertEquals(0, server.requestCount)

        for (off in listOf(
            on.copy(backgroundViaServer = false),
            on.copy(demoMode = true),
            on.copy(backgroundChecks = false),
            on.copy(anomalyStoreViaServer = false),
        )) {
            settings = off.copy(standAccessGranted = true)
            server.enqueue(ok("""{"enabled":true,"registered":false,"intervalMinutes":15.0,"windowHours":3}"""))
            access().sync()
            val request = server.takeRequest()
            assertEquals("/v1/background/access/revoke", request.path)
            assertEquals("""{"deviceId":"$deviceId"}""", request.body.readUtf8())
            assertFalse(settings.standAccessGranted)
        }
    }

    @Test
    fun failedRevokeIsRetriedLater() = runTest {
        settings = on.copy(backgroundViaServer = false, standAccessGranted = true)
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"Стенд перегружен"}"""))
        val access = access()
        assertTrue(runCatching { access.sync() }.isFailure)
        assertTrue(settings.standAccessGranted)
        assertEquals("Стенд перегружен", access.state.value.error)
    }

    @Test
    fun deviceIdIsCreatedOnceAndKept() = runTest {
        settings = on.copy(deviceId = "")
        server.enqueue(status(passwordStored = false))
        server.enqueue(status(passwordStored = false))
        var made = 0
        val access = access { made++; "a1b2c3d4-0000-4000-8000-000000000001" }
        access.sync()
        access.sync()
        assertEquals(1, made)
        assertEquals("a1b2c3d4-0000-4000-8000-000000000001", settings.deviceId)
        assertTrue(server.takeRequest().body.readUtf8().contains(settings.deviceId))
    }

    @Test
    fun claimKeepsOnlyAnomaliesNotShownOnOtherDevices() = runTest {
        server.enqueue(ok(fixture("claim-response.json")))
        val found = listOf(
            anomaly("rule|overheat|42|TemperatureCOOL|2026-09-16T05:30Z"),
            anomaly("rule|drain|42|FuelDrainVolume|2026-09-16T06:10Z"),
        )
        val mine = access().claim(found)
        val request = server.takeRequest()
        assertEquals("/v1/notifications/claim", request.path)
        assertEquals(Json.parseToJsonElement(fixture("claim-request.json")), Json.parseToJsonElement(request.body.readUtf8()))
        assertEquals(listOf("rule|drain|42|FuelDrainVolume|2026-09-16T06:10Z"), mine.map { it.id })

        // Стенд не ответил — уведомляем обо всём (лучше повтор на втором устройстве, чем пропуск).
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(found, access().claim(found))
        // Без фоновой проверки на стенде — как раньше, без запросов.
        settings = on.copy(backgroundViaServer = false)
        assertEquals(found, access().claim(found))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun informationalAndSwitchedOffSeveritiesAreNotNotified() {
        val all = listOf(anomaly("c", Severity.CRITICAL), anomaly("w", Severity.WARNING), anomaly("i", Severity.INFO))
        assertEquals(listOf("c", "w"), AnomalyNotifications.wanted(all, pushCritical = true, pushWarning = true).map { it.id })
        assertEquals(listOf("w"), AnomalyNotifications.wanted(all, pushCritical = false, pushWarning = true).map { it.id })
    }

    @Test
    fun feedRefreshTakesLastScanFromStandOnlyWithBackgroundOnStand() = runTest {
        val seen = mutableListOf<Long?>()
        val store = AnomalyStore(File(tmp.root, "anomalies.json"))
        val sync = AnomalySync({ settings }, store, StandAnomalies(client(), utcOffsetMinutes = { 300 }), File(tmp.root, "report.json"),
            onStandLastScan = { seen += it })
        server.enqueue(ok("""{"total":0,"items":[],"lastScanAt":1789535700000}"""))
        sync.refresh()
        server.enqueue(ok("""{"total":0,"items":[]}"""))
        sync.refresh()
        settings = on.copy(backgroundViaServer = false)
        server.enqueue(ok("""{"total":0,"items":[],"lastScanAt":1}"""))
        sync.refresh()
        assertEquals(listOf(1789535700000L, null), seen)
    }

    @Test
    fun rejectedPasswordIsNotResentButTokenAccessStays() = runTest {
        settings = on.copy(standPasswordConsent = true)
        server.enqueue(status(passwordStored = false))
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"AutoGRAPH не принял логин или пароль — пароль на стенде не сохранён"}"""))
        val access = access()
        val status = access.sync()!!
        // Токен на стенде есть — доступ выдан и будет отозван при выключении; ошибка пароля — в состоянии.
        assertTrue(status.registered && settings.standAccessGranted)
        assertTrue(access.state.value.error!!.startsWith("AutoGRAPH не принял"))
        assertTrue(settings.standPasswordRejected.isNotEmpty())

        // Тот же пароль повторно не уходит (повторы входа могут заблокировать учётную запись).
        server.enqueue(status(passwordStored = false))
        access.sync()
        assertEquals(3, server.requestCount)
        // Пароль сменили в приложении — уходит снова.
        settings = settings.copy(password = "secret-2")
        server.enqueue(status(passwordStored = false))
        server.enqueue(status(passwordStored = true))
        access.sync()
        assertEquals(5, server.requestCount)
    }

    @Test
    fun passwordNotDeliveredBecauseOfNetworkIsRetried() = runTest {
        settings = on.copy(standPasswordConsent = true)
        server.enqueue(status(passwordStored = false))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"AutoGRAPH недоступен"}"""))
        access().sync()
        assertEquals("", settings.standPasswordRejected)
        server.enqueue(status(passwordStored = false))
        server.enqueue(status(passwordStored = true))
        access().sync()
        assertEquals(4, server.requestCount)
    }

    @Test
    fun screenRefreshDoesNotSwallowNotifications() = runTest {
        val store = AnomalyStore(File(tmp.root, "anomalies.json"))
        val sync = AnomalySync({ settings }, store, StandAnomalies(client(), utcOffsetMinutes = { 300 }), File(tmp.root, "report.json"))
        fun list(vararg items: Anomaly) = ok(ApiFactory.json.encodeToString(AnomalyListResponse.serializer(), AnomalyListResponse(items.size, items.toList())))
        val old = anomaly("rule|overheat|42|TemperatureCOOL|2099-01-01T05:30Z", detectedAt = 1000)
        val new = anomaly("rule|drain|42|FuelDrainVolume|2099-01-01T06:10Z", detectedAt = 2000)

        server.enqueue(list(old))
        assertEquals(emptyList<Anomaly>(), sync.refresh())          // точка отсчёта
        // Стенд по расписанию нашёл новое; пользователь открыл сводку раньше фонового обновления.
        server.enqueue(list(new, old))
        assertEquals(listOf(new.id), sync.refresh()!!.map { it.id })
        // Фоновое обновление всё равно уведомит — и сдвинет отметку.
        server.enqueue(list(new, old))
        assertEquals(listOf(new.id), sync.refresh(advance = true)!!.map { it.id })
        server.enqueue(list(new, old))
        assertEquals(emptyList<Anomaly>(), sync.refresh(advance = true))

        // Отметка переживает перезапуск приложения.
        val again = AnomalySync({ settings }, store, StandAnomalies(client(), utcOffsetMinutes = { 300 }), File(tmp.root, "report.json"))
        server.enqueue(list(new, old))
        assertEquals(emptyList<Anomaly>(), again.refresh(advance = true))
    }
}
