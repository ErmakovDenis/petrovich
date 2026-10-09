package ru.petrovich.telemetry.data

import java.time.LocalDate
import java.time.LocalDateTime

data class Vehicle(
    val id: String,
    val name: String,
    val group: String? = null,
)

/** Разделы табличных данных / графиков. */
enum class MetricCategory(val title: String, val keywords: List<String>) {
    FUEL("Топливо", listOf("топлив", "fuel", "бак", "дут", "lls", "расход", "заправ", "слив")),
    POWER("Аккумулятор", listOf("аккум", "питан", "напряж", "voltage", "power", "батар", "бортов", "vbat")),
    ENGINE("Двигатель", listOf("двигат", "engine", "оборот", "rpm", "охлажд", "coolant", "масл", "oil", "моточас")),
    MOTION("Движение", listOf("скорост", "speed", "пробег", "odometer", "mileage", "одометр"));

    companion object {
        fun classify(vararg texts: String?): MetricCategory? {
            val haystack = texts.filterNotNull().joinToString(" ").lowercase()
            return entries.firstOrNull { c -> c.keywords.any { it in haystack } }
        }
    }
}

data class ParameterInfo(
    val name: String,
    val caption: String,
    val unit: String?,
    val category: MetricCategory,
)

data class ParameterColumn(
    val parameter: ParameterInfo,
    /** Значения, выровненные по [CategoryTable.timestamps]; null — нет данных. */
    val values: List<Double?>,
)

data class CategoryTable(
    val category: MetricCategory,
    val timestamps: List<LocalDateTime>,
    val columns: List<ParameterColumn>,
)

data class VehicleTelemetry(
    val vehicle: Vehicle,
    val from: LocalDateTime,
    val to: LocalDateTime,
    val tables: Map<MetricCategory, CategoryTable>,
)

data class Schema(val id: String, val name: String)

// ---------- Карта машины (3.14) ----------
//
// АвтоГРАФ Service API, с которым сегодня работает это приложение, не отдаёт ни широту/долготу,
// ни геозоны (см. data/api/Dto.kt, data/api/AutoGraphApi.kt — там нет ни одного такого поля).
// Поэтому реальных GPS-координат у нас честно нет, а карта в приложении — схема (как и в макете,
// см. стр. 20: «Карта в прототипе — схема»), не привязанная к географии. Координаты ниже —
// условные 0..1 по области схематичной карты, а не широта/долгота.

/** Точка трека на схематичной карте: условные координаты 0..1 (не географические). */
data class RoutePoint(val x: Float, val y: Float, val at: LocalDateTime)

/** Прямоугольная геозона (база/объект) на той же условной сетке 0..1. */
data class GeoZone(val x: Float, val y: Float, val width: Float, val height: Float, val label: String)

enum class TripEventKind { DEPARTURE, DRIVE, STOP, ANOMALY }

/** Строка списка поездок под картой: «07:10–09:40 · В пути · 95 км» и т. п. */
data class TripEvent(
    val from: LocalDateTime,
    val to: LocalDateTime? = null,
    val kind: TripEventKind,
    val text: String,
    val detail: String? = null,
)

/**
 * Маршрут и геозона машины за один день для схематичной карты (3.14).
 * Возвращается только источником, который реально знает местоположение (сегодня — только демо-данные,
 * см. [ru.petrovich.telemetry.data.DemoTelemetryRepository]). Для реального АвтоГРАФ этого источника
 * нет — [ru.petrovich.telemetry.data.TelemetryRepository.route] честно возвращает null.
 */
data class VehicleRoute(
    val day: LocalDate,
    val base: GeoZone?,
    val points: List<RoutePoint>,
    val events: List<TripEvent>,
    val movedToday: Boolean,
)
