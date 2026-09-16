package ru.petrovich.telemetry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.BuildConfig
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.Schema
import ru.petrovich.telemetry.data.settings.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val repo = ServiceLocator.settings
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var schemas by remember { mutableStateOf<List<Schema>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!initialized) {
            user = s.userName; password = s.password; initialized = true
        }
    }

    fun save(transform: (AppSettings) -> AppSettings) = scope.launch {
        repo.update(transform)
        onChanged()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SwitchRow(
                title = "Демо-режим",
                subtitle = "Синтетические данные 10 машин без обращения к API",
                checked = s.demoMode,
                onChecked = { v -> save { it.copy(demoMode = v) } },
            )
            SwitchRow(
                title = "Фоновая проверка аномалий",
                subtitle = "Каждые 15 минут с уведомлениями",
                checked = s.backgroundChecks,
                onChecked = { v -> save { it.copy(backgroundChecks = v) } },
            )
            HorizontalDivider()

            Text("AutoGRAPH API", style = MaterialTheme.typography.titleMedium)
            Text(BuildConfig.API_BASE_URL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = user, onValueChange = { user = it },
                label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(
                enabled = !busy && user.isNotBlank(),
                onClick = {
                    busy = true; status = null
                    scope.launch {
                        runCatching { ServiceLocator.autoGraph.schemas(user.trim(), password) }
                            .onSuccess { list ->
                                schemas = list
                                repo.update { it.copy(userName = user.trim(), password = password, demoMode = false) }
                                if (list.size == 1) repo.update { it.copy(schemaId = list[0].id, schemaName = list[0].name) }
                                status = "Вход выполнен. Схем: ${list.size}"
                                onChanged()
                            }
                            .onFailure { status = "Ошибка: ${it.message ?: it}" }
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Войти и загрузить схемы")
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            if (s.schemaName.isNotBlank()) {
                Text("Текущая схема: ${s.schemaName}", style = MaterialTheme.typography.bodyMedium)
            }
            schemas.forEach { schema ->
                Row(
                    Modifier.fillMaxWidth().selectable(
                        selected = schema.id == s.schemaId,
                        onClick = { save { it.copy(schemaId = schema.id, schemaName = schema.name) } },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = schema.id == s.schemaId, onClick = null)
                    Text(schema.name, Modifier.padding(start = 8.dp))
                }
            }
            HorizontalDivider()

            Text("Аномалии", style = MaterialTheme.typography.titleMedium)
            Text(
                "Детектор: ${ServiceLocator.anomalyDetector.name}. " +
                    if (ServiceLocator.mlDetector.isReady) "ML-модель загружена." else "ML-модель не найдена (assets/models/anomaly.tflite).",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { scope.launch { ServiceLocator.anomalyStore.clear() } }) {
                Text("Очистить историю аномалий")
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
