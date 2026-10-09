package ru.petrovich.telemetry.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SignalCellularOff
import androidx.compose.ui.graphics.vector.ImageVector
import ru.petrovich.telemetry.data.settings.AppSettings

/**
 * Виджеты «Сводки» — ровно список из макета 3.16 («Можно добавить»): показатели из АвтоГРАФ,
 * без выдуманных денег и прочего, чего там нет (см. «5. Данные на экранах»).
 */
enum class WidgetType(val key: String, val title: String, val description: String, val icon: ImageVector) {
    FUEL("fuel", "Топливо", "Сколько топлива потратил парк", Icons.Outlined.LocalGasStation),
    IDLE("idle", "Холостой ход", "Часы на холостом ходу", Icons.Outlined.Schedule),
    MILEAGE("mileage", "Пробег", "Километраж по данным скорости", Icons.Outlined.Route),
    REFUELS("refuels", "Заправки", "Число заправок по датчику уровня", Icons.Outlined.LocalGasStation),
    OFFLINE("offline", "Без связи", "Какие машины молчат и сколько", Icons.Outlined.SignalCellularOff),
    FUEL_TRUCKS("fuel_trucks", "Топливо · грузовые", "Расход топлива у грузовых машин", Icons.Outlined.LocalShipping);

    companion object {
        fun byKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

data class WidgetSlot(val type: WidgetType)

/** По умолчанию на главной — Топливо и Холостой ход, как в макете 3.2/3.16. */
val DefaultLayout = listOf(WidgetSlot(WidgetType.FUEL), WidgetSlot(WidgetType.IDLE))

/** Раскладка хранится строкой `ключ,ключ,...` (формат проще прежнего: у виджетов больше нет размера). */
fun parseLayout(raw: String): List<WidgetSlot> {
    if (raw.isBlank()) return DefaultLayout
    val slots = raw.split(',').mapNotNull { key -> WidgetType.byKey(key.substringBefore(':'))?.let { WidgetSlot(it) } }.distinctBy { it.type }
    return slots.ifEmpty { DefaultLayout }
}

fun serializeLayout(slots: List<WidgetSlot>): String = slots.joinToString(",") { it.type.key }

val AppSettings.layout: List<WidgetSlot> get() = parseLayout(widgetLayout)
