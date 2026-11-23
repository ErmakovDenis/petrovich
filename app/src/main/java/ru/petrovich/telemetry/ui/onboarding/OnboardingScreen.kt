package ru.petrovich.telemetry.ui.onboarding

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import retrofit2.HttpException
import ru.petrovich.telemetry.BuildConfig
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.TELEGRAM_CATEGORIES
import ru.petrovich.telemetry.data.TELEGRAM_KINDS
import ru.petrovich.telemetry.data.settings.DefaultReportDays
import ru.petrovich.telemetry.data.settings.ThemeMode
import ru.petrovich.telemetry.ui.common.PChip
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.MailIntent
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Шаги мастера: вход → настройки после AutoGRAPH. */
private enum class Step { WELCOME, THEME, REPORT, PROBLEMS, NOTIFY }

private val ReportTimeOptions = listOf("07:00", "08:00", "09:00", "10:00")
private val QuietStartOptions = listOf("21:00", "22:00", "23:00")
private val QuietEndOptions = listOf("06:00", "07:00", "08:00")
private val WeekdayOrder = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)

/** Что сказать владельцу при ошибке подключения — настоящий код ошибки, без выдумывания. */
private data class ConnectError(val message: String, val supportCode: String?)

/**
 * Первый запуск: знакомство → AutoGRAPH → тема, доклад, типы проблем, уведомления.
 * После входа в учётку пользователь выбирает то же, что потом в «Настройках».
 */
