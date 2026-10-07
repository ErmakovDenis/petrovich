package ru.petrovich.telemetry.chat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import ru.petrovich.telemetry.BuildConfig
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.data.StandSession
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ошибка стенда с текстом, который можно показать пользователю. */
class StandException(message: String) : Exception(message)

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
 * возвращается текстом. Доступ — токен сессии AutoGRAPH пользователя и id схемы в заголовках.
 */
class RemoteChatAgent(
    private val serverUrl: suspend () -> String,
    /** Аргумент — токен, который стенд отверг (null — первая попытка). */
    private val session: suspend (rejectedToken: String?) -> StandSession,
    private val client: OkHttpClient = defaultClient(),
    private val utcOffsetMinutes: () -> Int = { ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60 },
) : ContextualChatAgent {

    override suspend fun reply(history: List<ChatMessage>): String = reply(history, null)

    override suspend fun reply(history: List<ChatMessage>, anomaly: Anomaly?): String {
        val url = serverUrl().trim().trimEnd('/').plus("/v1/chat").toHttpUrlOrNull()
            ?: throw StandException("Неверный адрес стенда в настройках")
        val body = ApiFactory.json.encodeToString(ChatRequest.serializer(), request(history, anomaly))
        // Токен мог истечь: стенд ответит 401 — входим в AutoGRAPH заново и повторяем один раз.
        val first = session(null)
        var (code, text) = post(url.toString(), first, body)
        if (code == 401) {
            val retry = post(url.toString(), session(first.token), body)
            code = retry.first
            text = retry.second
        }
        return when (code) {
            200 -> runCatchingCancellable { ApiFactory.json.decodeFromString(ChatResponse.serializer(), text).reply }
                .getOrNull()?.takeIf { it.isNotBlank() }
                ?: throw StandException("Стенд вернул пустой ответ")
            401 -> throw StandException("Стенд не принял сессию AutoGRAPH — войдите заново в настройках")
            else -> throw StandException(detail(text) ?: "Стенд ответил ошибкой $code")
        }
    }

    /** История без сообщений об ошибках; роли — как в OpenAI chat completions. */
    fun request(history: List<ChatMessage>, anomaly: Anomaly?) = ChatRequest(
        messages = history.filterNot { it.isError }.map {
            ChatTurn(role = if (it.author == Author.USER) "user" else "assistant", content = it.text)
        },
        utcOffsetMinutes = utcOffsetMinutes(),
        anomaly = anomaly,
    )

    private suspend fun post(url: String, session: StandSession, body: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${session.token}")
            .header("X-Schema-Id", session.schemaId)
            .post(body.toRequestBody(JSON))
            .build()
        return try {
            val response = client.newCall(request).await()
            // Тело читается под тем же перехватом: обрыв или таймаут при чтении — тоже «стенд недоступен».
            withContext(Dispatchers.IO) { response.use { it.code to (it.body?.string().orEmpty()) } }
        } catch (e: IOException) {
            throw StandException("Стенд недоступен: проверьте адрес в настройках и сеть")
        }
    }

    /** Стенд отдаёт ошибки как `{"detail": "<текст по-русски>"}`. */
    private fun detail(text: String): String? = runCatching {
        (ApiFactory.json.parseToJsonElement(text).jsonObject["detail"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Стенд отвечает не дольше FS_CHAT_TIMEOUT_SECONDS (100 с по умолчанию).
            .readTimeout(120, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    // BASIC — только метод, адрес и код ответа; заголовок с токеном в лог не попадает.
                    addInterceptor(HttpLoggingInterceptor { Log.d("Stand", it) }.apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()
    }
}

/** Запрос OkHttp как suspend-функция: отмена корутины (закрыли чат) отменяет и запрос. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _ -> response.close() }
    })
}
