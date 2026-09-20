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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import ru.petrovich.telemetry.data.settings.ThemeMode
import ru.petrovich.telemetry.ui.theme.Petrovich
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.util.runCatchingCancellable

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

    /** Сохраняет настройку; [reload] — если она меняет источник данных (демо/API, схема). */
    fun save(reload: Boolean = true, transform: (AppSettings) -> AppSettings) = scope.launch {
        repo.update(transform)
        if (reload) {
            ServiceLocator.onDataSourceChanged()
            onChanged()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "Настройки", onBack = onBack)
        },
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Оформление", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = s.themeMode == mode,
                        onClick = { save(reload = false) { it.copy(themeMode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Petrovich.colors.accentSoft, activeContentColor = Petrovich.colors.accent,
                            activeBorderColor = Petrovich.colors.accent,
                            inactiveContainerColor = Petrovich.colors.surface, inactiveContentColor = Petrovich.colors.muted,
                            inactiveBorderColor = Petrovich.colors.line,
                        ),
                        label = { Text(mode.title) },
                    )
                }
            }
            HorizontalDivider()

            SwitchRow(
                title = "Демо-режим",
                subtitle = "Условные машины и аномалии без обращения к API — чтобы посмотреть, как всё выглядит",
                checked = s.demoMode,
                onChecked = { v -> save { it.copy(demoMode = v) } },
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
                        runCatchingCancellable { ServiceLocator.autoGraph.schemas(user.trim(), password) }
                            .onSuccess { list ->
                                schemas = list
                                repo.update {
                                    val single = list.singleOrNull()
                                    it.copy(
                                        userName = user.trim(), password = password, demoMode = false,
                                        schemaId = single?.id ?: it.schemaId, schemaName = single?.name ?: it.schemaName,
                                    )
                                }
                                status = "Вход выполнен. Схем: ${list.size}"
                                ServiceLocator.onDataSourceChanged()
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
