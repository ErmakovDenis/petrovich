package ru.petrovich.telemetry.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.data.PersonalHttpResponse
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandRequests
import ru.petrovich.telemetry.data.api.ApiFactory
import java.util.UUID

/** Контракт /v2/chat; в запросе один вопрос, история приходит с сервера. */
@Serializable
data class DurableChatRequest(
    val conversationId: String? = null,
    val clientMessageId: String,
    val expectedVersion: Int = 0,
    val content: String,
    val utcOffsetMinutes: Int,
    val anomalyId: String? = null,
    val allowEmail: Boolean = false,
    val emailContacts: List<EmailContact>? = null,
)

@Serializable
data class DurableDraft(
    val id: String, val recipients: List<EmailRecipient>, val subject: String, val body: String,
    val expiresAt: Long, val status: String, val sentAt: Long? = null,
) {
    fun ui(owner: String) = StandEmailDraft(id, recipients, subject, body, expiresAt, personal = true, status = status, personalScope = owner)
}

@Serializable
data class DurableChatResponse(
    val conversationId: String, val clientMessageId: String, val userMessageId: String,
    val assistantMessageId: String? = null, val version: Int, val state: String,
    val reply: String? = null, val drafts: List<DurableDraft> = emptyList(),
    val unavailableDraftIds: List<String> = emptyList(),
)

@Serializable
private data class Conversation(val id: String, val version: Int, val channel: String = "android")
@Serializable
private data class ConversationList(val conversations: List<Conversation> = emptyList())
@Serializable
private data class StoredMessage(
    val id: String, val clientMessageId: String, val role: String, val content: String,
    val state: String, val createdAt: Long, val drafts: List<DurableDraft> = emptyList(),
)
@Serializable
private data class MessageList(val messages: List<StoredMessage> = emptyList())
@Serializable
private data class PendingConversation(val id: String? = null, val version: Int = 0, val pending: DurableChatRequest? = null, val fresh: Boolean = false, val interruptedContent: String? = null)

