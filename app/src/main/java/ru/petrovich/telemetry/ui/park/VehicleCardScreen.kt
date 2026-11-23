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
import ru.petrovich.telemetry.ui.common.PeriodChips
import ru.petrovich.telemetry.ui.common.PeriodPreset
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.TimeRange
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.formatValue
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.common.shown
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.ui.common.resolutionLabel
import ru.petrovich.telemetry.ui.common.softColor
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.home.WidgetPeriod
import ru.petrovich.telemetry.ui.home.loadVehiclePeriodTotals
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.LocalDateTime
import androidx.compose.foundation.layout.PaddingValues

private val CardPresets = listOf(
    // Те же окна, что у виджетов сводки (календарные сутки с 00:00), не lastDays(7) с середины дня.
    PeriodPreset("today", "Сегодня") { TimeRange.today() },
    PeriodPreset("week", "Неделя") { WidgetPeriod.WEEK.toRange() },
    PeriodPreset("month", "Месяц") { WidgetPeriod.MONTH.toRange() },
)

/**
 * 3.13 «Карточка машины»: номер крупно, период (пресеты + свой), три показателя (в баке,
 * пробег, холостой ход — честно посчитаны из телеметрии), детали, текущая аномалия (если есть)
 * и «Петрович помнит». Кнопка «Показать на карте» скрыта: у AutoGRAPH нет GPS/маршрута.
 */
