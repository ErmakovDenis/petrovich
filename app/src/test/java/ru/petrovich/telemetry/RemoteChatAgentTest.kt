package ru.petrovich.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.chat.Author
import ru.petrovich.telemetry.chat.ChatAgent
import ru.petrovich.telemetry.chat.ChatMessage
import ru.petrovich.telemetry.chat.ChatReply
import ru.petrovich.telemetry.chat.ChatRequest
import ru.petrovich.telemetry.chat.ContextualChatAgent
import ru.petrovich.telemetry.chat.RemoteChatAgent
import ru.petrovich.telemetry.chat.SwitchingChatAgent
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandSession
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.AppSettings
import java.io.File

class RemoteChatAgentTest {
    private lateinit var server: MockWebServer
    private val sessions = mutableListOf<String?>()

    private val anomaly = Anomaly(
        id = "rule|drain|42|TankMainFuelLevel|2026-09-01T08:10",
        vehicleId = "42",
        vehicleName = "Урал NEXT А001АА",
        category = MetricCategory.FUEL,
        parameterName = "TankMainFuelLevel",
        parameterCaption = "Уровень топлива",
        eventTime = "2026-09-01T08:10",
        detectedAt = 1788000000000,
        severity = Severity.CRITICAL,
        title = "Слив топлива",
        description = "Уровень упал на 80 л за 15 мин на стоянке",
        value = 80.0,
        source = "rules",
    )

    private val history = listOf(
        ChatMessage(author = Author.USER, text = "Что с этой машиной?"),
        ChatMessage(author = Author.AGENT, text = "Уровень топлива упал на 80 л за 15 минут на стоянке."),
        ChatMessage(author = Author.AGENT, text = "Ошибка: стенд недоступен", isError = true),
        ChatMessage(author = Author.USER, text = "Он раньше так делал?"),
    )

    @Before fun start() { server = MockWebServer().apply { start() } }

    @After fun stop() = server.shutdown()

    private fun agent(url: String = server.url("/").toString()) = RemoteChatAgent(
        serverUrl = { url },
        session = { rejected ->
            sessions += rejected
            StandSession(if (rejected != null) "fresh-token" else "old-token", "schema-1")
        },
        client = OkHttpClient(),
        utcOffsetMinutes = { 300 },
    )

