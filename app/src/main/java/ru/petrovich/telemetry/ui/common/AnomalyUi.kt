package ru.petrovich.telemetry.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Названия уровней для владельца: не «критично/инфо», а что с этим делать. */
fun Severity.label(): String = when (this) {
    Severity.CRITICAL -> "Срочно"
    Severity.WARNING -> "Внимание"
    Severity.INFO -> "Мелочь"
}

@Composable
fun Severity.color(): Color = with(Petrovich.colors) {
    when (this@color) {
        Severity.CRITICAL -> high
        Severity.WARNING -> med
        Severity.INFO -> low
    }
}

@Composable
fun Severity.softColor(): Color = with(Petrovich.colors) {
    when (this@softColor) {
        Severity.CRITICAL -> highSoft
        Severity.WARNING -> medSoft
        Severity.INFO -> lowSoft
    }
}

val Anomaly.isNew: Boolean get() = resolution == null && !acknowledged

val Anomaly.time: LocalDateTime? get() = runCatching { LocalDateTime.parse(eventTime) }.getOrNull()

private val RuLocale = Locale("ru")
private val DayFormat = DateTimeFormatter.ofPattern("d MMMM", RuLocale)
private val WeekdayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", RuLocale)
private val HourMinute = DateTimeFormatter.ofPattern("HH:mm", RuLocale)

fun LocalDateTime.hhmm(): String = format(HourMinute)

fun LocalDate.title(): String = format(DayFormat)

/** «четверг, 17 сентября» — для шапки сводки. */
fun LocalDate.weekdayTitle(): String = format(WeekdayFormat).replaceFirstChar { it.uppercase() }

/** «Пн, 28 сентября» — компактная дата для шапки «Главной». */
fun LocalDate.shortDate(): String {
    val weekday = dayOfWeek.getDisplayName(TextStyle.SHORT, RuLocale).replaceFirstChar { it.uppercase() }
    return "$weekday, ${format(DateTimeFormatter.ofPattern("d MMMM", RuLocale))}"
}

/** Обращение по времени суток — для приветствия на «Главной». */
fun greeting(time: LocalTime = LocalTime.now()): String = when (time.hour) {
    in 5..10 -> "Доброе утро"
    in 11..16 -> "Добрый день"
    in 17..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

/** Заголовок группы в ленте: «Сегодня», «Вчера» или дата. */
fun dayHeader(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
    today -> "Сегодня"
    today.minusDays(1) -> "Вчера, ${day.title()}"
    else -> day.title()
}

fun resolutionLabel(a: Anomaly): String? = when (a.resolution) {
    Resolution.CONFIRMED -> "Подтверждено"
    Resolution.FALSE_ALARM -> "Ложная"
    Resolution.IN_PROGRESS -> "В работе" + (a.assignedTo?.let { " · $it" } ?: "")
    null -> null
}

/** Что делать — по типу события (id правила). Для неизвестных типов — общий совет. */
fun Anomaly.advice(): String = when (kind) {
    "drain", "drop" -> "Сверьте с заправками по картам за это время, проверьте пломбу и горловину бака. " +
        "Покажите водителю график: уровень падает, пока машина стоит."
    "power" -> "Проверьте клеммы и предохранитель трекера. Если отключения повторяются, " +
        "это частый признак вмешательства в питание."
    "volt" -> "Проверьте генератор и клеммы аккумулятора. Если напряжение не выравнивается — нужна диагностика."
    "overheat" -> "Остановите машину и проверьте уровень охлаждающей жидкости и термостат. Не эксплуатируйте с перегревом."
    "oil" -> "Проверьте уровень масла и датчик давления. Если давление действительно низкое — не эксплуатируйте машину."
    "brake" -> "Проверьте пневмосистему и датчик давления в тормозных контурах."
    "nodata" -> "Датчик не передаёт данные. Проверьте подключение датчика и напомните механику — без этого Петрович не увидит топливо по этой машине."
    else -> "Посмотрите график и уточните у водителя, что происходило в это время."
}

/** Похожий случай на той же машине раньше — для напоминания «Петрович помнит». */
fun List<Anomaly>.previousOccurrence(of: Anomaly): Anomaly? =
    filter { it.id != of.id && it.vehicleId == of.vehicleId && it.kind == of.kind && it.eventTime < of.eventTime }
        .maxByOrNull { it.eventTime }

fun plural(n: Int, one: String, few: String, many: String): String {
    val m = n % 10
    val h = n % 100
    return when {
        m == 1 && h != 11 -> one
        m in 2..4 && h !in 12..14 -> few
        else -> many
    }
}
