package ru.petrovich.telemetry.ui.anomalies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.ui.common.MessageBox
import ru.petrovich.telemetry.ui.common.short
import ru.petrovich.telemetry.ui.theme.SeverityCritical
import ru.petrovich.telemetry.ui.theme.SeverityInfo
import ru.petrovich.telemetry.ui.theme.SeverityWarning
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.LocalDateTime

fun Severity.color(): Color = when (this) {
    Severity.CRITICAL -> SeverityCritical
    Severity.WARNING -> SeverityWarning
    Severity.INFO -> SeverityInfo
}

private enum class Filter(val title: String) { NEW("Новые"), ALL("Все"), CRITICAL("Критичные") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnomaliesScreen(onOpenSettings: () -> Unit) {
    val store = ServiceLocator.anomalyStore
    val anomalies by store.anomalies.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(Filter.NEW) }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val mlReady = ServiceLocator.mlDetector.isReady

    val shown = anomalies.filter {
        when (filter) {
            Filter.NEW -> !it.acknowledged
            Filter.ALL -> true
            Filter.CRITICAL -> it.severity == Severity.CRITICAL
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Аномалии") },
                actions = {
                    IconButton(onClick = { scope.launch { store.acknowledgeAll() } }) { Icon(Icons.Filled.DoneAll, "Отметить все") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Настройки") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (scanning) return@ExtendedFloatingActionButton
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
                },
                icon = {
                    if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Search, null)
                },
                text = { Text(if (scanning) "Проверка…" else "Проверить сейчас") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            DetectorBanner(mlReady)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Filter.entries) { f ->
                    val count = when (f) {
                        Filter.NEW -> anomalies.count { !it.acknowledged }
                        Filter.ALL -> anomalies.size
                        Filter.CRITICAL -> anomalies.count { it.severity == Severity.CRITICAL }
                    }
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text("${f.title} · $count") })
                }
            }
            if (shown.isEmpty()) {
                MessageBox(
                    if (anomalies.isEmpty()) "Аномалий пока не обнаружено.\nНажмите «Проверить сейчас», чтобы проанализировать данные."
                    else "В этом фильтре пусто."
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { a ->
                        AnomalyCard(a, onClick = { scope.launch { store.acknowledge(a.id) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectorBanner(mlReady: Boolean) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Psychology, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (mlReady) "ML-модель активна"
                else "ML-модель не подключена — используются базовые пороговые правила",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AnomalyCard(a: Anomaly, onClick: () -> Unit) {
    val time = runCatching { LocalDateTime.parse(a.eventTime).short() }.getOrDefault(a.eventTime)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (a.acknowledged) MaterialTheme.colorScheme.surfaceContainerLow
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(Modifier.padding(12.dp)) {
            Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(a.severity.color()))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        a.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (a.acknowledged) FontWeight.Normal else FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(a.severity.title, style = MaterialTheme.typography.labelSmall, color = a.severity.color())
                }
                Text(a.vehicleName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                Text(a.description, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "$time · ${a.category.title} · ${a.parameterCaption} · ${a.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
