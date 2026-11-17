package ru.petrovich.telemetry.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Диалог «Свой период»: дата и время «с» / «по». Лимит и порядок границ — [TimeRange.isValid].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomPeriodDialog(
    initial: TimeRange,
    onConfirm: (TimeRange) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Petrovich.colors
    var fromDate by remember { mutableStateOf(initial.from.toLocalDate()) }
    var fromTime by remember { mutableStateOf(initial.from.toLocalTime().withSecond(0).withNano(0)) }
    var toDate by remember { mutableStateOf(initial.to.toLocalDate()) }
    var toTime by remember { mutableStateOf(initial.to.toLocalTime().withSecond(0).withNano(0)) }
    var pick by remember { mutableStateOf<Pick?>(null) }

    val draft = TimeRange.of(fromDate, fromTime, toDate, toTime)
    val error = draft.validationMessage()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Свой период") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("С какого момента по какой смотреть данные.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("С ${fromDate.format(DayLabel)}", onClick = { pick = Pick.FromDate }, modifier = Modifier.weight(1f))
                    SoftButton(fromTime.format(TimeLabel), onClick = { pick = Pick.FromTime }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("По ${toDate.format(DayLabel)}", onClick = { pick = Pick.ToDate }, modifier = Modifier.weight(1f))
                    SoftButton(toTime.format(TimeLabel), onClick = { pick = Pick.ToTime }, modifier = Modifier.weight(1f))
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.med) }
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = { onConfirm(draft) }) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    when (val p = pick) {
        Pick.FromDate -> DatePickDialog(fromDate, onConfirm = { fromDate = it; pick = null }, onDismiss = { pick = null })
        Pick.ToDate -> DatePickDialog(toDate, onConfirm = { toDate = it; pick = null }, onDismiss = { pick = null })
        Pick.FromTime -> TimePickDialog(fromTime, onConfirm = { fromTime = it; pick = null }, onDismiss = { pick = null })
        Pick.ToTime -> TimePickDialog(toTime, onConfirm = { toTime = it; pick = null }, onDismiss = { pick = null })
        null -> Unit
    }
}

private enum class Pick { FromDate, FromTime, ToDate, ToTime }

private val DayLabel = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale("ru"))
private val TimeLabel = DateTimeFormatter.ofPattern("HH:mm", Locale("ru"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(current: LocalDate, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = current.atStartOfDay(zone).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = state.selectedDateMillis ?: return@TextButton
                    onConfirm(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())
                },
            ) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickDialog(current: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        text = { TimePicker(state = state, colors = TimePickerDefaults.colors(selectorColor = Petrovich.colors.accent)) },
    )
}

/** Стартовый черновик для диалога: текущий custom или последние сутки. */
fun defaultCustomDraft(current: TimeRange?): TimeRange {
    val now = LocalDateTime.now().withSecond(0).withNano(0)
    return current?.takeIf { it.isValid } ?: TimeRange(now.minusDays(1), now)
}
