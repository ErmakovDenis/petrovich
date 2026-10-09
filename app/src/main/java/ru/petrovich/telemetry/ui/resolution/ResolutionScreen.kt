package ru.petrovich.telemetry.ui.resolution

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.util.runCatchingCancellable
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.anomaly.TimelineStep
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.Dot
import ru.petrovich.telemetry.ui.common.MessageBox
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.previousOccurrence
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val OutcomeOptions = listOf("Слив подтвердился", "Водитель объяснил — не слив", "Ложная тревога")
private val ActionOptions = listOf("Вычли из премии", "Опломбировали бак", "Провели беседу", "Без мер")
private val StepTimeFormat = DateTimeFormatter.ofPattern("HH:mm")

/**
 * «Ход разбора и итог» (3.10): честный журнал того, что уже сделано, и форма итога —
 * экран живёт, пока событие «В работе», и остаётся записью после закрытия.
 */
@Composable
fun ResolutionScreen(anomalyId: String, onBack: () -> Unit, onOpenCard: (String) -> Unit) {
    val store = ServiceLocator.anomalyStore
    val anomalies by store.anomalies.collectAsStateWithLifecycle()
    val anomaly = anomalies.firstOrNull { it.id == anomalyId }
    val c = Petrovich.colors
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var outcome by remember(anomalyId) { mutableStateOf<String?>(null) }
    var actions by remember(anomalyId) { mutableStateOf(setOf<String>()) }
    val closed = anomaly?.resolution == Resolution.CONFIRMED || anomaly?.resolution == Resolution.FALSE_ALARM

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Разбор · ${anomaly?.vehicleName ?: ""}",
                onBack = onBack,
                actions = {
                    if (anomaly != null) Pill(
                        if (closed) "Закрыт" else "В работе",
                        if (closed) c.ok else c.muted,
                        if (closed) c.okSoft else c.surface2,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (anomaly == null) {
            Box(Modifier.padding(padding)) { MessageBox("Эта аномалия больше не хранится в истории") }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SurfaceCard(Modifier.fillMaxWidth(), onClick = { onOpenCard(anomaly.id) }, shape = RoundedCornerShape(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(anomaly.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            anomaly.time?.let { "${it.toLocalDate().title()}, ${it.hhmm()}" } ?: anomaly.eventTime,
                            style = MaterialTheme.typography.bodySmall, color = c.muted,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = c.muted)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Ход разбора")
                anomaly.timeline.forEach { step -> TimelineRow(step) }
                if (!closed) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TimelineRow(null, "Ждём итог проверки от СБ", modifier = Modifier.weight(1f))
                        if (anomaly.assignedTo != null) {
                            TextButton(onClick = {
                                scope.launch {
                                    store.appendStep(anomaly.id, "Напомнили ${anomaly.assignedTo}")
                                    snackbar.showSnackbar("Сотруднику напомнили")
                                }
                            }) { Text("Напомнить СБ", color = c.accent, style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }

            if (closed) {
                ClosedCard(anomaly.outcomeDetail ?: "Разбор закрыт", anomaly.actionsTaken, anomaly.vehicleName, anomalies.previousOccurrence(anomaly) != null)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Итог разбора")
                    Text("Чем закончилось?", style = MaterialTheme.typography.titleSmall)
                    OutcomeOptions.forEach { opt ->
                        RadioRow(opt, selected = outcome == opt) {
                            outcome = opt
                            if (opt == "Ложная тревога") actions = emptySet()
                        }
                    }
                }
                if (outcome != null && outcome != "Ложная тревога") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Что сделали? можно несколько", style = MaterialTheme.typography.labelMedium, color = c.muted)
                        ActionOptions.forEach { act ->
                            CheckRow(act, checked = act in actions) {
                                actions = if (act in actions) actions - act else actions + act
                            }
                        }
                    }
                }
                PrimaryButton(
                    "Закрыть разбор",
                    enabled = outcome != null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val resolution = if (outcome == "Ложная тревога") Resolution.FALSE_ALARM else Resolution.CONFIRMED
                        val detail = outcome!!
                        scope.launch {
                            // Решение — через синхронизацию: при хранилище на стенде оно уходит на стенд, иначе остаётся на устройстве.
                            val reason = if (resolution == Resolution.FALSE_ALARM) detail else null
                            runCatchingCancellable { ServiceLocator.anomalySync.resolve(anomaly.id, resolution, reason) }
                                .onFailure { snackbar.showSnackbar("Решение не сохранено на стенде: ${it.message}") }
                            // Итог, что сделали и журнал разбора стенд не хранит — они остаются на устройстве.
                            store.closeWithOutcome(anomaly.id, resolution, detail, actions.toList())
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(step: TimelineStep?, placeholder: String? = null, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 5.dp)) { Dot(if (step != null) c.accent else c.line) }
        Column {
            Text(
                step?.let { Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).format(StepTimeFormat) } ?: "сейчас",
                style = MaterialTheme.typography.labelSmall, color = c.muted,
            )
            Text(step?.text ?: placeholder.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = if (step != null) c.ink else c.muted)
        }
    }
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Petrovich.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = c.accent))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onToggle: () -> Unit) {
    val c = Petrovich.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onToggle).padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = c.accent))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ClosedCard(outcome: String, actionsTaken: List<String>, vehicleName: String, remembered: Boolean) {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth(), color = c.okSoft, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CheckCircle, null, tint = c.ok)
                Text("Разбор закрыт", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(outcome + (actionsTaken.takeIf { it.isNotEmpty() }?.joinToString(prefix = ": ") { it.lowercase() } ?: ""), style = MaterialTheme.typography.bodyMedium)
            Text(
                "Петрович запомнил итог и напомнит о нём, если $vehicleName это повторится.",
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
        }
    }
}
