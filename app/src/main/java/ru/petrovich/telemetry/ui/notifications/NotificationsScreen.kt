package ru.petrovich.telemetry.ui.notifications

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.NotificationItem
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.Dot
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.dayHeader
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 3.3 «Уведомления»: история, сгруппированная по дням, плюс ссылка-пример того, как выглядит
 * срочное уведомление на экране блокировки.
 */
@Composable
fun NotificationsScreen(onBack: () -> Unit, onOpenCard: (String) -> Unit) {
    val c = Petrovich.colors
    val store = ServiceLocator.notificationStore
    val items by store.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showExample by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                "Уведомления",
                onBack = onBack,
                actions = {
                    if (items.any { !it.read }) {
                        TextButton(onClick = { scope.launch { store.markAllRead() } }) { Text("Прочитать все") }
                    }
                },
            )
        },
        containerColor = c.bg,
    ) { padding ->
        val today = LocalDate.now()
        val grouped = items.sortedByDescending { it.at }.groupBy { it.localDate() }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            item { ExampleLinkCard(onClick = { showExample = true }) }

            if (items.isEmpty()) {
                item { EmptyState() }
            } else {
                grouped.toSortedMap(compareByDescending { it }).forEach { (day, dayItems) ->
                    item { DayLabel(dayHeader(day, today)) }
                    items(dayItems, key = { it.id }) { n ->
                        NotificationRow(
                            n,
                            onClick = {
                                scope.launch { store.markRead(n.id) }
                                n.targetAnomalyId?.let(onOpenCard)
                            },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (showExample) {
        UrgentExampleDialog(onDismiss = { showExample = false })
    }
}

private fun NotificationItem.localDate(): LocalDate =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

private fun NotificationItem.localTime(): LocalDateTime =
    Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDateTime()

@Composable
private fun DayLabel(text: String) {
    val c = Petrovich.colors
    Text(
        text.uppercase(),
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = c.muted,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ExampleLinkCard(onClick: () -> Unit) {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth(), onClick = onClick, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(Icons.Outlined.NotificationsActive, null, tint = c.accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Как приходит срочное уведомление", style = MaterialTheme.typography.titleSmall)
                Text("Пример на экране блокировки", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.muted)
        }
    }
}

/** Иконка и цвет строки — по важности; данных о severity в истории нет, поэтому судим по тексту
 * заголовка, который уже содержит метку («Срочно · …», «Внимание · …») — так же, как её видел владелец. */
private fun NotificationItem.importanceColor(c: ru.petrovich.telemetry.ui.theme.PetrovichColors): Color = when {
    title.startsWith("Срочно") -> c.high
    title.startsWith("Внимание") -> c.med
    else -> c.muted
}

private fun NotificationItem.icon(): ImageVector = when {
    title.contains("доклад", ignoreCase = true) -> Icons.Filled.PlayArrow
    title.contains("без связи", ignoreCase = true) || subtitle.contains("сигнала", ignoreCase = true) -> Icons.Filled.SignalWifiOff
    title.contains("датчик", ignoreCase = true) -> Icons.Filled.SensorsOff
    title.startsWith("Внимание") -> Icons.Filled.TrendingUp
    title.startsWith("Срочно") -> Icons.Filled.LocalShipping
    else -> Icons.Outlined.NotificationsActive
}

@Composable
private fun NotificationRow(n: NotificationItem, onClick: () -> Unit) {
    val c = Petrovich.colors
    val tint = n.importanceColor(c)
    SurfaceCard(
        Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (!n.read) c.accentSoft else c.surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(n.icon(), null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    n.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (!n.read) FontWeight.Bold else FontWeight.SemiBold,
                )
                Text(n.subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 2)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(n.localTime().hhmm(), style = MaterialTheme.typography.labelSmall, color = c.faint)
                if (!n.read) Dot(c.accent, size = 7)
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val c = Petrovich.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(Icons.Filled.NotificationsNone, null, tint = c.faint, modifier = Modifier.size(26.dp))
        }
        Text("Здесь пусто. Петрович сообщит, если что-то случится.", color = c.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Самодостаточный пример срочного уведомления — повторяет текст из макета (3.3), не связан с экраном настроек. */
@Composable
private fun UrgentExampleDialog(onDismiss: () -> Unit) {
    val c = Petrovich.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xFF14202E)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Text("П", color = Color(0xFF14202E), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
                Text("ПЕТРОВИЧ", color = Color(0xFFB8C4D4), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("сейчас", color = Color(0xFF7E8CA0), style = MaterialTheme.typography.labelSmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Срочно · X452: ушло 140 л топлива", color = Color(0xFFFF8A75), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("На стоянке «Север», двигатель выключен, датчик исправен.", color = Color(0xFFD6DEE8), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Разберись", onClick = {}, modifier = Modifier.weight(1f))
                SoftButton("Напомнить утром", onClick = {}, modifier = Modifier.weight(1f), container = Color(0xFF223246), content = Color.White)
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C2A3A)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("ПЕТРОВИЧ · вчера, 21:30", color = Color(0xFF7E8CA0), style = MaterialTheme.typography.labelSmall)
                Text("Внимание · T118: расход втрое выше обычного", color = Color(0xFFD6DEE8), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Так приходит срочное уведомление. Нажмите на него.",
                color = Color(0xFF7E8CA0), style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(),
            )
            SoftButton("Закрыть пример", onClick = onDismiss, modifier = Modifier.fillMaxWidth(), container = Color(0xFF223246), content = Color.White)
        }
    }
}
