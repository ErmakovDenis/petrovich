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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import ru.petrovich.telemetry.BuildConfig
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.BackgroundState
import ru.petrovich.telemetry.anomaly.ImportReport
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.data.Schema
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.data.settings.ThemeMode
import ru.petrovich.telemetry.ui.theme.Petrovich
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Instant
import java.time.ZoneId

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
    var serverUrl by remember { mutableStateOf("") }
    var serverStatus by remember { mutableStateOf<String?>(null) }
    /** Адрес, который можно сохранить, не отозвав доступ на прежнем стенде (тот не ответил). */
    var urlWithoutRevoke by remember { mutableStateOf<String?>(null) }
    var storeStatus by remember { mutableStateOf<String?>(null) }
    var storeBusy by remember { mutableStateOf(false) }
    val importReport by ServiceLocator.anomalySync.lastImport.collectAsStateWithLifecycle()
    val backgroundState by ServiceLocator.backgroundAccess.state.collectAsStateWithLifecycle()

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!initialized) {
            user = s.userName; password = s.password; serverUrl = s.serverUrl; initialized = true
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

            Text("Стенд", style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.backgroundOnServer && s.standPasswordConsent) {
                    "Сервер «Петровича» с ассистентом. Доступ — по сессии AutoGRAPH; пароль передан стенду с вашего " +
                        "согласия для фоновой проверки (раздел «Аномалии»)."
                } else {
                    "Сервер «Петровича» с ассистентом. Доступ — по сессии AutoGRAPH, логин и пароль на стенд не передаются."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it; serverStatus = null },
                label = { Text("Адрес стенда") }, placeholder = { Text("https://stand.example.ru") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            val normalizedUrl = serverUrl.trim().trimEnd('/')
            OutlinedButton(
                enabled = normalizedUrl != s.serverUrl,
                onClick = {
                    val parsed = normalizedUrl.toHttpUrlOrNull()
                    if (normalizedUrl.isNotEmpty() && parsed == null) {
                        serverStatus = "Адрес должен начинаться с http:// или https://"
                    } else if (parsed != null && !parsed.isHttps && !BuildConfig.DEBUG) {
                        // HTTP без TLS разрешён только в debug-сборке (app/src/debug, network security config).
                        serverStatus = "Нужен адрес https:// — без шифрования стенд доступен только в отладочной сборке"
                    } else {
                        scope.launch {
                            // Доступ для фоновой проверки отзывается на прежнем стенде: потом отозвать будет негде.
                            // Прежний стенд не ответил — адрес меняется только по повторному нажатию.
                            val revoked = runCatchingCancellable { ServiceLocator.backgroundAccess.revoke() }
                            if (revoked.isFailure && urlWithoutRevoke != normalizedUrl) {
                                urlWithoutRevoke = normalizedUrl
                                serverStatus = "Прежний стенд не ответил — доступ для фоновой проверки там не отозван " +
                                    "(без обновлений он удалится сам). Нажмите «Сохранить адрес» ещё раз, чтобы сменить адрес без отзыва"
                                return@launch
                            }
                            urlWithoutRevoke = null
                            // Доступ на новом стенде выдаётся заново (TelemetryApp следит за адресом).
                            repo.update { it.copy(serverUrl = normalizedUrl, standAccessGranted = false) }
                            // С «Данными через стенд» адрес решает, откуда грузятся данные: экраны перечитываем.
                            if (s.telemetryViaServer && !s.demoMode) onChanged()
                            serverStatus = if (normalizedUrl.isEmpty()) "Адрес стенда очищен" else "Адрес стенда сохранён"
                        }
                    }
                },
            ) { Text("Сохранить адрес") }
            serverStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            SwitchRow(
                title = "Ассистент через стенд",
                subtitle = when {
                    s.demoMode -> "Не действует в демо-режиме — отвечает заглушка"
                    s.serverUrl.isBlank() -> "Сначала укажите адрес стенда"
                    else -> "Чат «Петрович» отвечает моделью на стенде; выключено — заглушка"
                },
                checked = s.assistantViaServer,
                onChecked = { v -> save(reload = false) { it.copy(assistantViaServer = v) } },
            )
            SwitchRow(
                title = "Письма из чата",
                subtitle = when {
                    !s.assistantOnServer -> "Сначала включите «Ассистент через стенд»"
                    else -> "Петрович может подготовить письмо получателям из списка стенда; письмо уходит, только когда вы " +
                        "нажмёте «Отправить» в чате"
                },
                checked = s.emailViaServer,
                enabled = s.assistantOnServer,
                onChecked = { v -> save(reload = false) { it.copy(emailViaServer = v) } },
            )
            SwitchRow(
                title = "Данные через стенд",
                subtitle = when {
                    s.demoMode -> "Не действует в демо-режиме — данные демо"
                    s.serverUrl.isBlank() -> "Сначала укажите адрес стенда"
                    else -> "Таблицы, графики и проверка аномалий берут данные со стенда; выключено — из AutoGRAPH напрямую"
                },
                checked = s.telemetryViaServer,
                // Машины те же (id AutoGRAPH), поэтому найденные аномалии не сбрасываем — только перезагружаем данные.
                onChecked = { v ->
                    scope.launch {
                        repo.update { it.copy(telemetryViaServer = v) }
                        onChanged()
                    }
                },
            )
            HorizontalDivider()

            Text("Аномалии", style = MaterialTheme.typography.titleMedium)
            Text(
                if (s.anomaliesOnServer) {
                    "Проверку выполняет стенд: правила и сервис аналитики. Если модели аналитики не загружены, проверка идёт только по правилам."
                } else {
                    "Детектор: ${ServiceLocator.anomalyDetector.name}. " +
                        if (ServiceLocator.mlDetector.isReady) "ML-модель загружена." else "ML-модель не найдена (assets/models/anomaly.tflite)."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchRow(
                title = "Аномалии со стенда",
                subtitle = when {
                    s.demoMode -> "Не действует в демо-режиме — проверяет устройство"
                    s.serverUrl.isBlank() -> "Сначала укажите адрес стенда"
                    else -> "Проверка машин — на стенде; лента, уведомления и решения остаются в приложении. Выключено — проверяет устройство"
                },
                checked = s.anomaliesViaServer,
                // id аномалий у стенда и устройства одинаковые (тот же алгоритм), поэтому ленту не сбрасываем.
                onChecked = { v -> save(reload = false) { it.copy(anomaliesViaServer = v) } },
            )
            SwitchRow(
                title = "Хранить аномалии на стенде",
                subtitle = when {
                    !s.anomaliesOnServer -> "Сначала включите «Аномалии со стенда»"
                    else -> "Лента и решения — на стенде, общие для всех пользователей схемы; в приложении — копия для " +
                        "показа без сети. При включении история с устройства переносится на стенд. Выключено — всё на устройстве"
                },
                checked = s.anomalyStoreViaServer,
                enabled = s.anomaliesOnServer && !storeBusy,
                onChecked = { v ->
                    storeStatus = null
                    scope.launch {
                        repo.update { it.copy(anomalyStoreViaServer = v) }
                        // Выключено: записи стенда уходят из копии ленты — у проверки на устройстве другие id.
                        if (!v) runCatchingCancellable { ServiceLocator.anomalySync.refresh() }
                        if (v) {
                            storeBusy = true
                            storeStatus = "Переносим историю на стенд и загружаем ленту…"
                            storeStatus = runCatchingCancellable { ServiceLocator.anomalySync.refresh() }
                                .fold({ "Лента загружена со стенда" }, { "Не получилось: ${it.message}. Повторим при следующем обновлении ленты" })
                            storeBusy = false
                        }
                    }
                },
            )
            storeStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            importReport?.let { ImportReportText(it) }
            SwitchRow(
                title = "Фоновая проверка на стенде",
                subtitle = when {
                    !s.anomalyStoreOnServer -> "Сначала включите «Хранить аномалии на стенде»"
                    !s.backgroundChecks -> "Сначала включите «Фоновую проверку» в «Что присылать»"
                    else -> "Стенд сам проверяет машины по расписанию, даже когда телефон выключен; приложение в фоне " +
                        "только забирает новые аномалии. Стенд работает по вашей сессии AutoGRAPH, пока она действует. " +
                        "Выключено — проверяет телефон, доступ стенда отзывается"
                },
                checked = s.backgroundViaServer,
                enabled = s.anomalyStoreOnServer && s.backgroundChecks,
                onChecked = { v -> save(reload = false) { it.copy(backgroundViaServer = v) } },
            )
            if (s.backgroundOnServer) {
                SwitchRow(
                    title = "Разрешить стенду входить самостоятельно",
                    subtitle = "Стенд хранит пароль AutoGRAPH в зашифрованном виде и сам входит, когда сессия истекла, — " +
                        "проверка не прерывается, даже если телефон долго не в сети. Выключено — пароль удаляется со стенда",
                    checked = s.standPasswordConsent,
                    onChecked = { v -> save(reload = false) { it.copy(standPasswordConsent = v) } },
                )
                BackgroundStatusText(backgroundState)
            }
            if (BuildConfig.DEBUG) {
                SwitchRow(
                    title = "Сравнивать с устройством (отладка)",
                    subtitle = "Проверка идёт и на устройстве, и на стенде; расхождения — в logcat (AnomalyCompare). " +
                        "В ленту попадает результат по переключателю выше",
                    checked = s.anomalyCompare,
                    onChecked = { v -> save(reload = false) { it.copy(anomalyCompare = v) } },
                )
            }
            OutlinedButton(onClick = { scope.launch { ServiceLocator.anomalyStore.clear() } }) {
                // С хранилищем на стенде очищается только копия на устройстве: лента вернётся при обновлении.
                Text(if (s.anomalyStoreOnServer) "Очистить копию ленты на устройстве" else "Очистить историю аномалий")
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

/** Состояние доступа стенда для фоновой проверки. */
@Composable
private fun BackgroundStatusText(state: BackgroundState) {
    val st = state.status
    val text = when {
        state.error != null -> "Стенд не принял доступ: ${state.error}. Повторим при следующем фоновом обновлении"
        st == null -> "Передаём стенду доступ…"
        else -> buildString {
            append(if (st.passwordStored) "Доступ стенда: сессия AutoGRAPH и сохранённый пароль" else "Доступ стенда: сессия AutoGRAPH")
            val interval = if (st.intervalMinutes % 1.0 == 0.0) st.intervalMinutes.toInt().toString() else st.intervalMinutes.toString()
            append(". Проверка каждые $interval мин за последние ${st.windowHours} ч")
            st.lastScanAt?.let {
                val at = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
                append(". Последняя проверка: ${at.toLocalDate().title()}, ${at.hhmm()}")
            }
            st.lastError?.let { append(". $it") }
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (state.error != null || st?.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Отчёт о переносе истории на стенд: несопоставленные решения перечислены, а не потеряны молча. */
@Composable
private fun ImportReportText(report: ImportReport) {
    var expanded by remember { mutableStateOf(false) }
    val r = report.result
    val at = Instant.ofEpochMilli(report.at).atZone(ZoneId.systemDefault()).toLocalDateTime()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Перенос истории на стенд (${at.toLocalDate().title()}, ${at.hhmm()}): записей ${report.sent}, решений ${r.decisions} — " +
                "перенесено ${r.applied}, на стенде уже были ${r.alreadyResolved}, без пары ${r.unmatched.size}. " +
                "Аномалий без решения найдено на стенде ${r.restored}, не найдено ${r.notFound}.",
            style = MaterialTheme.typography.bodySmall,
        )
        (report.failed + r.scanErrors).forEach {
            Text("Не перенесено, повторим при обновлении ленты: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (r.unmatched.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Скрыть решения без пары" else "Показать решения без пары (${r.unmatched.size})")
            }
            if (expanded) r.unmatched.forEach { u ->
                val decision = if (u.resolution == Resolution.CONFIRMED) "подтверждено" else "ложная тревога" + (u.reason?.let { " ($it)" } ?: "")
                Text(
                    "${u.vehicleName.ifBlank { u.vehicleId }}: ${u.title}, ${u.eventTime.replace('T', ' ')} — $decision. ${u.why}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
