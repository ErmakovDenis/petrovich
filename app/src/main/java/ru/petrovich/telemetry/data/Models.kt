package ru.petrovich.telemetry.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Сериализация — контракт со стендом (ml/fleet_service, schemas/contract.py): camelCase, время без пояса.

@Serializable
data class Vehicle(
    val id: String,
    val name: String,
    val group: String? = null,
)

/** Разделы табличных данных / графиков. */
@Serializable
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

@Serializable
data class ParameterInfo(
    val name: String,
    val caption: String,
    val unit: String?,
    val category: MetricCategory,
)

@Serializable
data class ParameterColumn(
    val parameter: ParameterInfo,
    /** Значения, выровненные по [CategoryTable.timestamps]; null — нет данных. */
    val values: List<Double?>,
)

@Serializable
data class CategoryTable(
    val category: MetricCategory,
    val timestamps: List<@Serializable(LocalDateTimeSerializer::class) LocalDateTime>,
    val columns: List<ParameterColumn>,
)

@Serializable
data class VehicleTelemetry(
    val vehicle: Vehicle,
    @Serializable(LocalDateTimeSerializer::class) val from: LocalDateTime,
    @Serializable(LocalDateTimeSerializer::class) val to: LocalDateTime,
    val tables: Map<MetricCategory, CategoryTable>,
)

data class Schema(val id: String, val name: String)

/** Местное время без пояса: `2026-09-16T10:00:00` (секунды всегда, как у стенда); при разборе — любой ISO-вид. */
object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    private val format: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    override val descriptor = PrimitiveSerialDescriptor("LocalDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(value.format(format))

    override fun deserialize(decoder: Decoder): LocalDateTime = LocalDateTime.parse(decoder.decodeString())
}
