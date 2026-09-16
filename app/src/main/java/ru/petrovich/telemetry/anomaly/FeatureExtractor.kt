package ru.petrovich.telemetry.anomaly

import ru.petrovich.telemetry.data.VehicleTelemetry
import java.time.LocalDateTime

/**
 * Матрица признаков для ML-модели: строка = момент времени, столбец = параметр.
 * Пропуски заполняются последним известным значением (или 0).
 */
class FeatureMatrix(
    val timestamps: List<LocalDateTime>,
    /** Порядок столбцов (имена параметров). */
    val featureNames: List<String>,
    val rows: Array<FloatArray>,
)

object FeatureExtractor {

    fun extract(telemetry: VehicleTelemetry): FeatureMatrix {
        val columns = telemetry.tables.values.flatMap { it.columns }
        val timestamps = telemetry.tables.values.firstOrNull()?.timestamps.orEmpty()
        val lastKnown = FloatArray(columns.size)
        val rows = Array(timestamps.size) { i ->
            FloatArray(columns.size) { j ->
                val v = columns[j].values.getOrNull(i)?.toFloat()
                if (v != null) lastKnown[j] = v
                lastKnown[j]
            }
        }
        return FeatureMatrix(timestamps, columns.map { it.parameter.name }, rows)
    }
}