@Composable
fun OnboardingScreen(onConnected: () -> Unit) {
    val c = Petrovich.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stepOrdinal by rememberSaveable { mutableStateOf(0) }
    val step = Step.entries.getOrElse(stepOrdinal) { Step.WELCOME }

    var showCredentials by rememberSaveable { mutableStateOf(false) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }

    var connectError by remember { mutableStateOf<ConnectError?>(null) }
    var showError by remember { mutableStateOf(false) }

    var themeMode by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM.name) }
    var reportTime by rememberSaveable { mutableStateOf("08:00") }
    var reportDaysCsv by rememberSaveable {
        mutableStateOf(DefaultReportDays.joinToString(",") { it.name })
    }
    var voiceReport by rememberSaveable { mutableStateOf(true) }
    var mutedCsv by rememberSaveable { mutableStateOf("") }
    var limitDailyPush by rememberSaveable { mutableStateOf(true) }
    var quietHoursEnabled by rememberSaveable { mutableStateOf(true) }
    var quietStart by rememberSaveable { mutableStateOf("22:00") }
    var quietEnd by rememberSaveable { mutableStateOf("07:00") }

    val reportDays = remember(reportDaysCsv) {
        reportDaysCsv.split(',').mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }.toSet()
    }
    val mutedKinds = remember(mutedCsv) {
        mutedCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
    val theme = remember(themeMode) {
        ThemeMode.entries.firstOrNull { it.name == themeMode } ?: ThemeMode.SYSTEM
    }

    fun supportCodeFor(e: Throwable): String =
        (e as? HttpException)?.let { "HTTP ${it.code()}" } ?: e.message?.take(70) ?: (e::class.simpleName ?: "неизвестная ошибка")

    fun goNext() {
        if (stepOrdinal < Step.entries.lastIndex) stepOrdinal++
    }

    fun goBack() {
        if (stepOrdinal > 0) stepOrdinal--
    }

    fun attemptLogin() {
        busy = true; loginError = null
        scope.launch {
            runCatchingCancellable { ServiceLocator.autoGraph.schemas(user.trim(), password) }
                .onSuccess { list ->
                    val schema = list.firstOrNull()
                    if (schema == null) {
                        loginError = "Для этого логина нет доступных схем."
                    } else {
                        ServiceLocator.settings.update {
                            it.copy(
                                userName = user.trim(), password = password, demoMode = false,
                                schemaId = schema.id, schemaName = schema.name,
                            )
                        }
                        ServiceLocator.onDataSourceChanged()
                        stepOrdinal = Step.THEME.ordinal
                    }
                }
                .onFailure { e ->
                    connectError = ConnectError(
                        message = "Похоже, у вашего дилера старая версия сервера. Это не ваша ошибка, и данные в безопасности.",
                        supportCode = supportCodeFor(e),
                    )
                    showError = true
                }
            busy = false
        }
    }

    fun applyTheme(mode: ThemeMode) {
        themeMode = mode.name
        scope.launch { ServiceLocator.settings.update { it.copy(themeMode = mode) } }
    }

    fun finish() {
        scope.launch {
            // Порядок важен: после onboarded=true этот экран уходит из композиции и корутина отменяется.
            ServiceLocator.settings.update {
                it.copy(
                    themeMode = theme,
                    reportTime = reportTime,
                    reportDays = reportDays.ifEmpty { DefaultReportDays },
                    voiceReportEnabled = voiceReport,
                    mutedKinds = mutedKinds,
                    limitDailyPush = limitDailyPush,
                    quietHoursEnabled = quietHoursEnabled,
                    quietHoursStart = quietStart,
                    quietHoursEnd = quietEnd,
                )
            }
            onConnected()
            ServiceLocator.settings.update { it.copy(onboarded = true) }
        }
    }

    if (showError) {
        ConnectionErrorScreen(
            error = connectError,
            onRetry = { showError = false },
            onWriteDealer = {
                val code = connectError?.supportCode ?: "—"
                MailIntent.send(
                    context = context,
                    to = null,
                    subject = "Не удаётся подключиться к АвтоГРАФ",
                    body = "Код для поддержки: $code · сервер дилера\n" +
                        "Нужен сервер АвтоГРАФ.WEB версии 2023.2.3.28 или новее.\n" +
                        "Версия приложения «Петрович»: ${BuildConfig.VERSION_NAME}",
                )
            },
        )
        return
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        StepProgress(step)
        when (step) {
            Step.WELCOME -> WelcomeStep(
                user = user, onUserChange = { user = it },
                password = password, onPasswordChange = { password = it },
                showCredentials = showCredentials, onShowCredentials = { showCredentials = true },
                busy = busy, error = loginError,
                onSubmit = ::attemptLogin,
                onCantLogin = { showError = true },
            )
            Step.THEME -> ThemeStep(
                theme = theme,
                onTheme = ::applyTheme,
                onNext = ::goNext,
                onBack = ::goBack,
            )
            Step.REPORT -> ReportStep(
                reportTime = reportTime, onReportTime = { reportTime = it },
                reportDays = reportDays,
                onToggleDay = { day ->
                    val next = if (day in reportDays) reportDays - day else reportDays + day
                    reportDaysCsv = next.joinToString(",") { it.name }
                },
                voiceReport = voiceReport, onVoice = { voiceReport = it },
                onNext = ::goNext,
                onBack = ::goBack,
            )
            Step.PROBLEMS -> ProblemsStep(
                mutedKinds = mutedKinds,
                onToggle = { kind, on ->
                    val next = if (on) mutedKinds - kind else mutedKinds + kind
                    mutedCsv = next.joinToString(",")
                },
                onNext = ::goNext,
                onBack = ::goBack,
            )
            Step.NOTIFY -> NotifyStep(
                limitDailyPush = limitDailyPush, onLimit = { limitDailyPush = it },
                quietHoursEnabled = quietHoursEnabled, onQuiet = { quietHoursEnabled = it },
                quietStart = quietStart, onQuietStart = { quietStart = it },
                quietEnd = quietEnd, onQuietEnd = { quietEnd = it },
                onStart = ::finish,
                onBack = ::goBack,
            )
        }
    }
}

@Composable
private fun StepProgress(step: Step) {
    val c = Petrovich.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Шаг ${step.ordinal + 1} из ${Step.entries.size}",
            style = MaterialTheme.typography.labelSmall,
            color = c.muted,
            fontWeight = FontWeight.SemiBold,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Step.entries.forEach { s ->
                Box(
                    Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (s.ordinal <= step.ordinal) c.accent else c.line),
                )
            }
        }
    }
}

@Composable
private fun Logo() {
    Text(
        buildAnnotatedString {
            append("Петрович")
            withStyle(SpanStyle(color = Petrovich.colors.accent)) { append(".") }
        },
        fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 20.sp,
    )
}

