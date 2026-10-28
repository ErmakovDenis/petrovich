package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandSession
import ru.petrovich.telemetry.data.StandTelegram
import ru.petrovich.telemetry.data.TelegramSubscription
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Telegram-бот: подключение (ссылка и код из бота) и подписка — запросы как в контракте стенда, отказ — с его текстом. */
class StandTelegramTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().apply { start() } }

    @After fun stop() = server.shutdown()

    private fun telegram() = StandTelegram(
        StandClient({ server.url("/").toString() }, { StandSession("token", "schema-1", "Петров") }, OkHttpClient()),
        utcOffsetMinutes = { 300 },
    )

    private fun fixture(name: String) = File(TripTablesGolden.repoRoot(), "testdata/contract/$name").readText()

    private fun firstChat() = Json.parseToJsonElement(fixture("telegram-chats-response.json")).jsonObject["chats"]!!.jsonArray[0].toString()

    @Test
    fun linkSendsFixtureRequestAndReadsUrl() = runTest {
        server.enqueue(MockResponse().setBody(fixture("telegram-link-response.json")))
        val link = telegram().link("6f1d2c3e-8a4b-4c5d-9e6f-0a1b2c3d4e5f", "Колонна №1")
        val request = server.takeRequest()
        assertEquals("/v1/telegram/link", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        assertEquals("%D0%9F%D0%B5%D1%82%D1%80%D0%BE%D0%B2", request.getHeader("X-User-Name"))
        assertEquals(Json.parseToJsonElement(fixture("telegram-link-request.json")), Json.parseToJsonElement(request.body.readUtf8()))
        assertEquals("https://t.me/petrovich_bot?start=Qm9vZ2llV29vZ2llMTIz", link.url)
        assertEquals(1789541700000L, link.expiresAt)
    }

    @Test
    fun withoutBackgroundAccessStandExplainsWhatToEnable() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Сначала включите «Фоновая проверка на стенде»"}"""))
        val error = runCatching { telegram().link("6f1d2c3e-8a4b-4c5d-9e6f-0a1b2c3d4e5f", "Колонна №1") }.exceptionOrNull()
        assertTrue(error is StandException && error.code == 409 && error.message!!.startsWith("Сначала включите"))
    }

    @Test
    fun pairSendsFixtureRequest() = runTest {
        server.enqueue(MockResponse().setBody(firstChat()))
        val chat = telegram().pair("6f1d2c3e-8a4b-4c5d-9e6f-0a1b2c3d4e5f", "Колонна №1", " K7MX-2QPD ")
        val request = server.takeRequest()
        assertEquals("/v1/telegram/pair", request.path)
        assertEquals(Json.parseToJsonElement(fixture("telegram-pair-request.json")), Json.parseToJsonElement(request.body.readUtf8()))
        assertEquals("@petrov", chat.title)
    }

    @Test
    fun chatsAreReadFromFixture() = runTest {
        server.enqueue(MockResponse().setBody(fixture("telegram-chats-response.json")))
        val chats = telegram().chats()
        assertEquals("/v1/telegram/chats", server.takeRequest().path)
        assertTrue(chats.configured)
        val sub = chats.chats.single().subscription
        assertEquals(listOf("veh-2"), sub.vehicleIds)
        assertEquals(null, sub.dailyLimit)
        assertEquals(listOf("power"), sub.mutedKinds)
        assertEquals(3, sub.repeatHours)
        assertEquals(true, sub.lists.subscribedOnly)
        assertEquals("severity", sub.lists.order)
        assertEquals(false, sub.card.resolveButtons)
        assertEquals(false, sub.card.dataButton)
        assertEquals("08:00", sub.reports.dailyTime)
        assertEquals(5, sub.reports.weeklyDay)
        assertEquals(listOf("КАМАЗ 43118", "Scania R450"), chats.vehicles.map { it.name })
    }

    @Test
    fun subscriptionIsSentWholeWithNulls() = runTest {
        server.enqueue(MockResponse().setBody(firstChat()))
        telegram().setSubscription("1001", TelegramSubscription(dailyLimit = null, quietFromHour = null, quietToHour = null))
        val request = server.takeRequest()
        assertEquals("/v1/telegram/chats/1001/subscription", request.path)
        // «Без лимита» и «без тихих часов» — явным null: пропущенное поле стенд заменил бы умолчанием.
        assertEquals(
            Json.parseToJsonElement(
                """{"vehicleIds":null,"warnings":true,"info":false,""" +
                    """"dailyLimit":null,"quietFromHour":null,"quietToHour":null,"mutedKinds":[],"repeatHours":null,""" +
                    """"lists":{"subscribedOnly":false,"minSeverity":"INFO","periodHours":null,"pageSize":10,"order":"newest"},""" +
                    """"card":{"description":true,"memory":true,"parameter":false,"source":false,"resolvedBy":true,"recheck":true,""" +
                    """"reviewButton":true,"resolveButtons":true,"dataButton":true},""" +
                    """"reports":{"daily":true,"dailyTime":"09:30","weekdaysOnly":false,"whenEmpty":true,"weekly":true,""" +
                    """"weeklyDay":1,"weeklyTime":"09:30"}}""",
            ),
            Json.parseToJsonElement(request.body.readUtf8()),
        )
    }
}
