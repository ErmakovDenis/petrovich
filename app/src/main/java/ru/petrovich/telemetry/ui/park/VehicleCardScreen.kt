package ru.petrovich.telemetry.ui.park

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.VehicleActivity
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.ui.common.MessageBox
import ru.petrovich.telemetry.ui.common.PChip
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.formatValue
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.ui.common.resolutionLabel
import ru.petrovich.telemetry.ui.common.softColor
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.LocalDateTime

private enum class CardPeriod(val title: String, val days: Long) {
    TODAY("Сегодня", 1), WEEK("Неделя", 7), MONTH("Месяц", 30)
}

/**
 * 3.13 «Карточка машины»: номер крупно, период Сегодня/Неделя/Месяц, три показателя (в баке,
 * пробег, холостой ход — честно посчитаны из телеметрии), детали, «Показать на карте»,
 * текущая аномалия (если есть) и «Петрович помнит».
 */
@Composable
fun VehicleCardScreen(
    vehicleId: String,
    onBack: () -> Unit,
    onShowOnMap: (String) -> Unit,
    onOpenAnomaly: (String) -> Unit,
    onAsk: (String) -> Unit,
) {
    val c = Petrovich.colors
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    var vehicle by remember(vehicleId) { mutableStateOf<Vehicle?>(null) }
    var vehicleLoaded by remember(vehicleId) { mutableStateOf(false) }
    var period by remember { mutableStateOf(CardPeriod.TODAY) }
    var stats by remember(vehicleId) { mutableStateOf<VehicleStats?>(null) }
    var loadingStats by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vehicleId) {
        vehicleLoaded = false
        runCatchingCancellable { ServiceLocator.telemetry.vehicles().firstOrNull { it.id == vehicleId } }
            .onSuccess { vehicle = it; vehicleLoaded = true }
            .onFailure { vehicleLoaded = true; error = it.message ?: it.toString() }
    }

    LaunchedEffect(vehicleId, period, vehicle) {
        val v = vehicle ?: return@LaunchedEffect
        loadingStats = true
        error = null
        val now = LocalDateTime.now()
        val from = if (period == CardPeriod.TODAY) now.toLocalDate().atStartOfDay() else now.minusDays(period.days)
        runCatchingCancellable { ServiceLocator.telemetry.telemetry(v, from, now) }
            .onSuccess { t -> stats = computeVehicleStats(t, now); loadingStats = false }
            .onFailure { e -> error = e.message ?: e.toString(); loadingStats = false }
    }

    val worstPending = remember(anomalies, vehicleId) {
        anomalies.filter { it.vehicleId == vehicleId && it.isNew && it.severity != ru.petrovich.telemetry.anomaly.Severity.INFO }
            .maxByOrNull { it.severity.ordinal }?.severity
    }
    val currentAnomaly = remember(anomalies, vehicleId) {
        anomalies.filter { it.vehicleId == vehicleId && it.isNew }
            .sortedWith(compareByDescending<Anomaly> { it.severity.ordinal }.thenByDescending { it.eventTime })
            .firstOrNull()
    }
    val remembered = remember(anomalies, vehicleId) {
        anomalies.filter { it.vehicleId == vehicleId && it.resolution != null }
            .sortedByDescending { it.eventTime }
            .firstOrNull()
    }

    Scaffold(containerColor = c.bg, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        if (!vehicleLoaded) {
            Box(Modifier.padding(padding).fillMaxSize()) { MessageBox("Загружаю машину…") }
            return@Scaffold
        }
        val v = vehicle
        if (v == null) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                Column {
                    Row(Modifier.statusBarsPadding().padding(8.dp)) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } }
                    MessageBox(error?.let { "Не удалось загрузить машину: $it" } ?: "Эта машина не найдена")
                }
            }
            return@Scaffold
        }

        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                Box(Modifier.weight(1f))
                val (pillText, pillColor, pillBg) = statusPill(stats?.activity ?: VehicleActivity.OFFLINE, worstPending)
                Pill(pillText, pillColor, pillBg, modifier = Modifier.padding(end = 8.dp))
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.LocalShipping, null, tint = c.accent)
                }
                Text(v.name, Modifier.padding(top = 8.dp), fontFamily = Petrovich.display, fontWeight = FontWeight.Black, fontSize = 26.sp, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                v.group?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CardPeriod.entries.forEach { p -> PChip(p.title, selected = period == p, onClick = { period = p }, modifier = Modifier.weight(1f)) }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loadingStats && stats == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp).padding(vertical = 16.dp), strokeWidth = 2.dp, color = c.accent)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile("в баке", stats?.fuelLevel.formatValue(), stats?.fuelUnit ?: "л", Modifier.weight(1f))
                        StatTile("пробег", periodWord(period, "пробег"), stats?.mileageKm.formatValue(), "км", Modifier.weight(1f))
                        StatTile("холостой ход", periodWord(period, "холостой"), stats?.idleHours?.let { "%.1f".format(it) } ?: "—", "ч", Modifier.weight(1f))
                    }
                    error?.let { Text("Не удалось обновить данные: $it", style = MaterialTheme.typography.bodySmall, color = c.med) }

                    SurfaceCard(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            DetailRow("Где", whereText(v, stats))
                            HorizontalDivider(color = c.line)
                            DetailRow("Последняя связь", lastContactText(stats))
                            HorizontalDivider(color = c.line)
                            DetailRow("Датчик топлива", fuelSensorText(stats), valueColor = if (stats?.fuelSensorOk == false) c.med else null)
                            HorizontalDivider(color = c.line)
                            DetailRow("Сейчас", (stats?.activity ?: VehicleActivity.OFFLINE).label().replaceFirstChar { it.lowercase() })
                        }
                    }

                    SurfaceCard(onClick = { onShowOnMap(vehicleId) }, shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Map, null, tint = c.accent)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Показать на карте", style = MaterialTheme.typography.titleSmall)
                                Text("где сейчас и маршрут", style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = c.muted)
                        }
                    }

                    if (currentAnomaly != null) CurrentAnomalyCard(currentAnomaly, onClick = { onOpenAnomaly(currentAnomaly.id) })

                    remembered?.let { RememberedCard(it) }

                    SurfaceCard(onClick = { onAsk(vehicleId) }, shape = RoundedCornerShape(16.dp), color = c.surface2) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.Center) {
                            Text("Спросить Петровича про ${v.name}", color = c.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Box(Modifier.size(16.dp))
        }
    }
}

