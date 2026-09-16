package ru.petrovich.telemetry.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.petrovich.telemetry.data.Vehicle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    subtitle: String? = null,
    onOpenSettings: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
) {
    TopAppBar(
        title = {
            Column {
                Text(title)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = {
            if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Обновить") }
            if (onOpenSettings != null) IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Настройки") }
        },
    )
}

/** Переключение между машинами. */
@Composable
fun VehicleSelector(vehicles: List<Vehicle>, selectedId: String?, onSelect: (String) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedId, vehicles) {
        val idx = vehicles.indexOfFirst { it.id == selectedId }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(vehicles, key = { it.id }) { v ->
            FilterChip(
                selected = v.id == selectedId,
                onClick = { onSelect(v.id) },
                label = { Text(v.name) },
                leadingIcon = { Icon(Icons.Filled.LocalShipping, null, Modifier.padding(0.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodSelector(selected: Period, onSelect: (Period) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Period.entries.forEachIndexed { i, p ->
            SegmentedButton(
                selected = p == selected,
                onClick = { onSelect(p) },
                shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                label = { Text(p.title) },
            )
        }
    }
}

/** Выбор машины + периода + индикатор загрузки. Общий заголовок для «Данных» и «Графиков». */
@Composable
fun TelemetryHeader(state: TelemetryUiState, vm: TelemetryViewModel) {
    Column(Modifier.fillMaxWidth()) {
        VehicleSelector(state.vehicles, state.selectedVehicleId, vm::selectVehicle)
        Spacer(Modifier.height(4.dp))
        PeriodSelector(state.period, vm::selectPeriod)
        Spacer(Modifier.height(8.dp))
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun MessageBox(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
