package ru.petrovich.telemetry.ui.home

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.settings.SettingsRepository
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.rememberVoiceInput
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.util.Locale

/**
 * «Сводка на главной» (3.16): что показывать на «Главной» решает владелец — список активных
 * виджетов с «Убрать» и список доступных с «+». Голосовая строка только подсказывает пример фразы
 * и сопоставляет её с ближайшим виджетом — настоящего распознавания языка нет.
 */
@Composable
fun WidgetsScreen(onBack: () -> Unit) {
    val repo = ServiceLocator.settings
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val c = Petrovich.colors
    val context = LocalContext.current
    val voiceInput = rememberVoiceInput(
        onResult = { query -> bestMatch(query)?.let { add(repo, scope, it) } },
        onUnavailable = { Toast.makeText(context, "Голосовой ввод недоступен на этом устройстве", Toast.LENGTH_SHORT).show() },
    )

    Scaffold(topBar = { AppTopBar("Сводка на главной", subtitle = "Что показывать каждый день", onBack = onBack) }, containerColor = c.bg) { padding ->
        val s = settings ?: return@Scaffold
        val onLayout = s.layout.map { it.type }
        val available = WidgetType.entries.filter { it !in onLayout }

        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VoiceHint(onClick = voiceInput)

            if (onLayout.isNotEmpty()) {
                SectionLabel("На главной")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    onLayout.forEach { type ->
                        WidgetRow(type) {
                            TextButton(onClick = { remove(repo, scope, type) }) {
                                Text("Убрать", color = c.high, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            if (available.isNotEmpty()) {
                SectionLabel("Можно добавить")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    available.forEach { type ->
                        WidgetRow(type) {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(c.accent).clickable { add(repo, scope, type) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Add, "Добавить", tint = c.onAccent, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceHint(onClick: () -> Unit) {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth(), color = c.accentSoft, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Text("П", color = c.onAccent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Скажите, что добавить. Например:", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clickable(onClick = onClick)) {
                    Icon(Icons.Filled.Mic, null, Modifier.size(16.dp), tint = c.accent)
                    Text("«Добавь топливо по грузовым»", style = MaterialTheme.typography.bodyMedium, color = c.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun WidgetRow(type: WidgetType, trailing: @Composable () -> Unit) {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(type.icon, null, Modifier.size(20.dp), tint = c.muted)
            Column(Modifier.weight(1f)) {
                Text(type.title, style = MaterialTheme.typography.labelLarge)
                Text(type.description, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
            }
            trailing()
        }
    }
}

// Берём актуальную раскладку из [SettingsRepository.update] (всегда свежая), а не из снимка композиции —
// иначе параллельный тап по нескольким виджетам подряд мог бы потерять часть изменений.
private fun add(repo: SettingsRepository, scope: CoroutineScope, type: WidgetType) {
    scope.launch { repo.update { s -> if (s.layout.any { it.type == type }) s else s.copy(widgetLayout = serializeLayout(s.layout + WidgetSlot(type))) } }
}

private fun remove(repo: SettingsRepository, scope: CoroutineScope, type: WidgetType) {
    scope.launch { repo.update { s -> s.copy(widgetLayout = serializeLayout(s.layout.filterNot { it.type == type })) } }
}

/** Ищет виджет, чьи название/описание сильнее всего пересекаются по словам с голосовой фразой. */
private fun bestMatch(query: String): WidgetType? {
    val words = query.lowercase(Locale("ru")).split(Regex("\\W+")).filter { it.length >= 3 }
    if (words.isEmpty()) return null
    return WidgetType.entries.map { type ->
        val haystack = "${type.title} ${type.description}".lowercase(Locale("ru"))
        type to words.count { haystack.contains(it) }
    }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
}
