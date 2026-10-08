package ru.petrovich.telemetry

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.AnomalyStore
import ru.petrovich.telemetry.anomaly.AnomalySync
import ru.petrovich.telemetry.anomaly.ImportRequest
import ru.petrovich.telemetry.anomaly.ResolveRequest
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.anomaly.ScanRequest
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.anomaly.StandAnomalies
import ru.petrovich.telemetry.anomaly.StoredVehicleChecker
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

/** Хранилище аномалий на стенде: запросы и ответы (общие фикстуры testdata/contract) и кэш ленты в приложении. */
class StandAnomaliesTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val vehicle = Vehicle("42", "Урал NEXT А001АА")
    private val to = LocalDateTime.of(2026, 9, 16, 18, 0)
    private val standOn = AppSettings(serverUrl = "https://stand", anomaliesViaServer = true, anomalyStoreViaServer = true)

    @Before fun start() { server = MockWebServer().apply { start() } }

    @After fun stop() = server.shutdown()

    private fun stand() = StandAnomalies(
        StandClient({ server.url("/").toString() }, { StandSession("token", "schema-1", "Пётр Петров") }, OkHttpClient()),
        utcOffsetMinutes = { 300 },
    )

    private fun fixture(name: String) = File(TripTablesGolden.repoRoot(), "testdata/contract/$name").readText()

    private fun ok(body: String) = MockResponse().setBody(body)

    private fun local(id: String, eventTime: String, resolution: Resolution? = null, reason: String? = null, acknowledged: Boolean = false) = Anomaly(
        id = id, vehicleId = "42", vehicleName = "Урал NEXT А001АА", category = MetricCategory.ENGINE,
        parameterName = id.split('|')[3], parameterCaption = "p", eventTime = eventTime, detectedAt = 0,
        severity = Severity.WARNING, title = if (id.contains("overheat")) "Перегрев двигателя" else "Пропадание питания",
        description = "d", source = "Базовые правила", acknowledged = acknowledged, resolution = resolution, falseAlarmReason = reason,
    )

    // --- запросы и ответы ---

    @Test
    fun listIsDecodedWithDecisionsAndUserNameIsSentEncoded() = runTest {
        server.enqueue(ok(fixture("anomalies-response.json")))
        val list = stand().list(500)

        val request = server.takeRequest()
        assertEquals("/v1/anomalies?utcOffsetMinutes=300&limit=500", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("%D0%9F%D1%91%D1%82%D1%80%20%D0%9F%D0%B5%D1%82%D1%80%D0%BE%D0%B2", request.getHeader("X-User-Name"))
        assertEquals(7, list.total)
        assertEquals(1789003800000, list.lastScanAt)
        val resolved = list.items[1]
        assertTrue(resolved.isStandId)
        assertEquals("overheat", resolved.kind)
        assertEquals(Resolution.FALSE_ALARM, resolved.resolution)
        assertEquals("Ошибка датчика", resolved.falseAlarmReason)
        assertEquals("Петров", resolved.resolvedBy)
        assertEquals(1789003800000, resolved.resolvedAt)
        assertNull(list.items[0].resolution)
    }

    @Test
    fun scanRequestMatchesFixtureAndFailedVehicleIsAnError() = runTest {
        assertEquals(
            Json.parseToJsonElement(fixture("scan-request.json")),
            ApiFactory.json.encodeToJsonElement(ScanRequest.serializer(), stand().scanRequest(vehicle, to.minusHours(24), to)),
        )
        server.enqueue(ok(fixture("scan-response.json")))
        val found = StoredVehicleChecker(stand()).check(vehicle, to.minusHours(24), to)
        assertEquals("/v1/anomalies/scan", server.takeRequest().path)
        assertEquals(2, found.size)
        assertEquals(Resolution.FALSE_ALARM, found[1].resolution)

        server.enqueue(ok("""{"from":"x","to":"y","results":[{"vehicleId":"42","ok":false,"error":"AutoGRAPH недоступен"}]}"""))
        assertFailsWith("AutoGRAPH недоступен") { stand().scan(vehicle, to.minusHours(3), to) }
    }

    @Test
    fun resolveEncodesIdAsOnePathSegment() = runTest {
        val id = "rule|overheat|42|TemperatureCOOL|2026-09-16T05:30Z"
        val stored = ApiFactory.json.parseToJsonElement(fixture("anomalies-response.json"))
        val item = (stored as kotlinx.serialization.json.JsonObject)["items"]!!.let { (it as kotlinx.serialization.json.JsonArray)[1] }
        server.enqueue(ok(item.toString()))

        val result = stand().resolve(id, Resolution.FALSE_ALARM, "Ошибка датчика")

        val request = server.takeRequest()
        assertEquals("/v1/anomalies/rule%7Coverheat%7C42%7CTemperatureCOOL%7C2026-09-16T05:30Z/resolve", request.path)
        assertEquals(Json.parseToJsonElement(fixture("resolve-request.json")), Json.parseToJsonElement(request.body.readUtf8()))
        assertEquals("Петров", result.resolvedBy)
        // «Вернуть в ждут решения»: null-поля не сериализуются, стенд считает отсутствие resolution возвратом.
        assertEquals(
            """{"utcOffsetMinutes":300}""",
            ApiFactory.json.encodeToString(ResolveRequest.serializer(), ResolveRequest(null, null, 300)),
        )
    }

    @Test
    fun importRequestMatchesFixture() {
        val items = listOf(
            local("rule|overheat|42|TemperatureCOOL|2026-09-16T10:30", "2026-09-16T10:30", Resolution.FALSE_ALARM, "Ошибка датчика"),
            local("rule|power|42|Power|2026-09-16T08:12", "2026-09-16T08:12"),
        )
        assertEquals(
            Json.parseToJsonElement(fixture("import-request.json")),
            ApiFactory.json.encodeToJsonElement(ImportRequest.serializer(), stand().importRequest(items)),
        )
    }

    // --- кэш ленты ---

    private fun sync(
        settings: () -> AppSettings,
        store: AnomalyStore,
        report: File = File(tmp.root, "report.json"),
        now: () -> LocalDateTime = LocalDateTime::now,
    ) = AnomalySync({ settings() }, store, stand(), report, now)

    @Test
    fun withoutStoreOnStandDecisionsStayOnDevice() = runTest {
        val store = AnomalyStore(File(tmp.root, "a.json"))
        store.addAll(listOf(local("rule|overheat|42|TemperatureCOOL|2026-09-16T10:30", "2026-09-16T10:30")))
        val sync = sync({ standOn.copy(anomalyStoreViaServer = false) }, store)

        assertNull(sync.refresh())
        sync.resolve("rule|overheat|42|TemperatureCOOL|2026-09-16T10:30", Resolution.CONFIRMED)
        assertEquals(Resolution.CONFIRMED, store.anomalies.value.single().resolution)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun firstRefreshMovesLocalHistoryToStandAndReplacesCache() = runTest {
        val store = AnomalyStore(File(tmp.root, "a.json"))
        store.addAll(listOf(
            local("rule|overheat|42|TemperatureCOOL|2026-09-16T10:30", "2026-09-16T10:30", Resolution.FALSE_ALARM, "Ошибка датчика"),
            local("rule|power|42|Power|2026-09-16T08:12", "2026-09-16T08:12"),
        ))
        server.enqueue(ok(fixture("import-response.json")))
        server.enqueue(ok(fixture("anomalies-response.json")))
        val reportFile = File(tmp.root, "report.json")

        // Первое обновление — точка отсчёта: уведомлять не о чем.
        assertEquals(emptyList<Anomaly>(), sync({ standOn }, store, reportFile).refresh())

        assertEquals("/v1/anomalies/import", server.takeRequest().path)
        assertTrue(server.takeRequest().path!!.startsWith("/v1/anomalies?"))
        // В кэше — лента стенда; записи с id устройства перенесены и убраны.
        assertEquals(
            listOf("rule|overheat|42|TemperatureCOOL|2026-09-16T06:30Z", "rule|overheat|42|TemperatureCOOL|2026-09-16T05:30Z"),
            store.anomalies.value.map { it.id },
        )
        // Отчёт сохранён и читается после перезапуска приложения.
        val report = sync({ standOn }, store, reportFile).lastImport.first()!!
        assertEquals(2, report.sent)
        assertEquals("стенд не нашёл это событие при проверке того же периода", report.result.unmatched.single().why)

        // Следующее обновление — без переноса.
        server.enqueue(ok(fixture("anomalies-response.json")))
        sync({ standOn }, store, reportFile).refresh()
        assertTrue(server.takeRequest().path!!.startsWith("/v1/anomalies?"))
    }

    @Test
    fun failedTransferKeepsLocalRecordsForNextAttempt() = runTest {
        val store = AnomalyStore(File(tmp.root, "a.json"))
        val decided = local("rule|power|42|Power|2026-09-16T08:12", "2026-09-16T08:12", Resolution.CONFIRMED)
        store.addAll(listOf(decided, decided.copy(id = "rule|power|7|Power|2026-09-16T09:00", vehicleId = "7", vehicleName = "FAW")))
        // Машина 42: стенд не ответил; машина 7: период не удалось проверить (AutoGRAPH).
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"AutoGRAPH недоступен: не удалось проверить сессию"}"""))
        server.enqueue(ok("""{"decisions":1,"applied":0,"alreadyResolved":0,"unmatched":[{"localId":"rule|power|7|Power|2026-09-16T09:00",
            "vehicleId":"7","vehicleName":"FAW","title":"t","eventTime":"2026-09-16T09:00:00","resolution":"CONFIRMED",
            "why":"период не удалось проверить: AutoGRAPH недоступен"}],"restored":0,"notFound":0,"scanErrors":["FAW: AutoGRAPH недоступен"]}"""))
        server.enqueue(ok(fixture("anomalies-response.json")))
        val sync = sync({ standOn }, store)

        sync.refresh()
        // Лента стенда загружена, обе локальные записи с решениями остались до следующей попытки переноса.
        assertEquals(4, store.anomalies.value.size)
        assertEquals(2, store.anomalies.value.count { !it.isStandId && it.resolution == Resolution.CONFIRMED })
        val report = sync.lastImport.first()!!
        assertEquals(listOf("Урал NEXT А001АА: AutoGRAPH недоступен: не удалось проверить сессию"), report.failed)
        assertEquals(listOf("FAW: AutoGRAPH недоступен"), report.result.scanErrors)
    }

    @Test
    fun newAnomaliesAreThoseFirstDetectedAfterLastRefresh() = runTest {
        val store = AnomalyStore(File(tmp.root, "a.json"))
        server.enqueue(ok(fixture("anomalies-response.json")))
        val sync = sync({ standOn }, store, now = { LocalDateTime.of(2026, 9, 16, 18, 0) })
        assertEquals(emptyList<Anomaly>(), sync.refresh())

        // На стенде появились: новое событие (другой пользователь схемы), давний эпизод вне кэша и старое событие,
        // впервые найденное сейчас (перенос чужой истории) — уведомление только о первом.
        val list = Json.parseToJsonElement(fixture("anomalies-response.json")) as JsonObject
        val template = (list["items"] as JsonArray)[0] as JsonObject
        fun item(id: String, eventTime: String, detectedAt: Long) = JsonObject(template + mapOf(
            "id" to JsonPrimitive(id), "eventTime" to JsonPrimitive(eventTime), "detectedAt" to JsonPrimitive(detectedAt)))
        val items = JsonArray(listOf(
            item("rule|overheat|42|TemperatureCOOL|2026-09-16T12:30Z", "2026-09-16T17:30", 1789010000000),
            item("rule|brake|42|PressureBrake1|2026-09-10T01:00Z", "2026-09-10T06:00", 1788000000000),
            item("rule|drain|42|FuelDrainVolume|2026-09-01T01:00Z", "2026-09-01T06:00", 1789010000000),
        ) + (list["items"] as JsonArray))
        server.enqueue(ok(JsonObject(list + mapOf("items" to items)).toString()))
        assertEquals(listOf("rule|overheat|42|TemperatureCOOL|2026-09-16T12:30Z"), sync.refresh()!!.map { it.id })
    }

    @Test
    fun turningStoreOffRemovesStandRecordsFromCache() = runTest {
        val store = AnomalyStore(File(tmp.root, "a.json"))
        server.enqueue(ok(fixture("anomalies-response.json")))
        var settings = standOn
        val sync = sync({ settings }, store)
        sync.refresh()
        store.addAll(listOf(local("rule|power|42|Power|2026-09-16T08:12", "2026-09-16T08:12")))
        settings = standOn.copy(anomalyStoreViaServer = false)
        assertNull(sync.refresh())
        assertEquals(listOf("rule|power|42|Power|2026-09-16T08:12"), store.anomalies.value.map { it.id })
    }

    @Test
    fun decisionsGoToStandAndKeepSeenMark() = runTest {
        val store = AnomalyStore(File(tmp.root, "a.json"))
        server.enqueue(ok(fixture("anomalies-response.json")))
        val sync = sync({ standOn }, store)
        sync.refresh()
        store.acknowledge("rule|overheat|42|TemperatureCOOL|2026-09-16T06:30Z")
        server.takeRequest()

        // Обновление ленты не сбрасывает «просмотрено».
        server.enqueue(ok(fixture("anomalies-response.json")))
        sync.refresh()
        server.takeRequest()
        assertTrue(store.anomalies.value.first { it.id.endsWith("06:30Z") }.acknowledged)

        val item = (ApiFactory.json.parseToJsonElement(fixture("anomalies-response.json")) as kotlinx.serialization.json.JsonObject)["items"]!!
            .let { (it as kotlinx.serialization.json.JsonArray)[1] }
        server.enqueue(ok(item.toString().replace("\"FALSE_ALARM\"", "null").replace("\"Петров\"", "null")))
        sync.reopen("rule|overheat|42|TemperatureCOOL|2026-09-16T05:30Z")
        assertTrue(server.takeRequest().path!!.endsWith("/resolve"))
        val reopened = store.anomalies.value.first { it.id.endsWith("05:30Z") }
        assertNull(reopened.resolution)
        assertFalse(reopened.acknowledged)

        // Запись с id устройства (ещё не перенесена) решается локально и уедет при переносе.
        store.addAll(listOf(local("rule|power|42|Power|2026-09-16T08:12", "2026-09-16T08:12")))
        sync.resolve("rule|power|42|Power|2026-09-16T08:12", Resolution.CONFIRMED)
        assertEquals(3, server.requestCount)
        assertEquals(Resolution.CONFIRMED, store.anomalies.value.first { it.id.endsWith("08:12") }.resolution)
    }

    @Test
    fun storedCheckIsChosenOnlyWithStoreOnStand() = runTest {
        val calls = mutableListOf<String>()
        fun fake(name: String) = object : VehicleChecker {
            override suspend fun check(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime) = emptyList<Anomaly>().also { calls += name }
        }
        var settings = standOn.copy(anomaliesViaServer = false)
        val switching = SwitchingVehicleChecker({ settings }, fake("local"), fake("server"), compareAllowed = true, stored = fake("stored")) {}
        switching.check(vehicle, to, to)
        settings = standOn
        switching.check(vehicle, to, to)
        settings = standOn.copy(anomalyStoreViaServer = false)
        switching.check(vehicle, to, to)
        assertEquals(listOf("local", "stored", "server"), calls)
        assertFalse(standOn.copy(demoMode = true).anomalyStoreOnServer)
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
