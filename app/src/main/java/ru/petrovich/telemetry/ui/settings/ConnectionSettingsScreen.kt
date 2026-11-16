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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import ru.petrovich.telemetry.data.api.PetrovichAi
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.data.settings.ThemeMode
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSettingsScreen(onBack: () -> Unit, onChanged: () -> Unit, onOpenTelegram: () -> Unit = {}) {
    val repo = ServiceLocator.settings
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var apiUser by remember { mutableStateOf("") }
    var apiPassword by remember { mutableStateOf("") }
    var apiBusy by remember { mutableStateOf(false) }
    var apiStatus by remember { mutableStateOf<String?>(null) }
    var schemas by remember { mutableStateOf<List<Schema>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var initialized by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var serverStatus by remember { mutableStateOf<String?>(null) }
    /** Адрес, который можно сохранить, не отозвав доступ на прежнем стенде (тот не ответил). */
    var urlWithoutRevoke by remember { mutableStateOf<String?>(null) }
    val importReport by ServiceLocator.anomalySync.lastImport.collectAsStateWithLifecycle()
    val backgroundState by ServiceLocator.backgroundAccess.state.collectAsStateWithLifecycle()

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!initialized) {
            user = s.userName; password = s.password; serverUrl = s.serverUrl
            apiUser = s.anomalyApiUser; apiPassword = s.anomalyApiPassword
            initialized = true
        }
    }

    /** Сохраняет настройку; [reload] — если она меняет схему или учётную запись. */
    fun save(reload: Boolean = true, transform: (AppSettings) -> AppSettings) = scope.launch {
        repo.update(transform)
        if (reload) {
            ServiceLocator.onDataSourceChanged()
            onChanged()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "Подключение и данные", onBack = onBack)
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

            Text("AutoGRAPH", style = MaterialTheme.typography.titleMedium)
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
                "Данные, проверка машин, чат и письма всегда идут через стенд. " +
                    if (s.standPasswordConsent) {
                        "Пароль AutoGRAPH передан стенду с вашего согласия для фоновой проверки."
                    } else {
                        "Доступ — по сессии AutoGRAPH; пароль на стенд не передаётся, пока вы не разрешите ниже."
                    },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it; serverStatus = null },
                label = { Text("Адрес стенда") }, placeholder = { Text(BuildConfig.STAND_URL) },
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
                            // Пустой адрес → снова адрес из сборки; доступ на новом стенде выдаётся заново.
                            val saved = normalizedUrl.ifBlank { BuildConfig.STAND_URL }
                            repo.update { it.copy(serverUrl = saved, standAccessGranted = false) }
                            serverUrl = saved
                            onChanged()
                            serverStatus = if (normalizedUrl.isEmpty()) "Адрес стенда сброшен на ${BuildConfig.STAND_URL}" else "Адрес стенда сохранён"
                        }
                    }
                },
            ) { Text("Сохранить адрес") }
            serverStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text("Детекция через API", style = MaterialTheme.typography.titleMedium)
            Text(
                "API Petrovich AI (${BuildConfig.PETROVICH_AI_URL}) проверяет машины по данным, загруженным у себя, " +
                    "— вместо детектора на устройстве. Доступ — по логину и паролю (HTTP Basic). Машины сопоставляются " +
                    "со списком API по id, госномеру или модели — парк должен быть загружен в API (ingest или sync AutoGRAPH).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = apiUser, onValueChange = { apiUser = it; apiStatus = null },
                label = { Text("Логин API") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiPassword, onValueChange = { apiPassword = it; apiStatus = null },
                label = { Text("Пароль API") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(
                enabled = !apiBusy && apiUser.isNotBlank() && apiPassword.isNotBlank(),
                onClick = {
                    apiBusy = true; apiStatus = null
                    scope.launch {
                        runCatchingCancellable {
                            repo.update { it.copy(anomalyApiUser = apiUser.trim(), anomalyApiPassword = apiPassword) }
                            // Разовый клиент с теми кредами, что только что сохранены: проверяем именно их, без гонки с кэшем.
                            PetrovichAi.create(credentials = { apiUser.trim() to apiPassword }).health()
                        }
                            .onSuccess { apiStatus = "Соединение установлено — API принял логин и пароль" }
                            .onFailure { apiStatus = "Ошибка: ${it.message ?: it}" }
                        apiBusy = false
                    }
                },
            ) {
                if (apiBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Сохранить и проверить")
            }
            apiStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            SwitchRow(
                title = "Детекция аномалий через API",
                subtitle = when {
                    s.anomalyApiUser.isBlank() || s.anomalyApiPassword.isBlank() ->
                        "Сначала укажите логин и пароль API и нажмите «Сохранить и проверить»"
                    s.anomalyApiEnabled && s.anomalyApiDegraded ->
                        "API сейчас недоступен — проверка идёт пороговыми правилами на устройстве, как раньше"
                    else -> "Проверку выполняет API Petrovich AI; выключено — детектор на устройстве (пороговые правила, ML-модель)"
                },
                checked = s.anomalyApiEnabled,
                // id аномалий у API свои: ленту не сбрасываем — старые записи просто не пополняются.
                onChecked = { v -> save(reload = false) { it.copy(anomalyApiEnabled = v) } },
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
                OutlinedButton(onClick = onOpenTelegram) { Text("Telegram-бот: подключить и настроить") }
            }
            importReport?.let { ImportReportText(it) }
            if (BuildConfig.DEBUG) {
                SwitchRow(
                    title = "Сравнивать с устройством (отладка)",
                    subtitle = "Проверка идёт и на устройстве, и на стенде; расхождения — в logcat (AnomalyCompare)",
                    checked = s.anomalyCompare,
                    onChecked = { v -> save(reload = false) { it.copy(anomalyCompare = v) } },
                )
            }
            OutlinedButton(onClick = { scope.launch { ServiceLocator.anomalyStore.clear() } }) {
                Text("Очистить копию ленты на устройстве")
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