/** Только pointer и один незавершённый запрос сохраняются шифротекстом, не локальная копия переписки. */
class DurableChatAgent(
    private val scope: suspend () -> String,
    private val load: suspend () -> String?,
    private val save: suspend (String, String?) -> Unit,
    private val call: suspend (String, String, List<String>, String?, Map<String, String>) -> PersonalHttpResponse,
    private val offset: () -> Int = StandRequests::utcOffsetMinutes,
    private val allowEmail: suspend () -> Boolean = { false },
    private val contacts: suspend () -> List<EmailContact>? = { null },
) : ContextualChatAgent {
    private val mutex = Mutex()

    private suspend fun read(): PendingConversation = load()?.let {
        StandRequests.decode(PendingConversation.serializer(), it)
    } ?: PendingConversation()

    private suspend fun write(owner: String, value: PendingConversation) =
        save(owner, ApiFactory.json.encodeToString(PendingConversation.serializer(), value))

    private fun validateId(value: String): String {
        if (!Regex("[A-Za-z0-9_-]{1,36}").matches(value)) throw StandException("Некорректный идентификатор разговора")
        return value
    }

    override suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?): ChatReply = mutex.withLock {
        val owner = scope()
        val current = read()
        val question = history.lastOrNull { it.author == Author.USER }?.text?.trim().orEmpty()
        if (question.isBlank()) throw StandException("Введите вопрос")
        if (current.interruptedContent == question) throw StandException("Результат действия неизвестен. Сначала проверьте его отдельным вопросом", 409)
        if (current.pending != null && current.pending.content != question) {
            throw StandException("Предыдущий вопрос не завершён. Повторите его или начните новый разговор")
        }
        val payload = current.pending ?: DurableChatRequest(
            current.id, UUID.randomUUID().toString(), current.version, question, offset(),
            anomalyId = anomaly?.takeIf { it.isStandId }?.id, allowEmail = allowEmail(), emailContacts = contacts(),
        )
        // Ключ и всё тело фиксируются ДО сети, чтобы retry/перезапуск не создавал новый turn.
        write(owner, current.copy(pending = payload))
        val response = call(owner, "POST", listOf("v2", "chat"), ApiFactory.json.encodeToString(DurableChatRequest.serializer(), payload), emptyMap())
        if (response.code == 409) {
            val interrupted = ru.petrovich.telemetry.util.runCatchingCancellable {
                StandRequests.decode(DurableChatResponse.serializer(), response.body)
            }.getOrNull()
            if (interrupted?.state == "interrupted") {
                if (interrupted.clientMessageId != payload.clientMessageId || (current.id != null && interrupted.conversationId != current.id)) {
                    throw StandException("Стенд вернул ответ другого запроса")
                }
                write(owner, PendingConversation(validateId(interrupted.conversationId), interrupted.version, interruptedContent = question))
                throw StandException("Результат начатого действия неизвестен. Проверьте его перед новым вопросом", 409)
            }
            val id = current.id
            val version = if (id != null) metadata(owner, id).version else current.version
            write(owner, current.copy(version = version, pending = null))
            throw StandException("Разговор изменился на другом устройстве. История обновлена; повторите вопрос", 409)
        }
        val result = StandRequests.decode(DurableChatResponse.serializer(), response.body)
        validateId(result.conversationId)
        if (result.clientMessageId != payload.clientMessageId || (current.id != null && result.conversationId != current.id)) {
            throw StandException("Стенд вернул ответ другого запроса")
        }
        if (response.code == 202 || result.state == "processing") {
            write(owner, current.copy(id = result.conversationId, pending = payload))
            throw StandException("Вопрос ещё обрабатывается. Повторите тот же вопрос чуть позже", 202)
        }
        if (result.state != "completed" || result.reply.isNullOrBlank() || result.version < 1) {
            throw StandException("Стенд вернул незавершённый ответ")
        }
        write(owner, PendingConversation(result.conversationId, maxOf(current.version, result.version)))
        ChatReply(result.reply, standDrafts = result.drafts.map { it.ui(owner) })
    }

    private suspend fun metadata(owner: String, id: String) = StandRequests.decode(Conversation.serializer(),
        call(owner, "GET", listOf("v2", "conversations", validateId(id)), null, emptyMap()).body)

    override suspend fun history(): List<ChatMessage> = mutex.withLock {
        val owner = scope()
        var current = read()
        if (current.id == null) {
            current.pending?.let { pending ->
                return@withLock listOf(ChatMessage(id = pending.clientMessageId, author = Author.USER, text = pending.content))
            }
            if (current.fresh) return@withLock emptyList()
            val latest = StandRequests.decode(ConversationList.serializer(), call(owner, "GET", listOf("v2", "conversations"), null, emptyMap()).body)
                .conversations.firstOrNull { it.channel == "android" }
            if (latest == null) return@withLock emptyList()
            current = current.copy(id = validateId(latest.id), version = latest.version)
        }
        val id = current.id ?: return@withLock emptyList()
        val meta = metadata(owner, id)
        if (meta.channel != "android" || meta.id != id) throw StandException("Стенд вернул другой разговор")
        current = current.copy(version = meta.version)
        write(owner, current)
        // Версия даёт нижнюю границу числа сообщений; дочитываем страницы до конца, храним в UI последние 100.
        var start = maxOf(0L, meta.version.toLong() * 2 - 100)
        val rows = ArrayDeque<StoredMessage>()
        do {
            val page = StandRequests.decode(MessageList.serializer(), call(owner, "GET",
                listOf("v2", "conversations", id, "messages"), null, mapOf("limit" to "100", "offset" to start.toString())).body).messages
            page.forEach { rows.addLast(it); if (rows.size > 100) rows.removeFirst() }
            start += page.size
        } while (page.size == 100)
        if (current.pending != null && rows.any { it.clientMessageId == current.pending?.clientMessageId && it.role == "assistant" && it.state == "committed" }) {
            write(owner, current.copy(pending = null))
        }
        rows.flatMap { row ->
            if (row.role !in setOf("user", "assistant")) throw StandException("Неизвестная роль в истории")
            listOf(ChatMessage(id = row.id, author = if (row.role == "user") Author.USER else Author.AGENT,
                text = row.content, timestamp = row.createdAt)) + row.drafts.map { draft ->
                ChatMessage(id = "draft:${draft.id}", author = Author.AGENT, text = "", standDraft = draft.ui(owner),
                    draftState = draft.ui(owner).uiState())
            }
        }
    }

    override suspend fun newConversation(): Boolean = mutex.withLock {
        val owner = scope()
        val conversation = StandRequests.decode(Conversation.serializer(), call(owner, "POST", listOf("v2", "conversations"), "{\"channel\":\"android\"}", emptyMap()).body)
        write(owner, PendingConversation(validateId(conversation.id), conversation.version))
        true
    }

    override suspend fun deleteConversation(): Boolean = mutex.withLock {
        val owner = scope()
        val current = read()
        current.id?.let { call(owner, "DELETE", listOf("v2", "conversations", validateId(it)), null, emptyMap()) }
        write(owner, PendingConversation(fresh = true))
        true
    }

}

class HistorySwitchingRemote(
    private val legacy: ContextualChatAgent,
    private val durable: ContextualChatAgent,
    private val enabled: suspend () -> Boolean,
) : ContextualChatAgent {
    override suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?) =
        if (enabled()) durable.reply(history, anomaly) else legacy.reply(history, anomaly)
    override suspend fun history(): List<ChatMessage>? = if (enabled()) durable.history() else null
    override suspend fun newConversation() = if (enabled()) durable.newConversation() else false
    override suspend fun deleteConversation() = if (enabled()) durable.deleteConversation() else false
}