@Composable
private fun EditableLaterNote() {
    val c = Petrovich.colors
    Text(
        "Эти настройки можно изменить позже в разделе «Настройки» приложения.",
        style = MaterialTheme.typography.bodySmall,
        color = c.muted,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(12.dp),
    )
}

@Composable
private fun StepNav(onBack: () -> Unit, onNext: () -> Unit, nextLabel: String = "Далее") {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SoftButton("Назад", onClick = onBack, modifier = Modifier.weight(1f))
        PrimaryButton(nextLabel, onClick = onNext, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WelcomeStep(
    user: String, onUserChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    showCredentials: Boolean, onShowCredentials: () -> Unit,
    busy: Boolean, error: String?,
    onSubmit: () -> Unit,
    onCantLogin: () -> Unit,
) {
    val c = Petrovich.colors
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Logo()
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Text("П", color = c.onAccent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            }
            Text("Здравствуйте!\nЯ Петрович", textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Слежу за вашим автопарком и коротко расскажу, что важно.",
                textAlign = TextAlign.Center, color = c.muted, style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Promise(Icons.Outlined.NotificationsActive, "Сразу сообщу, если с машиной что-то не так")
            Promise(Icons.Filled.PlayArrow, "Каждое утро — короткий доклад: прочитать или послушать")
            Promise(Icons.Filled.Call, "Позвоню и напишу сотрудникам по вашей просьбе")
        }
        if (!showCredentials) {
            PrimaryButton("Войти через АвтоГРАФ", onClick = onShowCredentials, modifier = Modifier.fillMaxWidth())
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = user, onValueChange = onUserChange, label = { Text("Логин AutoGRAPH") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                )
                OutlinedTextField(
                    value = password, onValueChange = onPasswordChange, label = { Text("Пароль") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                error?.let {
                    Text(
                        it, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.highSoft).padding(14.dp),
                        color = c.high, fontWeight = FontWeight.Medium,
                    )
                }
                if (busy) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
                        Spacer(Modifier.size(10.dp))
                        Text("Подключаемся к АвтоГРАФ…", style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    PrimaryButton(
                        "Войти", onClick = onSubmit, modifier = Modifier.fillMaxWidth(),
                        enabled = user.isNotBlank() && password.isNotEmpty(),
                    )
                }
            }
        }
        Text(
            "Данные берём только из вашей системы мониторинга АвтоГРАФ",
            textAlign = TextAlign.Center, color = c.faint, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Не получается войти?",
            textAlign = TextAlign.Center, color = c.accent, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onCantLogin).padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun Promise(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val c = Petrovich.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(16.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ThemeStep(
    theme: ThemeMode,
    onTheme: (ThemeMode) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Оформление", style = MaterialTheme.typography.headlineSmall)
            Text("Как показывать экраны Петровича.", style = MaterialTheme.typography.bodyMedium, color = Petrovich.colors.muted)
        }
        EditableLaterNote()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                PChip(mode.title, selected = theme == mode, onClick = { onTheme(mode) })
            }
        }
        Spacer(Modifier.height(8.dp))
        StepNav(onBack = onBack, onNext = onNext)
    }
}

@Composable
private fun ReportStep(
    reportTime: String, onReportTime: (String) -> Unit,
    reportDays: Set<DayOfWeek>, onToggleDay: (DayOfWeek) -> Unit,
    voiceReport: Boolean, onVoice: (Boolean) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Petrovich.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Утренний доклад", style = MaterialTheme.typography.headlineSmall)
            Text("Когда и в какие дни присылать сводку по парку.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        EditableLaterNote()
        Text("Время", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportTimeOptions.forEach { t -> PChip(t, selected = t == reportTime, onClick = { onReportTime(t) }) }
        }
        Text("Дни недели", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WeekdayOrder.forEach { day ->
                val on = day in reportDays
                val label = day.getDisplayName(TextStyle.SHORT, Locale("ru")).replaceFirstChar { it.uppercase() }.take(2)
                FilledTonalButton(
                    onClick = { onToggleDay(day) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (on) c.accent else c.surface,
                        contentColor = if (on) c.onAccent else c.muted,
                    ),
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Text("Формат", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PChip("Только текст", selected = !voiceReport, onClick = { onVoice(false) })
            PChip("Текст и голос", selected = voiceReport, onClick = { onVoice(true) })
        }
        Spacer(Modifier.height(8.dp))
        StepNav(onBack = onBack, onNext = onNext)
    }
}

@Composable
private fun ProblemsStep(
    mutedKinds: Set<String>,
    onToggle: (kind: String, enabled: Boolean) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Petrovich.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Какие проблемы показывать", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Выключенное не видно в списках и не приходит уведомлением, даже срочное.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
            )
        }
        EditableLaterNote()
        SurfaceCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                TELEGRAM_KINDS.forEach { (category, kinds) ->
                    Text(
                        TELEGRAM_CATEGORIES.getValue(category),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    kinds.forEach { (kind, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(kind, kind in mutedKinds) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = kind !in mutedKinds,
                                onCheckedChange = { on -> onToggle(kind, on) },
                            )
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        StepNav(onBack = onBack, onNext = onNext)
    }
}

@Composable
private fun NotifyStep(
    limitDailyPush: Boolean, onLimit: (Boolean) -> Unit,
    quietHoursEnabled: Boolean, onQuiet: (Boolean) -> Unit,
    quietStart: String, onQuietStart: (String) -> Unit,
    quietEnd: String, onQuietEnd: (String) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val c = Petrovich.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Уведомления", style = MaterialTheme.typography.headlineSmall)
            Text("Сколько обычных push в день и когда тихие часы.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        EditableLaterNote()
        SurfaceCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SwitchRow(
                    "Не больше 3 в день", "Остальное — в утреннем докладе. Срочные без лимита.",
                    limitDailyPush, onLimit,
                )
                SwitchRow(
                    "Тихие часы", "Ночью push только для срочных случаев.",
                    quietHoursEnabled, onQuiet,
                )
            }
        }
        if (quietHoursEnabled) {
            Text("Начало тихих часов", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietStartOptions.forEach { t -> PChip(t, selected = t == quietStart, onClick = { onQuietStart(t) }) }
            }
            Text("Конец тихих часов", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietEndOptions.forEach { t -> PChip(t, selected = t == quietEnd, onClick = { onQuietEnd(t) }) }
            }
        }
        Spacer(Modifier.height(8.dp))
        StepNav(onBack = onBack, onNext = onStart, nextLabel = "Начать работу")
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val c = Petrovich.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent, checkedThumbColor = c.surface,
                uncheckedTrackColor = c.line, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.line,
            ),
        )
    }
}

