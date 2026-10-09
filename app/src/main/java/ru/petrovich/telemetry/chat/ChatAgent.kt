package ru.petrovich.telemetry.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.data.settings.SettingsRepository
import java.util.UUID

enum class Author { USER, AGENT }

/** Что с черновиком письма в сообщении. */
enum class DraftState { PENDING, SENDING, CANCELLING, SENT, CANCELLED, FAILED }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val author: Author,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    /** Черновик письма от ассистента: показывается с кнопками «Отправить» и «Отменить». */
    val draft: EmailDraft? = null,
    val draftState: DraftState = DraftState.PENDING,
    /** Почему письмо не отправлено (отказ стенда или ошибка SMTP). */
    val draftError: String? = null,
)

/** Ответ агента: текст и (только у ассистента на стенде) черновики писем. */
data class AgentReply(val text: String, val drafts: List<EmailDraft> = emptyList())

/**
 * ИИ-агент, отвечающий о состоянии автопарка.
 * Реализация должна получать историю диалога и возвращать ответ агента.
 * Для контекста агенту можно передавать аномалии из AnomalyStore и данные TelemetryRepository.
 */
interface ChatAgent {
    /** false — заглушка: интерфейс должен честно сказать, что настоящего агента нет. */
    val connected: Boolean get() = true

    suspend fun reply(history: List<ChatMessage>): AgentReply
}

/** Агент, которому можно передать аномалию, из карточки которой открыт чат. */
interface ContextualChatAgent : ChatAgent {
    suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?): AgentReply
}

/**
 * Выбирает агента на каждый вызов по настройкам: ассистент на стенде ([AppSettings.assistantOnServer])
 * или заглушка, как раньше. [connectedState] — для заголовка чата, следует за настройками.
 */
class SwitchingChatAgent(
    /** [SettingsRepository.settings]. */
    private val settings: Flow<AppSettings>,
    private val stub: ChatAgent,
    private val remote: ContextualChatAgent,
    scope: CoroutineScope,
) : ContextualChatAgent {
    val connectedState: StateFlow<Boolean> = settings
        .map { pick(it).connected }
        .stateIn(scope, SharingStarted.Eagerly, stub.connected)

    override val connected: Boolean get() = connectedState.value

    private fun pick(s: AppSettings): ChatAgent = if (s.assistantOnServer) remote else stub

    override suspend fun reply(history: List<ChatMessage>): AgentReply = reply(history, null)

    override suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?): AgentReply =
        when (val agent = pick(settings.first())) {
            is ContextualChatAgent -> agent.reply(history, anomaly)
            else -> agent.reply(history)
        }
}

/** Заглушка до подключения настоящего агента. */
class StubChatAgent : ChatAgent {
    override val connected = false

    override suspend fun reply(history: List<ChatMessage>): AgentReply {
        delay(700)
        return AgentReply("ИИ-агент пока не подключён. Здесь появится ответ о состоянии машин и выявленных аномалиях.")
    }
}
