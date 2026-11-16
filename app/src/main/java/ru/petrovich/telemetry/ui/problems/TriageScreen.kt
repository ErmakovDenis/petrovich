package ru.petrovich.telemetry.ui.problems

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.ui.charts.ChartMarker
import ru.petrovich.telemetry.ui.charts.LineChart
import ru.petrovich.telemetry.ui.common.FalseAlarmSheet
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.advice
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.headline
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.ui.common.shown
import ru.petrovich.telemetry.ui.common.softColor
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.home.AnomalyOrder
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * «Разбор по порядку»: неразобранные проблемы карточками одна за другой —
 * подтвердить / пропустить / ложная тревога, как в Telegram-боте.
 */
@Composable
fun TriageScreen(onDone: () -> Unit) {
    val c = Petrovich.colors
    val store = ServiceLocator.anomalyStore
    val sync = ServiceLocator.anomalySync
    val all by store.anomalies.collectAsStateWithLifecycle()
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val queue = remember(all, settings?.mutedKinds) {
        all.shown(settings?.mutedKinds.orEmpty()).filter { it.isNew }.sortedWith(AnomalyOrder)
    }
    var index by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var reasons by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val remaining = remember(queue, skipped) { queue.filter { it.id !in skipped } }
    val current = remaining.getOrNull(index.coerceAtMost(remaining.lastIndex.coerceAtLeast(0)))
    val total = queue.size
    val doneCount = total - remaining.size + if (current != null) index else remaining.size

    LaunchedEffect(remaining.size, index) {
        if (remaining.isEmpty() && total > 0) onDone()
        if (index >= remaining.size && remaining.isNotEmpty()) index = remaining.lastIndex
    }

    fun advance() {
        if (index >= remaining.size - 1) onDone() else index++
    }

    fun confirm(a: Anomaly) {
        if (busy) return
        busy = true
        scope.launch {
            runCatchingCancellable { sync.resolve(a.id, Resolution.CONFIRMED) }
                .onFailure { snackbar.showSnackbar("Не сохранилось: ${it.message}") }
            busy = false
            advance()
        }
    }

    fun skip(a: Anomaly) {
        skipped = skipped + a.id
        advance()
    }

    fun falseAlarm(a: Anomaly, reason: String) {
        if (busy) return
        busy = true
        scope.launch {
            runCatchingCancellable { sync.resolve(a.id, Resolution.FALSE_ALARM, reason) }
                .onFailure { snackbar.showSnackbar("Не сохранилось: ${it.message}") }
            busy = false
            advance()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = c.accent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).statusBarsPadding().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (total == 0) "Разбор" else "Разбор · ${(doneCount + 1).coerceAtMost(total)} из $total",
                    Modifier.weight(1f),
                    color = c.onAccent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                )
                IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "Закрыть", tint = c.onAccent) }
            }
            if (total > 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(total) { i ->
                        Box(
                            Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (i <= doneCount) c.onAccent else c.onAccent.copy(alpha = .25f)),
                        )
                    }
                }
            }

            when {
                current == null -> {
                    Spacer(Modifier.weight(1f))
                    Text("Новых проблем нет", color = c.onAccent, style = MaterialTheme.typography.titleMedium)
                    SoftButton("Закрыть", onDone, Modifier.fillMaxWidth())
                    Spacer(Modifier.weight(1f))
                }
                else -> {
                    TriageCard(current, Modifier.weight(1f))
                    Text(
                        "Подтвердить · пропустить · ложная тревога",
                        style = MaterialTheme.typography.labelMedium, color = c.onAccent.copy(alpha = .7f),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton("Ложная тревога", { reasons = true }, Modifier.weight(1f), enabled = !busy)
                        SoftButton("Пропустить", { skip(current) }, Modifier.weight(1f), enabled = !busy)
                        PrimaryButton("Подтвердить", { confirm(current) }, Modifier.weight(1f), enabled = !busy)
                    }
                }
            }
        }
    }

    if (reasons && current != null) {
        val a = current
        FalseAlarmSheet(
            onDismiss = { reasons = false },
            onPick = { reason ->
                reasons = false
                falseAlarm(a, reason)
            },
        )
    }
}

@Composable
private fun TriageCard(a: Anomaly, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    var chart by remember(a.id) { mutableStateOf<Pair<List<LocalDateTime>, List<Double?>>?>(null) }
    var chartUnit by remember(a.id) { mutableStateOf<String?>(null) }
    var marker by remember(a.id) { mutableIntStateOf(0) }
    var loading by remember(a.id) { mutableStateOf(true) }

    LaunchedEffect(a.id) {
        loading = true
        val at = a.time
        if (at != null) {
            runCatchingCancellable {
                val repo = ServiceLocator.telemetry
                val vehicle = repo.vehicles().firstOrNull { it.id == a.vehicleId } ?: return@runCatchingCancellable
                val t = repo.telemetry(vehicle, at.minusHours(3), at.plusHours(3))
                val table = t.tables[a.category] ?: return@runCatchingCancellable
                val column = table.columns.firstOrNull { it.parameter.name == a.parameterName } ?: return@runCatchingCancellable
                val idx = table.timestamps.indices.minByOrNull { abs(Duration.between(table.timestamps[it], at).seconds) } ?: 0
                chart = table.timestamps to column.values
                chartUnit = column.parameter.unit
                marker = idx
            }
        }
        loading = false
    }

    val whenText = a.time?.let { t ->
        val day = t.toLocalDate()
        val today = LocalDate.now()
        val dayText = when (day) {
            today -> "сегодня"
            today.minusDays(1) -> "вчера"
            else -> day.title()
        }
        "$dayText ${t.hhmm()}"
    } ?: a.eventTime

    SurfaceCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Pill(a.severity.label().uppercase(), a.severity.color(), a.severity.softColor())
            Text(a.headline(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("${a.vehicleName} · $whenText", style = MaterialTheme.typography.bodyMedium, color = c.muted)

            when {
                loading -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
                }
                chart != null && chart!!.first.size >= 2 -> {
                    val (times, values) = chart!!
                    LineChart(times, values, chartUnit, lineColor = c.accent, markers = listOf(ChartMarker(marker, a.severity.color())))
                }
            }

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Что проверить", style = MaterialTheme.typography.titleSmall)
                Text(
                    a.advice().split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium, color = c.ink,
                )
            }
        }
    }
}
