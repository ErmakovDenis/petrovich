package ru.petrovich.telemetry.ui.report

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.plural
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.home.AnomalyOrder
import ru.petrovich.telemetry.ui.home.HomeData
import ru.petrovich.telemetry.ui.home.formatCount
import ru.petrovich.telemetry.ui.home.rememberSpeaker
import ru.petrovich.telemetry.ui.problems.ProblemRow
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.LocalDateTime

/** Отчёт за последние 24 часа: сводка словами, её можно послушать, и список случаев. */
@Composable
fun ReportScreen(onBack: () -> Unit, onOpenCard: (String) -> Unit, onOpenProblems: () -> Unit, onOpenSettings: () -> Unit, onAsk: () -> Unit) {
    val all by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val c = Petrovich.colors
    val list = remember(all) {
        val since = LocalDateTime.now().minusHours(24)
        all.filter { a -> a.resolution != Resolution.FALSE_ALARM && a.time?.let { it.isAfter(since) } == true }.sortedWith(AnomalyOrder)
    }
    val vehicles = list.map { it.vehicleId }.distinct().size
    val voiceEnabled = settings?.voiceReportEnabled ?: true

    // Те же сутки, что и у списка случаев выше — один и тот же период для всего экрана.
    var stats by remember { mutableStateOf<ReportStats?>(null) }
    LaunchedEffect(Unit) {
        val to = LocalDateTime.now()
        stats = runCatchingCancellable { loadReportStats(to.minusHours(24), to) }.getOrNull()
    }
    // Доклад — строго «за прошлые сутки» (3.4): и голос, и текст ниже должны говорить про один и тот же
    // список случаев ([list]) и парк ([stats]), а не про весь открытый бэклог аномалий (как на «Главной»).
    val data = remember(list, stats) { HomeData(list, vehicleCount = stats?.vehicleTotal, scanned = true) }

    Scaffold(
        // Заголовок как в макете (3.4) — «Доклад на HH:mm»; подзаголовок честный: окно —
        // последние 24 часа от текущего момента, не обязательно календарное «вчера».
        topBar = { AppTopBar("Доклад на ${settings?.reportTime ?: "--:--"}", subtitle = "за последние 24 часа", onBack = onBack) },
        containerColor = c.bg,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (voiceEnabled) PlayerCard(data.speech())
            SurfaceCard(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Последние 24 часа", style = MaterialTheme.typography.labelMedium, color = c.muted)
                    Column {
                        Text("${list.size}", fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                        Text(plural(list.size, "аномалия", "аномалии", "аномалий"), style = MaterialTheme.typography.labelMedium, color = c.muted)
                    }
                    Text(
                        summary(list.size, vehicles, stats?.vehicleTotal, list.firstOrNull()?.let { "${it.vehicleName} — ${it.title.lowercase()}" }, list.count { it.resolution == null }),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            stats?.let { StatsGrid(it) }
            if (list.isNotEmpty()) {
                SectionLabel("Все случаи")
                list.forEach { a -> ProblemRow(a, onClick = { onOpenCard(a.id) }) }
            }
            val pending = list.count { it.resolution == null }
            if (pending > 0) PrimaryButton("Разобрать проблемы", onOpenProblems, Modifier.fillMaxWidth())
            TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Изменить время доклада", color = c.accent, fontWeight = FontWeight.SemiBold)
            }
            SoftButton("Спросить у Петровича", onAsk, Modifier.fillMaxWidth(), container = c.ink, content = c.bg, leading = Icons.Outlined.ChatBubbleOutline)
        }
    }
}

@Composable
private fun PlayerCard(text: String) {
    val c = Petrovich.colors
    val speaker = rememberSpeaker()
    var progress by remember { mutableFloatStateOf(0f) }
    val durationMs = remember(text) { (text.length * 55L).coerceAtLeast(4000L) }

    LaunchedEffect(speaker.speaking, speaker.rate) {
        if (!speaker.speaking) return@LaunchedEffect
        val start = System.currentTimeMillis() - (progress * durationMs / speaker.rate).toLong()
        while (speaker.speaking) {
            val elapsed = System.currentTimeMillis() - start
            progress = (elapsed * speaker.rate / durationMs).coerceIn(0f, 1f)
            if (progress >= 1f) break
            delay(100)
        }
    }
    LaunchedEffect(speaker.speaking) { if (!speaker.speaking) progress = 0f }

    SurfaceCard(color = c.accent, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
                IconButton(onClick = { if (speaker.speaking) speaker.stop() else speaker.speak(text) }) {
                    Icon(if (speaker.speaking) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Слушать", tint = c.accent)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.accentSoft.copy(alpha = .35f))) {
                    Box(Modifier.fillMaxWidth(progress).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.onAccent))
                }
                Text(
                    secondsLabel((progress * durationMs / 1000).toInt()) + " / " + secondsLabel((durationMs / 1000).toInt()),
                    style = MaterialTheme.typography.labelMedium, color = c.onAccent.copy(alpha = .85f),
                )
            }
            OutlinedButton(
                onClick = speaker::cycleRate,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = c.onAccent),
                border = BorderStroke(1.dp, c.onAccent.copy(alpha = .5f)),
            ) { Text(rateLabel(speaker.rate) + "×") }
        }
    }
    if (!speaker.available) Text("На устройстве нет русского синтезатора речи", style = MaterialTheme.typography.bodySmall, color = c.muted)
}

