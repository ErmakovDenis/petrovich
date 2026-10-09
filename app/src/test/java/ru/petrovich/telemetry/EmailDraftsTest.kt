package ru.petrovich.telemetry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.petrovich.telemetry.chat.AgentReply
import ru.petrovich.telemetry.chat.Author
import ru.petrovich.telemetry.chat.ChatAgent
import ru.petrovich.telemetry.chat.ChatMessage
import ru.petrovich.telemetry.chat.DraftState
import ru.petrovich.telemetry.chat.EmailActions
import ru.petrovich.telemetry.chat.EmailDraft
import ru.petrovich.telemetry.chat.EmailRecipient
import ru.petrovich.telemetry.chat.EmailResult
import ru.petrovich.telemetry.chat.RemoteChatAgent
import ru.petrovich.telemetry.chat.StandEmails
import ru.petrovich.telemetry.chat.StubChatAgent
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandSession
import ru.petrovich.telemetry.ui.chat.ChatViewModel
import java.io.File

/** Письма из чата: черновик приходит с ответом, уходит только по «Отправить», отказы видны пользователю. */
@OptIn(ExperimentalCoroutinesApi::class)
class EmailDraftsTest {
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer().apply { start() }
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun stop() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun client() = StandClient({ server.url("/").toString() }, { StandSession("token", "schema-1", "Пётр") }, OkHttpClient())

    private fun fixture(name: String) = File(TripTablesGolden.repoRoot(), "testdata/contract/$name").readText()

    private val draft = EmailDraft(
        id = "c3RhbmQtZHJhZnQtaWQtMDE",
        recipients = listOf(EmailRecipient("mechanic", "Иван Петров", "Механик", "mechanic@example.org")),
        subject = "Перегрев: Урал NEXT А001АА", body = "Прошу проверить систему охлаждения.", expiresAt = 2_000,
    )

    @Test
    fun replyCarriesDraftsAndRequestAllowsEmailOnlyWithToggle() = runTest {
        server.enqueue(MockResponse().setBody(fixture("chat-response-draft.json")))
        server.enqueue(MockResponse().setBody("""{"reply":"Нет данных."}"""))
        var allow = true
        val agent = RemoteChatAgent(client(), utcOffsetMinutes = { 300 }, allowEmail = { allow })
        val history = listOf(ChatMessage(author = Author.USER, text = "Подготовь письмо механику"))

        val reply = agent.reply(history)
        assertEquals("mechanic@example.org", reply.drafts.single().recipients.single().email)
        assertEquals(1789537500000, reply.drafts.single().expiresAt)
        assertEquals("true", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["allowEmail"]!!.jsonPrimitive.content)

        // Переключатель выключен — поле не передаётся (стенд считает false), ответ без черновиков — как раньше.
        allow = false
        assertEquals(AgentReply("Нет данных."), agent.reply(history))
        assertFalse(server.takeRequest().body.readUtf8().contains("allowEmail"))
    }

