package ru.petrovich.telemetry.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.ui.common.PChip
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.dayHeader
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.home.AnomalyOrder
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.LocalDate

enum class FeedFilter(val title: String) { ALL("Все"), HIGH("Срочно"), NEW("Ждут решения") }

@Composable
fun FeedScreen(
    filter: FeedFilter,
    onFilter: (FeedFilter) -> Unit,
    onOpenCard: (String) -> Unit,
    onOpenNotifSettings: () -> Unit,
) {
    val store = ServiceLocator.anomalyStore
    val anomalies by store.anomalies.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val shown = anomalies
        .filter {
            when (filter) {
                FeedFilter.ALL -> true
                FeedFilter.HIGH -> it.severity == Severity.CRITICAL
                FeedFilter.NEW -> it.isNew
            }
        }
        .groupBy { it.time?.toLocalDate() ?: LocalDate.MIN }
        .toSortedMap(compareByDescending { it })

    fun scan() {
        if (scanning) return
        scanning = true
        scope.launch {
            val msg = runCatchingCancellable { ServiceLocator.anomalyScanner.scan(lookbackHours = 24) }.fold(
                onSuccess = { r ->
                    buildString {
                        append("Проверено машин: ${r.checkedVehicles}, новых аномалий: ${r.newAnomalies.size}")
                        if (r.errors.isNotEmpty()) append(". Ошибок: ${r.errors.size}")
                    }
                },
                onFailure = { "Ошибка проверки: ${it.message}" },
            )
            scanning = false
            snackbar.showSnackbar(msg)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = Petrovich.colors.bg, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Всё, что прислал Петрович", style = MaterialTheme.typography.labelMedium, color = Petrovich.colors.muted)
                    Text("Уведомления", fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                }
                IconButton(onClick = ::scan, enabled = !scanning) {
                    if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Petrovich.colors.accent)
                    else Icon(Icons.Filled.Search, "Проверить сейчас")
                }
                IconButton(onClick = onOpenNotifSettings) { Icon(Icons.Filled.Settings, "Настроить уведомления") }
            }
            LazyRow(
                Modifier.padding(top = 8.dp, bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(FeedFilter.entries) { f -> PChip(f.title, selected = filter == f, onClick = { onFilter(f) }) }
            }
            if (shown.isEmpty()) {
                EmptyFeed(hasAny = anomalies.isNotEmpty(), onScan = ::scan)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    shown.forEach { (day, list) ->
                        item(key = "h$day") {
                            SectionLabel(if (day == LocalDate.MIN) "Без даты" else dayHeader(day))
                        }
                        items(list.sortedWith(AnomalyOrder), key = { it.id }) { a ->
                            AnomalyRow(a, onClick = { onOpenCard(a.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyFeed(hasAny: Boolean, onScan: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        SurfaceCard(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (hasAny) "Здесь пусто — и это хорошо." else "Аномалий пока не найдено.",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!hasAny) {
                    Text(
                        "Петрович проверяет данные каждые 15 минут. Можно запустить проверку сейчас — кнопка с лупой сверху.",
                        style = MaterialTheme.typography.bodyMedium, color = Petrovich.colors.muted,
                    )
                    PrimaryButton("Проверить сейчас", onScan, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
