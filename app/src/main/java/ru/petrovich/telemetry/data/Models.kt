package ru.petrovich.telemetry.data

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
