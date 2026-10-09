package ru.petrovich.telemetry.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.data.settings.Contact
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.PChip
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private val WeekdayOrder = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)

/**
 * Вкладка «Настройки»: когда и как приходит доклад, какие push идут, кому звонить и писать, помощь.
 * [onRestartOnboarding] — «Пройти знакомство заново»; сам сброс делает вызывающая сторона.
 */
@Composable
fun SettingsScreen(onOpenConnection: () -> Unit, onRestartOnboarding: () -> Unit = {}) {
    val repo = ServiceLocator.settings
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val c = Petrovich.colors
    fun save(transform: (AppSettings) -> AppSettings) = scope.launch { repo.update(transform) }

    var timePicker by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<Contact?>(null) }
    var addingContact by remember { mutableStateOf(false) }
    var quietHoursDialog by remember { mutableStateOf(false) }
    var notificationExample by remember { mutableStateOf(false) }
    var connectionHelp by remember { mutableStateOf(false) }

    Scaffold(topBar = { AppTopBar("Настройки") }, containerColor = c.bg) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("Доклад")
            Card {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Время", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    SoftButton(s.reportTime, onClick = { timePicker = true }, container = c.accentSoft, content = c.accent)
                }
                HorizontalDivider(color = c.line)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WeekdayOrder.forEach { day ->
                        val on = day in s.reportDays
                        DayToggle(day, on, Modifier.weight(1f)) { save { prev -> prev.copy(reportDays = if (on) prev.reportDays - day else prev.reportDays + day) } }
                    }
                }
                HorizontalDivider(color = c.line)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PChip("Только текст", selected = !s.voiceReportEnabled, onClick = { save { it.copy(voiceReportEnabled = false) } })
                    PChip("Текст и голос", selected = s.voiceReportEnabled, onClick = { save { it.copy(voiceReportEnabled = true) } })
                }
            }

            SectionLabel("Срочные уведомления")
            Card {
                SwitchRow(
                    "Не больше 3 в день", "Остальное — в докладе",
                    s.limitDailyPush, onChecked = { v -> save { it.copy(limitDailyPush = v) } },
                )
                HorizontalDivider(color = c.line)
                SwitchRow(
                    "Тихие часы", "${s.quietHoursStart}–${s.quietHoursEnd} — ночью push только для срочных",
                    s.quietHoursEnabled, onChecked = { v -> save { it.copy(quietHoursEnabled = v) } },
                    onClick = { quietHoursDialog = true },
                )
            }

            SectionLabel("Кому звонить и писать")
            if (s.contacts.isNotEmpty()) {
                Card {
                    s.contacts.forEachIndexed { i, contact ->
                        if (i > 0) HorizontalDivider(color = c.line)
                        ContactRow(contact, onClick = { editingContact = contact })
                    }
                }
            }
            SoftButton("+ Добавить сотрудника", onClick = { addingContact = true }, modifier = Modifier.fillMaxWidth(), leading = Icons.Filled.Add)

            SectionLabel("Помощь")
            Card {
                HelpRow("Как выглядит срочное уведомление", onClick = { notificationExample = true })
                HorizontalDivider(color = c.line)
                HelpRow("Если не удаётся подключиться", onClick = { connectionHelp = true })
                HorizontalDivider(color = c.line)
                HelpRow("Пройти знакомство заново", onClick = onRestartOnboarding)
            }

            SectionLabel("Ещё")
            SurfaceCard(Modifier.fillMaxWidth(), onClick = onOpenConnection, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Подключение и данные", style = MaterialTheme.typography.titleSmall)
                        Text("AutoGRAPH, демо-режим, оформление, детектор аномалий", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.muted)
                }
            }
        }

        if (timePicker) {
            ReportTimeDialog(s.reportTime, onConfirm = { newTime -> save { it.copy(reportTime = newTime) }; timePicker = false }, onDismiss = { timePicker = false })
        }
        if (quietHoursDialog) {
            QuietHoursDialog(
                s.quietHoursStart, s.quietHoursEnd,
                onConfirm = { start, end -> save { it.copy(quietHoursStart = start, quietHoursEnd = end) }; quietHoursDialog = false },
                onDismiss = { quietHoursDialog = false },
            )
        }
        if (addingContact) {
            ContactEditorDialog(null, onSave = { contact -> save { it.copy(contacts = it.contacts + contact) }; addingContact = false }, onDismiss = { addingContact = false }, onDelete = null)
        }
        editingContact?.let { contact ->
            ContactEditorDialog(
                contact,
                onSave = { updated -> save { it.copy(contacts = it.contacts.map { c2 -> if (c2.id == updated.id) updated else c2 }) }; editingContact = null },
                onDismiss = { editingContact = null },
                onDelete = { save { it.copy(contacts = it.contacts.filterNot { c2 -> c2.id == contact.id }) }; editingContact = null },
            )
        }
        if (notificationExample) NotificationExampleDialog(onDismiss = { notificationExample = false })
        if (connectionHelp) ConnectionHelpDialog(onDismiss = { connectionHelp = false })
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) { content() }
    }
}

