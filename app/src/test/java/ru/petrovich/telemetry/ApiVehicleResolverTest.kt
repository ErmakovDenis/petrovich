package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.petrovich.telemetry.anomaly.ApiVehicleResolver
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.api.PetrovichAi
import ru.petrovich.telemetry.data.api.PetrovichAiApi
import ru.petrovich.telemetry.data.api.VehicleOut
import java.io.IOException

class ApiVehicleResolverTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }

    @After fun stop() = server.shutdown()

    private fun resolver(now: () -> Long = { 0 }) =
        ApiVehicleResolver(PetrovichAi.create(server.url("/").toString(), { "op" to "secret" }, OkHttpClient()), now)

    private fun fleetJson() = "[" +
        """{"id":42,"plate":"А001АА","model":"Урал NEXT","created_at":"2026-11-13T10:00:00Z"},""" +
        """{"id":7,"plate":"В002ВВ","model":"FAW J6","created_at":"2026-11-13T10:00:00Z"}""" +
        "]"

    @Test
    fun matchesByApiIdThenPlateThenModel() = runTest {
        server.enqueue(MockResponse().setBody(fleetJson()))
        val r = resolver()
        // Тот же строковый id, что в приложении.
        assertEquals(42, r.resolve(Vehicle("42", "Как угодно назван")))
        // Госномер целиком, внутри имени, модель в имени.
        assertEquals(42, r.resolve(Vehicle("x", "а001аа")))
        assertEquals(42, r.resolve(Vehicle("x", "Урал NEXT А001АА")))
        assertEquals(7, r.resolve(Vehicle("x", "FAW J6 №2")))
        // Список API запрошен один раз на все четыре машины.
        assertEquals("/api/v1/vehicles", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unknownVehicleAndEmptyFleetHaveReadableErrors() = runTest {
        server.enqueue(MockResponse().setBody(fleetJson()))
        try {
            resolver().resolve(Vehicle("x", "КамАЗ П555ПП"))
            fail("ожидалась ошибка про не найденную машину")
        } catch (e: StandException) {
            assertEquals("КамАЗ П555ПП: машина не найдена в API аномалий", e.message)
        }

        server.enqueue(MockResponse().setBody("[]"))
        try {
            resolver().resolve(Vehicle("x", "Урал NEXT А001АА"))
            fail("ожидалась ошибка про пустой API")
        } catch (e: StandException) {
            assertEquals("в API аномалий нет машин — на стороне API не загружены данные (нужен ingest файла или sync AutoGRAPH)", e.message)
        }
    }

    @Test
    fun listIsRefetchedAfterTtl() = runTest {
        server.enqueue(MockResponse().setBody(fleetJson()))
        var now = 0L
        val r = resolver { now }
        r.resolve(Vehicle("42", "x"))
        now = ApiVehicleResolver.CACHE_TTL_MS + 1
        server.enqueue(MockResponse().setBody(fleetJson()))
        r.resolve(Vehicle("42", "x"))
        assertEquals(2, server.requestCount)
    }

    private class Unreachable : PetrovichAiApi {
        override suspend fun health() = error("не используется в тесте")
        override suspend fun vehicles(): List<VehicleOut> = throw IOException("нет сети")
        override suspend fun detect(vehicleId: Int) = error("не используется в тесте")
        override suspend fun events(
            dateFrom: String, dateTo: String, status: String, synthetic: String, limit: Int, offset: Int,
        ) = error("не используется в тесте")
    }

    @Test
    fun networkFailureIsReadable() = runTest {
        try {
            ApiVehicleResolver(Unreachable()).resolve(Vehicle("42", "Урал"))
            fail("ожидалась ошибка про недоступность")
        } catch (e: StandException) {
            assertEquals("API аномалий недоступен: проверьте сеть", e.message)
        }
    }
}
