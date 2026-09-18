package ru.petrovich.telemetry.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.ui.charts.ChartMarker
import ru.petrovich.telemetry.ui.charts.LineChart
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.MessageBox
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.advice
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.formatValue
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.ui.common.softColor
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

private val FalseAlarmReasons = listOf("Штатная работа", "Ошибка датчика", "Уже известно", "Другое")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnomalyDetailScreen(anomalyId: String, onBack: () -> Unit, onAsk: (String) -> Unit) {
    val store = ServiceLocator.anomalyStore
    val anomalies by store.anomalies.collectAsStateWithLifecycle()
    val anomaly = anomalies.firstOrNull { it.id == anomalyId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var reasons by remember { mutableStateOf(false) }
    val c = Petrovich.colors

    Scaffold(
        topBar = { AppTopBar(title = anomaly?.vehicleName ?: "Аномалия", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (anomaly != null) ActionsBar(
                anomaly = anomaly,
                onConfirm = {
                    scope.launch { store.resolve(anomaly.id, Resolution.CONFIRMED); snackbar.showSnackbar("Отмечено: подтверждено") }
                },
                onFalseAlarm = { reasons = true },
                onReopen = { scope.launch { store.reopen(anomaly.id) } },
                onAsk = { onAsk(anomaly.id) },
            )
        },
    ) { padding ->
        if (anomaly == null) {
            Box(Modifier.padding(padding)) { MessageBox("Эта аномалия больше не хранится в истории") }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("● ${anomaly.severity.label()}", anomaly.severity.color(), anomaly.severity.softColor())
                Text(anomaly.title, style = MaterialTheme.typography.headlineMedium)
                Text("${anomaly.vehicleName} · ${anomaly.category.title}", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            }

            SurfaceCard(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    val time = anomaly.time
                    Fact(Icons.Outlined.AccessTime, "Когда", time?.let { "${it.toLocalDate().title()}, ${it.hhmm()}" } ?: anomaly.eventTime)
                    HorizontalDivider(color = c.line)
                    Fact(Icons.Outlined.Sensors, "Параметр", anomaly.parameterCaption + (anomaly.value?.let { " · ${it.formatValue()}" } ?: ""))
                    HorizontalDivider(color = c.line)
                    Fact(Icons.Outlined.Memory, "Кто нашёл", anomaly.source)
                }
            }

            EvidenceCard(anomaly)

            TextBlock("Что случилось", anomaly.description)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accentSoft).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("ЧТО ДЕЛАТЬ", style = MaterialTheme.typography.labelSmall, color = c.muted, fontWeight = FontWeight.SemiBold)
                Text(anomaly.advice(), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (reasons && anomaly != null) {
        ModalBottomSheet(
            onDismissRequest = { reasons = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.bg,
        ) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Почему ложная тревога?", style = MaterialTheme.typography.titleLarge)
                Text("Причина сохранится вместе с аномалией.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                FalseAlarmReasons.forEach { reason ->
                    SurfaceCard(
                        Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        onClick = {
                            reasons = false
                            scope.launch {
                                store.resolve(anomaly.id, Resolution.FALSE_ALARM, reason)
                                snackbar.showSnackbar("Отмечено как ложная тревога")
                            }
                        },
                    ) { Text(reason, Modifier.padding(15.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = Petrovich.colors.faint)
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Petrovich.colors.muted)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TextBlock(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Petrovich.colors.muted, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** График параметра вокруг момента события: ±3 часа. */
@Composable
private fun EvidenceCard(a: Anomaly) {
    val c = Petrovich.colors
    var state by remember(a.id) { mutableStateOf<EvidenceState>(EvidenceState.Loading) }
    LaunchedEffect(a.id) {
        state = EvidenceState.Loading
        val at = a.time
        state = if (at == null) EvidenceState.Failed("Не удалось определить время события")
        else runCatchingCancellable { loadEvidence(a, at) }.fold(
            onSuccess = { it ?: EvidenceState.Failed("Нет данных по параметру рядом с событием") },
            onFailure = { EvidenceState.Failed("Не удалось загрузить график: ${it.message}") },
        )
    }
    SurfaceCard(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.parameterCaption, style = MaterialTheme.typography.labelMedium, color = c.muted)
            when (val s = state) {
                EvidenceState.Loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent)
                }
                is EvidenceState.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = c.muted)
                is EvidenceState.Ready -> {
                    LineChart(s.times, s.values, s.unit, lineColor = c.accent, markers = listOf(ChartMarker(s.markerIndex, a.severity.color())))
                    Text(
                        "Окно: 3 часа до и после события",
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private sealed interface EvidenceState {
    data object Loading : EvidenceState
    data class Failed(val message: String) : EvidenceState
    class Ready(val times: List<LocalDateTime>, val values: List<Double?>, val unit: String?, val markerIndex: Int) : EvidenceState
}

private suspend fun loadEvidence(a: Anomaly, at: LocalDateTime): EvidenceState.Ready? {
    val repo = ServiceLocator.telemetry
    val vehicle = repo.vehicles().firstOrNull { it.id == a.vehicleId } ?: return null
    val t: VehicleTelemetry = repo.telemetry(vehicle, at.minusHours(3), at.plusHours(3))
    val table = t.tables[a.category] ?: return null
    val column = table.columns.firstOrNull { it.parameter.name == a.parameterName } ?: return null
    if (table.timestamps.size < 2) return null
    val idx = table.timestamps.indices.minByOrNull { abs(Duration.between(table.timestamps[it], at).seconds) } ?: return null
    return EvidenceState.Ready(table.timestamps, column.values, column.parameter.unit, idx)
}

@Composable
private fun ActionsBar(anomaly: Anomaly, onConfirm: () -> Unit, onFalseAlarm: () -> Unit, onReopen: () -> Unit, onAsk: () -> Unit) {
    val c = Petrovich.colors
    Column(
        Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (anomaly.isNew) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Подтвердить", onConfirm, Modifier.weight(1f), container = c.high, content = c.surface)
                SoftButton("Ложная тревога", onFalseAlarm, Modifier.weight(1f))
            }
        } else {
            SurfaceCard(shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (anomaly.resolution == Resolution.CONFIRMED) Pill("Подтверждено", c.high, c.highSoft)
                        else Pill("Ложная тревога" + (anomaly.falseAlarmReason?.let { " · $it" } ?: ""), c.muted, c.surface2)
                    }
                    TextButton(onClick = onReopen) { Text("Изменить", color = c.accent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        SoftButton("Спросить у Петровича", onAsk, Modifier.fillMaxWidth(), container = c.ink, content = c.bg, leading = Icons.Outlined.ChatBubbleOutline)
    }
}
