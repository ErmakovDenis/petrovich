package ru.petrovich.telemetry.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Преобразует ответы GetTripTables в таблицы с равномерным шагом.
 *
 * AutoGRAPH отдаёт точку каждые ~10 с (с повторами времени) — сотни тысяч значений в сутки.
 * Ответ читается потоково ([JsonReader]) и сразу сворачивается в интервалы, без построения
 * дерева JSON: разбор в JsonElement на суточных данных приводил к нехватке памяти на устройстве.
 */
object TripTablesMapper {

    fun bucketFor(period: Duration): Duration = when {
        period <= Duration.ofHours(6) -> Duration.ofMinutes(1)
        period <= Duration.ofHours(24) -> Duration.ofMinutes(2)
        period <= Duration.ofDays(3) -> Duration.ofMinutes(5)
        else -> Duration.ofMinutes(15)
    }

    /** Накапливает значения из нескольких ответов (частей периода) в общую сетку интервалов. */
    class Builder(
        private val vehicle: Vehicle,
        private val from: LocalDateTime,
        private val to: LocalDateTime,
        private val parameters: List<ParameterInfo>,
        aggregation: Map<String, Aggregation>,
        bucket: Duration = bucketFor(Duration.between(from, to)),
    ) {
        private val start = from.truncatedTo(ChronoUnit.MINUTES)
        private val bucketSeconds = bucket.seconds
        private val count = (Duration.between(start, to).seconds / bucketSeconds + 1).toInt()
        private val accumulators = parameters.associate { it.name to Accumulator(count, aggregation[it.name] ?: Aggregation.MEAN) }

        /** Читает один ответ GetTripTables (словарь «ID прибора → данные»). */
        fun read(reader: Reader) {
            JsonReader(reader).use { json ->
                if (json.peek() != JsonToken.BEGIN_OBJECT) { json.skipValue(); return }
                json.beginObject()
                while (json.hasNext()) {
                    json.nextName()
                    readDevice(json)
                }
                json.endObject()
            }
        }

        private fun readDevice(json: JsonReader) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) { json.skipValue(); return }
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == "Trips" && json.peek() == JsonToken.BEGIN_ARRAY) {
                    json.beginArray()
                    while (json.hasNext()) readTrip(json)
                    json.endArray()
                } else {
                    json.skipValue()
                }
            }
            json.endObject()
        }

        private fun readTrip(json: JsonReader) {
            if (json.peek() != JsonToken.BEGIN_OBJECT) { json.skipValue(); return }
            // Индекс интервала для каждой точки; -1 — вне периода/не распознано.
            var buckets: IntArray? = null
            // Если «Values» встретится раньше «DT», значения буферизуются.
            val pending = mutableMapOf<String, DoubleArray>()

            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "DT" -> buckets = readBuckets(json)
                    "Values" -> {
                        json.beginArray()
                        while (json.hasNext()) readColumn(json, buckets, pending)
                        json.endArray()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()

            val b = buckets ?: return
            pending.forEach { (name, values) -> accumulators[name]?.let { acc -> values.forEachIndexed { i, v -> add(acc, b, i, v) } } }
        }

        private fun readBuckets(json: JsonReader): IntArray {
            var result = IntArray(4096)
            var n = 0
            json.beginArray()
            while (json.hasNext()) {
                val idx = if (json.peek() == JsonToken.STRING) bucketIndex(json.nextString()) else { json.skipValue(); -1 }
                if (n == result.size) result = result.copyOf(n * 2)
                result[n++] = idx
            }
            json.endArray()
            return result.copyOf(n)
        }

        /** Буфер значений колонки; переиспользуется, чтобы не выделять память на каждую колонку. */
        private var columnBuffer = DoubleArray(4096)

        private fun readColumn(json: JsonReader, buckets: IntArray?, pending: MutableMap<String, DoubleArray>) {
            // В ответе AutoGRAPH «Values» идёт раньше «Name», поэтому значения сначала читаются в буфер.
            var name: String? = null
            var size = -1
            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "Name" -> name = if (json.peek() == JsonToken.STRING) json.nextString() else { json.skipValue(); null }
                    "Values" -> {
                        if (json.peek() != JsonToken.BEGIN_ARRAY) { json.skipValue(); continue }
                        size = 0
                        json.beginArray()
                        while (json.hasNext()) {
                            if (size == columnBuffer.size) columnBuffer = columnBuffer.copyOf(size * 2)
                            columnBuffer[size++] = readNumber(json)
                        }
                        json.endArray()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()

            val acc = name?.let { accumulators[it] } ?: return
            if (size <= 0) return
            if (buckets != null) {
                for (i in 0 until size) add(acc, buckets, i, columnBuffer[i])
            } else {
                pending[name!!] = columnBuffer.copyOf(size)
            }
        }

        private fun add(acc: Accumulator, buckets: IntArray, i: Int, v: Double) {
            if (i >= buckets.size || v.isNaN()) return
            val b = buckets[i]
            if (b >= 0) acc.add(b, v)
        }

        private fun bucketIndex(dt: String): Int {
            val time = parseDateTime(dt) ?: return -1
            val idx = Duration.between(start, time).seconds / bucketSeconds
            return if (idx in 0 until count) idx.toInt() else -1
        }

        fun build(): VehicleTelemetry {
            val timestamps = List(count) { start.plusSeconds(it * bucketSeconds) }
            val columns = parameters.map { ParameterColumn(it, accumulators.getValue(it.name).result()) }
                // Отсутствующие / неподключённые датчики (всегда 0); состояния вроде «Зажигание» не скрываем.
                .filter { col ->
                    col.parameter.name in AutoGraphParameters.statusParameters && col.values.any { it != null } ||
                        col.values.any { it != null && it != 0.0 }
                }
                .let(::dropDuplicates)
            val tables = columns.groupBy { it.parameter.category }
                .mapValues { (category, cols) -> CategoryTable(category, timestamps, cols) }
            return VehicleTelemetry(vehicle, from, to, tables)
        }
    }

    /** Число, bool (1/0) или числовая строка; иначе NaN. Без регулярных выражений — вызывается сотни тысяч раз. */
    private fun readNumber(json: JsonReader): Double = when (json.peek()) {
        JsonToken.NUMBER -> json.nextDouble()
        JsonToken.BOOLEAN -> if (json.nextBoolean()) 1.0 else 0.0
        JsonToken.STRING -> {
            val s = json.nextString()
            val c = s.firstOrNull()
            if (c != null && (c.isDigit() || c == '-') && ':' !in s) {
                try { s.replace(',', '.').toDouble() } catch (_: NumberFormatException) { Double.NaN }
            } else Double.NaN
        }
        else -> { json.skipValue(); Double.NaN }
    }

    /** В AutoGRAPH один и тот же ДУТ часто виден под несколькими именами (FL1 = FLTankMain = TankMainFuelLevel). */
    private fun dropDuplicates(columns: List<ParameterColumn>): List<ParameterColumn> {
        val seen = mutableListOf<List<Double?>>()
        return columns.filter { col ->
            if (seen.any { it == col.values }) false else { seen += col.values; true }
        }
    }

    private class Accumulator(size: Int, private val mode: Aggregation) {
        private val sum = DoubleArray(size)
        private val n = IntArray(size)

        fun add(i: Int, v: Double) {
            if (mode.zeroIsMissing && v == 0.0) return
            when (mode) {
                Aggregation.MEAN, Aggregation.MEAN_NONZERO -> sum[i] += v
                Aggregation.MIN, Aggregation.MIN_NONZERO -> sum[i] = if (n[i] == 0) v else minOf(sum[i], v)
                Aggregation.MAX -> sum[i] = if (n[i] == 0) v else maxOf(sum[i], v)
            }
            n[i]++
        }

        fun result(): List<Double?> = sum.indices.map { i ->
            when {
                n[i] == 0 -> null
                mode == Aggregation.MEAN || mode == Aggregation.MEAN_NONZERO -> Math.round(sum[i] / n[i] * 10) / 10.0
                else -> Math.round(sum[i] * 10) / 10.0
            }
        }
    }

    fun parseDateTime(s: String?): LocalDateTime? {
        if (s.isNullOrBlank() || s.length < 19) return null
        return runCatching { LocalDateTime.parse(s.substring(0, 19)) }.getOrNull()
    }
}
