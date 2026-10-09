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
    /** Вместо [anomaly] — только id аномалии из хранилища стенда: стенд берёт её вместе с решением сам. */
    val anomalyId: String? = null,
    /** Письма из чата разрешены (переключатель «Письма из чата»): ассистент может готовить черновики. */
    val allowEmail: Boolean = false,
)

@Serializable
data class ChatTurn(val role: String, val content: String)

@Serializable
data class ChatResponse(val reply: String, val drafts: List<StandEmailDraft> = emptyList())

/**
 * Ассистент на стенде: история диалога и контекст аномалии уходят в `POST <стенд>/v1/chat`, ответ модели
 * возвращается текстом. Доступ — токен сессии AutoGRAPH пользователя и id схемы в заголовках ([StandClient]).
 */
class RemoteChatAgent(
    private val stand: StandClient,
    private val utcOffsetMinutes: () -> Int = { ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60 },
    /** true — хранилище аномалий на стенде: в запрос уходит только id аномалии (если он выдан стендом). */
    private val anomalyIdOnly: suspend () -> Boolean = { false },
    /** true — письма из чата разрешены ([AppSettings.emailsOnServer]). */
    private val allowEmail: suspend () -> Boolean = { false },
) : ContextualChatAgent {

    constructor(
        serverUrl: suspend () -> String,
        /** Аргумент — токен, который стенд отверг (null — первая попытка). */
        session: suspend (rejectedToken: String?) -> StandSession,
        client: OkHttpClient = StandClient.defaultClient(),
        utcOffsetMinutes: () -> Int = { ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60 },
    ) : this(StandClient(serverUrl, session, client), utcOffsetMinutes)

    suspend fun reply(history: List<ChatMessage>): ChatReply = reply(history, null)

    override suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?): ChatReply {
        val idOnly = anomaly != null && anomaly.isStandId && anomalyIdOnly()
        val body = ApiFactory.json.encodeToString(ChatRequest.serializer(), request(history, anomaly, idOnly, allowEmail()))
        val text = stand.post("v1/chat", body)
        val response = runCatchingCancellable { ApiFactory.json.decodeFromString(ChatResponse.serializer(), text) }
            .getOrNull()?.takeIf { it.reply.isNotBlank() }
            ?: throw StandException("Стенд вернул пустой ответ")
        return ChatReply(response.reply, standDrafts = response.drafts)
    }

    /**
     * История без сообщений об ошибках; роли — как в OpenAI chat completions. Черновик письма уходит модели
     * кратким пересказом с тем, что с ним стало, — чтобы она не говорила об отправке, которой не было.
     */
    fun request(history: List<ChatMessage>, anomaly: Anomaly?, idOnly: Boolean = false, allowEmail: Boolean = false) = ChatRequest(
        messages = history.filterNot { it.isError }.map {
            ChatTurn(role = if (it.author == Author.USER) "user" else "assistant", content = it.standDraft?.let { d -> draftSummary(d, it) } ?: it.text)
        },
        utcOffsetMinutes = utcOffsetMinutes(),
        anomaly = anomaly.takeUnless { idOnly },
        anomalyId = anomaly?.id.takeIf { idOnly },
        allowEmail = allowEmail,
    )

    private fun draftSummary(d: StandEmailDraft, m: ChatMessage): String {
        val state = when (m.draftState) {
            DraftState.SENT -> "пользователь отправил"
            DraftState.CANCELLED -> "пользователь отменил"
            DraftState.FAILED -> "отправка не подтверждена: ${m.draftError}"
            DraftState.PENDING, DraftState.SENDING, DraftState.CANCELLING -> "ждёт подтверждения пользователя"
        }
        return "[Черновик письма «${d.subject}» для ${d.recipients.joinToString { it.name }} — $state]"
    }
}
