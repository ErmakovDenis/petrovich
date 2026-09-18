package ru.petrovich.telemetry.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.CategoryTable
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.CategoryScreen
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.ui.common.TelemetryViewModel
import ru.petrovich.telemetry.ui.common.formatValue

@Composable
fun ChartsScreen(vm: TelemetryViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    CategoryScreen("Графики", vm, onBack, onOpenSettings, minRows = 2) { table, vehicleId -> Charts(table, vehicleId) }
}

@Composable
private fun Charts(table: CategoryTable, vehicleId: String?) {
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val palette = with(Petrovich.colors) { listOf(accent, low, med, ok, muted, high) }
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
            SurfaceCard(Modifier.fillMaxWidth()) {
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