    private fun reply(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun sendsHistoryContextAndSessionHeaders() = runTest {
        server.enqueue(reply(200, """{"reply":"Данных о прошлых сливах у меня пока нет."}"""))

        assertEquals("Данных о прошлых сливах у меня пока нет.", agent().reply(history, anomaly))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat", request.path)
        assertEquals("Bearer old-token", request.getHeader("Authorization"))
        assertEquals("schema-1", request.getHeader("X-Schema-Id"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        // Сообщение об ошибке в истории модели не передаётся.
        assertEquals(listOf("user", "assistant", "user"), body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("300", body["utcOffsetMinutes"]!!.jsonPrimitive.content)
        assertEquals(anomaly.id, body["anomaly"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(listOf<String?>(null), sessions)
    }

    /** Тот же JSON проверяет tests/test_contract.py стенда — так Kotlin и Python договариваются о формате. */
    @Test
    fun requestMatchesSharedContractFixture() {
        val fixture = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "testdata/contract/chat-request.json") }
            .first { it.exists() }
        val request = agent().request(history, anomaly)
        assertEquals(
            Json.parseToJsonElement(fixture.readText()),
            ApiFactory.json.encodeToJsonElement(ChatRequest.serializer(), request),
        )
    }

    /** Хранилище на стенде: из карточки уходит только id аномалии стенда (testdata/contract/chat-request-anomaly-id.json). */
    @Test
    fun withStoreOnStandOnlyAnomalyIdIsSent() = runTest {
        val fixture = File(TripTablesGolden.repoRoot(), "testdata/contract/chat-request-anomaly-id.json").readText()
        val stored = anomaly.copy(id = "rule|overheat|42|TemperatureCOOL|2026-09-16T05:30Z")
        val agent = RemoteChatAgent(
            StandClient({ server.url("/").toString() }, { StandSession("token", "schema-1") }, OkHttpClient()),
            utcOffsetMinutes = { 300 },
            anomalyIdOnly = { true },
        )
        server.enqueue(reply(200, """{"reply":"Решили: ложная тревога."}"""))
        server.enqueue(reply(200, """{"reply":"Нет данных."}"""))

        agent.reply(listOf(ChatMessage(author = Author.USER, text = "Что решили по этой аномалии?")), stored)
        assertEquals(Json.parseToJsonElement(fixture), Json.parseToJsonElement(server.takeRequest().body.readUtf8()))
        // Аномалия с id устройства (ещё не перенесена на стенд) уходит целиком.
        agent.reply(history, anomaly)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(anomaly.id, body["anomaly"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("anomalyId"))
    }

    @Test
    fun withoutContextAnomalyIsOmitted() = runTest {
        server.enqueue(reply(200, """{"reply":"Нет данных."}"""))
        agent().reply(history.take(1))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse(body.containsKey("anomaly"))
    }

    @Test
    fun expiredSessionIsRefreshedOnce() = runTest {
        server.enqueue(reply(401, """{"detail":"Сессия AutoGRAPH недействительна или истекла"}"""))
        server.enqueue(reply(200, """{"reply":"Готово."}"""))

        assertEquals("Готово.", agent().reply(history))

        assertEquals("Bearer old-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
        assertEquals(listOf(null, "old-token"), sessions)
    }

    @Test
    fun rejectedTwiceGivesClearError() = runTest {
        repeat(2) { server.enqueue(reply(401, """{"detail":"Сессия AutoGRAPH недействительна или истекла"}""")) }
        assertFailsWith("Стенд не принял сессию AutoGRAPH — войдите заново в настройках") { agent().reply(history) }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun serverErrorDetailIsShown() = runTest {
        server.enqueue(reply(502, """{"detail":"Модель не ответила: OpenRouter ответил 429: Rate limit exceeded"}"""))
        assertFailsWith("Модель не ответила: OpenRouter ответил 429: Rate limit exceeded") { agent().reply(history) }

        server.enqueue(reply(500, "<html>oops</html>"))
        assertFailsWith("Стенд ответил ошибкой 500") { agent().reply(history) }

        server.enqueue(reply(200, """{"reply":"  "}"""))
        assertFailsWith("Стенд вернул пустой ответ") { agent().reply(history) }
    }

    @Test
    fun unreachableOrInvalidAddress() = runTest {
        val url = server.url("/").toString()
        server.shutdown()
        assertFailsWith("Стенд недоступен: проверьте адрес в настройках и сеть") { agent(url).reply(history) }
        assertFailsWith("Неверный адрес стенда в настройках") { agent("stand.local").reply(history) }
    }

    @Test
    fun switchFollowsSettings() = runTest {
        val settings = MutableStateFlow(AppSettings())
        val calls = mutableListOf<String>()
        val stub = object : ChatAgent {
            override val connected = false
            override suspend fun reply(history: List<ChatMessage>, contextAnomalyId: String?) =
                ChatReply("заглушка").also { calls += it.text }
        }
        val remote = object : ContextualChatAgent {
            override suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?) =
                "стенд".also { calls += "$it:${anomaly?.id}" }
        }
        val switching = SwitchingChatAgent(settings, stub, remote, backgroundScope) { id -> anomaly.takeIf { it.id == id } }

        assertEquals("заглушка", switching.reply(history, anomaly.id).text)
        settings.value = AppSettings(assistantViaServer = true, serverUrl = "https://stand")
        assertEquals("стенд", switching.reply(history, anomaly.id).text)
        // Демо-режим: новые пути не действуют.
        settings.value = settings.value.copy(demoMode = true)
        assertEquals("заглушка", switching.reply(history, anomaly.id).text)
        settings.value = settings.value.copy(demoMode = false, serverUrl = "")
        assertEquals("заглушка", switching.reply(history).text)
        assertEquals(listOf("заглушка", "стенд:${anomaly.id}", "заглушка", "заглушка"), calls)
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
