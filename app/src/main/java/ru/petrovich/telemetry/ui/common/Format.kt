package ru.petrovich.telemetry.ui.common

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

val TimeShort: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale("ru"))

fun Double?.formatValue(): String = when {
    this == null -> "—"
    abs(this) >= 1000 -> "%.0f".format(this)
    abs(this - Math.round(this)) < 1e-9 -> Math.round(this).toString()
    else -> "%.1f".format(this)
}

fun LocalDateTime.short(): String = format(TimeShort)
