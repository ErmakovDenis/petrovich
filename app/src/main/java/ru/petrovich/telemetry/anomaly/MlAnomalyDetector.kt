package ru.petrovich.telemetry.anomaly

import android.content.Context
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.VehicleTelemetry

/**
 * Интерфейс ML-модели. Реализация получает матрицу признаков и возвращает
 * для каждой строки оценку аномальности в диапазоне 0..1.
 */
interface AnomalyModel {
    /** Ожидаемый порядок признаков (имена параметров API). null — любой. */
    val expectedFeatures: List<String>? get() = null

    fun score(features: FeatureMatrix): FloatArray

    fun close() {}
}

/**
 * МЕСТО ПОД ML-МОДЕЛЬ.
 *
 * Как подключить:
 *  1. Положить файл модели в app/src/main/assets/models/anomaly.tflite (или .onnx).
 *  2. Раскомментировать зависимость LiteRT/ONNX Runtime в app/build.gradle.kts.
 *  3. Реализовать [AnomalyModel] (пример — в комментарии в [loadModel]).
 *  4. Настроить [threshold] и нормализацию признаков под обученную модель.
 *
 * Пока модели нет, [isReady] = false и детектор ничего не возвращает —
 * работает [BaselineAnomalyDetector].
 */
class MlAnomalyDetector(
    private val context: Context,
    private val threshold: Float = 0.8f,
) : AnomalyDetector {

    override val name = "ML-модель"

    private val model: AnomalyModel? by lazy { loadModel() }

    override val isReady: Boolean get() = model != null

    private fun loadModel(): AnomalyModel? {
        val path = "models/anomaly.tflite"
        val exists = runCatching { context.assets.open(path).close() }.isSuccess
        if (!exists) return null
        // Пример для LiteRT (TensorFlow Lite):
        //
        // val buffer = context.assets.openFd(path).use { fd ->
        //     java.io.FileInputStream(fd.fileDescriptor).channel
        //         .map(java.nio.channels.FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        // }
        // val interpreter = org.tensorflow.lite.Interpreter(buffer)
        // return object : AnomalyModel {
        //     override fun score(features: FeatureMatrix): FloatArray {
        //         val out = Array(features.rows.size) { FloatArray(1) }
        //         interpreter.run(features.rows, out)
        //         return FloatArray(out.size) { out[it][0] }
        //     }
        //     override fun close() = interpreter.close()
        // }
        return null
    }

    override suspend fun detect(telemetry: VehicleTelemetry): List<Anomaly> {
        val model = model ?: return emptyList()
        val features = FeatureExtractor.extract(telemetry)
        if (features.rows.isEmpty()) return emptyList()
        val scores = model.score(features)
        val now = System.currentTimeMillis()

        return scores.indices.filter { scores[it] >= threshold }.map { i ->
            val time = features.timestamps[i]
            // Параметр с наибольшим отклонением от медианы — как «виновник» аномалии.
            val column = features.featureNames.indices.maxByOrNull { j ->
                val col = features.rows.map { it[j] }.sorted()
                val median = col[col.size / 2]
                kotlin.math.abs(features.rows[i][j] - median) / (kotlin.math.abs(median) + 1e-3f)
            } ?: 0
            val paramName = features.featureNames.getOrElse(column) { "" }
            val info = telemetry.tables.values.flatMap { it.columns }.firstOrNull { it.parameter.name == paramName }?.parameter
            Anomaly(
                id = "ml|${telemetry.vehicle.id}|$time",
                vehicleId = telemetry.vehicle.id,
                vehicleName = telemetry.vehicle.name,
                category = info?.category ?: MetricCategory.ENGINE,
                parameterName = paramName,
                parameterCaption = info?.caption ?: paramName,
                eventTime = time.toString(),
                detectedAt = now,
                severity = if (scores[i] > 0.95f) Severity.CRITICAL else Severity.WARNING,
                title = "Нетипичное поведение: ${info?.caption ?: paramName}",
                description = "Модель оценила состояние как аномальное (score=${"%.2f".format(scores[i])}).",
                value = features.rows[i][column].toDouble(),
                score = scores[i].toDouble(),
                source = name,
            )
        }
    }
}
