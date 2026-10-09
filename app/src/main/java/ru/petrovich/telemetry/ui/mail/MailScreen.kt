package ru.petrovich.telemetry.ui.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.chat.EmailDraft
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.MessageBox
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.mailContact
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.MailIntent
import ru.petrovich.telemetry.util.ReportPdf
import java.util.UUID

/**
 * «Письмо и отчёт» (3.9): отдельный экран вместо карточки внутри чата — кому, тема, текст,
 * вложение и «Отправить» открывает настоящий почтовый клиент, саму отправку делает владелец.
 */
@Composable
fun MailScreen(anomalyId: String, onBack: () -> Unit, onSent: (String) -> Unit) {
    val store = ServiceLocator.anomalyStore
    val anomalies by store.anomalies.collectAsStateWithLifecycle()
    val anomaly = anomalies.firstOrNull { it.id == anomalyId }
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val c = Petrovich.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        topBar = { AppTopBar(title = "Письмо и отчёт", onBack = onBack) },
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (anomaly == null) {
            Box(Modifier.padding(padding)) { MessageBox("Эта аномалия больше не хранится в истории") }
            return@Scaffold
        }
        val contact = mailContact(settings?.contacts.orEmpty())
        val draft = remember(anomaly.id, contact) { buildMailDraft(anomaly, contact) }
        var body by remember(anomaly.id) { mutableStateOf(draft.body) }
        var editing by remember(anomaly.id) { mutableStateOf(false) }

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SurfaceCard(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Кому", style = MaterialTheme.typography.labelMedium, color = c.muted)
                        if (draft.recipientEmail != null) {
                            Pill(draft.recipientName ?: draft.recipientRole, c.accent, c.accentSoft)
                        } else {
                            Text(draft.recipientName ?: draft.recipientRole, style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        }
                    }
                    HorizontalDivider(color = c.line)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Тема", style = MaterialTheme.typography.labelMedium, color = c.muted)
                        Text(draft.subject, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (draft.recipientEmail == null) {
                        Text(
                            "Адрес почты «${draft.recipientRole}» не указан — добавьте в «Настройках», иначе письмо откроется без адресата.",
                            style = MaterialTheme.typography.labelSmall, color = c.muted,
                        )
                    }
                }
            }

            if (editing) {
                OutlinedTextField(
                    value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth(),
                    minLines = 4, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line),
                )
            } else {
                Text(
                    body, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(12.dp),
                )
            }

            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.PictureAsPdf, null, tint = c.accent)
                Text("Данные события · PDF", style = MaterialTheme.typography.labelMedium, color = c.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(if (editing) "Готово" else "Изменить", onClick = { editing = !editing }, modifier = Modifier.weight(1f))
                PrimaryButton(
                    "Отправить",
                    onClick = {
                        val uri = runCatching { ReportPdf.build(context, "petrovich-${UUID.randomUUID()}.pdf", draft.subject, body) }.getOrNull()
                        MailIntent.send(context, draft.recipientEmail, draft.subject, body, uri)
                        scope.launch {
                            if (contact != null) store.markInProgress(anomaly.id, "${contact.name} · ${contact.role}")
                            store.appendStep(anomaly.id, "Письмо в ${draft.recipientRole.lowercase()} с данными события")
                        }
                        onSent(anomaly.id)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private val SubjectDateFormat = java.time.format.DateTimeFormatter.ofPattern("dd.MM")

private fun buildMailDraft(anomaly: ru.petrovich.telemetry.anomaly.Anomaly, contact: ru.petrovich.telemetry.data.settings.Contact?): EmailDraft {
    val whenText = anomaly.time?.let { "${it.toLocalDate().title()} в ${it.hhmm()}" } ?: anomaly.eventTime
    val dateSuffix = anomaly.time?.toLocalDate()?.format(SubjectDateFormat)?.let { ", $it" } ?: ""
    return EmailDraft(
        recipientRole = contact?.role ?: "Служба безопасности",
        recipientName = contact?.name,
        recipientEmail = contact?.email?.ifBlank { null },
        subject = "${anomaly.vehicleName}: ${anomaly.title}$dateSuffix",
        body = "$whenText у ${anomaly.vehicleName} — ${anomaly.description} Прошу проверить.",
    )
}
