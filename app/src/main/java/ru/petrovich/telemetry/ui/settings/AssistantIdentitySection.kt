package ru.petrovich.telemetry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.AssistantIdentityState
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Явное подключение личности: без автоматической отправки пароля и обещания ещё не реализованной памяти. */
@Composable
fun AssistantIdentitySection(settings: AppSettings) {
    val identity = ServiceLocator.assistantIdentity
    val scope = rememberCoroutineScope()
    val c = Petrovich.colors
    var password by remember(settings.identityGeneration) { mutableStateOf("") }
    var consent by remember(settings.identityGeneration) { mutableStateOf(false) }
    var state by remember { mutableStateOf(AssistantIdentityState()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember(settings.identityGeneration) { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }

    LaunchedEffect(settings.identityCiphertext, settings.identityGeneration, settings.identityNeedsRevocation) {
        while (true) {
            state = identity.state()
            delay(30_000)
        }
    }

    fun action(block: suspend () -> String) {
        busy = true
        message = null
        scope.launch {
            try {
                runCatchingCancellable { block() }
                    .onSuccess { message = it }.onFailure { message = it.message ?: "Не удалось изменить привязку" }
                state = identity.state()
            } finally { busy = false }
        }
    }

    SectionLabel("Личный профиль Петровича")
    SettingsCard {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Привязка аккаунта для будущей памяти ассистента. Прозвища машин и история памяти пока недоступны.",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )
            if (!state.pendingRevocation && settings.userName.isNotBlank()) {
                Text(
                    "Аккаунт: ${settings.userName}. Схема: ${settings.schemaName.ifBlank { settings.schemaId }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                )
            }
            when {
                state.error != null -> Text(state.error.orEmpty(), color = c.high, style = MaterialTheme.typography.bodyMedium)
                state.pendingRevocation -> Text(
                    "Привязка отключена на устройстве. Отзыв на прежнем стенде повторится при доступной сети.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.connected -> Text(
                    "Привязка сохранена до " + Instant.ofEpochMilli(state.expiresAt!!).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("dd.MM HH:mm")),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Text(
                    "Аккаунт не привязан. Обычный чат доступен без личного профиля.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!state.connected && !state.pendingRevocation && state.error == null) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth(), label = { Text("Подтвердите пароль AutoGRAPH") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !busy)
                    Text(
                        "Разрешаю передать пароль стенду ${settings.serverUrl} для проверки аккаунта. Стенд не сохраняет этот пароль.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                PrimaryButton(
                    text = "Подтвердить личность",
                    enabled = !busy && consent && password.isNotEmpty() && settings.assistantOnServer &&
                        settings.userName.isNotBlank() && settings.schemaId.isNotBlank(),
                    onClick = {
                        val suppliedPassword = password
                        val generation = settings.identityGeneration
                        password = ""
                        consent = false
                        action { identity.connect(suppliedPassword, consent = true, expectedGeneration = generation); "Аккаунт привязан" }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.connected) {
                SoftButton(
                    text = "Проверить привязку",
                    enabled = !busy,
                    onClick = { action { identity.verify(); "Стенд подтвердил привязку" } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.connected) TelegramIdentitySection(settings)
            if (settings.identityCiphertext.isNotEmpty()) {
                SoftButton(
                    text = if (state.pendingRevocation) "Повторить отзыв" else "Отключить привязку",
                    enabled = !busy,
                    onClick = {
                        action {
                            if (identity.disconnect()) "Привязка отключена; отзыв сохранён и будет повторён"
                            else "Привязка отключена"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.error != null || state.pendingRevocation) {
                    TextButton(enabled = !busy, onClick = { confirmForget = true }) {
                        Text("Удалить только с устройства", color = c.high)
                    }
                }
            }
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
        }
    }
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text("Удалить локальную привязку?") },
        text = {
            Text(
                "Стенд не подтвердил отзыв. Его ключ доступа может действовать до исходного срока — не более часа после выдачи. " +
                    "После удаления повторить отзыв с устройства не получится.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                confirmForget = false
                action { identity.forgetLocally(); "Локальные данные удалены; отзыв на стенде не подтверждён" }
            }) { Text("Удалить с устройства") }
        },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Отмена") } },
    )
}
