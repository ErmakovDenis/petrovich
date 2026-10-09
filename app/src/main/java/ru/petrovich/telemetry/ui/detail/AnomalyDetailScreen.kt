package ru.petrovich.telemetry.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.OilBarrel
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.ui.charts.ChartMarker
import ru.petrovich.telemetry.ui.charts.LineChart
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.FalseAlarmSheet
import ru.petrovich.telemetry.ui.common.MessageBox
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.advice
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.formatValue
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.ui.common.previousOccurrence
import ru.petrovich.telemetry.ui.common.resolutionLabel
import ru.petrovich.telemetry.ui.common.softColor
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnomalyDetailScreen(
    anomalyId: String,
    onBack: () -> Unit,
    onAct: (String) -> Unit,
    onOpenCharts: (String) -> Unit,
    onOpenResolution: (String) -> Unit,
) {
    val store = ServiceLocator.anomalyStore
    val anomalies by store.anomalies.collectAsStateWithLifecycle()
    val anomaly = anomalies.firstOrNull { it.id == anomalyId }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var reasons by remember { mutableStateOf(false) }
    val c = Petrovich.colors

    // Состояние графика поднято наверх экрана: его честные цифры нужны и крупному заголовку (3.6 «Главное
    // крупно»), и карточке деталей (строка «Двигатель»/«Машина» из реально проверенных соседних параметров).
    var evidence by remember(anomaly?.id) { mutableStateOf<EvidenceState>(EvidenceState.Loading) }
    LaunchedEffect(anomaly?.id) {
        val a = anomaly ?: return@LaunchedEffect
        evidence = EvidenceState.Loading
        val at = a.time
        evidence = if (at == null) EvidenceState.Failed("Не удалось определить время события")
        else runCatchingCancellable { loadEvidence(a, at) }.fold(
            onSuccess = { it ?: EvidenceState.Failed("Нет данных по параметру рядом с событием") },
            onFailure = { EvidenceState.Failed("Не удалось загрузить график: ${it.message}") },
        )
    }

    // Честный шаг журнала «Хода разбора» (3.10): фиксируем сам факт, что владелец открыл событие,
    // один раз — не статус, просто запись в ленте.
    LaunchedEffect(anomaly?.id) {
        val a = anomaly ?: return@LaunchedEffect
        if (a.timeline.none { it.text == "Вы открыли событие" }) store.appendStep(a.id, "Вы открыли событие")
    }

    Scaffold(
        topBar = {
            // Метка статуса — в шапке рядом с «назад», как в макете 3.6, а не в теле карточки.
            AppTopBar(title = "", onBack = onBack, actions = { if (anomaly != null) StatusPill(anomaly, Modifier.padding(end = 12.dp)) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (anomaly != null) ActionsBar(
                anomaly = anomaly,
                onAct = { onAct(anomaly.id) },
                onFalseAlarm = { reasons = true },
                onReopen = { scope.launch { store.reopen(anomaly.id) } },
                onCharts = { onOpenCharts(anomaly.vehicleId) },
                onRemindMechanic = {
                    scope.launch {
                        store.appendStep(anomaly.id, "Напоминание механику отправлено")
                        snackbar.showSnackbar("Механику напомнили")
                    }
                },
                onOpenResolution = { onOpenResolution(anomaly.id) },
            )
        },
    ) { padding ->
        if (anomaly == null) {
            Box(Modifier.padding(padding)) { MessageBox("Эта аномалия больше не хранится в истории") }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Headline(anomaly)
            VehicleTimeLink(anomaly, onClick = { onOpenCharts(anomaly.vehicleId) })

            EvidenceCard(anomaly, evidence)
            DetailsCard(anomaly, evidence)

            anomalies.previousOccurrence(anomaly)?.let { prev -> RecallCard(prev) }

            TextBlock("Что случилось", anomaly.description)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accentSoft).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("ЧТО ДЕЛАТЬ", style = MaterialTheme.typography.labelSmall, color = c.muted, fontWeight = FontWeight.SemiBold)
                Text(anomaly.advice(), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (reasons && anomaly != null) {
        FalseAlarmSheet(
            onDismiss = { reasons = false },
            onPick = { reason ->
                reasons = false
                scope.launch {
                    store.resolve(anomaly.id, Resolution.FALSE_ALARM, reason)
                    snackbar.showSnackbar("Отмечено как ложная тревога")
                }
            },
        )
    }
}

/** Иконка по типу события — тот же словарь, что и в списке «Проблемы» (ProblemRow.kt). */
private fun Anomaly.icon(): ImageVector = when (kind) {
    "drain", "drop" -> Icons.Outlined.LocalGasStation
    "power" -> Icons.Outlined.ElectricBolt
    "volt" -> Icons.Outlined.BatteryAlert
    "overheat" -> Icons.Outlined.Thermostat
    "oil" -> Icons.Outlined.OilBarrel
    "brake" -> Icons.Outlined.Compress
    "nodata" -> Icons.Outlined.WifiOff
    else -> Icons.Outlined.ReportProblem
}

/**
 * Статус-метка по единому словарю макета (4. Общие элементы): «Срочно» — красная, «Внимание» — жёлтая,
 * «Закрыто» — зелёная, «В работе» и «Ложная тревога» — серые. Молчащий датчик — это продолжающаяся
 * проблема, а не разовое срочное событие, поэтому у него всегда «В работе», как в макете 3.6 (скриншот 3).
 */
@Composable
private fun StatusPill(a: Anomaly, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    val (text, color, soft) = when {
        a.kind == "nodata" -> Triple("В работе", c.muted, c.surface2)
        a.resolution == Resolution.IN_PROGRESS -> Triple(resolutionLabel(a) ?: "В работе", c.muted, c.surface2)
        a.resolution == Resolution.CONFIRMED -> Triple("Закрыто", c.ok, c.okSoft)
        a.resolution == Resolution.FALSE_ALARM -> Triple(resolutionLabel(a) ?: "Ложная тревога", c.muted, c.surface2)
        else -> Triple(a.severity.label(), a.severity.color(), a.severity.softColor())
    }
    Pill(text, color, soft, modifier)
}

/**
 * Единица измерения крупного числа — честно по тому же параметру, что завёл правило (AutoGraphParameters):
 * бак и слив всегда в литрах, напряжение в вольтах, ОЖ в градусах, давление в кПа.
 */
private fun Anomaly.unitLabel(): String? = when (kind) {
    "drain", "drop" -> "л"
    "volt" -> "В"
    "overheat" -> "°C"
    "oil", "brake" -> "кПа"
    else -> null
}

/**
 * «Главное крупно» (3.6): «−140 л» / «42 л/100 км» / «Нет данных» в цвете важности, подпись одной фразой.
 * Знак «минус» ставим только для «drain»: это единственный вид события, где value — честно сам объём
 * слива, а не значение параметра после происшествия (иначе пришлось бы придумывать число из описания).
 */
@Composable
private fun Headline(a: Anomaly) {
    val c = Petrovich.colors
    val nodata = a.kind == "nodata"
    val color = if (nodata) c.muted else a.severity.color()
    val unit = a.unitLabel()
    val big = when {
        nodata -> "Нет данных"
        a.value != null && unit != null -> "${if (a.kind == "drain") "−" else ""}${a.value.formatValue()} $unit"
        else -> a.title
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(a.icon(), null, Modifier.size(30.dp), tint = color)
        Text(big, style = MaterialTheme.typography.headlineMedium, color = color, textAlign = TextAlign.Center)
        if (big != a.title) Text(a.title, style = MaterialTheme.typography.bodyMedium, color = c.muted, textAlign = TextAlign.Center)
    }
}

/** Машина и время — кликабельная ссылка на карточку машины, как требует макет 3.6. */
@Composable
private fun VehicleTimeLink(a: Anomaly, onClick: () -> Unit) {
    val c = Petrovich.colors
    val t = a.time
    val whenText = if (t != null) {
        val today = LocalDate.now()
        val day = t.toLocalDate()
        val dayText = when (day) { today -> "сегодня"; today.minusDays(1) -> "вчера"; else -> day.title() }
        "$dayText, ${t.hhmm()}"
    } else a.eventTime
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${a.vehicleName} · $whenText", style = MaterialTheme.typography.labelLarge, color = c.accent)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = c.accent)
    }
}

@Composable
private fun Fact(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = Petrovich.colors.faint)
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Petrovich.colors.muted)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Детали строками (3.6): когда, параметр, кто нашёл — и честно проверенные соседние параметры
 * (скорость, зажигание), если они вообще пишутся. «Где» намеренно не показываем — у AutoGRAPH
 * нет гео-данных в этом проекте (нет lat/lon ни в Models.kt, ни в api/Dto.kt), а выдумывать место
 * запрещает правило «Только известные данные» (1. Общие правила).
 */
@Composable
private fun DetailsCard(a: Anomaly, state: EvidenceState) {
    val c = Petrovich.colors
    SurfaceCard(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            val time = a.time
            Fact(Icons.Outlined.AccessTime, "Когда", time?.let { "${it.toLocalDate().title()}, ${it.hhmm()}" } ?: a.eventTime)
            HorizontalDivider(color = c.line)
            Fact(Icons.Outlined.Sensors, "Параметр", a.parameterCaption + (a.value?.let { " · ${it.formatValue()}" } ?: ""))
            HorizontalDivider(color = c.line)
            Fact(Icons.Outlined.Memory, "Кто нашёл", a.source)
            val checks = (state as? EvidenceState.Ready)?.checks.orEmpty()
            if (checks.isNotEmpty()) {
                HorizontalDivider(color = c.line)
                Column(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ПРОВЕРЕНО ПЕТРОВИЧЕМ", style = MaterialTheme.typography.labelSmall, color = c.muted, fontWeight = FontWeight.SemiBold)
                    checks.forEach { check ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = c.ok)
                            Text(check, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** «Петрович помнит»: такой же случай на этой машине уже был раньше — честно посчитано по истории. */
@Composable
private fun RecallCard(prev: Anomaly) {
    val c = Petrovich.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accentSoft).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Text("П", color = c.onAccent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Text(
            "${prev.time?.toLocalDate()?.title() ?: prev.eventTime} у этой же машины уже было похожее. Это повтор.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TextBlock(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Petrovich.colors.muted, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** График параметра вокруг момента события: ±3 часа. Данные приходят уже загруженными сверху экрана. */
@Composable
private fun EvidenceCard(a: Anomaly, state: EvidenceState) {
    val c = Petrovich.colors
    SurfaceCard(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.parameterCaption, style = MaterialTheme.typography.labelMedium, color = c.muted)
            when (state) {
                EvidenceState.Loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = c.accent)
                }
                is EvidenceState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = c.muted)
                is EvidenceState.Ready -> {
                    // Если за всё окно нет ни одного значения (молчит датчик), LineChart сам честно
                    // показывает «Нет данных» вместо пустого/обманчивого графика — ничего придумывать не нужно.
                    LineChart(state.times, state.values, state.unit, lineColor = c.accent, markers = listOf(ChartMarker(state.markerIndex, a.severity.color())))
                    Text(
                        "Окно: 3 часа до и после события",
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private sealed interface EvidenceState {
    data object Loading : EvidenceState
    data class Failed(val message: String) : EvidenceState
    class Ready(
        val times: List<LocalDateTime>, val values: List<Double?>, val unit: String?, val markerIndex: Int,
        /** Факты, которые Петрович реально проверил по соседним параметрам — не выдумка, честный расчёт. */
        val checks: List<String>,
    ) : EvidenceState
}

private suspend fun loadEvidence(a: Anomaly, at: LocalDateTime): EvidenceState.Ready? {
    val repo = ServiceLocator.telemetry
    val vehicle = repo.vehicles().firstOrNull { it.id == a.vehicleId } ?: return null
    val t: VehicleTelemetry = repo.telemetry(vehicle, at.minusHours(3), at.plusHours(3))
    val table = t.tables[a.category] ?: return null
    val column = table.columns.firstOrNull { it.parameter.name == a.parameterName } ?: return null
    if (table.timestamps.size < 2) return null
    val idx = table.timestamps.indices.minByOrNull { abs(Duration.between(table.timestamps[it], at).seconds) } ?: return null
    return EvidenceState.Ready(table.timestamps, column.values, column.parameter.unit, idx, buildChecks(t, at, a))
}

/** Что Петрович реально нашёл по соседним параметрам в момент события — скорость и зажигание, если они вообще пишутся. */
private fun buildChecks(t: VehicleTelemetry, at: LocalDateTime, a: Anomaly): List<String> {
    val checks = mutableListOf<String>()
    fun valueNear(category: MetricCategory, vararg names: String): Double? {
        val table = t.tables[category] ?: return null
        if (table.timestamps.isEmpty()) return null
        val idx = table.timestamps.indices.minByOrNull { Duration.between(table.timestamps[it], at).abs() } ?: return null
        return names.firstNotNullOfOrNull { name -> table.columns.firstOrNull { c -> c.parameter.name == name }?.values?.getOrNull(idx) }
    }
    if (a.category != MetricCategory.MOTION) {
        valueNear(MetricCategory.MOTION, "Speed")?.let { speed ->
            checks += if (speed < 1.0) "Скорость в этот момент — 0 км/ч: машина стояла" else "Машина двигалась: ${speed.formatValue()} км/ч"
        }
    }
    if (a.category != MetricCategory.POWER) {
        valueNear(MetricCategory.POWER, "DIgnition", "DIgnitionCAN")?.let { ignition ->
            checks += if (ignition < 0.5) "Зажигание было выключено" else "Зажигание было включено"
        }
    }
    return checks
}

/**
 * Нижняя панель — три честных варианта по макету 3.6, ровно по две кнопки в каждом (своей третьей
 * «Графики машины» в макете нет — к машине теперь ведёт кликабельная ссылка «машина · время» сверху):
 * новая («Ложная»/«Разберись»), молчит датчик («Машина»/«Напомнить механику» — независимо от статуса),
 * в работе («Вернуть»/«Ход разбора»), закрыта (итог/«Ход разбора»/«Изменить»).
 */
@Composable
private fun ActionsBar(
    anomaly: Anomaly,
    onAct: () -> Unit,
    onFalseAlarm: () -> Unit,
    onReopen: () -> Unit,
    onCharts: () -> Unit,
    onRemindMechanic: () -> Unit,
    onOpenResolution: () -> Unit,
) {
    val c = Petrovich.colors
    Column(
        Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            anomaly.isNew && anomaly.kind == "nodata" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Машина", onCharts, Modifier.weight(1f))
                PrimaryButton("Напомнить механику", onRemindMechanic, Modifier.weight(1f))
            }
            anomaly.isNew -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Точная подпись макета 3.6 — короткая «Ложная» в нижней панели (не путать с «Ложная тревога» в статус-метке).
                SoftButton("Ложная", onFalseAlarm, Modifier.weight(1f))
                PrimaryButton("Разберись", onAct, Modifier.weight(1f))
            }
            anomaly.resolution == Resolution.IN_PROGRESS -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("Вернуть", onReopen, Modifier.weight(1f))
                PrimaryButton("Ход разбора", onOpenResolution, Modifier.weight(1f))
            }
            else -> SurfaceCard(shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(onClick = onOpenResolution)) {
                        // «Закрыто» — статус по словарю макета; «Подтверждено» в нём нет (см. StatusPill выше).
                        val label = anomaly.outcomeDetail
                            ?: if (anomaly.resolution == Resolution.CONFIRMED) "Закрыто" else resolutionLabel(anomaly) ?: ""
                        Pill(label, if (anomaly.resolution == Resolution.CONFIRMED) c.ok else c.muted, if (anomaly.resolution == Resolution.CONFIRMED) c.okSoft else c.surface2)
                    }
                    TextButton(onClick = onOpenResolution) { Text("Ход разбора", color = c.accent, fontWeight = FontWeight.SemiBold) }
                    TextButton(onClick = onReopen) { Text("Изменить", color = c.accent, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}
