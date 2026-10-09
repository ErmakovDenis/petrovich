package ru.petrovich.telemetry.ui.problems

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.ui.common.PChip
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.home.AnomalyOrder
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable

enum class ProblemsTab(val title: String) { NEW("Новые"), IN_PROGRESS("В работе"), CLOSED("Закрыты") }

@Composable
fun ProblemsScreen(
    tab: ProblemsTab,
    onTab: (ProblemsTab) -> Unit,
    onOpenCard: (String) -> Unit,
    onAsk: (String) -> Unit,
) {
    val store = ServiceLocator.anomalyStore
    val anomalies by store.anomalies.collectAsStateWithLifecycle()
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val c = Petrovich.colors

    val newCount = anomalies.count { it.isNew }
    val progressCount = anomalies.count { it.resolution == Resolution.IN_PROGRESS }
    val closedCount = anomalies.count { it.resolution == Resolution.CONFIRMED || it.resolution == Resolution.FALSE_ALARM }

    val inProgress = anomalies.filter { it.resolution == Resolution.IN_PROGRESS }.sortedByDescending { it.eventTime }
    val shown = when (tab) {
        ProblemsTab.NEW -> anomalies.filter { it.isNew }.sortedWith(AnomalyOrder)
        ProblemsTab.IN_PROGRESS -> inProgress
        ProblemsTab.CLOSED -> anomalies.filter { it.resolution == Resolution.CONFIRMED || it.resolution == Resolution.FALSE_ALARM }.sortedByDescending { it.eventTime }
    }

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

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = c.bg, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Всё, что заметил Петрович", style = MaterialTheme.typography.labelMedium, color = c.muted)
                    Text("Проблемы", fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                }
                IconButton(onClick = ::scan, enabled = !scanning) {
                    if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
                    else Icon(Icons.Filled.Search, "Проверить сейчас")
                }
            }
            LazyRow(
                Modifier.padding(top = 8.dp, bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(ProblemsTab.entries) { t ->
                    // Как в макете 3.5: «Новые 2», «Закрыты 0» — счётчик через пробел, без разделителя.
                    val count = when (t) {
                        ProblemsTab.NEW -> newCount
                        ProblemsTab.IN_PROGRESS -> progressCount
                        ProblemsTab.CLOSED -> closedCount
                    }
                    PChip("${t.title} $count", selected = tab == t, onClick = { onTab(t) })
                }
            }
            // На вкладке «Новые» дополнительно видно, что уже передано в работу — чтобы не потерялось из виду.
            val teaser = if (tab == ProblemsTab.NEW) inProgress else emptyList()
            if (shown.isEmpty() && teaser.isEmpty()) {
                EmptyProblems(tab = tab, hasAny = anomalies.isNotEmpty(), onScan = ::scan)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { a ->
                        if (tab == ProblemsTab.NEW) NewProblemCard(a, onOpen = { onOpenCard(a.id) }, onAsk = { onAsk(a.id) })
                        else ProblemRow(a, onClick = { onOpenCard(a.id) })
                    }
                    if (teaser.isNotEmpty()) {
                        item(key = "in-progress-label") { SectionLabel("В работе") }
                        items(teaser, key = { "teaser-${it.id}" }) { a -> ProblemRow(a, onClick = { onOpenCard(a.id) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyProblems(tab: ProblemsTab, hasAny: Boolean, onScan: () -> Unit) {
    val c = Petrovich.colors
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        SurfaceCard(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        tab == ProblemsTab.NEW && !hasAny -> "Здесь пусто. Петрович сообщит, если что-то случится."
                        tab == ProblemsTab.NEW -> "Новых проблем нет — и это хорошо."
                        tab == ProblemsTab.IN_PROGRESS -> "Здесь пусто. Ничего не передано в работу."
                        else -> "Здесь пусто. Закрытых случаев пока нет."
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (tab == ProblemsTab.NEW && !hasAny) {
                    Text(
                        "Петрович проверяет данные каждые 15 минут. Можно запустить проверку сейчас.",
                        style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    )
                    PrimaryButton("Проверить сейчас", onScan, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