/** 3.1 «Ошибка подключения»: что сказать дилеру, без технических кодов для владельца. */
@Composable
private fun ConnectionErrorScreen(error: ConnectError?, onRetry: () -> Unit, onWriteDealer: () -> Unit) {
    val c = Petrovich.colors
    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Logo()
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.WifiOff, null, tint = c.muted, modifier = Modifier.size(26.dp))
            }
            Text(
                "Не получилось подключиться к АвтоГРАФ",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                error?.message ?: "Проверьте логин, пароль и адрес сервера в АвтоГРАФ.WEB. Если всё верно — скорее всего, у дилера старая версия сервера.",
                textAlign = TextAlign.Center, color = c.muted, style = MaterialTheme.typography.bodyMedium,
            )
        }
        SurfaceCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Что сказать дилеру", style = MaterialTheme.typography.titleSmall)
                Text("Нужен сервер АвтоГРАФ.WEB версии 2023.2.3.28 или новее.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                error?.supportCode?.let {
                    Text("Код для поддержки: $it · сервер дилера", style = MaterialTheme.typography.labelMedium, color = c.faint)
                }
            }
        }
        Spacer(Modifier.weight(1f, fill = false).height(8.dp))
        PrimaryButton("Повторить", onClick = onRetry, modifier = Modifier.fillMaxWidth())
        SoftButton("Написать дилеру", onClick = onWriteDealer, modifier = Modifier.fillMaxWidth())
    }
}
