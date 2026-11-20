package ru.petrovich.telemetry.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.petrovich.telemetry.data.api.ApiFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
class BoundIdentity(
    val principalId: String,
    val tenantId: String,
    val credential: String,
    val autographSession: String,
    val expiresAt: Long,
) {
    override fun toString() = "BoundIdentity(секреты скрыты)"
}

@Serializable
private class BindIdentityRequest(
    val userName: String,
    val password: String,
    val schemaId: String,
    val utcOffsetMinutes: Int,
    val consent: Boolean,
) {
    override fun toString() = "BindIdentityRequest(секреты скрыты)"
}

@Serializable
private class IdentityStatus(val principalId: String, val tenantId: String)

@Serializable
data class TelegramIdentityLink(val chatId: String, val expiresAt: Long)

@Serializable
private class TelegramIdentityLinks(val links: List<TelegramIdentityLink>)

@Serializable
private class TelegramIdentityPair(val code: String, val consent: Boolean) {
    override fun toString() = "TelegramIdentityPair(код скрыт)"
}

interface IdentityTransport {
    suspend fun bind(serverUrl: String, userName: String, password: String, schemaId: String): BoundIdentity
    suspend fun verify(serverUrl: String, identity: BoundIdentity)
    suspend fun revoke(serverUrl: String, identity: BoundIdentity)
    suspend fun personalRequest(serverUrl: String, identity: BoundIdentity, method: String, segments: List<String>,
                               body: String?, query: Map<String, String>): PersonalHttpResponse =
        throw StandException("Серверная история недоступна")
    suspend fun pairTelegram(serverUrl: String, identity: BoundIdentity, code: String): TelegramIdentityLink =
        throw StandException("Персональная привязка Telegram недоступна")
    suspend fun telegramLinks(serverUrl: String, identity: BoundIdentity): List<TelegramIdentityLink> =
        throw StandException("Персональная привязка Telegram недоступна")
    suspend fun unlinkTelegram(serverUrl: String, identity: BoundIdentity, chatId: String): Unit =
        throw StandException("Персональная привязка Telegram недоступна")
}

data class PersonalHttpResponse(val code: Int, val body: String) {
    override fun toString() = "PersonalHttpResponse(code=$code, содержимое скрыто)"
}

