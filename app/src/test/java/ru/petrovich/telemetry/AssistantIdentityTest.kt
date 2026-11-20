package ru.petrovich.telemetry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.petrovich.telemetry.data.AssistantIdentity
import ru.petrovich.telemetry.data.BoundIdentity
import ru.petrovich.telemetry.data.IdentityTransport
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.TelegramIdentityLink
import ru.petrovich.telemetry.data.settings.AesGcmIdentityVault
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.data.settings.invalidatingIdentityFrom
import javax.crypto.KeyGenerator

class AssistantIdentityTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val vault = AesGcmIdentityVault { key }
    private var now = 1_000L
    private var settings = AppSettings(userName = "Пётр", password = "existing-password", schemaId = "schema-1", serverUrl = "https://stand.example")
    private val issued = BoundIdentity("principal", "schema-1", "pvi_" + "a".repeat(43), "verified-session", 3_601_000L)
    private var bindCalls = 0
    private var revokeFails = false
    private var cancelBind = false
    private var cancelSave = false
    private var changeDuringBind: (() -> Unit)? = null
    private var changeDuringPair: (() -> Unit)? = null
    private var pairCalls = 0
    private val unlinkedChats = mutableListOf<String>()
    private val revocations = mutableListOf<String>()
    private val transport = object : IdentityTransport {
        override suspend fun personalRequest(serverUrl: String, identity: BoundIdentity, method: String, segments: List<String>, body: String?, query: Map<String, String>): ru.petrovich.telemetry.data.PersonalHttpResponse {
            assertSamePair(identity)
            changeDuringPair?.invoke()
            return ru.petrovich.telemetry.data.PersonalHttpResponse(200, "{}")
        }
        override suspend fun bind(serverUrl: String, userName: String, password: String, schemaId: String): BoundIdentity {
            bindCalls++
            if (cancelBind) throw CancellationException("Отмена")
            changeDuringBind?.invoke()
            return issued
        }
        override suspend fun verify(serverUrl: String, identity: BoundIdentity) { assertSamePair(identity) }
        override suspend fun pairTelegram(serverUrl: String, identity: BoundIdentity, code: String): TelegramIdentityLink {
            assertSamePair(identity)
            pairCalls++
            changeDuringPair?.invoke()
            return TelegramIdentityLink("1001", identity.expiresAt)
        }
        override suspend fun telegramLinks(serverUrl: String, identity: BoundIdentity) = listOf(TelegramIdentityLink("1001", identity.expiresAt))
        override suspend fun unlinkTelegram(serverUrl: String, identity: BoundIdentity, chatId: String) { assertSamePair(identity); unlinkedChats += chatId }
        override suspend fun revoke(serverUrl: String, identity: BoundIdentity) {
            assertSamePair(identity)
            if (revokeFails) throw StandException("Нет сети")
            revocations += serverUrl
        }
    }
    private fun assertSamePair(identity: BoundIdentity) { assertEquals(issued.credential, identity.credential); assertEquals(issued.autographSession, identity.autographSession) }
    private fun change(transform: (AppSettings) -> AppSettings) { settings = transform(settings).invalidatingIdentityFrom(settings) }
    private fun manager() = AssistantIdentity({ settings }, { if (cancelSave) throw CancellationException("Отмена сохранения"); change(it) }, vault, transport, { now })

    @Test fun consentIsRequiredAndSecretsSurviveRestartOnlyEncrypted() = runTest {
        assertTrue(runCatching { manager().connect("supplied-password", false, settings.identityGeneration) }.exceptionOrNull() is StandException)
        assertEquals(0, bindCalls)
        manager().connect("supplied-password", true, settings.identityGeneration)
        assertFalse(settings.identityCiphertext.contains(issued.credential))
        assertFalse(settings.identityCiphertext.contains(issued.autographSession))
        assertFalse(settings.identityCiphertext.contains("supplied-password"))
        assertEquals("existing-password", settings.password)
        assertTrue(manager().state().connected)
        manager().verify()
    }

    @Test fun accountSchemaPasswordAndStandChangesDisablePairEvenAfterSwitchingBack() = runTest {
        for (transform in listOf<(AppSettings) -> AppSettings>(
            { it.copy(userName = "Иван") }, { it.copy(password = "changed") },
            { it.copy(schemaId = "schema-2") }, { it.copy(serverUrl = "https://other.example") },
        )) {
            manager().connect("supplied-password", true, settings.identityGeneration)
            val before = settings
            change(transform)
            assertFalse(manager().state().connected)
            assertTrue(manager().state().pendingRevocation)
            change { before.copy(identityGeneration = it.identityGeneration) }
            assertFalse(manager().state().connected)
            assertTrue(manager().cleanupIfNeeded().not())
            assertEquals("https://stand.example", revocations.last())
        }
    }

    @Test fun offlineDisconnectPersistsRevocationAndRetriesOnOriginalServerAfterRestart() = runTest {
        manager().connect("supplied-password", true, settings.identityGeneration)
        revokeFails = true
        assertTrue(manager().disconnect())
        assertFalse(manager().state().connected)
        change { it.copy(serverUrl = "https://new.example") }
        revokeFails = false
        assertFalse(manager().cleanupIfNeeded())
        assertEquals(listOf("https://stand.example"), revocations)
        assertTrue(settings.identityCiphertext.isEmpty())
    }

    @Test fun failedRevocationCannotCreateAnotherCredential() = runTest {
        manager().connect("supplied-password", true, settings.identityGeneration)
        revokeFails = true
        assertTrue(runCatching { manager().connect("supplied-password", true, settings.identityGeneration) }.exceptionOrNull() is StandException)
        assertEquals(1, bindCalls)
        assertFalse(manager().state().connected)
    }

    @Test fun responseFromOldScopeIsNeverActivated() = runTest {
        changeDuringBind = { change { it.copy(userName = "Иван") } }
        assertTrue(runCatching { manager().connect("supplied-password", true, settings.identityGeneration) }.exceptionOrNull() is StandException)
        assertFalse(manager().state().connected)
        assertEquals(1, revocations.size)
    }

    @Test fun cancellationIsNotConvertedToOrdinaryError() = runTest {
        cancelBind = true
        assertTrue(runCatching { manager().connect("supplied-password", true, settings.identityGeneration) }.exceptionOrNull() is CancellationException)
        assertTrue(settings.identityCiphertext.isEmpty())
    }

    @Test fun expiredPairIsNotUsedOrSilentlyRenewed() = runTest {
        manager().connect("supplied-password", true, settings.identityGeneration)
        now = issued.expiresAt
        assertFalse(manager().state().connected)
        assertTrue(runCatching { manager().verify() }.exceptionOrNull() is StandException)
        manager().cleanupIfNeeded()
        assertTrue(settings.identityCiphertext.isEmpty())
        assertEquals(1, bindCalls)
    }

    @Test fun cipherUsesFreshNonceAndRejectsTamperingAndWrongKey() {
        val a = vault.seal("private-pair")
        val b = vault.seal("private-pair")
        assertNotEquals(a, b)
        assertEquals("private-pair", vault.open(a))
        val bytes = java.util.Base64.getDecoder().decode(a.removePrefix("v1."))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertTrue(runCatching { vault.open("v1." + java.util.Base64.getEncoder().encodeToString(bytes)) }.isFailure)
        val anotherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertTrue(runCatching { AesGcmIdentityVault { anotherKey }.open(a) }.isFailure)
    }

    @Test fun cancellationAfterBindRevokesTheUnsavedPair() = runTest {
        cancelSave = true
        assertTrue(runCatching { manager().connect("supplied-password", true, settings.identityGeneration) }.exceptionOrNull() is CancellationException)
        assertTrue(settings.identityCiphertext.isEmpty())
        assertEquals(1, revocations.size)
    }

    @Test fun damagedStorageIsClosedUntilExplicitRemoval() = runTest {
        manager().connect("supplied-password", true, settings.identityGeneration)
        change { it.copy(identityCiphertext = "v1.corrupted") }
        assertFalse(manager().state().connected)
        assertNotNull(manager().state().error)
        assertTrue(runCatching { manager().connect("supplied-password", true, settings.identityGeneration) }.exceptionOrNull() is StandException)
        assertEquals(1, bindCalls)
        manager().forgetLocally()
        manager().connect("supplied-password", true, settings.identityGeneration)
        assertTrue(manager().state().connected)
    }

    @Test fun encryptionFailureRevokesAlreadyIssuedCredential() = runTest {
        val brokenVault = object : ru.petrovich.telemetry.data.settings.IdentityVault {
            override fun open(sealed: String): String = error("Нет ключа")
            override fun seal(plain: String): String = error("Нет ключа")
        }
        val manager = AssistantIdentity({ settings }, { change(it) }, brokenVault, transport, { now })
        assertTrue(runCatching { manager.connect("supplied-password", true, settings.identityGeneration) }.exceptionOrNull() is StandException)
        assertTrue(settings.identityCiphertext.isEmpty())
        assertEquals(1, revocations.size)
    }

    @Test fun consentForPreviousScopeCannotSendPasswordToNewStand() = runTest {
        val consentedGeneration = settings.identityGeneration
        change { it.copy(serverUrl = "https://other.example") }
        assertTrue(runCatching { manager().connect("supplied-password", true, consentedGeneration) }.exceptionOrNull() is StandException)
        assertEquals(0, bindCalls)
    }

    @Test fun telegramRequiresSeparateConsentAndCurrentCredential() = runTest {
        manager().connect("supplied-password", true, settings.identityGeneration)
        val original = settings
        assertTrue(runCatching { manager().pairTelegram("ABCD2345", false, settings.identityGeneration, settings.identityCiphertext) }.exceptionOrNull() is StandException)
        manager().connect("supplied-password", true, settings.identityGeneration)
        assertTrue(runCatching { manager().pairTelegram("ABCD2345", true, original.identityGeneration, original.identityCiphertext) }.exceptionOrNull() is StandException)
        assertEquals(0, pairCalls)
        val linked = manager().pairTelegram("ABCD2345", true, settings.identityGeneration, settings.identityCiphertext)
        assertEquals("1001", linked.chatId)
        assertEquals(listOf(linked), manager().telegramLinks())
        assertFalse(settings.identityCiphertext.contains("ABCD2345"))
    }

    @Test fun pairingResponseForPreviousScopeIsRemoved() = runTest {
        manager().connect("supplied-password", true, settings.identityGeneration)
        changeDuringPair = { change { it.copy(schemaId = "schema-2") } }
        assertTrue(runCatching { manager().pairTelegram("ABCD2345", true, settings.identityGeneration, settings.identityCiphertext) }.exceptionOrNull() is StandException)
        assertEquals(listOf("1001"), unlinkedChats)
        assertFalse(manager().state().connected)
    }

    @Test fun staleTelegramListCannotUnlinkUsingAnotherProfile() = runTest {
        manager().connect("supplied-password", true, settings.identityGeneration)
        val original = settings
        change { it.copy(userName = "Иван") }
        assertTrue(runCatching { manager().unlinkTelegram("1001", original.identityGeneration, original.identityCiphertext) }.exceptionOrNull() is StandException)
        assertTrue(unlinkedChats.isEmpty())
    }
    @Test fun pendingConversationIsEncryptedAndBoundToOriginalProfile() = runTest {
        manager().connect("supplied-password", true, settings.identityGeneration)
        val owner = manager().conversationScope()
        manager().saveConversation(owner, "private-pending-question")
        assertFalse(settings.conversationCiphertext.contains("private-pending-question"))
        assertEquals("private-pending-question", manager().loadConversation())
        val ciphertext = settings.conversationCiphertext
        change { it.copy(userName = "Иван") }
        assertTrue(settings.conversationCiphertext.isEmpty())
        assertTrue(runCatching { manager().saveConversation(owner, "old-question") }.isFailure)
        manager().cleanupIfNeeded()
        manager().connect("supplied-password", true, settings.identityGeneration)
        change { it.copy(conversationCiphertext = ciphertext) }
        assertNull(manager().loadConversation())
    }

    @Test fun profileSwitchDuringPersonalRequestRejectsResponse() = runTest {
        manager().connect("supplied-password", true, settings.identityGeneration)
        val owner = manager().conversationScope()
        changeDuringPair = { change { it.copy(userName = "Иван") } }
        assertTrue(runCatching { manager().personalRequest(owner, "POST", listOf("v2", "chat"), "{}", emptyMap()) }.isFailure)
    }
}