@Composable
fun VehicleCardScreen(
    vehicleId: String,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onShowOnMap: (String) -> Unit,
    onOpenAnomaly: (String) -> Unit,
    onAsk: (String) -> Unit,
) {
    val c = Petrovich.colors
    val allAnomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val anomalies = remember(allAnomalies, settings?.mutedKinds) { allAnomalies.shown(settings?.mutedKinds.orEmpty()) }
    var vehicle by remember(vehicleId) { mutableStateOf<Vehicle?>(null) }
    var vehicleLoaded by remember(vehicleId) { mutableStateOf(false) }
    var selectedPresetId by remember { mutableStateOf<String?>("today") }
    var customRange by remember { mutableStateOf<TimeRange?>(null) }
    fun activeRange(): TimeRange =
        customRange?.takeIf { selectedPresetId == null }
            ?: CardPresets.firstOrNull { it.id == selectedPresetId }?.range?.invoke()
            ?: TimeRange.today()
    // Пресеты сегодня/неделя/месяц — одним заходом при открытии карточки; переключение без повторных запросов.
    var statsByPreset by remember(vehicleId) { mutableStateOf<Map<String, VehicleStats>>(emptyMap()) }
    var presetErrors by remember(vehicleId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var customStats by remember { mutableStateOf<VehicleStats?>(null) }
    var customLoading by remember { mutableStateOf(false) }
    var customError by remember { mutableStateOf<String?>(null) }
    var vehicleError by remember { mutableStateOf<String?>(null) }
    val stats = if (selectedPresetId != null) statsByPreset[selectedPresetId] else customStats
    val loadingStats = if (selectedPresetId != null) {
        stats == null && selectedPresetId !in presetErrors
    } else {
        customLoading && customStats == null
    }
    val error = if (selectedPresetId != null) presetErrors[selectedPresetId] else customError

    LaunchedEffect(vehicleId) {
        vehicleLoaded = false
        vehicleError = null
        runCatchingCancellable { ServiceLocator.telemetry.vehicles().firstOrNull { it.id == vehicleId } }
            .onSuccess { vehicle = it; vehicleLoaded = true }
            .onFailure { vehicleLoaded = true; vehicleError = it.message ?: it.toString() }
    }

    // Пресеты по очереди: месяц — десятки кусков AutoGRAPH; параллельно с неделей часто таймаутится → «—».
    LaunchedEffect(vehicleId, vehicle) {
        val v = vehicle ?: return@LaunchedEffect
        statsByPreset = emptyMap()
        presetErrors = emptyMap()
        for (preset in CardPresets) {
            val range = preset.range()
            runCatchingCancellable {
                val t = ServiceLocator.telemetry.telemetry(v, range.from, range.to)
                val totals = loadVehiclePeriodTotals(v.id, range)
                computeVehicleStats(t, range.to, totals)
            }.onSuccess { ready ->
                statsByPreset = statsByPreset + (preset.id to ready)
            }.onFailure { e ->
                presetErrors = presetErrors + (preset.id to (e.message ?: e.toString()))
            }
        }
    }

    LaunchedEffect(vehicleId, vehicle, selectedPresetId, customRange) {
        val range = customRange?.takeIf { selectedPresetId == null }
        if (range == null) {
            customStats = null
            customError = null
            customLoading = false
            return@LaunchedEffect
        }
        val v = vehicle ?: return@LaunchedEffect
        customLoading = true
        customStats = null
        customError = null
        runCatchingCancellable {
            val t = ServiceLocator.telemetry.telemetry(v, range.from, range.to)
            val totals = loadVehiclePeriodTotals(v.id, range)
            computeVehicleStats(t, range.to, totals)
        }
            .onSuccess { ready -> customStats = ready; customLoading = false }
            .onFailure { e -> customError = e.message ?: e.toString(); customLoading = false }
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
                    MessageBox(vehicleError?.let { "Не удалось загрузить машину: $it" } ?: "Эта машина не найдена")
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
                // Гос. номер крупно (карта экранов 3.13); если номера нет — имя машины.
                Text(
                    v.shortLabel,
                    Modifier.padding(top = 8.dp),
                    fontFamily = Petrovich.display, fontWeight = FontWeight.Black, fontSize = 26.sp,
                    maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (v.plate != null && v.nameWithoutPlate != v.shortLabel) {
                    Text(v.nameWithoutPlate, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                v.group?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
            }

            PeriodChips(
                presets = CardPresets,
                selectedPresetId = selectedPresetId,
                customRange = customRange,
                onSelectPreset = { selectedPresetId = it.id; customRange = null },
                onSelectCustom = { customRange = it; selectedPresetId = null },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            )

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loadingStats && stats == null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp).padding(vertical = 16.dp), strokeWidth = 2.dp, color = c.accent)
                    }
                } else {
                    val periodLabel = periodWord(selectedPresetId, activeRange())
                    // Расход/пробег/заправки/холостой за период + остаток в баке «сейчас».
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("расход", periodLabel, stats?.fuelUsedL.formatValue(), "л", Modifier.weight(1f))
                            StatTile("пробег", periodLabel, stats?.mileageKm.formatValue(), "км", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(
                                "заправки",
                                periodLabel,
                                stats?.refuels?.toString() ?: "—",
                                "",
                                Modifier.weight(1f),
                            )
                            StatTile(
                                "холостой ход",
                                periodLabel,
                                stats?.idleHours?.let { "%.1f".format(it) } ?: "—",
                                "ч",
                                Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("в баке", stats?.fuelLevel.formatValue(), stats?.fuelUnit ?: "л", Modifier.weight(1f))
                            Box(Modifier.weight(1f))
                        }
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

                    if (currentAnomaly != null) CurrentAnomalyCard(currentAnomaly, onClick = { onOpenAnomaly(currentAnomaly.id) })

                    remembered?.let { RememberedCard(it) }

                    SurfaceCard(onClick = { onAsk(vehicleId) }, shape = RoundedCornerShape(16.dp), color = c.surface2) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.Center) {
                            Text("Спросить Петровича про ${v.shortLabel}", color = c.accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Box(Modifier.size(16.dp))
        }
    }
}

private fun periodWord(presetId: String?, range: TimeRange): String = when (presetId) {
    "today" -> "сегодня"
    "week" -> "за неделю"
    "month" -> "за месяц"
    else -> range.shortLabel
}

@Composable
private fun StatTile(label: String, period: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = c.ink)
            if (unit.isNotBlank()) {
                Text(unit, style = MaterialTheme.typography.labelSmall, color = c.muted)
            }
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
            if (unit.isNotBlank()) {
                Text(unit, style = MaterialTheme.typography.labelSmall, color = c.muted)
            }
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
