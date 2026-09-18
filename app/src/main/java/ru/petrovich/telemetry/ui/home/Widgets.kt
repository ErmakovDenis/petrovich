package ru.petrovich.telemetry.ui.home

import ru.petrovich.telemetry.data.settings.AppSettings

enum class WidgetSize(val key: String) { S("s"), L("l") }

enum class WidgetType(
    val key: String,
    val title: String,
    val group: String,
    val description: String,
    val sizes: List<WidgetSize>,
) {
    DECISIONS("decisions", "Ждут решения", "Аномалии", "Сколько аномалий ещё не разобрано", listOf(WidgetSize.S, WidgetSize.L)),
    URGENT("urgent", "Срочные случаи", "Аномалии", "Неразобранные срочные аномалии", listOf(WidgetSize.L)),
    WEEK("week", "Аномалии за неделю", "Аномалии", "Сколько аномалий по дням за последние 7 дней", listOf(WidgetSize.L, WidgetSize.S)),
    BY_CATEGORY("by_category", "По разделам", "Аномалии", "Топливо, аккумулятор, двигатель, движение", listOf(WidgetSize.L)),
    TOP_VEHICLES("top_vehicles", "Больше всего аномалий", "Машины", "Три машины с наибольшим числом аномалий", listOf(WidgetSize.L)),
    VEHICLES("vehicles", "Машины", "Машины", "Сколько машин в парке и у скольких есть замечания", listOf(WidgetSize.S, WidgetSize.L)),
    FUEL("fuel", "Топливо", "Топливо", "Сливы и резкие падения уровня за неделю", listOf(WidgetSize.S, WidgetSize.L));

    companion object {
        fun byKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

data class WidgetSlot(val type: WidgetType, val size: WidgetSize)

val DefaultLayout = listOf(
    WidgetSlot(WidgetType.DECISIONS, WidgetSize.S),
    WidgetSlot(WidgetType.VEHICLES, WidgetSize.S),
    WidgetSlot(WidgetType.WEEK, WidgetSize.L),
    WidgetSlot(WidgetType.BY_CATEGORY, WidgetSize.L),
)

/** Раскладка хранится строкой `ключ:размер,ключ:размер`. */
fun parseLayout(raw: String): List<WidgetSlot> {
    if (raw.isBlank()) return DefaultLayout
    val slots = raw.split(',').mapNotNull { part ->
        val (key, size) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val type = WidgetType.byKey(key) ?: return@mapNotNull null
        val s = WidgetSize.entries.firstOrNull { it.key == size }?.takeIf { it in type.sizes } ?: type.sizes.first()
        WidgetSlot(type, s)
    }.distinctBy { it.type }
    return slots
}

fun serializeLayout(slots: List<WidgetSlot>): String = slots.joinToString(",") { "${it.type.key}:${it.size.key}" }

val AppSettings.layout: List<WidgetSlot> get() = parseLayout(widgetLayout)