    @Test
    fun draftGoesToModelAsSummaryWithItsFate() {
        val agent = RemoteChatAgent(client(), utcOffsetMinutes = { 300 })
        val history = listOf(
            ChatMessage(author = Author.USER, text = "Подготовь письмо"),
            ChatMessage(author = Author.AGENT, text = "", draft = draft, draftState = DraftState.CANCELLED),
            ChatMessage(author = Author.USER, text = "Подготовь ещё раз"),
        )
        val turns = Json.parseToJsonElement(ApiFactoryJson.encode(agent.request(history, null))).jsonObject["messages"]!!.jsonArray
        assertEquals(
            "[Черновик письма «Перегрев: Урал NEXT А001АА» для Иван Петров — пользователь отменил]",
            turns[1].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun sendAndCancelGoToStandWithIdAsOnePathSegment() = runTest {
        server.enqueue(MockResponse().setBody(fixture("email-result.json")))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"detail":"Лимит писем исчерпан: не больше 20 за 24 ч"}"""))
        val emails = StandEmails(client())
        assertEquals("sent", emails.send("c3RhbmQt/ZHJh").status)
        val request = server.takeRequest()
        assertEquals("/v1/emails/c3RhbmQt%2FZHJh/confirm", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
        val error = runCatching { emails.cancel("x") }.exceptionOrNull()
        assertTrue(error is StandException)
        assertEquals("Лимит писем исчерпан: не больше 20 за 24 ч", error!!.message)
        assertEquals("/v1/emails/x/cancel", server.takeRequest().path)
    }

    private class FakeEmails(var fail: String? = null, var failCode: Int? = null) : EmailActions {
        val sent = mutableListOf<String>()
        val cancelled = mutableListOf<String>()

        override suspend fun send(draftId: String): EmailResult {
            fail?.let { throw StandException(it, failCode) }
            sent += draftId
            return EmailResult(draftId, "sent", 1)
        }

        override suspend fun cancel(draftId: String): EmailResult {
            cancelled += draftId
            return EmailResult(draftId, "cancelled")
        }
    }

    private fun agentWithDraft() = object : ChatAgent {
        override suspend fun reply(history: List<ChatMessage>) = AgentReply("Черновик готов.", listOf(draft))
    }

    @Test
    fun draftIsSentOnlyByUserAndErrorsAreShown() = runTest {
        val emails = FakeEmails(fail = "Письмо не отправлено: почтовый сервер ответил ошибкой 451")
        val vm = ChatViewModel(agentWithDraft(), emails)
        vm.send("Подготовь письмо механику")
        val message = vm.state.value.messages.last()
        assertEquals(draft, message.draft)
        assertEquals("Черновик готов.", vm.state.value.messages[vm.state.value.messages.size - 2].text)
        // Ответ с черновиком сам ничего не отправляет.
        assertTrue(emails.sent.isEmpty())

        vm.sendDraft(message.id)
        val failed = vm.state.value.messages.last()
        assertEquals(DraftState.FAILED, failed.draftState)
        assertEquals("Письмо не отправлено: почтовый сервер ответил ошибкой 451", failed.draftError)

        // Повтор после ошибки — можно; отправленное второй раз не уходит.
        emails.fail = null
        vm.sendDraft(message.id)
        vm.sendDraft(message.id)
        assertEquals(listOf(draft.id), emails.sent)
        assertEquals(DraftState.SENT, vm.state.value.messages.last().draftState)
        assertNull(vm.state.value.messages.last().draftError)
        vm.cancelDraft(message.id)
        assertTrue(emails.cancelled.isEmpty())
    }

    @Test
    fun cancelledOrExpiredDraftIsNotSent() = runTest {
        val emails = FakeEmails()
        val vm = ChatViewModel(agentWithDraft(), emails)
        vm.send("Письмо")
        vm.send("Ещё письмо")
        val (first, second) = vm.state.value.messages.filter { it.draft != null }
        vm.cancelDraft(first.id)
        vm.sendDraft(first.id)
        assertEquals(listOf(draft.id), emails.cancelled)
        assertEquals(DraftState.CANCELLED, vm.state.value.messages.first { it.id == first.id }.draftState)

        // Срок проверяет стенд (по своим часам), приложение показывает его отказ.
        emails.fail = "Срок черновика истёк — попросите ассистента подготовить письмо заново"
        emails.failCode = 410
        vm.sendDraft(second.id)
        val expired = vm.state.value.messages.first { it.id == second.id }
        assertEquals(DraftState.FAILED, expired.draftState)
        assertTrue(expired.draftError!!.startsWith("Срок черновика истёк"))
        assertTrue(emails.sent.isEmpty())
    }

    @Test
    fun alreadySentAnswerMeansSent() = runTest {
        // Письмо ушло, ответ потерялся; повторное нажатие — стенд отвечает 409 «уже отправлено».
        val emails = FakeEmails(fail = "Письмо уже отправлено", failCode = 409)
        val vm = ChatViewModel(agentWithDraft(), emails)
        vm.send("Письмо")
        val id = vm.state.value.messages.last().id
        vm.sendDraft(id)
        assertEquals(DraftState.SENT, vm.state.value.messages.last().draftState)
        // Другой 409 (черновик отменён) — ошибка.
        emails.fail = "Черновик отменён"
        vm.send("Ещё")
        vm.sendDraft(vm.state.value.messages.last().id)
        assertEquals(DraftState.FAILED, vm.state.value.messages.last().draftState)
    }

    @Test
    fun stubAgentStillAnswersWithoutDrafts() = runTest {
        val vm = ChatViewModel(StubChatAgent(), FakeEmails())
        vm.send("Привет")
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.messages.none { it.draft != null })
    }
}

private object ApiFactoryJson {
    fun encode(request: ru.petrovich.telemetry.chat.ChatRequest): String =
        ru.petrovich.telemetry.data.api.ApiFactory.json.encodeToString(ru.petrovich.telemetry.chat.ChatRequest.serializer(), request)
}
