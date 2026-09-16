package ru.petrovich.telemetry.chat

import kotlinx.coroutines.delay
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
    suspend fun reply(history: List<ChatMessage>): String
}

/** Заглушка до подключения настоящего агента. */
class StubChatAgent : ChatAgent {
    override suspend fun reply(history: List<ChatMessage>): String {
        delay(700)
        return "ИИ-агент пока не подключён. Здесь появится ответ о состоянии машин и выявленных аномалиях."
    }
}
