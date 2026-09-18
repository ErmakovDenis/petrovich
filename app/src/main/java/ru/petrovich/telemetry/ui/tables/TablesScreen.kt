package ru.petrovich.telemetry.ui.tables

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.CategoryTable
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.CategoryScreen
import ru.petrovich.telemetry.ui.common.TelemetryViewModel
import ru.petrovich.telemetry.ui.common.formatValue
import ru.petrovich.telemetry.ui.common.short

private val TimeColumn = 110.dp
private val ValueColumn = 120.dp

@Composable
fun TablesScreen(vm: TelemetryViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    CategoryScreen("Данные телеметрии", vm, onBack, onOpenSettings) { table, vehicleId -> DataTable(table, vehicleId) }
}

@Composable
private fun DataTable(table: CategoryTable, vehicleId: String?) {
    val hScroll = rememberScrollState()
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    // Подсветка ячеек, в которых найдена аномалия.
    val marks = remember(anomalies, vehicleId) {
        anomalies.filter { it.vehicleId == vehicleId }.associateBy { it.parameterName to it.eventTime }
    }
    // Новые записи сверху; интервалы без данных (машина не на связи) не показываем.
    val order = remember(table) {
        table.timestamps.indices.reversed().filter { i -> table.columns.any { it.values.getOrNull(i) != null } }
    }

    Column(Modifier.fillMaxSize()) {
        HeaderRow(table, hScroll)
        StatsRow(table, hScroll)
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(order) { rowIndex, i ->
                val time = table.timestamps[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (rowIndex % 2 == 0) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow)
                        .horizontalScroll(hScroll)
                        .padding(vertical = 6.dp),
                ) {
                    Cell(time.short(), TimeColumn, bold = false, align = TextAlign.Start)
                    table.columns.forEach { col ->
                        val mark = marks[col.parameter.name to time.toString()]
                        Cell(
                            col.values.getOrNull(i).formatValue(),
                            ValueColumn,
                            color = mark?.severity?.color(),
                            bold = mark != null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(table: CategoryTable, hScroll: ScrollState) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(hScroll).padding(vertical = 8.dp),
    ) {
        Cell("Время", TimeColumn, bold = true, align = TextAlign.Start)
        table.columns.forEach { col ->
            val unit = col.parameter.unit?.let { ", $it" }.orEmpty()
            Cell(col.parameter.caption + unit, ValueColumn, bold = true, maxLines = 2)
        }
    }
}

@Composable
private fun StatsRow(table: CategoryTable, hScroll: ScrollState) {
    Row(Modifier.fillMaxWidth().horizontalScroll(hScroll).padding(vertical = 6.dp)) {
        Cell("мин / ср\nмакс", TimeColumn, align = TextAlign.Start, color = MaterialTheme.colorScheme.onSurfaceVariant, small = true, maxLines = 2)
        table.columns.forEach { col ->
            val v = col.values.filterNotNull()
            val text = if (v.isEmpty()) "—" else "${v.min().formatValue()} / ${v.average().formatValue()}\n${v.max().formatValue()}"
            Cell(text, ValueColumn, color = MaterialTheme.colorScheme.onSurfaceVariant, small = true, maxLines = 2)
        }
    }
}

@Composable
private fun Cell(
    text: String,
    width: Dp,
    bold: Boolean = false,
    align: TextAlign = TextAlign.End,
    color: Color? = null,
    maxLines: Int = 1,
    small: Boolean = false,
) {
    Text(
        text,
        modifier = Modifier.width(width).padding(horizontal = 8.dp),
        style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = align,
        color = color ?: MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
