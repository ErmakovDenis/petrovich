package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.petrovich.telemetry.data.LocalDateTimeSerializer
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.ServerTelemetryRepository
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandSession
import ru.petrovich.telemetry.data.SwitchingTelemetryRepository
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.AppSettings
import java.io.File
import java.time.LocalDateTime

class ServerTelemetryRepositoryTest {
    private lateinit var server: MockWebServer
    private val sessions = mutableListOf<String?>()
    private val vehicle = Vehicle("dev-1", "FAW №1 (в приложении)", "Колонна 1")

    @Before fun start() { server = MockWebServer().apply { start() } }

    @After fun stop() = server.shutdown()

    private fun repo() = ServerTelemetryRepository(
        StandClient(
            serverUrl = { server.url("/").toString() },
            session = { rejected ->
                sessions += rejected
                StandSession(if (rejected != null) "fresh-token" else "old-token", "schema-1")
            },
            client = OkHttpClient(),
        ),
        utcOffsetMinutes = { 300 },
    )

    private fun reply(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    /** Эталон стенда (тот же JSON отдаёт /v1/telemetry — проверяет tests/test_golden.py). */
    private fun goldenExpected(): String {
        val case = File(TripTablesGolden.repoRoot(), "testdata/golden/trip-tables/synthetic-6h.case.json")
        return (ApiFactory.json.parseToJsonElement(case.readText()) as JsonObject).getValue("expected").toString()
    }

    @Test
    fun vehiclesAreReadFromStand() = runTest {
        server.enqueue(reply(200, """[{"id":"dev-1","name":"FAW №1","group":"Колонна 1"},{"id":"dev-2","name":"Урал","group":null}]"""))
        assertEquals(listOf(Vehicle("dev-1", "FAW №1", "Колонна 1"), Vehicle("dev-2", "Урал")), repo().vehicles())
        val request = server.takeRequest()
        assertEquals("/v1/vehicles", request.path)
        assertEquals("Bearer old-token", request.getHeader("Authorization"))
        assertEquals("schema-1", request.getHeader("X-Schema-Id"))
    }

    @Test
    fun telemetryIsDecodedAndPeriodIsTruncatedToMinutes() = runTest {
        val body = goldenExpected()
        server.enqueue(reply(200, body))
        val from = LocalDateTime.of(2026, 9, 16, 8, 0, 30, 123_000_000)
        val t = repo().telemetry(vehicle, from, from.plusHours(6))

        val request = server.requestUrl()
        assertEquals("/v1/telemetry", request.encodedPath)
        assertEquals("dev-1", request.queryParameter("vehicleId"))
        assertEquals("2026-09-16T08:00:00", request.queryParameter("from"))
        assertEquals("2026-09-16T14:00:00", request.queryParameter("to"))
        assertEquals("300", request.queryParameter("utcOffsetMinutes"))

        // Машина — выбранная в приложении; таблицы — как прислал стенд.
        assertEquals(vehicle, t.vehicle)
        val expected = ApiFactory.json.decodeFromString(VehicleTelemetry.serializer(), body)
        assertEquals(expected.tables, t.tables)
        assertEquals(MetricCategory.entries.toSet(), t.tables.keys)
        t.tables.values.forEach { table -> table.columns.forEach { assertEquals(table.timestamps.size, it.values.size) } }
    }

    @Test
    fun timeIsSerializedWithSecondsAndParsedInAnyIsoForm() {
        val json = ApiFactory.json
        assertEquals("\"2026-09-16T10:00:00\"", json.encodeToString(LocalDateTimeSerializer, LocalDateTime.of(2026, 9, 16, 10, 0)))
        assertEquals(LocalDateTime.of(2026, 9, 16, 10, 0), json.decodeFromString(LocalDateTimeSerializer, "\"2026-09-16T10:00\""))
        assertEquals(
            LocalDateTime.of(2026, 9, 16, 10, 0, 5, 500_000_000),
            json.decodeFromString(LocalDateTimeSerializer, "\"2026-09-16T10:00:05.5\""),
        )
    }

    @Test
    fun expiredSessionIsRefreshedOnce() = runTest {
        server.enqueue(reply(401, """{"detail":"Сессия AutoGRAPH недействительна или истекла"}"""))
        server.enqueue(reply(200, "[]"))
        assertEquals(emptyList<Vehicle>(), repo().vehicles())
        assertEquals("Bearer old-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
        assertEquals(listOf(null, "old-token"), sessions)
    }

    @Test
    fun standErrorsAreShownAsText() = runTest {
        server.enqueue(reply(503, """{"detail":"AutoGRAPH недоступен: нет ответа после 3 попыток"}"""))
        assertFailsWith("AutoGRAPH недоступен: нет ответа после 3 попыток") { repo().vehicles() }

        server.enqueue(reply(404, """{"detail":"Машина не найдена или недоступна этому пользователю"}"""))
        assertFailsWith("Машина не найдена или недоступна этому пользователю") {
            repo().telemetry(vehicle, LocalDateTime.of(2026, 9, 16, 8, 0), LocalDateTime.of(2026, 9, 16, 14, 0))
        }

        server.enqueue(reply(200, """{"unexpected":true}"""))
        assertFailsWith("Стенд вернул данные в неожиданном формате") {
            repo().telemetry(vehicle, LocalDateTime.of(2026, 9, 16, 8, 0), LocalDateTime.of(2026, 9, 16, 14, 0))
        }

        // Время с поясом — не местное время приложения.
        server.enqueue(reply(200, """{"vehicle":{"id":"dev-1","name":"x"},"from":"2026-09-16T08:00:00Z",
            "to":"2026-09-16T14:00:00","tables":{}}"""))
        assertFailsWith("Стенд вернул данные в неожиданном формате") {
            repo().telemetry(vehicle, LocalDateTime.of(2026, 9, 16, 8, 0), LocalDateTime.of(2026, 9, 16, 14, 0))
        }
    }

    @Test
    fun periodIsTruncatedOnlyWhenBucketStaysTheSame() {
        val at = LocalDateTime.of(2026, 9, 16, 10, 0, 30, 500)
        // Обычный случай (to = сейчас, from = to − N ч): секунды одинаковые — усекаем.
        assertEquals(at.withSecond(0).withNano(0) to at.plusHours(6).withSecond(0).withNano(0),
            ServerTelemetryRepository.period(at, at.plusHours(6)))
        // 10:00:00 — 16:00:30 — это больше 6 ч (интервал 2 мин); усечение дало бы ровно 6 ч (1 мин) — шлём как есть.
        val from = LocalDateTime.of(2026, 9, 16, 10, 0)
        val to = LocalDateTime.of(2026, 9, 16, 16, 0, 30)
        assertEquals(from to to, ServerTelemetryRepository.period(from, to))
    }

    @Test
    fun switchingPicksSourceBySettings() = runTest {
        var settings = AppSettings()
        val calls = mutableListOf<String>()
        fun source(name: String) = object : TelemetryRepository {
            override suspend fun vehicles(): List<Vehicle> = emptyList<Vehicle>().also { calls += name }
            override suspend fun telemetry(vehicle: Vehicle, from: LocalDateTime, to: LocalDateTime) = error("не нужен")
        }
        val switching = SwitchingTelemetryRepository({ settings }, source("демо"), source("autograph"), source("стенд"))

        switching.vehicles()
        settings = AppSettings(telemetryViaServer = true) // адрес стенда не задан — по-прежнему AutoGRAPH
        switching.vehicles()
        settings = settings.copy(serverUrl = "https://stand")
        switching.vehicles()
        settings = settings.copy(demoMode = true) // демо-режим: новые пути не действуют
        switching.vehicles()
        assertEquals(listOf("autograph", "autograph", "стенд", "демо"), calls)
    }

    private fun MockWebServer.requestUrl() = takeRequest().requestUrl!!

    private suspend fun assertFailsWith(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("ожидалась ошибка «$message»")
        } catch (e: StandException) {
            assertEquals(message, e.message)
        }
    }
}
