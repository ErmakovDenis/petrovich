package ru.petrovich.telemetry.chat

import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.data.StandClient
import ru.petrovich.telemetry.data.StandException
import ru.petrovich.telemetry.data.StandSession
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Instant
import java.time.ZoneId

/** Тело `POST /v1/chat` стенда (ml/fleet_service, schemas/chat.py). */
@Serializable
data class ChatRequest(
    val messages: List<ChatTurn>,
    /** Смещение пояса пользователя от UTC в минутах — то же, что UTCOffset при входе в AutoGRAPH. */
    val utcOffsetMinutes: Int,
    /** Аномалия, из карточки которой открыт чат. */
    val anomaly: Anomaly? = null,
)

@Serializable
data class ChatTurn(val role: String, val content: String)

@Serializable
data class ChatResponse(val reply: String)

/**
 * Ассистент на стенде: история диалога и контекст аномалии уходят в `POST <стенд>/v1/chat`, ответ модели
 * возвращается текстом. Доступ — токен сессии AutoGRAPH пользователя и id схемы в заголовках ([StandClient]).
 */
class RemoteChatAgent(
    private val stand: StandClient,
    private val utcOffsetMinutes: () -> Int = { ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60 },
) : ContextualChatAgent {

    constructor(
        serverUrl: suspend () -> String,
        /** Аргумент — токен, который стенд отверг (null — первая попытка). */
        session: suspend (rejectedToken: String?) -> StandSession,
        client: OkHttpClient = StandClient.defaultClient(),
        utcOffsetMinutes: () -> Int = { ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60 },
    ) : this(StandClient(serverUrl, session, client), utcOffsetMinutes)

    override suspend fun reply(history: List<ChatMessage>): String = reply(history, null)

    override suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?): String {
        val body = ApiFactory.json.encodeToString(ChatRequest.serializer(), request(history, anomaly))
        val text = stand.post("v1/chat", body)
        return runCatchingCancellable { ApiFactory.json.decodeFromString(ChatResponse.serializer(), text).reply }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?: throw StandException("Стенд вернул пустой ответ")
    }

    /** История без сообщений об ошибках; роли — как в OpenAI chat completions. */
    fun request(history: List<ChatMessage>, anomaly: Anomaly?) = ChatRequest(
        messages = history.filterNot { it.isError }.map {
            ChatTurn(role = if (it.author == Author.USER) "user" else "assistant", content = it.text)
        },
        utcOffsetMinutes = utcOffsetMinutes(),
        anomaly = anomaly,
    )
}
