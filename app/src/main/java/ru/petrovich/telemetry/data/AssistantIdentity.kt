package ru.petrovich.telemetry.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.data.settings.IdentityVault
import ru.petrovich.telemetry.util.runCatchingCancellable

@Serializable
private class IdentityRecord(
    val serverUrl: String,
    val userName: String,
    val schemaId: String,
    val generation: Long,
    val identity: BoundIdentity,
) {
    override fun toString() = "IdentityRecord(секреты скрыты)"
}

data class AssistantIdentityState(
    val connected: Boolean = false,
    val pendingRevocation: Boolean = false,
    val expiresAt: Long? = null,
    val error: String? = null,
)

/** Персональная привязка отдельно от чата v1 и фонового доступа; отключённая пара хранится только для отзыва. */
class AssistantIdentity(
    private val settings: suspend () -> AppSettings,
    private val update: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val vault: IdentityVault,
    private val transport: IdentityTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private suspend fun read(s: AppSettings): IdentityRecord? {
        if (s.identityCiphertext.isEmpty()) return null
        return runCatchingCancellable {
            withContext(Dispatchers.IO) {
                ApiFactory.json.decodeFromString(IdentityRecord.serializer(), vault.open(s.identityCiphertext))
            }
        }.getOrElse { throw StandException("Не удалось открыть защищённую привязку. Удалите её с устройства и подтвердите личность заново") }
    }

    private fun IdentityRecord.matches(s: AppSettings) = generation == s.identityGeneration &&
        serverUrl == s.serverUrl && userName == s.userName && schemaId == s.schemaId && s.assistantOnServer

    suspend fun state(): AssistantIdentityState = mutex.withLock {
        runCatchingCancellable {
            val s = settings()
            val record = read(s) ?: return@withLock AssistantIdentityState()
            val pending = s.identityNeedsRevocation || !record.matches(s)
            AssistantIdentityState(!pending && record.identity.expiresAt > clock(), pending, record.identity.expiresAt)
        }.getOrElse { AssistantIdentityState(error = it.message) }
    }

    /** Возвращает true, если отзыв ещё нужно повторить. Вызывается также после перезапуска приложения. */
    suspend fun cleanupIfNeeded(): Boolean = mutex.withLock { cleanupLocked() }

    private suspend fun clear(ciphertext: String) {
        update { if (it.identityCiphertext == ciphertext) it.copy(identityCiphertext = "", identityNeedsRevocation = false) else it }
    }

    private suspend fun cleanupLocked(): Boolean {
        val s = settings()
        val record = runCatchingCancellable { read(s) }.getOrElse { return s.identityCiphertext.isNotEmpty() } ?: return false
        if (record.identity.expiresAt <= clock()) {
            clear(s.identityCiphertext)
            return false
        }
        if (!s.identityNeedsRevocation && record.matches(s)) return false
        val result = runCatchingCancellable { transport.revoke(record.serverUrl, record.identity) }
        if (result.isSuccess) clear(s.identityCiphertext)
        return result.isFailure
    }

    suspend fun connect(password: String, consent: Boolean, expectedGeneration: Long): Unit = mutex.withLock {
        if (!consent) throw StandException("Подтвердите согласие на проверку аккаунта стендом")
        if (password.isEmpty()) throw StandException("Введите пароль AutoGRAPH для подтверждения")
        var s = settings()
        if (s.identityGeneration != expectedGeneration) throw StandException("Подключение изменилось. Подтвердите согласие заново")
        if (!s.assistantOnServer || s.userName.isBlank() || s.schemaId.isBlank()) {
            throw StandException("Сначала войдите в AutoGRAPH и выберите схему")
        }
        if (s.identityCiphertext.isNotEmpty()) {
            update { it.copy(identityNeedsRevocation = it.identityCiphertext.isNotEmpty()) }
            if (cleanupLocked() || settings().identityCiphertext.isNotEmpty()) {
                throw StandException("Не удалось отозвать прежнюю привязку. Повторите позже или удалите её с устройства")
            }
        }
        s = settings()
        if (s.identityGeneration != expectedGeneration) throw StandException("Подключение изменилось. Подтвердите согласие заново")
        val identity = transport.bind(s.serverUrl, s.userName, password, s.schemaId)
        val record = IdentityRecord(s.serverUrl, s.userName, s.schemaId, s.identityGeneration, identity)
        var persisted = false
        try {
            if (identity.expiresAt <= clock()) throw StandException("Срок подтверждения истёк. Проверьте время на устройстве")
            val encrypted = runCatchingCancellable {
                withContext(Dispatchers.IO) { vault.seal(ApiFactory.json.encodeToString(IdentityRecord.serializer(), record)) }
            }.getOrElse { throw StandException("Не удалось сохранить подтверждение в защищённом хранилище") }
            var accepted = false
            update { current ->
                accepted = record.matches(current)
                current.copy(identityCiphertext = encrypted, identityNeedsRevocation = !accepted)
            }
            persisted = true
            if (!accepted) {
                cleanupLocked()
                throw StandException("Подключение изменилось во время проверки. Подтвердите личность заново")
            }
        } finally {
            // При отмене/ошибке сохранения стараемся отозвать уже выданную пару, сохраняя отмену корутины.
            if (!persisted) withContext(NonCancellable) {
                withTimeoutOrNull(5_000) { runCatchingCancellable { transport.revoke(record.serverUrl, identity) } }
            }
        }
    }

    suspend fun verify(): Unit = mutex.withLock {
        val s = settings()
        val record = read(s)
        if (record == null || s.identityNeedsRevocation || !record.matches(s) || record.identity.expiresAt <= clock()) {
            throw StandException("Подтвердите личность заново")
        }
        try {
            transport.verify(record.serverUrl, record.identity)
        } catch (error: StandException) {
            if (error.code == 401 || error.code == 403) update {
                if (it.identityCiphertext == s.identityCiphertext) it.copy(identityNeedsRevocation = true) else it
            }
            throw error
        }
        if (!record.matches(settings())) throw StandException("Подключение изменилось во время проверки")
    }

    suspend fun disconnect() = mutex.withLock {
        // Сначала делаем пару недоступной и сохраняем намерение отзыва, затем обращаемся к сети.
        update { it.copy(identityNeedsRevocation = it.identityCiphertext.isNotEmpty()) }
        cleanupLocked()
    }

    private suspend fun active(s: AppSettings): IdentityRecord {
        val record = read(s)
        if (record == null || s.identityNeedsRevocation || !record.matches(s) || record.identity.expiresAt <= clock()) {
            throw StandException("Подтвердите личность заново")
        }
        return record
    }

    private suspend fun unchanged(s: AppSettings, record: IdentityRecord) {
        val current = settings()
        if (current.identityCiphertext != s.identityCiphertext || current.identityNeedsRevocation || !record.matches(current)) {
            throw StandException("Подключение изменилось. Повторите действие в новом профиле")
        }
    }

    suspend fun pairTelegram(code: String, consent: Boolean, expectedGeneration: Long, expectedCiphertext: String): TelegramIdentityLink = mutex.withLock {
        if (!consent) throw StandException("Подтвердите согласие на привязку личного чата")
        val s = settings()
        if (s.identityGeneration != expectedGeneration || s.identityCiphertext != expectedCiphertext) {
            throw StandException("Подключение изменилось. Подтвердите согласие заново")
        }
        val record = active(s)
        val link = transport.pairTelegram(record.serverUrl, record.identity, code)
        try {
            unchanged(s, record)
        } catch (error: StandException) {
            runCatchingCancellable { transport.unlinkTelegram(record.serverUrl, record.identity, link.chatId) }
            throw error
        }
        link
    }

    suspend fun telegramLinks(): List<TelegramIdentityLink> = mutex.withLock {
        val s = settings()
        val record = active(s)
        val links = transport.telegramLinks(record.serverUrl, record.identity)
        unchanged(s, record)
        links
    }

    suspend fun unlinkTelegram(chatId: String, expectedGeneration: Long, expectedCiphertext: String): Unit = mutex.withLock {
        val s = settings()
        if (s.identityGeneration != expectedGeneration || s.identityCiphertext != expectedCiphertext) {
            throw StandException("Подключение изменилось. Повторите действие в новом профиле")
        }
        val record = active(s)
        transport.unlinkTelegram(record.serverUrl, record.identity, chatId)
    }

    /** Только после отдельного предупреждения: серверная пара может действовать до исходного срока. */
    suspend fun forgetLocally() = mutex.withLock {
        update { it.copy(identityCiphertext = "", identityNeedsRevocation = false) }
    }
}
