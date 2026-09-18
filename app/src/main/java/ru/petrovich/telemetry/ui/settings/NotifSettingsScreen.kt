package ru.petrovich.telemetry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.Dot
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.theme.Petrovich

/** «Что присылать»: проверка в фоне и какие уровни срочности приходят push-уведомлением. */
@Composable
fun NotifSettingsScreen(onBack: () -> Unit) {
    val repo = ServiceLocator.settings
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val c = Petrovich.colors
    fun save(transform: (AppSettings) -> AppSettings) = scope.launch { repo.update(transform) }

    Scaffold(topBar = { AppTopBar("Что присылать", onBack = onBack) }, containerColor = c.bg) { padding ->
        val s = settings ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("Проверка")
            Card {
                SwitchRow(
                    "Фоновая проверка", "Каждые 15 минут ищем аномалии, даже когда приложение закрыто",
                    s.backgroundChecks, onChecked = { v -> save { it.copy(backgroundChecks = v) } },
                )
            }
            SectionLabel("Push-уведомления")
            Card {
                SwitchRow(
                    "Срочно", "Слив топлива, перегрев, низкое давление масла",
                    s.pushCritical, dot = Severity.CRITICAL, onChecked = { v -> save { it.copy(pushCritical = v) } },
                )
                HorizontalDivider(color = c.line)
                SwitchRow(
                    "Разобраться", "Отклонения, которые стоит проверить в течение дня",
                    s.pushWarning, dot = Severity.WARNING, onChecked = { v -> save { it.copy(pushWarning = v) } },
                )
            }
            Text(
                "«Мелочи» приходят только в ленту уведомлений, без push.",
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) { content() }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit, dot: Severity? = null) {
    val c = Petrovich.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (dot != null) Dot(dot.color())
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
        }
        Switch(
            checked = checked, onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.surface, uncheckedTrackColor = c.line, uncheckedThumbColor = c.surface, uncheckedBorderColor = c.line),
        )
    }
}
