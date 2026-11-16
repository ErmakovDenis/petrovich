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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import retrofit2.HttpException
import ru.petrovich.telemetry.BuildConfig
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.ui.common.PChip
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.MailIntent
import ru.petrovich.telemetry.util.runCatchingCancellable

/** Шаги мастера первого запуска (3.1 «Первый запуск и ошибка подключения»). */
private enum class Step { WELCOME, SETUP }

private val ReportTimeOptions = listOf("07:00", "08:00", "09:00", "10:00")

/** Что сказать владельцу при ошибке подключения — настоящий код ошибки, без выдумывания. */
private data class ConnectError(val message: String, val supportCode: String?)

/**
 * Первый запуск: знакомство → подключение к AutoGRAPH → время доклада.
 * Парк и находки не ждём здесь: после входа в приложение Home сам запускает первую проверку,
 * а список машин и телеметрия подгружаются по экранам, пока пользователь уже может ходить по приложению.
 * Контракт с AppRoot не меняется: один колбэк [onConnected], вызывается один раз, когда мастер завершён.
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

    var reportTime by rememberSaveable { mutableStateOf("08:00") }

    fun supportCodeFor(e: Throwable): String =
        (e as? HttpException)?.let { "HTTP ${it.code()}" } ?: e.message?.take(70) ?: (e::class.simpleName ?: "неизвестная ошибка")

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
                            it.copy(userName = user.trim(), password = password, demoMode = false, schemaId = schema.id, schemaName = schema.name)
                        }
                        ServiceLocator.onDataSourceChanged()
                        stepOrdinal = Step.SETUP.ordinal
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

    fun finish() {
        scope.launch {
            // Порядок важен: после onboarded=true этот экран уходит из композиции и корутина отменяется.
            ServiceLocator.settings.update { it.copy(reportTime = reportTime) }
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
            Step.SETUP -> SetupStep(
                reportTime = reportTime, onReportTimeChange = { reportTime = it },
                onStart = ::finish,
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
            Text("Здравствуйте!\nЯ Петрович", textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Слежу за вашим автопарком и коротко расскажу, что важно.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = c.muted, style = MaterialTheme.typography.bodyMedium,
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
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = c.faint, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Не получается войти?",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = c.accent, fontWeight = FontWeight.SemiBold,
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
private fun SetupStep(
    reportTime: String, onReportTimeChange: (String) -> Unit,
    onStart: () -> Unit,
) {
    val c = Petrovich.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("Готово к работе", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Парк и первые находки подгрузятся уже в приложении — можно сразу ходить по экранам.",
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Когда присылать утренний доклад?", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportTimeOptions.forEach { t -> PChip(t, selected = t == reportTime, onClick = { onReportTimeChange(t) }) }
            }
        }
        Spacer(Modifier.weight(1f, fill = false).height(8.dp))
        PrimaryButton("Начать работу", onClick = onStart, modifier = Modifier.fillMaxWidth())
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
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                error?.message ?: "Проверьте логин, пароль и адрес сервера в АвтоГРАФ.WEB. Если всё верно — скорее всего, у дилера старая версия сервера.",
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = c.muted, style = MaterialTheme.typography.bodyMedium,
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
