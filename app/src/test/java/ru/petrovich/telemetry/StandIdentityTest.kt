package ru.petrovich.telemetry

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import ru.petrovich.telemetry.data.BoundIdentity
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandIdentity
import ru.petrovich.telemetry.data.api.ApiFactory
import java.io.File

class StandIdentityTest {
    private lateinit var server: MockWebServer
    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() = server.shutdown()
    private fun fixture(name: String) = File(TripTablesGolden.repoRoot(), "testdata/contract/$name").readText()
    private fun identity() = ApiFactory.json.decodeFromString(BoundIdentity.serializer(), fixture("identity-bind-response.json"))
    private fun client(local: Boolean = true) = StandIdentity(OkHttpClient(), { 300 }, allowLocalHttp = local)

    @Test fun bindMatchesServerContractWithoutOldSessionOrUserHeader() = runTest {
        server.enqueue(MockResponse().setBody(fixture("identity-bind-response.json")))
        val result = client().bind(server.url("/stand/").toString(), "Пётр", "fake-password", "schema-1")
        val request = server.takeRequest()
        assertEquals("/stand/v2/identity/bind", request.path)
        assertEquals(Json.parseToJsonElement(fixture("identity-bind-request.json")), Json.parseToJsonElement(request.body.readUtf8()))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("X-User-Name"))
        assertEquals("schema-1", result.tenantId)
        assertFalse(result.toString().contains(result.credential))
        assertFalse(result.toString().contains(result.autographSession))
    }

    @Test fun verifyAndRevokeAlwaysUseOriginalPair() = runTest {
        val bound = identity()
        server.enqueue(MockResponse().setBody("""{"principalId":"${bound.principalId}","tenantId":"schema-1"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val client = client()
        client.verify(server.url("/").toString(), bound)
        client.revoke(server.url("/").toString(), bound)
        for (action in listOf("me", "revoke")) {
            val request = server.takeRequest()
            assertEquals("/v2/identity/$action", request.path)
            assertEquals("Bearer ${bound.autographSession}", request.getHeader("Authorization"))
            assertEquals(bound.credential, request.getHeader("X-Identity-Credential"))
            assertEquals("schema-1", request.getHeader("X-Schema-Id"))
        }
    }

    @Test fun redirectsAreNotFollowedAndUnauthorizedIsNotRetried() = runTest {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/other")))
        val first = runCatching { client().bind(server.url("/").toString(), "Пётр", "fake-password", "schema-1") }.exceptionOrNull()
        assertEquals(307, (first as StandException).code)
        assertEquals(1, server.requestCount)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"fake-password pvi_secret"}"""))
        val second = runCatching { client().verify(server.url("/").toString(), identity()) }.exceptionOrNull()
        assertEquals(401, (second as StandException).code)
        assertFalse(second.message!!.contains("fake-password"))
        assertEquals(2, server.requestCount)
    }

    @Test fun passwordCannotGoToPlainHttpOutsideLocalTests() = runTest {
        val error = runCatching { client(local = false).bind(server.url("/").toString(), "Пётр", "fake-password", "schema-1") }.exceptionOrNull()
        assertTrue(error is StandException)
        assertEquals(0, server.requestCount)
        assertTrue(runCatching { client().bind("http://example.org", "Пётр", "fake-password", "schema-1") }.exceptionOrNull() is StandException)
    }

    @Test fun unexpectedTenantOrPrincipalIsRejected() = runTest {
        server.enqueue(MockResponse().setBody(fixture("identity-bind-response.json").replace("schema-1", "schema-2")))
        assertTrue(runCatching { client().bind(server.url("/").toString(), "Пётр", "fake-password", "schema-1") }.exceptionOrNull() is StandException)
        server.enqueue(MockResponse().setBody("""{"principalId":"someone-else","tenantId":"schema-1"}"""))
        assertTrue(runCatching { client().verify(server.url("/").toString(), identity()) }.exceptionOrNull() is StandException)
    }

    @Test fun malformedSessionNeverAppearsInHeaderErrors() = runTest {
        val valid = identity()
        val broken = BoundIdentity(valid.principalId, valid.tenantId, valid.credential, "private-session\nsecret", valid.expiresAt)
        val error = runCatching { client().verify(server.url("/").toString(), broken) }.exceptionOrNull()
        assertTrue(error is StandException)
        assertFalse(error!!.message!!.contains("private-session"))
        assertEquals(0, server.requestCount)
    }

    @Test fun personalTelegramUsesSamePairAndSharedContract() = runTest {
        val bound = identity()
        val link = fixture("identity-telegram-pair-response.json")
        server.enqueue(MockResponse().setBody(link))
        server.enqueue(MockResponse().setBody("{\"links\":[" + link + "]}"))
        server.enqueue(MockResponse().setResponseCode(204))
        val client = client()
        val linked = client.pairTelegram(server.url("/").toString(), bound, "ABCD2345")
        assertEquals("1001", linked.chatId)
        assertEquals(listOf(linked), client.telegramLinks(server.url("/").toString(), bound))
        client.unlinkTelegram(server.url("/").toString(), bound, "1001")
        val paired = server.takeRequest()
        assertEquals("/v2/identity/telegram/pair", paired.path)
        assertEquals(Json.parseToJsonElement(fixture("identity-telegram-pair-request.json")), Json.parseToJsonElement(paired.body.readUtf8()))
        val requests = listOf(paired, server.takeRequest(), server.takeRequest())
        assertEquals(listOf("POST", "GET", "DELETE"), requests.map { it.method })
        assertEquals("/v2/identity/telegram/links/1001", requests.last().path)
        requests.forEach {
            assertEquals("Bearer ${bound.autographSession}", it.getHeader("Authorization"))
            assertEquals(bound.credential, it.getHeader("X-Identity-Credential"))
            assertNull(it.getHeader("X-User-Name"))
        }
    }

    @Test fun malformedTelegramChatCannotChangeRequestPath() = runTest {
        val error = runCatching { client().unlinkTelegram(server.url("/").toString(), identity(), "../me") }.exceptionOrNull()
        assertTrue(error is StandException)
        assertEquals(0, server.requestCount)
    }
    @Test fun conversationTransportKeepsIdentityPairAndBasePath() = runTest {
        val bound = identity()
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        val result = client().personalRequest(server.url("/stand/").toString(), bound, "POST", listOf("v2", "chat"), "{}", emptyMap())
        assertEquals(202, result.code)
        val request = server.takeRequest()
        assertEquals("/stand/v2/chat", request.path)
        assertEquals("Bearer ${bound.autographSession}", request.getHeader("Authorization"))
        assertEquals(bound.credential, request.getHeader("X-Identity-Credential"))
        assertNull(request.getHeader("X-User-Name"))
        val error = runCatching { client().personalRequest(server.url("/").toString(), bound, "GET", listOf("v2", "conversations", "../me"), null, emptyMap()) }
        assertTrue(error.isFailure)
        assertEquals(1, server.requestCount)
    }
}
