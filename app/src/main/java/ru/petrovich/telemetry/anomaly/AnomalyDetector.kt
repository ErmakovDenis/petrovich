package ru.petrovich.telemetry.anomaly

import ru.petrovich.telemetry.data.VehicleTelemetry

/**
 * Контракт детектора аномалий. На вход — табличные данные одной машины за период,
 * на выходе — найденные аномалии.
 */
interface AnomalyDetector {
    val name: String

    /** true, если детектор готов работать (например, модель загружена). */
    val isReady: Boolean get() = true

    suspend fun detect(telemetry: VehicleTelemetry): List<Anomaly>
}

/** Объединяет результаты нескольких детекторов. */
class CompositeAnomalyDetector(private val detectors: List<AnomalyDetector>) : AnomalyDetector {
    override val name = detectors.joinToString(" + ") { it.name }

    override suspend fun detect(telemetry: VehicleTelemetry): List<Anomaly> =
        detectors.filter { it.isReady }.flatMap { it.detect(telemetry) }.distinctBy { it.id }
}
