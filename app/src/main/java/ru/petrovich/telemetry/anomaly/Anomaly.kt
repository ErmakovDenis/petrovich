package ru.petrovich.telemetry.anomaly

import kotlinx.serialization.Serializable
import ru.petrovich.telemetry.data.MetricCategory

@Serializable
enum class Severity(val title: String) { INFO("Инфо"), WARNING("Предупреждение"), CRITICAL("Критично") }

/** Решение владельца по аномалии. */
@Serializable
enum class Resolution { CONFIRMED, FALSE_ALARM }

@Serializable
data class Anomaly(
    /** Детерминированный ключ — чтобы не дублировать одну и ту же аномалию при повторных проверках. */
    val id: String,
    val vehicleId: String,
    val vehicleName: String,
    val category: MetricCategory,
    val parameterName: String,
    val parameterCaption: String,
    /** Время события в данных, ISO-8601 (локальное время). */
    val eventTime: String,
    /** Когда обнаружено, epoch millis. */
    val detectedAt: Long,
    val severity: Severity,
    val title: String,
    val description: String,
    val value: Double? = null,
    /** Оценка аномальности от модели (0..1), если есть. */
    val score: Double? = null,
    /** Какой детектор нашёл аномалию. */
    val source: String,
    val acknowledged: Boolean = false,
    /** Решение по аномалии; null — ещё не разобрана. */
    val resolution: Resolution? = null,
    /** Причина ложной тревоги (для [Resolution.FALSE_ALARM]). */
    val falseAlarmReason: String? = null,
) {
    /** Тип события из id (`rule|<kind>|...`) — drain, power, volt, overheat, oil, brake, drop. */
    val kind: String get() = id.split('|').getOrNull(1).orEmpty()
}