private fun periodWord(p: CardPeriod, @Suppress("UNUSED_PARAMETER") kind: String): String = when (p) {
    CardPeriod.TODAY -> "сегодня"
    CardPeriod.WEEK -> "за неделю"
    CardPeriod.MONTH -> "за месяц"
}

@Composable
private fun StatTile(label: String, period: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = c.ink)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = c.muted)
        }
        Text(period, style = MaterialTheme.typography.labelSmall, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Упрощённый вариант тайла «в баке» — без подписи периода (это текущий остаток, а не величина за период). */
@Composable
private fun StatTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = c.ink)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = c.muted)
        }
        Text("сейчас", style = MaterialTheme.typography.labelSmall, color = c.faint)
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color? = null) {
    val c = Petrovich.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor ?: c.ink)
    }
}

private fun whereText(v: Vehicle, stats: VehicleStats?): String = when (stats?.activity) {
    VehicleActivity.ON_LINE -> "в пути"
    VehicleActivity.STOPPED -> v.group?.let { "на базе «$it»" } ?: "стоит"
    else -> "нет связи"
}

private fun lastContactText(stats: VehicleStats?): String {
    val at = stats?.lastContact ?: return "нет данных"
    val mins = Duration.between(at, LocalDateTime.now()).toMinutes()
    return when {
        mins < 5 -> "сейчас"
        mins < 60 -> "$mins мин назад"
        mins < 24 * 60 -> "${mins / 60} ч назад"
        else -> "${mins / (24 * 60)} дн назад"
    }
}

private fun fuelSensorText(stats: VehicleStats?): String = when (stats?.fuelSensorOk) {
    true -> "исправен"
    false -> "нет данных"
    null -> "нет данных"
}

@Composable
private fun CurrentAnomalyCard(a: Anomaly, onClick: () -> Unit) {
    val c = Petrovich.colors
    SurfaceCard(onClick = onClick, shape = RoundedCornerShape(16.dp), color = a.severity.softColor()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(a.severity.color()))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.time?.let { "${it.toLocalDate().title()} · ${it.hhmm()}" } ?: a.eventTime, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = c.muted)
        }
    }
}

/** «Петрович помнит» — честно: последний разобранный случай по этой же машине. */
@Composable
private fun RememberedCard(a: Anomaly) {
    val c = Petrovich.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accentSoft).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Text("П", color = c.onAccent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Column(Modifier.weight(1f)) {
            Text("Петрович помнит", style = MaterialTheme.typography.labelSmall, color = c.muted)
            Text(
                "${a.time?.toLocalDate()?.title() ?: a.eventTime} — ${a.title}" + (resolutionLabel(a)?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
