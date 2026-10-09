package ru.petrovich.telemetry.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.petrovich.telemetry.chat.EmailDraft
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.MailIntent
import ru.petrovich.telemetry.util.ReportPdf
import java.util.UUID

/**
 * Черновик письма, который собрал Петрович: получатель, тело, вложение-PDF. «Отправить» открывает
 * настоящий почтовый клиент устройства с готовым письмом — саму отправку делает пользователь.
 */
@Composable
fun EmailDraftCard(draft: EmailDraft, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    val context = LocalContext.current
    var editing by remember(draft) { mutableStateOf(false) }
    var body by remember(draft) { mutableStateOf(draft.body) }
    var sent by remember(draft) { mutableStateOf(false) }

    SurfaceCard(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.MailOutline, null, tint = c.accent)
                Text("ЧЕРНОВИК ПИСЬМА", style = MaterialTheme.typography.labelSmall, color = c.accent, fontWeight = FontWeight.Bold)
            }
            Text(
                buildString {
                    append("Кому: ")
                    append(draft.recipientName ?: draft.recipientRole)
                    if (draft.recipientEmail != null) append(" · ${draft.recipientEmail}")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (draft.recipientEmail == null) c.muted else c.ink,
            )
            if (draft.recipientEmail == null) {
                Text(
                    "Адрес почты «${draft.recipientRole}» не указан — добавьте в «Настройках», иначе письмо откроется без адресата.",
                    style = MaterialTheme.typography.labelSmall, color = c.muted,
                )
            }
            if (editing) {
                OutlinedTextField(
                    value = body, onValueChange = { body = it }, modifier = Modifier.fillMaxWidth(),
                    minLines = 3, shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Сохранить", onClick = { editing = false }, modifier = Modifier.weight(1f))
                    SoftButton("Отмена", onClick = { body = draft.body; editing = false }, modifier = Modifier.weight(1f))
                }
            } else {
                Text(
                    body, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(12.dp),
                )
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.PictureAsPdf, null, Modifier, tint = c.accent)
                    Text("Отчёт и данные · PDF", style = MaterialTheme.typography.labelMedium, color = c.accent, fontWeight = FontWeight.SemiBold)
                }
                if (sent) {
                    Text("✓ Письмо открыто в почтовом клиенте", style = MaterialTheme.typography.labelMedium, color = c.muted)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            "Отправить",
                            onClick = {
                                val uri = runCatching { ReportPdf.build(context, "petrovich-${UUID.randomUUID()}.pdf", draft.subject, body) }.getOrNull()
                                sent = MailIntent.send(context, draft.recipientEmail, draft.subject, body, uri)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        SoftButton("Изменить", onClick = { editing = true }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
