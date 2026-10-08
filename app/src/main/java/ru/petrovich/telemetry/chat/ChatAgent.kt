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

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val author: Author,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
)

/**
 * ИИ-агент, отвечающий о состоянии автопарка.
 * Реализация должна получать историю диалога и возвращать ответ агента.
 * Для контекста агенту можно передавать аномалии из AnomalyStore и данные TelemetryRepository.
 */
interface ChatAgent {
    /** false — заглушка: интерфейс должен честно сказать, что настоящего агента нет. */
    val connected: Boolean get() = true

    suspend fun reply(history: List<ChatMessage>): String
}

/** Агент, которому можно передать аномалию, из карточки которой открыт чат. */
interface ContextualChatAgent : ChatAgent {
    suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?): String
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

    override suspend fun reply(history: List<ChatMessage>): String = reply(history, null)

    override suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?): String =
        when (val agent = pick(settings.first())) {
            is ContextualChatAgent -> agent.reply(history, anomaly)
            else -> agent.reply(history)
        }
}

/** Заглушка до подключения настоящего агента. */
class StubChatAgent : ChatAgent {
    override val connected = false

    override suspend fun reply(history: List<ChatMessage>): String {
        delay(700)
        return "ИИ-агент пока не подключён. Здесь появится ответ о состоянии машин и выявленных аномалиях."
    }
}
