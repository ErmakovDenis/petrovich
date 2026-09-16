package ru.petrovich.telemetry.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.petrovich.telemetry.data.CategoryTable
import ru.petrovich.telemetry.data.MetricCategory

/**
 * Общий каркас разделов «Данные» и «Графики»: шапка с машиной, выбор машины и периода,
 * вкладки категорий и сообщения о загрузке/ошибках. [content] рисует таблицу категории.
 *
 * @param minRows минимум строк, при котором есть что показывать (графику нужно 2 точки).
 */
@Composable
fun CategoryScreen(
    title: String,
    vm: TelemetryViewModel,
    onOpenSettings: () -> Unit,
    minRows: Int = 1,
    content: @Composable (table: CategoryTable, vehicleId: String?) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val categories = MetricCategory.entries

    Scaffold(
        topBar = {
            AppTopBar(
                title = title,
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
                table == null || table.columns.isEmpty() || table.timestamps.size < minRows ->
                    MessageBox("Нет данных «${categories[tab].title}» за выбранный период")
                else -> content(table, state.selectedVehicleId)
            }
        }
    }
}
