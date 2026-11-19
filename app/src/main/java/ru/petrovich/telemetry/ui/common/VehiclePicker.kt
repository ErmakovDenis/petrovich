package ru.petrovich.telemetry.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.ui.theme.Petrovich

private const val WITH_ISSUES = "С аномалиями"
private const val NO_GROUP = "Без группы"

/**
 * Выбор машины, рассчитанный на сотни ТС: компактная шапка «◀ машина ▶» для быстрого листания
 * и лист с поиском по названию/группе, где машины с неразобранными аномалиями подняты наверх.
 */
@Composable
fun VehiclePicker(vehicles: List<Vehicle>, selectedId: String?, onSelect: (String) -> Unit) {
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val pending = remember(anomalies, settings?.mutedKinds) {
        anomalies.shown(settings?.mutedKinds.orEmpty()).filter { it.isNew }.groupingBy { it.vehicleId }.eachCount()
    }
    var open by remember { mutableStateOf(false) }
    val c = Petrovich.colors
    val index = vehicles.indexOfFirst { it.id == selectedId }
    val current = vehicles.getOrNull(index)

    fun step(delta: Int) {
        if (vehicles.isEmpty()) return
        val next = if (index < 0) 0 else (index + delta).mod(vehicles.size)
        onSelect(vehicles[next].id)
    }

    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { step(-1) }, enabled = vehicles.size > 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Предыдущая машина") }
        SurfaceCard(Modifier.weight(1f), onClick = { open = true }, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(current?.name ?: "Выберите машину", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(current?.group, if (index >= 0) "${index + 1} из ${vehicles.size}" else "${vehicles.size} ТС").joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                val n = current?.let { pending[it.id] } ?: 0
                if (n > 0) Pill("$n", c.high, c.highSoft)
                Icon(Icons.Filled.ExpandMore, null, tint = c.muted)
            }
        }
        IconButton(onClick = { step(1) }, enabled = vehicles.size > 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующая машина") }
    }

    if (open) {
        VehicleSheet(vehicles, selectedId, pending, onSelect = { onSelect(it); open = false }, onDismiss = { open = false })
    }
}

/**
 * Фильтр по машине: [selectedId] = null — «Все машины».
 * Для «Проблем»: очередь / в работе / закрытые по одной машине.
 */
@Composable
fun VehicleFilterBar(
    vehicles: List<Vehicle>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val pending = remember(anomalies, settings?.mutedKinds) {
        anomalies.shown(settings?.mutedKinds.orEmpty()).filter { it.isNew }.groupingBy { it.vehicleId }.eachCount()
    }
    var open by remember { mutableStateOf(false) }
    val c = Petrovich.colors
    val current = vehicles.firstOrNull { it.id == selectedId }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SurfaceCard(
            Modifier.weight(1f),
            onClick = { open = true },
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        current?.name ?: "Все машины",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (current != null) {
                            listOfNotNull(current.group, current.plate).joinToString(" · ").ifEmpty { "только эта машина" }
                        } else {
                            "проблемы по всему парку"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = c.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val n = current?.let { pending[it.id] } ?: pending.values.sum()
                if (n > 0) Pill("$n", c.high, c.highSoft)
                Icon(Icons.Filled.ExpandMore, null, tint = c.muted)
            }
        }
        if (selectedId != null) {
            IconButton(onClick = { onSelect(null) }) {
                Icon(Icons.Filled.Close, contentDescription = "Сбросить фильтр")
            }
        }
    }

    if (open) {
        VehicleSheet(
            vehicles = vehicles,
            selectedId = selectedId,
            pending = pending,
            allLabel = "Все машины",
            onSelect = { onSelect(it); open = false },
            onSelectAll = { onSelect(null); open = false },
            onDismiss = { open = false },
        )
    }
}

private sealed interface PickerRow {
    data class Header(val title: String, val count: Int) : PickerRow
    data class Item(val vehicle: Vehicle, val issues: Int, val section: String) : PickerRow
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun VehicleSheet(
    vehicles: List<Vehicle>,
    selectedId: String?,
    pending: Map<String, Int>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    allLabel: String? = null,
    onSelectAll: (() -> Unit)? = null,
) {
    val c = Petrovich.colors
    var query by remember { mutableStateOf("") }

    val rows = remember(vehicles, pending, query) {
        val q = query.trim()
        val found = if (q.isEmpty()) vehicles else vehicles.filter { v ->
            v.label.contains(q, ignoreCase = true) ||
                v.plate?.contains(q, ignoreCase = true) == true ||
                v.group?.contains(q, ignoreCase = true) == true
        }
        buildList {
            // Пока не ищем — сверху машины, по которым есть что разбирать.
            val problem = if (q.isEmpty()) found.filter { (pending[it.id] ?: 0) > 0 }.sortedByDescending { pending[it.id] } else emptyList()
            if (problem.isNotEmpty()) {
                add(PickerRow.Header(WITH_ISSUES, problem.size))
                problem.forEach { add(PickerRow.Item(it, pending[it.id] ?: 0, WITH_ISSUES)) }
            }
            found.groupBy { it.group ?: NO_GROUP }
                .toSortedMap(compareBy({ it == NO_GROUP }, { it }))
                .forEach { (group, list) ->
                    add(PickerRow.Header(group, list.size))
                    list.sortedBy { it.shortLabel }.forEach { add(PickerRow.Item(it, pending[it.id] ?: 0, group)) }
                }
        }
    }
    val matches = rows.count { it is PickerRow.Item && it.section != WITH_ISSUES }
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.bg,
    ) {
        Column(Modifier.imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Выберите машину", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Номер, название или группа", maxLines = 1) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Очистить") } },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.line,
                ),
            )
            if (query.isNotBlank()) Text("Найдено: $matches из ${vehicles.size}", style = MaterialTheme.typography.labelMedium, color = c.muted)
            if (rows.isEmpty() && allLabel == null) {
                Text("Ничего не найдено", Modifier.padding(vertical = 24.dp), color = c.muted)
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxWidth()) {
                    if (allLabel != null && onSelectAll != null && query.isBlank()) {
                        item(key = "all") {
                            val selected = selectedId == null
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable { onSelectAll() }
                                    .padding(horizontal = 10.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    allLabel,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                    rows.forEach { row ->
                        when (row) {
                            is PickerRow.Header -> stickyHeader(key = "h:${row.title}") {
                                Text(
                                    "${row.title.uppercase()} · ${row.count}",
                                    Modifier.fillMaxWidth().background(c.bg).padding(top = 10.dp, bottom = 6.dp),
                                    style = MaterialTheme.typography.labelSmall, color = c.muted, fontWeight = FontWeight.SemiBold,
                                )
                            }
                            // Одна и та же машина может встретиться в двух секциях («С аномалиями» и своей группе).
                            is PickerRow.Item -> item(key = "v:${row.section}:${row.vehicle.id}") { VehicleRow(row, row.vehicle.id == selectedId, onSelect) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleRow(row: PickerRow.Item, selected: Boolean, onSelect: (String) -> Unit) {
    val c = Petrovich.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onSelect(row.vehicle.id) }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            row.vehicle.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (row.issues > 0) Pill("${row.issues}", c.high, c.highSoft)
    }
}
