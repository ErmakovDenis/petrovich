package ru.petrovich.telemetry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.TelegramIdentityLink
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.util.runCatchingCancellable

@Composable
fun TelegramIdentitySection(settings: AppSettings) {
    val identity = ServiceLocator.assistantIdentity
    val scope = rememberCoroutineScope()
    var code by remember(settings.identityCiphertext) { mutableStateOf("") }
    var consent by remember(settings.identityCiphertext) { mutableStateOf(false) }
    var links by remember(settings.identityCiphertext) { mutableStateOf<List<TelegramIdentityLink>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember(settings.identityCiphertext) { mutableStateOf<String?>(null) }

    fun action(block: suspend () -> String) {
        busy = true
        scope.launch {
            try {
                runCatchingCancellable { block() }.onSuccess { status = it }
                    .onFailure { status = it.message ?: "Не удалось изменить привязку Telegram" }
            } finally { busy = false }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Telegram: личный профиль", style = MaterialTheme.typography.titleSmall)
        Text("Отправьте /identity в личном чате с ботом и введите персональный код. Старое подключение уведомлений отдельного согласия не заменяет.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth(), label = { Text("Персональный код из Telegram") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy)
            Text("Это мой личный чат. Разрешаю связать его с моим профилем Петровича.", style = MaterialTheme.typography.bodySmall)
        }
        Button(enabled = !busy && consent && code.isNotBlank(), onClick = {
            val supplied = code
            code = ""; consent = false
            action {
                val linked = identity.pairTelegram(supplied, true, settings.identityGeneration, settings.identityCiphertext)
                links = listOf(linked)
                "Личный чат подключён. Проверить: /identity_status; отключить: /forget_identity"
            }
        }) { Text("Подключить личный чат") }
        OutlinedButton(enabled = !busy, onClick = {
            action { links = identity.telegramLinks(); if (links.isEmpty()) "Подключённых личных чатов нет" else "Привязки загружены" }
        }) { Text("Показать привязки") }
        links.forEach { link ->
            TextButton(enabled = !busy, onClick = {
                action {
                    identity.unlinkTelegram(link.chatId, settings.identityGeneration, settings.identityCiphertext)
                    links = links.filterNot { it.chatId == link.chatId }
                    "Личный профиль этого чата отключён"
                }
            }) { Text("Отключить чат ${link.chatId}") }
        }
        Text("Привязка действует в пределах срока текущего подтверждения личности. При его отзыве Telegram теряет персональный доступ.", style = MaterialTheme.typography.bodySmall)
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
