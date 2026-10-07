package ru.petrovich.telemetry.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import ru.petrovich.telemetry.BuildConfig
import ru.petrovich.telemetry.data.api.ApiFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ошибка стенда с текстом, который можно показать пользователю. */
class StandException(message: String) : Exception(message)

/**
 * HTTP-доступ к стенду fleet_service от имени пользователя: токен сессии AutoGRAPH и id схемы в заголовках.
 * Истёкший токен: стенд отвечает 401 — входим в AutoGRAPH заново и повторяем запрос один раз.
 * Ошибки стенда (`{"detail": "<текст по-русски>"}`) и недоступность сети — [StandException] с понятным текстом.
 */
class StandClient(
    private val serverUrl: suspend () -> String,
    /** Аргумент — токен, который стенд отверг (null — первая попытка). */
    private val session: suspend (rejectedToken: String?) -> StandSession,
    private val client: OkHttpClient = defaultClient(),
) {
    /**
     * GET `<стенд>/<path>?<query>`; тело ответа 200. [readTimeoutSeconds] — для долгих запросов (телеметрия за 7 дней —
     * 28 последовательных запросов стенда к AutoGRAPH, стенд молчит до готовности ответа).
     */
    suspend fun get(path: String, query: Map<String, String> = emptyMap(), readTimeoutSeconds: Long? = null): String =
        call(url(path, query), null, readTimeoutSeconds)

    /** POST JSON на `<стенд>/<path>`; тело ответа 200. */
    suspend fun post(path: String, json: String): String = call(url(path, emptyMap()), json, null)

    private suspend fun url(path: String, query: Map<String, String>): HttpUrl {
        val base = serverUrl().trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw StandException("Неверный адрес стенда в настройках")
        return base.newBuilder()
            .addPathSegments(path.trim('/'))
            .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
    }

    private suspend fun call(url: HttpUrl, json: String?, readTimeoutSeconds: Long?): String {
        val http = readTimeoutSeconds?.let { client.newBuilder().readTimeout(it, TimeUnit.SECONDS).build() } ?: client
        val first = session(null)
        var (code, text) = send(http, url, first, json)
        if (code == 401) {
            val retry = send(http, url, session(first.token), json)
            code = retry.first
            text = retry.second
        }
        return when (code) {
            200 -> text
            401 -> throw StandException("Стенд не принял сессию AutoGRAPH — войдите заново в настройках")
            else -> throw StandException(detail(text) ?: "Стенд ответил ошибкой $code")
        }
    }

    private suspend fun send(client: OkHttpClient, url: HttpUrl, session: StandSession, json: String?): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${session.token}")
            .header("X-Schema-Id", session.schemaId)
            .apply { if (json != null) post(json.toRequestBody(JSON)) }
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
            // Чат — не дольше FS_CHAT_TIMEOUT_SECONDS (100 с); телеметрия задаёт свой срок (readTimeoutSeconds).
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

/** Запрос OkHttp как suspend-функция: отмена корутины (закрыли экран) отменяет и запрос. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { _ -> response.close() }
    })
}
