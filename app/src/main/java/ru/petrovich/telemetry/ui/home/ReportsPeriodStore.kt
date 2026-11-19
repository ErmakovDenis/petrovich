package ru.petrovich.telemetry.ui.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.petrovich.telemetry.ui.common.TimeRange
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RangeDayFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))

/** Базовое окно по пресету / своему периоду (без сдвига стрелками). */
fun selectionBaseRange(presetId: String?, custom: TimeRange?): TimeRange =
    custom?.takeIf { presetId == null }
        ?: WidgetPeriod.entries.firstOrNull { it.name.equals(presetId, ignoreCase = true) }?.toRange()
        ?: WidgetPeriod.WEEK.toRange()

/** Подпись окна данных: «данные с 12 ноября 2026 по 19 ноября 2026». */
fun reportsRangeCaption(range: TimeRange): String {
    val from = range.from.toLocalDate()
    val to = range.to.toLocalDate()
    return if (from == to) "данные за ${from.format(RangeDayFmt)}"
    else "данные с ${from.format(RangeDayFmt)} по ${to.format(RangeDayFmt)}"
}

fun reportsRangeCaption(presetId: String?, custom: TimeRange?): String =
    reportsRangeCaption(selectionBaseRange(presetId, custom))

/**
 * Выбор периода на «Отчётах» и карточке виджета.
 * Живёт в [ru.petrovich.telemetry.ServiceLocator], чтобы не сбрасывался при переходах по стеку.
 */
class ReportsPeriodStore {
    data class Selection(
        /** id пресета (`week` / `month` / `quarter` / `day`) или null при своём периоде. */
        val presetId: String? = "week",
        val customRange: TimeRange? = null,
    ) {
        val lockedWidgetPeriod: WidgetPeriod?
            get() = WidgetPeriod.entries.firstOrNull { it.name.equals(presetId, ignoreCase = true) }
    }

    private val _selection = MutableStateFlow(Selection())
    val selection: StateFlow<Selection> = _selection.asStateFlow()

    fun selectPreset(id: String) {
        _selection.value = Selection(presetId = id, customRange = null)
    }

    fun selectCustom(range: TimeRange) {
        _selection.value = Selection(presetId = null, customRange = range)
    }

    fun clear() {
        _selection.value = Selection()
    }
}
