package ru.petrovich.telemetry.data.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.petrovich.telemetry.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApiFactory {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun create(baseUrl: String = BuildConfig.API_BASE_URL): AutoGraphApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxAttempts = 3))
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                    })
                }
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AutoGraphApi::class.java)
    }
}

/** Сервер AutoGRAPH периодически не принимает соединение — повторяем запрос при сетевой ошибке. */
private class RetryInterceptor(private val maxAttempts: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var last: IOException? = null
        repeat(maxAttempts) { attempt ->
            try {
                return chain.proceed(chain.request())
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                last = e
                Thread.sleep(1000L * (attempt + 1))
            }
        }
        throw last!!
    }
}
