package ru.petrovich.telemetry.data.api

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import ru.petrovich.telemetry.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

// API детекции аномалий «Petrovich AI»: контракт — petrovich-openapi.json (FastAPI, поля snake_case,
// время ISO-8601 с поясом). Аутентификация — HTTP Basic перед Caddy: без заголовка любой путь отвечает 401.

/** Тело ответа детекции (`AnomalyOut` в petrovich-openapi.json). */
@Serializable
data class AnomalyOut(
    val id: Int,
    @SerialName("vehicle_id") val vehicleId: Int,
    /** Время события, ISO-8601 (обычно с «Z»). */
    val ts: String,
    val kind: String,
    /** high | med | low. */
    val severity: String,
    val description: String,
    @SerialName("evidence_json") val evidenceJson: String? = null,
    @SerialName("created_at") val createdAt: String,
    val status: String = "new",
)

/** Машина API (`VehicleOut`): у API свои числовые id — не id AutoGRAPH. */
@Serializable
data class VehicleOut(
    val id: Int,
    val plate: String,
    val model: String,
    @SerialName("pump_model") val pumpModel: String? = null,
    @SerialName("created_at") val createdAt: String,
)

/** Ответ `/health`: `{"status":"ok", ...}` — лишние поля сервиса игнорируются. */
@Serializable
data class HealthOut(val status: String)

/** Событие из ленты разбора (`GET /api/v1/anomaly-events`): основное хранилище аномалий API. */
@Serializable
data class AnomalyEventOut(
    val id: Long,
    @SerialName("vehicle_id") val vehicleId: Int,
    /** Имя машины, как в API — сопоставлять с парком приложения не нужно. */
    val vehicle: String,
    val kind: String,
    val title: String,
    /** high | med | low. */
    val severity: String,
    /** new | confirmed | dismissed — состояние разбора на стороне API. */
    val status: String,
    /** Начало события, ISO-8601 с поясом. */
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String? = null,
    /** Объём слива, л — для fuel_drain. */
    @SerialName("volume_l") val volumeL: Double? = null,
    val metric: Double? = null,
    @SerialName("metric_unit") val metricUnit: String? = null,
    @SerialName("producer_name") val producerName: String? = null,
    @SerialName("producer_version") val producerVersion: String? = null,
    @SerialName("is_synthetic") val isSynthetic: Boolean = false,
)

/** Ответ `GET /api/v1/anomaly-events`: одна страница плюс общее число для пагинации. */
@Serializable
data class AnomalyEventsResponse(
    val total: Int,
    val items: List<AnomalyEventOut>,
)

interface PetrovichAiApi {
    /** Проверка доступности и учётных данных: 200 только с принятым логином/паролем. */
    @GET("health")
    suspend fun health(): HealthOut

    /** Машины, известные API. */
    @GET("api/v1/vehicles")
    suspend fun vehicles(): List<VehicleOut>

    /** Запустить детекцию по машине: API считает по данным, загруженным у себя; период запросом не задаётся. */
    @POST("api/v1/anomalies/detect/{vehicleId}")
    suspend fun detect(@Path("vehicleId") vehicleId: Int): List<AnomalyOut>

    /** Лента событий за даты (YYYY-MM-DD) — готовые аномалии парка, одним запросом на страницу. */
    @GET("api/v1/anomaly-events")
    suspend fun events(
        @Query("date_from") dateFrom: String,
        @Query("date_to") dateTo: String,
        @Query("status") status: String = "new",
        @Query("synthetic") synthetic: String = "include",
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
    ): AnomalyEventsResponse
}

/** Сборка клиента API детекции. Логин/пароль запрашиваются на каждый вызов — смена в настройках не требует пересоздания. */
object PetrovichAi {
    fun create(
        baseUrl: String = BuildConfig.PETROVICH_AI_URL,
        credentials: () -> Pair<String, String>,
        client: OkHttpClient = defaultClient(),
    ): PetrovichAiApi =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client.newBuilder().addInterceptor(BasicAuthInterceptor(credentials)).build())
            .addConverterFactory(ApiFactory.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(PetrovichAiApi::class.java)

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                // BASIC — только метод, адрес и код ответа; заголовок с паролем в лог не попадает.
                addInterceptor(HttpLoggingInterceptor { Log.d("PetrovichAI", it) }.apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .build()
}

/**
 * HTTP Basic на каждый запрос. Креды не заданы — запрос не уходит совсем: иначе каждая машина сверки
 * получила бы по 401, а владелец — общее «проверьте логин/пароль» без понятной причины.
 */
private class BasicAuthInterceptor(private val credentials: () -> Pair<String, String>) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val (user, password) = credentials()
        if (user.isBlank() || password.isBlank()) {
            throw IOException("В настройках не заданы логин/пароль API детекции аномалий")
        }
        val request = chain.request().newBuilder()
            .header("Authorization", Credentials.basic(user.trim(), password, Charsets.UTF_8))
            .build()
        return chain.proceed(request)
    }
}
