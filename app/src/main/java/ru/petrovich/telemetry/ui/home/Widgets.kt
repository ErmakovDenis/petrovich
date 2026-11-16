package ru.petrovich.telemetry.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SignalCellularOff
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.ui.graphics.vector.ImageVector
import ru.petrovich.telemetry.data.settings.AppSettings

/**
 * Виджеты «Недели в цифрах» по карте экранов: широкие — на всю ширину, узкие — по два в ряд.
 * Названия — как на макете (вопрос владельца), не внутренние метки АвтоГРАФ.
 */
enum class WidgetType(
    val key: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    /** Широкая карточка на всю ширину; иначе в сетке по две. */
    val wide: Boolean = true,
) {
    FUEL("fuel", "Расход топлива", "Тратим больше, чем обычно?", Icons.Outlined.LocalGasStation, wide = true),
    OVERCONSUMERS("overconsumers", "Кто перерасходует", "Какая машина жжёт больше нормы?", Icons.Outlined.TrendingUp, wide = true),
    IDLE("idle", "Простой с мотором", "Сколько часов впустую с зажиганием", Icons.Outlined.Schedule, wide = false),
    OFFLINE("offline", "Кто молчит", "Какие машины не на связи", Icons.Outlined.SignalCellularOff, wide = false),
    ALARMS("alarms", "Тревоги подтвердились", "Сколько тревог были настоящими", Icons.Outlined.Verified, wide = false),
    MILEAGE("mileage", "Пробег", "Километраж по данным скорости", Icons.Outlined.Route, wide = false),
    REFUELS("refuels", "Заправки", "Число заправок по датчику уровня", Icons.Outlined.LocalGasStation, wide = false),
    FUEL_TRUCKS("fuel_trucks", "Топливо · грузовые", "Расход топлива у грузовых машин", Icons.Outlined.LocalShipping, wide = true);

    companion object {
        fun byKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

data class WidgetSlot(val type: WidgetType)

/** Стартовый набор как на варианте «1б» карты экранов. */
val DefaultLayout = listOf(
    WidgetSlot(WidgetType.FUEL),
    WidgetSlot(WidgetType.OVERCONSUMERS),
    WidgetSlot(WidgetType.IDLE),
    WidgetSlot(WidgetType.OFFLINE),
)

/** Раскладка хранится строкой `ключ,ключ,...` (формат проще прежнего: у виджетов больше нет размера). */
fun parseLayout(raw: String): List<WidgetSlot> {
    if (raw.isBlank()) return DefaultLayout
    val slots = raw.split(',').mapNotNull { key -> WidgetType.byKey(key.substringBefore(':'))?.let { WidgetSlot(it) } }.distinctBy { it.type }
    return slots.ifEmpty { DefaultLayout }
}

fun serializeLayout(slots: List<WidgetSlot>): String = slots.joinToString(",") { it.type.key }

val AppSettings.layout: List<WidgetSlot> get() = parseLayout(widgetLayout)

/** Ряды сетки: широкие — по одному, узкие — парами (одинокий узкий — на всю ширину). */
fun widgetRows(slots: List<WidgetSlot>): List<List<WidgetSlot>> {
    val rows = mutableListOf<List<WidgetSlot>>()
    var i = 0
    while (i < slots.size) {
        val cur = slots[i]
        if (!cur.type.wide && i + 1 < slots.size && !slots[i + 1].type.wide) {
            rows += listOf(cur, slots[i + 1])
            i += 2
        } else {
            rows += listOf(cur)
            i += 1
        }
    }
    return rows
}
