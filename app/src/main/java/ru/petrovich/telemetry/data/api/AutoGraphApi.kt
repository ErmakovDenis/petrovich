package ru.petrovich.telemetry.data.api

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * AutoGRAPH.NET Service API (https://web.tk-ekat.ru/ServiceAPI/index.html).
 * Все методы принимают токен сессии в параметре `session`, полученный из [login].
 */
interface AutoGraphApi {

    /** Возвращает строку-токен; при неверных данных — HTTP 401. */
    @GET("Login")
    suspend fun login(
        @Query("UserName") userName: String,
        @Query("Password") password: String,
        @Query("UTCOffset") utcOffsetMinutes: Int,
    ): ResponseBody

    @GET("EnumSchemas")
    suspend fun enumSchemas(@Query("session") session: String): List<RSchema>

    @GET("EnumDevices")
    suspend fun enumDevices(
        @Query("session") session: String,
        @Query("schemaID") schemaId: String,
    ): REnumDevices

    /** Ключ словаря — ID прибора. */
    @GET("EnumParameters")
    suspend fun enumParameters(
        @Query("session") session: String,
        @Query("schemaID") schemaId: String,
        @Query("IDs") ids: String,
    ): Map<String, RParameters>

    /**
     * Табличные (онлайн) параметры за период. Ответ большой (~10 с между точками),
     * поэтому возвращается как поток и разбирается [ru.petrovich.telemetry.data.TripTablesMapper].
     * @param from/to формат yyyyMMdd-HHmm в часовом поясе, заданном при Login.
     * @param onlineParams имена параметров через запятую.
     */
    @Streaming
    @GET("GetTripTables")
    suspend fun getTripTables(
        @Query("session") session: String,
        @Query("schemaID") schemaId: String,
        @Query("IDs") ids: String,
        @Query("SD") from: String,
        @Query("ED") to: String,
        @Query("onlineParams") onlineParams: String,
        @Query("tripSplitterIndex") tripSplitterIndex: Int = -1,
    ): ResponseBody
}