/** Отдельный клиент: пара credential/сессия не заменяется обычным повторным входом StandClient. */
class StandIdentity(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build(),
    private val utcOffsetMinutes: () -> Int = StandRequests::utcOffsetMinutes,
    private val allowLocalHttp: Boolean = false,
) : IdentityTransport {
    // Ни редиректы с паролем/заголовками, ни автоматический повтор Login не разрешены.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()
    private val jsonType = "application/json".toMediaType()

    private fun url(serverUrl: String, action: String): HttpUrl {
        val base = serverUrl.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw StandException("Неверный адрес стенда")
        val local = base.host in setOf("localhost", "127.0.0.1", "::1")
        if ((!base.isHttps && !(allowLocalHttp && local)) || base.username.isNotEmpty() || base.password.isNotEmpty() ||
            base.query != null || base.fragment != null
        ) throw StandException("Для подтверждения личности нужен адрес стенда по HTTPS")
        return base.newBuilder().addPathSegments("v2/identity/$action").build()
    }

    override suspend fun bind(serverUrl: String, userName: String, password: String, schemaId: String): BoundIdentity {
        val body = ApiFactory.json.encodeToString(
            BindIdentityRequest.serializer(), BindIdentityRequest(userName, password, schemaId, utcOffsetMinutes(), true),
        )
        val request = Request.Builder().url(url(serverUrl, "bind")).post(body.toRequestBody(jsonType)).build()
        val identity = StandRequests.decode(BoundIdentity.serializer(), call(request, 200))
        if (identity.tenantId != schemaId || identity.principalId.isBlank() ||
            !Regex("pvi_[A-Za-z0-9_-]{43}").matches(identity.credential) || !safeHeader(identity.autographSession) || identity.expiresAt <= 0
        ) throw StandException("Стенд вернул некорректное подтверждение личности")
        return identity
    }

    private fun safeHeader(value: String) = value.isNotEmpty() && value.length <= 4096 && value.all { it.code in 33..126 }

    private fun request(serverUrl: String, action: String, identity: BoundIdentity): Request.Builder {
        if (!safeHeader(identity.autographSession) || !safeHeader(identity.credential) || !safeHeader(identity.tenantId)) {
            throw StandException("Повреждено подтверждение личности — подключите аккаунт заново")
        }
        return Request.Builder().url(url(serverUrl, action))
            .header("Authorization", "Bearer ${identity.autographSession}")
            .header("X-Schema-Id", identity.tenantId)
            .header("X-Identity-Credential", identity.credential)
    }

    override suspend fun verify(serverUrl: String, identity: BoundIdentity) {
        val status = StandRequests.decode(IdentityStatus.serializer(), call(request(serverUrl, "me", identity).build(), 200))
        if (status.principalId != identity.principalId || status.tenantId != identity.tenantId) {
            throw StandException("Стенд подтвердил другую учётную запись")
        }
    }

    override suspend fun revoke(serverUrl: String, identity: BoundIdentity) {
        call(request(serverUrl, "revoke", identity).post("{}".toRequestBody(jsonType)).build(), 204)
    }

    override suspend fun pairTelegram(serverUrl: String, identity: BoundIdentity, code: String): TelegramIdentityLink {
        val body = ApiFactory.json.encodeToString(TelegramIdentityPair.serializer(), TelegramIdentityPair(code, true))
        return StandRequests.decode(TelegramIdentityLink.serializer(), call(
            request(serverUrl, "telegram/pair", identity).post(body.toRequestBody(jsonType)).build(), 200,
        ))
    }

    override suspend fun telegramLinks(serverUrl: String, identity: BoundIdentity): List<TelegramIdentityLink> =
        StandRequests.decode(TelegramIdentityLinks.serializer(), call(request(serverUrl, "telegram", identity).build(), 200)).links

    override suspend fun unlinkTelegram(serverUrl: String, identity: BoundIdentity, chatId: String) {
        if (!Regex("[1-9][0-9]{0,18}").matches(chatId)) throw StandException("Некорректный личный чат Telegram")
        call(request(serverUrl, "telegram/links/$chatId", identity).delete().build(), 204)
    }

    private suspend fun call(request: Request, expected: Int): String {
        try {
            val (code, body) = http.newCall(request).await().use { response ->
                withContext(Dispatchers.IO) { response.code to response.body?.string().orEmpty() }
            }
            if (code == expected) return body
            // Не показываем произвольный ответ сервера: он может содержать секреты запроса.
            val message = when (code) {
                401, 403 -> "Стенд не подтвердил личность или доступ к схеме — войдите повторно"
                404 -> "Код или привязка не найдены. Получите новый персональный код в личном чате с ботом"
                503 -> "Подтверждение личности на стенде недоступно"
                429 -> "Слишком много попыток входа, повторите позже"
                in 300..399 -> "Стенд перенаправил запрос. Проверьте адрес подключения"
                else -> "Не удалось подтвердить личность (HTTP $code)"
            }
            throw StandException(message, code)
        } catch (error: IOException) {
            throw StandException("Стенд недоступен: проверьте подключение к сети")
        }
    }

    override suspend fun personalRequest(serverUrl: String, identity: BoundIdentity, method: String, segments: List<String>,
                                        body: String?, query: Map<String, String>): PersonalHttpResponse {
        if (method !in setOf("GET", "POST", "DELETE") || segments.firstOrNull() != "v2" ||
            segments.getOrNull(1) !in setOf("chat", "conversations", "emails") ||
            segments.any { !Regex("[A-Za-z0-9_-]+").matches(it) } || query.keys.any { it !in setOf("limit", "offset") } ||
            query.values.any { it.toLongOrNull()?.let { n -> n < 0 } != false }
        ) throw StandException("Некорректный запрос истории")
        val authenticated = request(serverUrl, "me", identity)
        val endpoint = authenticated.build().url.newBuilder().apply {
            repeat(3) { removePathSegment(build().pathSegments.lastIndex) }
            segments.forEach { addPathSegment(it) }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = authenticated.url(endpoint).method(method,
            if (method == "POST") (body ?: "{}").toRequestBody(jsonType) else null).build()
        try {
            val http = this.http.newBuilder().readTimeout(115, TimeUnit.SECONDS).callTimeout(130, TimeUnit.SECONDS).build()
            return http.newCall(request).await().use { response ->
                val text = withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
                if (response.code in setOf(200, 202, 204, 409)) PersonalHttpResponse(response.code, text)
                else throw StandException(when (response.code) {
                    401, 403 -> "Подтвердите личность и доступ к схеме повторно"
                    404 -> "Разговор или письмо удалены либо недоступны"
                    429 -> "Слишком много вопросов — повторите позже"
                    503, 504 -> "Ответ не завершён. Повторите тот же вопрос позже"
                    in 300..399 -> "Стенд перенаправил запрос. Проверьте адрес подключения"
                    else -> "Серверная история недоступна (HTTP ${response.code})"
                }, response.code)
            }
        } catch (error: IOException) {
            throw StandException("Стенд недоступен. Повторите тот же вопрос после восстановления сети")
        }
    }
}
