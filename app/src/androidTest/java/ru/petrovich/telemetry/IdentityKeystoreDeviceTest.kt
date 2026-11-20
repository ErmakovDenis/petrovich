package ru.petrovich.telemetry

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.petrovich.telemetry.data.AssistantIdentity
import ru.petrovich.telemetry.data.BoundIdentity
import ru.petrovich.telemetry.data.IdentityTransport
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.settings.AndroidIdentityVault
import ru.petrovich.telemetry.data.settings.SettingsRepository

/** Настоящий Android Keystore и DataStore, только синтетические данные и подставной транспорт. */
@RunWith(AndroidJUnit4::class)
class IdentityKeystoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName == "ru.petrovich.telemetry.p0test")
    }
    private val settings get() = SettingsRepository(context)
    private var offline = false
    private var revocations = 0
    private val transport = object : IdentityTransport {
        override suspend fun bind(serverUrl: String, userName: String, password: String, schemaId: String) =
            BoundIdentity("device-test-principal", "schema-1", "pvi_" + "a".repeat(43), "p0-private-session", System.currentTimeMillis() + 3_600_000)
        override suspend fun verify(serverUrl: String, identity: BoundIdentity) {
            assertEquals("p0-private-session", identity.autographSession)
        }
        override suspend fun revoke(serverUrl: String, identity: BoundIdentity) {
            if (offline) throw StandException("Нет сети")
            revocations++
        }
    }
    private fun manager() = AssistantIdentity({ settings.current() }, { settings.update(it) }, AndroidIdentityVault(), transport)

    private suspend fun prepare() {
        settings.update { it.copy(userName = "p0user", password = "fake-password", schemaId = "schema-1", schemaName = "P0",
            serverUrl = "http://127.0.0.1:18080", identityCiphertext = "", identityNeedsRevocation = false) }
        manager().connect("fake-password", true, settings.current().identityGeneration)
    }

    @Test fun keystoreAndDatastoreRetainOnlyAuthenticatedCiphertext() = runBlocking {
        prepare()
        val saved = settings.current().identityCiphertext
        assertTrue(saved.startsWith("v1."))
        assertTrue(manager().state().connected)
        manager().verify()
        val file = context.filesDir.resolve("datastore/settings.preferences_pb")
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("p0-private-session"))
        assertFalse(bytes.contains("pvi_" + "a".repeat(43)))
        val vault = AndroidIdentityVault()
        val cipher = vault.seal("private-p0-value")
        assertEquals("private-p0-value", AndroidIdentityVault().open(cipher))
        val damaged = cipher.dropLast(2) + "AA"
        assertTrue(runCatching { vault.open(damaged) }.isFailure)
        manager().disconnect()
        assertTrue(settings.current().identityCiphertext.isEmpty())
    }

    @Test fun offlineRevocationAndChangingBackNeverReactivateOldProfile() = runBlocking {
        prepare()
        offline = true
        assertTrue(manager().disconnect())
        assertFalse(manager().state().connected)
        settings.update { it.copy(userName = "another-p0-user") }
        settings.update { it.copy(userName = "p0user") }
        assertFalse(manager().state().connected)
        offline = false
        manager().cleanupIfNeeded()
        assertEquals(1, revocations)
        assertTrue(settings.current().identityCiphertext.isEmpty())
    }

    /** Запускается дважды отдельными процессами, с force-stop между write/read. */
    @Test fun profileSurvivesProcessRestart() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("p0Phase")
        assumeTrue(phase == "write" || phase == "read")
        if (phase == "write") prepare()
        assertTrue(manager().state().connected)
        manager().verify()
        if (phase == "read") manager().disconnect()
    }
}