/** «4 показателя» (3.4) — только те, для которых реально нашлись данные: пустых цифр не рисуем. */
@Composable
private fun StatsGrid(stats: ReportStats) {
    data class Tile(val icon: ImageVector, val label: String, val value: String)
    val tiles = buildList {
        stats.fuelLiters?.let { add(Tile(Icons.Outlined.LocalGasStation, "Топливо", "${formatCount(it)} л")) }
        stats.mileageKm?.let { add(Tile(Icons.Outlined.Route, "Пробег", "${formatCount(it)} км")) }
        stats.idleHours?.let { add(Tile(Icons.Outlined.Schedule, "Холостой ход", "${formatCount(it)} ч")) }
        stats.refuels?.let { add(Tile(Icons.Outlined.LocalGasStation, "Заправки", "$it")) }
    }
    if (tiles.isEmpty()) return
    val c = Petrovich.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile ->
                    SurfaceCard(Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(tile.icon, null, Modifier.size(16.dp), tint = c.muted)
                                Text(tile.label, style = MaterialTheme.typography.labelMedium, color = c.muted)
                            }
                            Text(tile.value, fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                        }
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

private fun rateLabel(rate: Float): String = if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()

private fun secondsLabel(total: Int): String = "%d:%02d".format(total / 60, total % 60)

/**
 * «Итог одной фразой» (3.4): «N из M машин без замечаний», как в макете — когда известен весь парк
 * ([vehicleTotal] из [ReportStats], приходит чуть позже списка случаев, поэтому может быть ещё null).
 */
private fun summary(count: Int, vehiclesWithIssues: Int, vehicleTotal: Int?, top: String?, pending: Int): String {
    if (count == 0) {
        return if (vehicleTotal != null && vehicleTotal > 0) {
            "Все $vehicleTotal ${plural(vehicleTotal, "машина", "машины", "машин")} без замечаний: ни сливов топлива, ни перегрева, ни других отклонений."
        } else {
            "За сутки замечаний нет: ни сливов топлива, ни перегрева, ни других отклонений."
        }
    }
    val fleetLine = if (vehicleTotal != null && vehicleTotal > 0) {
        val working = (vehicleTotal - vehiclesWithIssues).coerceAtLeast(0)
        "$working из $vehicleTotal ${plural(vehicleTotal, "машины", "машин", "машин")} без замечаний. "
    } else ""
    return buildString {
        append(fleetLine)
        append("За сутки найдено $count ${plural(count, "аномалия", "аномалии", "аномалий")} на $vehiclesWithIssues ${plural(vehiclesWithIssues, "машине", "машинах", "машинах")}. ")
        if (top != null) append("Главное: $top. ")
        append(if (pending == 0) "Всё уже разобрано." else "Ждут решения: $pending.")
    }
}
