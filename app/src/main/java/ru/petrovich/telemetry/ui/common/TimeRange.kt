package ru.petrovich.telemetry.ui.common

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Произвольный отрезок «с … по …» для карточки машины, графиков/таблиц и виджетов.
 * Максимум — [MAX_DAYS] суток (согласован со стендом FS_TELEMETRY_MAX_PERIOD_HOURS).
 */
data class TimeRange(
    val from: LocalDateTime,
    val to: LocalDateTime,
) {
    val isValid: Boolean
        get() = from.isBefore(to) && !Duration.between(from, to).isNegative &&
            Duration.between(from, to) <= Duration.ofDays(MAX_DAYS)

    val span: Duration get() = Duration.between(from, to).coerceAtLeast(Duration.ZERO)

    /** Короткая подпись чипа: «12.11 08:00–15.11 18:00» или «12.11 08:00–18:00» в один день. */
    val label: String
        get() {
            val sameDay = from.toLocalDate() == to.toLocalDate()
            return if (sameDay) {
                "${from.format(DayTime)}–${to.format(TimeOnly)}"
            } else {
                "${from.format(DayTime)}–${to.format(DayTime)}"
            }
        }

    /** Подпись под цифрой: «за период» / короткий диапазон. */
    val shortLabel: String get() = label

    fun previousOfSameLength(): TimeRange {
        val length = span
        return TimeRange(from.minus(length), from)
    }

    companion object {
        const val MAX_DAYS = 180L

        private val Ru = Locale("ru")
        private val DayTime = DateTimeFormatter.ofPattern("dd.MM HH:mm", Ru)
        private val TimeOnly = DateTimeFormatter.ofPattern("HH:mm", Ru)

        fun today(now: LocalDateTime = LocalDateTime.now()): TimeRange =
            TimeRange(now.toLocalDate().atStartOfDay(), now)

        fun lastHours(hours: Long, now: LocalDateTime = LocalDateTime.now()): TimeRange =
            TimeRange(now.minusHours(hours), now)

        fun lastDays(days: Long, now: LocalDateTime = LocalDateTime.now()): TimeRange =
            TimeRange(now.minusDays(days), now)

        /** Старт текущего календарного дня → сейчас; иначе последние [days] суток до сейчас. */
        fun presetDays(days: Long, fromStartOfToday: Boolean, now: LocalDateTime = LocalDateTime.now()): TimeRange =
            if (fromStartOfToday) today(now) else lastDays(days, now)

        fun of(fromDate: LocalDate, fromTime: LocalTime, toDate: LocalDate, toTime: LocalTime): TimeRange =
            TimeRange(LocalDateTime.of(fromDate, fromTime), LocalDateTime.of(toDate, toTime))
    }
}

/** Почему диапазон нельзя применить — для подсказки в диалоге. */
fun TimeRange.validationMessage(): String? = when {
    !from.isBefore(to) -> "Начало должно быть раньше конца"
    Duration.between(from, to) > Duration.ofDays(TimeRange.MAX_DAYS) ->
        "Не больше ${TimeRange.MAX_DAYS} суток"
    else -> null
}
