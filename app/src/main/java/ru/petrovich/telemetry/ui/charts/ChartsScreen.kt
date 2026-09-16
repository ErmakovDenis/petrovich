package ru.petrovich.telemetry.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.CategoryTable
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.ui.anomalies.color
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.MessageBox
import ru.petrovich.telemetry.ui.common.TelemetryHeader
import ru.petrovich.telemetry.ui.common.TelemetryViewModel
import ru.petrovich.telemetry.ui.common.formatValue

private val palette = listOf(
    Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFF6D4C41), Color(0xFF3949AB),
)

@Composable
fun ChartsScreen(vm: TelemetryViewModel, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val categories = MetricCategory.entries

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Графики",
                subtitle = state.selectedVehicle?.let { v -> listOfNotNull(v.name, v.group).joinToString(" · ") },
                onRefresh = vm::refresh,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TelemetryHeader(state, vm)
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                categories.forEachIndexed { i, c ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(c.title) })
                }
            }
            val table = state.telemetry?.tables?.get(categories[tab])
            when {
                state.error != null -> MessageBox("Не удалось загрузить данные:\n${state.error}", "Повторить", vm::refresh)
                state.vehicles.isEmpty() && !state.loading -> MessageBox("Нет доступных машин", "Настройки", onOpenSettings)
                state.telemetry == null -> Unit
                table == null || table.columns.isEmpty() || table.timestamps.size < 2 ->
                    MessageBox("Нет данных «${categories[tab].title}» за выбранный период")
                else -> Charts(table, state.selectedVehicleId)
            }
        }
    }
}

@Composable
private fun Charts(table: CategoryTable, vehicleId: String?) {
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val indexByTime = remember(table) { table.timestamps.withIndex().associate { (i, t) -> t.toString() to i } }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(table.columns.withIndex().toList(), key = { it.value.parameter.name }) { (idx, col) ->
            val markers = anomalies
                .filter { it.vehicleId == vehicleId && it.parameterName == col.parameter.name }
                .mapNotNull { a -> indexByTime[a.eventTime]?.let { ChartMarker(it, a.severity.color()) } }
            val present = col.values.filterNotNull()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        col.parameter.caption + (col.parameter.unit?.let { ", $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (present.isNotEmpty()) {
                        Text(
                            "Сейчас: ${present.last().formatValue()} · мин ${present.min().formatValue()} · макс ${present.max().formatValue()}" +
                                if (markers.isNotEmpty()) " · аномалий: ${markers.size}" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LineChart(
                        times = table.timestamps,
                        values = col.values,
                        unit = col.parameter.unit,
                        lineColor = palette[idx % palette.size],
                        markers = markers,
                    )
                }
            }
        }
    }
}
