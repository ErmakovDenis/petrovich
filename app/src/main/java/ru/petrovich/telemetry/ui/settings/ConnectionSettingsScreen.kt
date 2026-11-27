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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
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
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Instant
import java.time.ZoneId

@Composable
fun ConnectionSettingsScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val repo = ServiceLocator.settings
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val c = Petrovich.colors

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
    val importReport by ServiceLocator.anomalySync.lastImport.collectAsStateWithLifecycle()
    val backgroundState by ServiceLocator.backgroundAccess.state.collectAsStateWithLifecycle()

    LaunchedEffect(settings) {
        val s = settings ?: return@LaunchedEffect
        if (!initialized) {
            user = s.userName; password = s.password; serverUrl = s.serverUrl
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
        topBar = { AppTopBar(title = "Подключение и данные", onBack = onBack) },
        containerColor = c.bg,
    ) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("AutoGRAPH")
            SettingsCard {
                Text(
                    BuildConfig.API_BASE_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                PrimaryButton(
                    text = if (busy) "Входим…" else "Войти и загрузить схемы",
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
                if (busy) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
                    }
                }
                status?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(bottom = 12.dp))
                }
            }

            if (s.schemaName.isNotBlank() || schemas.isNotEmpty()) {
                SectionLabel("Схема")
                SettingsCard {
                    if (s.schemaName.isNotBlank()) {
                        Text(
                            "Текущая: ${s.schemaName}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    schemas.forEachIndexed { i, schema ->
                        if (i > 0 || s.schemaName.isNotBlank()) HorizontalDivider(color = c.line)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = schema.id == s.schemaId,
                                    onClick = { save { it.copy(schemaId = schema.id, schemaName = schema.name) } },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = schema.id == s.schemaId,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = c.accent),
                            )
                            Text(schema.name, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            SectionLabel("Стенд")
            SettingsCard {
                Text(
                    "Данные, проверка машин, чат и письма всегда идут через стенд. " +
                        if (s.standPasswordConsent) {
                            "Пароль AutoGRAPH передан стенду с вашего согласия для фоновой проверки."
                        } else {
                            "Доступ — по сессии AutoGRAPH; пароль на стенд не передаётся, пока вы не разрешите ниже."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.muted,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                OutlinedTextField(
                    value = serverUrl, onValueChange = { serverUrl = it; serverStatus = null },
                    label = { Text("Адрес стенда") }, placeholder = { Text(BuildConfig.STAND_URL) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                val normalizedUrl = serverUrl.trim().trimEnd('/')
                SoftButton(
                    text = "Сохранить адрес",
                    enabled = normalizedUrl != s.serverUrl,
                    onClick = {
                        val parsed = normalizedUrl.toHttpUrlOrNull()
                        if (normalizedUrl.isNotEmpty() && parsed == null) {
                            serverStatus = "Адрес должен начинаться с http:// или https://"
                        } else if (parsed != null && !parsed.isHttps && !BuildConfig.DEBUG) {
                            serverStatus = "Нужен адрес https:// — без шифрования стенд доступен только в отладочной сборке"
                        } else {
                            scope.launch {
                                val revoked = runCatchingCancellable { ServiceLocator.backgroundAccess.revoke() }
                                if (revoked.isFailure && urlWithoutRevoke != normalizedUrl) {
                                    urlWithoutRevoke = normalizedUrl
                                    serverStatus = "Прежний стенд не ответил — доступ для фоновой проверки там не отозван " +
                                        "(без обновлений он удалится сам). Нажмите «Сохранить адрес» ещё раз, чтобы сменить адрес без отзыва"
                                    return@launch
                                }
                                urlWithoutRevoke = null
                                val saved = normalizedUrl.ifBlank { BuildConfig.STAND_URL }
                                repo.update { it.copy(serverUrl = saved, standAccessGranted = false) }
                                serverUrl = saved
                                onChanged()
                                serverStatus = if (normalizedUrl.isEmpty()) {
                                    "Адрес стенда сброшен на ${BuildConfig.STAND_URL}"
                                } else {
                                    "Адрес стенда сохранён"
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                serverStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted, modifier = Modifier.padding(bottom = 12.dp))
                }
            }

            AssistantIdentitySection(s)

            SectionLabel("Антифрод")
            SettingsCard {
                SettingsSwitchRow(
                    title = "При недоступности антифрода использовать общие правила",
                    subtitle = "Если API Антифрод не отвечает — стенд посчитает проблемы пороговыми правилами. " +
                        "Чувствительность оценки может быть выше, чем у антифрода — включайте с осторожностью. " +
                        "Выключено — на экране проблем останется последний снимок с антифрода.",
                    checked = s.anomalyFallbackRules,
                    onChecked = { v -> save(reload = false) { it.copy(anomalyFallbackRules = v) } },
                )
                if (s.backgroundOnServer) {
                    HorizontalDivider(color = c.line)
                    SettingsSwitchRow(
                        title = "Разрешить стенду входить самостоятельно",
                        subtitle = "Стенд хранит пароль AutoGRAPH в зашифрованном виде и сам входит, когда сессия истекла, — " +
                            "проверка не прерывается, даже если телефон долго не в сети. Выключено — пароль удаляется со стенда",
                        checked = s.standPasswordConsent,
                        onChecked = { v -> save(reload = false) { it.copy(standPasswordConsent = v) } },
                    )
                    BackgroundStatusText(backgroundState)
                }
                if (BuildConfig.DEBUG) {
                    HorizontalDivider(color = c.line)
                    SettingsSwitchRow(
                        title = "Сравнивать с устройством (отладка)",
                        subtitle = "Проверка идёт и на устройстве, и на стенде; расхождения — в logcat (AnomalyCompare)",
                        checked = s.anomalyCompare,
                        onChecked = { v -> save(reload = false) { it.copy(anomalyCompare = v) } },
                    )
                }
            }

            importReport?.let { ImportReportText(it) }
            SoftButton(
                text = "Очистить копию ленты на устройстве",
                onClick = { scope.launch { ServiceLocator.anomalyStore.clear() } },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
        }
    }
}

/** Состояние доступа стенда для фоновой проверки. */
@Composable
private fun BackgroundStatusText(state: BackgroundState) {
    val c = Petrovich.colors
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
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state.error != null || st?.lastError != null) c.high else c.muted,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

/** Отчёт о переносе истории на стенд: несопоставленные решения перечислены, а не потеряны молча. */
@Composable
private fun ImportReportText(report: ImportReport) {
    val c = Petrovich.colors
    var expanded by remember { mutableStateOf(false) }
    val r = report.result
    val at = Instant.ofEpochMilli(report.at).atZone(ZoneId.systemDefault()).toLocalDateTime()
    SettingsCard {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Перенос истории на стенд (${at.toLocalDate().title()}, ${at.hhmm()}): записей ${report.sent}, решений ${r.decisions} — " +
                    "перенесено ${r.applied}, на стенде уже были ${r.alreadyResolved}, без пары ${r.unmatched.size}. " +
                    "Аномалий без решения найдено на стенде ${r.restored}, не найдено ${r.notFound}.",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )
            (report.failed + r.scanErrors).forEach {
                Text("Не перенесено, повторим при обновлении ленты: $it", style = MaterialTheme.typography.bodySmall, color = c.high)
            }
            if (r.unmatched.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Скрыть решения без пары" else "Показать решения без пары (${r.unmatched.size})")
                }
                if (expanded) r.unmatched.forEach { u ->
                    val decision = if (u.resolution == Resolution.CONFIRMED) {
                        "подтверждено"
                    } else {
                        "ложная тревога" + (u.reason?.let { " ($it)" } ?: "")
                    }
                    Text(
                        "${u.vehicleName.ifBlank { u.vehicleId }}: ${u.title}, ${u.eventTime.replace('T', ' ')} — $decision. ${u.why}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.muted,
                    )
                }
            }
        }
    }
}