@Composable
private fun DayToggle(day: DayOfWeek, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Petrovich.colors
    val label = day.getDisplayName(TextStyle.SHORT, Locale("ru")).replaceFirstChar { it.uppercase() }.take(2)
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (on) c.accent else c.surface,
            contentColor = if (on) c.onAccent else c.muted,
        ),
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit, onClick: (() -> Unit)? = null) {
    val c = Petrovich.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.surface, uncheckedTrackColor = c.line, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.line),
        )
    }
}

/** Строка раздела «Помощь»: заголовок и шеврон, целиком кликабельна. */
@Composable
private fun HelpRow(title: String, onClick: () -> Unit) {
    val c = Petrovich.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.muted)
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    val c = Petrovich.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Text(contact.initials, color = c.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(contact.name, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(contact.role, contact.phone.ifBlank { null }, contact.email.ifBlank { null }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Изменить", tint = c.muted)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportTimeDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val parsed = runCatching { LocalTime.parse(current) }.getOrDefault(LocalTime.of(8, 0))
    val state = rememberTimePickerState(initialHour = parsed.hour, initialMinute = parsed.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm("%02d:%02d".format(state.hour, state.minute)) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        text = { TimePicker(state = state, colors = TimePickerDefaults.colors(selectorColor = Petrovich.colors.accent)) },
    )
}

/** Границы тихих часов — две кнопки времени, каждая открывает тот же [ReportTimeDialog]. */
@Composable
private fun QuietHoursDialog(start: String, end: String, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    val c = Petrovich.colors
    var newStart by remember { mutableStateOf(start) }
    var newEnd by remember { mutableStateOf(end) }
    var editingStart by remember { mutableStateOf(false) }
    var editingEnd by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Тихие часы") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ночью push только для срочных случаев.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("С $newStart", onClick = { editingStart = true }, modifier = Modifier.weight(1f))
                    SoftButton("До $newEnd", onClick = { editingEnd = true }, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(newStart, newEnd) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    if (editingStart) ReportTimeDialog(newStart, onConfirm = { newStart = it; editingStart = false }, onDismiss = { editingStart = false })
    if (editingEnd) ReportTimeDialog(newEnd, onConfirm = { newEnd = it; editingEnd = false }, onDismiss = { editingEnd = false })
}

/**
 * «Как выглядит срочное уведомление» — пример экрана блокировки (3.3), по которому владелец
 * учится узнавать срочный push. Чисто иллюстративно: кнопки на самом примере не действуют.
 */
@Composable
private fun NotificationExampleDialog(onDismiss: () -> Unit) {
    val c = Petrovich.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = Color(0xFF12213A), modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))
                Text("вторник, 29 сентября", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                Text("03:52", color = Color.White, style = MaterialTheme.typography.displaySmall.copy(fontSize = 52.sp))
                Spacer(Modifier.height(28.dp))
                SurfaceCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(22.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                                Text("П", color = c.onAccent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                            Text("ПЕТРОВИЧ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c.ink)
                            Spacer(Modifier.weight(1f))
                            Text("сейчас", style = MaterialTheme.typography.labelSmall, color = c.muted)
                        }
                        Text("Срочно · X452: ушло 140 л топлива", style = MaterialTheme.typography.titleSmall, color = c.high)
                        Text(
                            "На стоянке «Север», двигатель выключен, датчик исправен.",
                            style = MaterialTheme.typography.bodySmall, color = c.muted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SoftButton("Разберись", onClick = {}, modifier = Modifier.weight(1f))
                            SoftButton("Напомнить утром", onClick = {}, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Так приходит срочное уведомление. Нажмите на него.",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                SoftButton("Закрыть пример", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** «Если не удаётся подключиться» — честное объяснение по данным раздела 3.1 (ошибка подключения). */
@Composable
private fun ConnectionHelpDialog(onDismiss: () -> Unit) {
    val c = Petrovich.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Не получилось подключиться к АвтоГРАФ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Похоже, у вашего дилера старая версия сервера. Это не ваша ошибка, и данные в безопасности.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Что сказать дилеру", style = MaterialTheme.typography.titleSmall)
                Text("Нужен сервер АвтоГРАФ.WEB версии 2023.2.3.28 или новее.", style = MaterialTheme.typography.bodyMedium)
                Text("Код для поддержки: 404 · сервер дилера", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
    )
}

@Composable
private fun ContactEditorDialog(existing: Contact?, onSave: (Contact) -> Unit, onDismiss: () -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var role by remember { mutableStateOf(existing?.role.orEmpty()) }
    var phone by remember { mutableStateOf(existing?.phone.orEmpty()) }
    var email by remember { mutableStateOf(existing?.email.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Новый сотрудник" else "Изменить сотрудника") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(role, { role = it }, label = { Text("Роль (например, Механик)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it }, label = { Text("Телефон") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                OutlinedTextField(email, { email = it }, label = { Text("Почта") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Удалить сотрудника", color = Petrovich.colors.high) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && role.isNotBlank(),
                onClick = { onSave(Contact(id = existing?.id ?: UUID.randomUUID().toString(), name = name.trim(), role = role.trim(), phone = phone.trim(), email = email.trim())) },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
