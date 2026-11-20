package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.petrovich.telemetry.chat.*
import ru.petrovich.telemetry.data.PersonalHttpResponse
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.api.ApiFactory
import java.io.File

class DurableChatAgentTest {
    private var stored: String? = null
    private var owner = "profile-a"
    private val requests = mutableListOf<DurableChatRequest>()
    private val cid = "00000000-0000-0000-0000-000000000001"
    private fun agent(call: suspend (String, List<String>, String?) -> PersonalHttpResponse) = DurableChatAgent(
        scope = { owner }, load = { stored },
        save = { expected, value -> check(expected == owner); stored = value },
        call = { expected, method, path, body, _ -> check(expected == owner); call(method, path, body) }, offset = { 300 },
    )
    private val question = listOf(ChatMessage(author = Author.USER, text = "Что произошло?"))
    private fun completed(body: String?): PersonalHttpResponse {
        val request = ApiFactory.json.decodeFromString(DurableChatRequest.serializer(), body!!)
        requests += request
        return PersonalHttpResponse(200, ApiFactory.json.encodeToString(DurableChatResponse.serializer(),
            DurableChatResponse(cid, request.clientMessageId, "user-id", "answer-id", 1, "completed", "Данных нет")))
    }

    @Test fun retryAfterRestartPreservesEntireRequest() = runTest {
        var first: String? = null
        val failed = agent { _, _, body -> first = body; throw StandException("Сеть недоступна") }
        assertTrue(runCatching { failed.reply(question, null) }.isFailure)
        assertNotNull(stored)
        val restarted = agent { _, _, body -> assertEquals(first, body); completed(body) }
        assertEquals("Данных нет", restarted.reply(question, null).text)
        assertFalse(stored!!.contains("Что произошло?"))
        assertFalse(stored!!.contains("Данных нет"))
    }

    @Test fun pendingQuestionCannotBeReplacedByAnother() = runTest {
        val a = agent { _, _, _ -> throw StandException("Сеть недоступна") }
        runCatching { a.reply(question, null) }
        val other = agent { _, _, _ -> error("Другой вопрос не должен отправляться") }
        assertTrue(runCatching { other.reply(listOf(ChatMessage(author = Author.USER, text = "Другой")), null) }.exceptionOrNull() is StandException)
    }

    @Test fun processingUsesOriginalNullConversationIdOnRetry() = runTest {
        var original: String? = null
        val a = agent { _, _, body ->
            original = body
            val r = ApiFactory.json.decodeFromString(DurableChatRequest.serializer(), body!!)
            PersonalHttpResponse(202, ApiFactory.json.encodeToString(DurableChatResponse.serializer(),
                DurableChatResponse(cid, r.clientMessageId, "user-id", version = 0, state = "processing")))
        }
        assertEquals(202, (runCatching { a.reply(question, null) }.exceptionOrNull() as StandException).code)
        agent { _, _, body -> assertEquals(original, body); completed(body) }.reply(question, null)
    }

    @Test fun wrongRequestResponseDoesNotClearPending() = runTest {
        val a = agent { _, _, _ -> PersonalHttpResponse(409, """{"conversationId":"$cid","clientMessageId":"other","userMessageId":"user-id","version":0,"state":"interrupted"}""") }
        assertTrue(runCatching { a.reply(question, null) }.isFailure)
        assertTrue(stored!!.contains("Что произошло?"))
    }

    @Test fun interruptedRetryCannotStartSameActionWithNewId() = runTest {
        var calls = 0
        val a = agent { _, _, body ->
            calls++
            val r = ApiFactory.json.decodeFromString(DurableChatRequest.serializer(), body!!)
            PersonalHttpResponse(409, ApiFactory.json.encodeToString(DurableChatResponse.serializer(),
                DurableChatResponse(cid, r.clientMessageId, "user-id", version = 0, state = "interrupted")))
        }
        assertTrue(runCatching { a.reply(question, null) }.isFailure)
        assertTrue(runCatching { a.reply(question, null) }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun newRetainsOldConversationAndDeleteUsesSeparateEndpoint() = runTest {
        agent { _, _, body -> completed(body) }.reply(question, null)
        val calls = mutableListOf<Pair<String, List<String>>>()
        val a = agent { method, path, _ ->
            calls += method to path
            if (method == "DELETE") PersonalHttpResponse(204, "")
            else PersonalHttpResponse(200, """{"id":"$cid","version":0,"channel":"android"}""")
        }
        a.newConversation()
        assertEquals(listOf("POST" to listOf("v2", "conversations")), calls)
        calls.clear()
        a.deleteConversation()
        assertEquals(listOf("DELETE"), calls.map { it.first })
    }

    @Test fun restoredHistoryUsesCurrentDraftStatus() = runTest {
        agent { _, _, body -> completed(body) }.reply(question, null)
        val a = agent { _, path, _ ->
            if (path.last() == "messages") PersonalHttpResponse(200, """{"messages":[{"id":"answer-id","clientMessageId":"request-id","role":"assistant","content":"Ответ","state":"committed","createdAt":1000,"drafts":[{"id":"draft-id","recipients":[],"subject":"Тема","body":"Письмо","expiresAt":2000,"status":"expired"}]}]}""")
            else PersonalHttpResponse(200, """{"id":"$cid","version":1,"channel":"android"}""")
        }
        val rows = a.history()
        assertEquals("Ответ", rows.first().text)
        assertEquals(DraftState.EXPIRED, rows.last().draftState)
        assertTrue(rows.last().standDraft!!.personal)
        assertEquals(owner, rows.last().standDraft!!.personalScope)
    }

    @Test fun sharedContractDecodesAndRoundTrips() {
        val root = TripTablesGolden.repoRoot()
        val req = ApiFactory.json.decodeFromString(DurableChatRequest.serializer(), File(root, "testdata/contract/chat-v2-request.json").readText())
        assertEquals(300, req.utcOffsetMinutes)
        assertEquals(req, ApiFactory.json.decodeFromString(DurableChatRequest.serializer(), ApiFactory.json.encodeToString(DurableChatRequest.serializer(), req)))
        val response = ApiFactory.json.decodeFromString(DurableChatResponse.serializer(), File(root, "testdata/contract/chat-v2-response.json").readText())
        assertEquals("completed", response.state)
        assertEquals(cid, response.conversationId)
    }

    @Test fun disabledHistoryUsesLegacyWithoutCallingDurable() = runTest {
        val legacy = object : ContextualChatAgent {
            override suspend fun reply(history: List<ChatMessage>, anomaly: ru.petrovich.telemetry.anomaly.Anomaly?) = ChatReply("Старый API")
        }
        val remote = HistorySwitchingRemote(legacy, agent { _, _, _ -> error("Выключено") }) { false }
        assertEquals("Старый API", remote.reply(question, null).text)
        assertNull(remote.history())
        assertFalse(remote.deleteConversation())
    }
}
