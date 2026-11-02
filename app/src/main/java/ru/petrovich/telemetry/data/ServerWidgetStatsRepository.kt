package ru.petrovich.telemetry.data

/** Компактные суточные суммы со стенда; тяжёлая телеметрия на главной не запрашивается. */
class ServerWidgetStatsRepository(private val stand: StandClient) {
    suspend fun load(from: String, to: String): WidgetStatsResponse {
        val query = mapOf("from" to from, "to" to to)
        return StandRequests.decode(WidgetStatsResponse.serializer(), stand.get("v1/widget-stats", query))
    }
}