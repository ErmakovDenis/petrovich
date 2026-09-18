package ru.petrovich.telemetry.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable

private enum class Mode { API, DEMO }

/** Первый запуск: подключить AutoGRAPH или посмотреть приложение на демо-данных. */
@Composable
fun OnboardingScreen(onConnected: () -> Unit) {
    val c = Petrovich.colors
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableStateOf(Mode.API) }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connectedTo by remember { mutableStateOf<String?>(null) }

    fun finish(demo: Boolean) {
        scope.launch {
            ServiceLocator.settings.update { it.copy(demoMode = demo, onboarded = true) }
            onConnected()
        }
    }

    fun connect() {
        busy = true; error = null
        scope.launch {
            runCatchingCancellable { ServiceLocator.autoGraph.schemas(user.trim(), password) }
                .onSuccess { list ->
                    val schema = list.firstOrNull()
                    if (schema == null) {
                        error = "Для этого логина нет доступных схем."
                    } else {
                        ServiceLocator.settings.update {
                            it.copy(userName = user.trim(), password = password, demoMode = false, schemaId = schema.id, schemaName = schema.name)
                        }
                        connectedTo = schema.name
                    }
                }
                .onFailure { error = "Не удалось подключиться. Проверьте логин и пароль AutoGRAPH." }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Logo()
        if (connectedTo != null) {
            Done(connectedTo!!) { finish(demo = false) }
            return@Column
        }
        Text("Подключите телеметрию — дальше Петрович докладывает сам", fontSize = 22.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold)
        Text("Утром короткий доклад, о срочных случаях — сразу.", style = MaterialTheme.typography.bodyLarge, color = c.muted)
        Option(mode == Mode.API, "Войти в AutoGRAPH", "Данные обновляются автоматически") { mode = Mode.API }
        Option(mode == Mode.DEMO, "Посмотреть на демо-данных", "10 условных машин со встроенными аномалиями") { mode = Mode.DEMO }
        if (mode == Mode.API) {
            OutlinedTextField(
                value = user, onValueChange = { user = it }, label = { Text("Логин AutoGRAPH") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Пароль") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        error?.let {
            Text(
                it, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.highSoft).padding(14.dp),
                color = c.high, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.weight(1f, fill = false).height(8.dp))
        if (busy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
                Spacer(Modifier.size(10.dp))
                Text("Подключаемся к AutoGRAPH…", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            PrimaryButton(
                if (mode == Mode.API) "Подключить" else "Открыть демо",
                onClick = { if (mode == Mode.API) connect() else finish(demo = true) },
                enabled = mode == Mode.DEMO || (user.isNotBlank() && password.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            )
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
        fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 28.sp,
    )
}

@Composable
private fun Option(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    val c = Petrovich.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(2.dp, if (selected) c.accent else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape)
                .border(if (selected) 6.dp else 2.dp, if (selected) c.accent else c.line, CircleShape),
        )
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
    }
}

@Composable
private fun Done(schemaName: String, onOpen: () -> Unit) {
    val c = Petrovich.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(24.dp)).background(c.okSoft).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Text("П", color = c.onAccent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Column {
                Text("Готово", style = MaterialTheme.typography.labelLarge)
                Text("Подключено: $schemaName", style = MaterialTheme.typography.labelSmall, color = c.muted)
            }
        }
        Text("Дальше я докладываю сам", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("• Каждые 15 минут ищу аномалии в данных\n• Срочные случаи присылаю сразу\n• Что присылать и какие цифры видеть — настраивается", style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(8.dp))
    PrimaryButton("Открыть сводку", onOpen, Modifier.fillMaxWidth())
}
