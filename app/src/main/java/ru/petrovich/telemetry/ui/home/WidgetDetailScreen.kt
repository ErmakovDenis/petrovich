package ru.petrovich.telemetry.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.PeriodChips
import ru.petrovich.telemetry.ui.common.PeriodPreset
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.TimeRange
import ru.petrovich.telemetry.ui.common.plural
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.LocalDateTime
import kotlin.math.abs

private val DetailPeriodPresets = WidgetPeriod.entries.map { p ->
    PeriodPreset(p.name.lowercase(), p.title) { p.toRange() }
}

private val HeroShape = RoundedCornerShape(20.dp)

/**
 * «Подробная сводка виджета» (3.15): раскрывает цифру с «Главной» — как менялась и за счёт каких машин.
 * У «Без связи» / «Тревог» выбора периода нет — там всегда «сейчас».
 * Период общий с «Отчётами» ([ReportsPeriodStore]). Стрелки сдвигают окно той же длины
 * назад/вперёд (12–19 → 5–12), подпись показывает даты текущего окна.
 *
 * @param onOpenVehicle тап по машине-лидеру → карточка машины (3.13, ui/park/VehicleCardScreen — не мой файл);
 * по умолчанию ничего не делает, если вызывающий экран не передал реальный переход.
 * @param lockedPeriod устаревший аргумент навигации; период берётся из [ReportsPeriodStore].
 */
@Composable
fun WidgetDetailScreen(
    type: WidgetType,
    onBack: () -> Unit,
    onAsk: () -> Unit,
    onOpenVehicle: (String) -> Unit = {},
    lockedPeriod: WidgetPeriod? = null,
) {
    val c = Petrovich.colors
    val scope = rememberCoroutineScope()
    val reportsSel by ServiceLocator.reportsPeriod.selection.collectAsStateWithLifecycle()
    // Если пришли с явным периодом в URL (отчёты) — подхватываем в store один раз на вход.
    LaunchedEffect(type, lockedPeriod) {
        lockedPeriod?.let { ServiceLocator.reportsPeriod.selectPreset(it.name.lowercase()) }
    }
    val selectedPresetId = reportsSel.presetId
    val customRange = reportsSel.customRange
    val selectedPeriod = WidgetPeriod.entries.firstOrNull {
        it.name.equals(selectedPresetId, ignoreCase = true)
    }
    val baseRange = remember(selectedPresetId, customRange) {
        selectionBaseRange(selectedPresetId, customRange)
    }
    // Сдвиг окна стрелками — только на карточке, пресет в store не трогаем.
    var viewRange by remember(type, selectedPresetId, customRange) { mutableStateOf(baseRange) }
    var summary by remember(type) { mutableStateOf<WidgetSummary?>(null) }
    var offline by remember(type) { mutableStateOf<OfflineSummary?>(null) }
    var overconsumers by remember(type) { mutableStateOf<OverconsumersReport?>(null) }
    var removed by remember { mutableStateOf(false) }

    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val now = LocalDateTime.now()
    val dayCount = remember(viewRange, selectedPeriod, customRange) {
        when {
            selectedPeriod != null && selectedPeriod != WidgetPeriod.DAY -> selectedPeriod.bucketCount
            else -> {
                val a = viewRange.from.toLocalDate()
                val b = viewRange.to.toLocalDate()
                (java.time.temporal.ChronoUnit.DAYS.between(a, b).toInt() + 1).coerceAtLeast(1)
            }
        }
    }
    val atLatest = viewRange.to.toLocalDate() >= baseRange.to.toLocalDate()
    val canGoPrev = !viewRange.from.toLocalDate().minusDays(dayCount.toLong())
        .isBefore(now.toLocalDate().minusDays(TimeRange.MAX_DAYS))
    val canGoNext = !atLatest

    val periodLabel = when {
        type == WidgetType.OFFLINE || type == WidgetType.ALARMS -> "сейчас"
        customRange != null && selectedPresetId == null -> "свой период"
        else -> selectedPeriod?.title ?: "Неделя"
    }
    val rangeCaption = reportsRangeCaption(viewRange)

    fun shiftByBuckets(direction: Int) {
        if (selectedPeriod == WidgetPeriod.DAY) {
            viewRange = if (direction < 0) viewRange.previousOfSameLength() else {
                val next = viewRange.nextOfSameLength()
                if (next.to.isAfter(baseRange.to)) baseRange else next
            }
            return
        }
        val end = viewRange.to.toLocalDate().plusDays(direction.toLong() * dayCount)
        if (direction > 0 && !end.isBefore(baseRange.to.toLocalDate())) {
            viewRange = baseRange
            return
        }
        val start = end.minusDays(dayCount.toLong() - 1)
        val to = if (!end.isBefore(now.toLocalDate())) now else end.plusDays(1).atStartOfDay()
        viewRange = TimeRange(start.atStartOfDay(), to)
    }

    LaunchedEffect(type, viewRange, selectedPeriod, customRange, atLatest) {
        summary = null
        offline = null
        overconsumers = null
        when (type) {
            WidgetType.OFFLINE -> {
                offline = runCatchingCancellable {
                    ServiceLocator.widgetPeriodCache.ensureFresh(WidgetPeriod.WEEK)?.offline()
                        ?: loadFleetWeek().offline()
                }.getOrNull()
            }
            WidgetType.ALARMS -> Unit
            WidgetType.OVERCONSUMERS -> {
                overconsumers = runCatchingCancellable {
                    // Тот же источник, что у отчётов на «текущем» окне.
                    if (atLatest && selectedPeriod != null && selectedPeriod != WidgetPeriod.DAY && customRange == null) {
                        ServiceLocator.widgetPeriodCache.ensureFresh(selectedPeriod)?.overconsumers()
                            ?: loadFleetPeriod(selectedPeriod).overconsumers()
                    } else {
                        loadFleetRange(viewRange).overconsumers()
                    }
                }.getOrNull()
            }
            else -> {
                summary = runCatchingCancellable {
                    when {
                        // Как на «Отчётах»: кэш периода / тот же loadWidgetSummary(period).
                        atLatest && selectedPeriod != null && customRange == null -> {
                            if (selectedPeriod == WidgetPeriod.DAY) {
                                loadWidgetSummary(type, selectedPeriod)
                            } else {
                                ServiceLocator.widgetPeriodCache.ensureFresh(selectedPeriod)?.summary(type)
                                    ?: loadWidgetSummary(type, selectedPeriod)
                            }
                        }
                        atLatest && customRange != null && selectedPresetId == null -> {
                            loadFleetRange(customRange).summary(type)
                        }
                        else -> loadWidgetSummary(type, viewRange)
                    }
                }.getOrNull()
            }
        }
    }

    Scaffold(topBar = { AppTopBar(type.title, onBack = onBack) }, containerColor = c.bg) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (type == WidgetType.OFFLINE || type == WidgetType.ALARMS) {
                Text("сейчас", style = MaterialTheme.typography.labelMedium, color = c.muted)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PeriodChips(
                        presets = DetailPeriodPresets,
                        selectedPresetId = selectedPresetId,
                        customRange = customRange,
                        onSelectPreset = {
                            ServiceLocator.reportsPeriod.selectPreset(it.id)
                        },
                        onSelectCustom = { ServiceLocator.reportsPeriod.selectCustom(it) },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { shiftByBuckets(-1) },
                            enabled = canGoPrev,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий период")
                        }
                        Text(
                            rangeCaption,
                            style = MaterialTheme.typography.bodySmall,
                            color = c.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { shiftByBuckets(1) },
                            enabled = canGoNext,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Следующий период")
                        }
                    }
                }
            }

            if (type == WidgetType.ALARMS) {
                val decided = anomalies.filter {
                    it.resolution == ru.petrovich.telemetry.anomaly.Resolution.CONFIRMED ||
                        it.resolution == ru.petrovich.telemetry.anomaly.Resolution.FALSE_ALARM
                }
                val confirmed = decided.count { it.resolution == ru.petrovich.telemetry.anomaly.Resolution.CONFIRMED }
                SurfaceCard(
                    modifier = Modifier.fillMaxWidth().then(
                        if (confirmed > 0) Modifier.border(2.dp, c.highSoft, HeroShape) else Modifier,
                    ),
                    shape = HeroShape,
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailHeroHeader(type, periodLabel)
                        Text(
                            if (decided.isEmpty()) "—" else "$confirmed из ${decided.size}",
                            fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp,
                        )
                        Text(
                            if (decided.isEmpty()) "Пока нет закрытых тревог" else "подтверждённых тревог",
                            style = MaterialTheme.typography.bodyMedium, color = c.muted,
                        )
                    }
                }
            } else if (type == WidgetType.OFFLINE) {
                val o = offline
                if (o == null) Loading() else {
                    val hasSilent = o.count > 0
                    SurfaceCard(
                        modifier = Modifier.fillMaxWidth().border(2.dp, if (hasSilent) c.highSoft else c.okSoft, HeroShape),
                        shape = HeroShape,
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DetailHeroHeader(type, periodLabel)
                            Text(
                                "${o.count}",
                                fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp,
                            )
                            Text(
                                if (o.count == 0) "Все машины на связи" else "без связи сейчас",
                                style = MaterialTheme.typography.bodyMedium, color = c.muted,
                            )
                        }
                    }
                    if (o.silent.isNotEmpty()) {
                        SectionLabel("Молчат")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            o.silent.forEach { entry ->
                                LeaderRow(
                                    name = entry.name,
                                    value = entry.status,
                                    onClick = { onOpenVehicle(entry.vehicleId) },
                                    marker = c.high,
                                )
                            }
                        }
                    }
                    CommentCard(if (o.count == 0) "Все машины на связи — молчащих нет." else "${o.count} ${plural(o.count, "машина", "машины", "машин")} не передают данные дольше 45 минут.")
                }
            } else if (type == WidgetType.OVERCONSUMERS) {
                val report = overconsumers
                if (report == null) Loading() else {
                    val count = report.rows.size
                    val hasOver = count > 0
                    SurfaceCard(
                        modifier = Modifier.fillMaxWidth().border(2.dp, if (hasOver) c.highSoft else c.okSoft, HeroShape),
                        shape = HeroShape,
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            DetailHeroHeader(type, periodLabel)
                            Text(
                                "$count ${plural(count, "машина", "машины", "машин")}",
                                fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp,
                            )
                            Text(
                                report.medianRate?.let { "медиана парка ${formatCount(it)} л/100 км" }
                                    ?: if (count == 0) "Нет перерасхода за период" else "выше медианы парка на ≥15 %",
                                style = MaterialTheme.typography.bodyMedium, color = c.muted,
                            )
                        }
                    }
                    if (report.rows.isNotEmpty()) {
                        SectionLabel("Перерасход")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            report.rows.forEach { row ->
                                LeaderRow(
                                    name = row.name,
                                    value = "${formatCount(row.ratePer100Km)} л/100 км · +${formatCount(row.excessPercent)} %",
                                    onClick = { onOpenVehicle(row.vehicleId) },
                                    marker = c.high,
                                    valueColor = c.high,
                                )
                            }
                        }
                    }
                    CommentCard(
                        "Сравниваем расход на 100 км с медианой парка за период; порог +15 %." +
                            if (count == 0) " Сейчас никто не выходит за порог." else "",
                    )
                }
            } else {
                val s = summary
                if (s == null) Loading() else {
                    MetricHero(type = type, summary = s, periodLabel = periodLabel)
                    if (s.leaders.isNotEmpty()) {
                        SectionLabel("По машинам")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.leaders.forEach { leader ->
                                LeaderRow(
                                    name = leader.name,
                                    subtitle = leader.subtitle,
                                    value = "${formatCount(leader.value)}${if (s.unit.isNotEmpty()) " ${s.unit}" else ""}",
                                    onClick = { onOpenVehicle(leader.vehicleId) },
                                )
                            }
                        }
                    }
                    CommentCard(comment(s))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(
                    if (removed) "Убрано" else "Убрать", enabled = !removed, modifier = Modifier.weight(1f),
                    onClick = {
                        removed = true
                        scope.launch {
                            ServiceLocator.settings.update { s -> s.copy(widgetLayout = serializeLayout(s.layout.filterNot { it.type == type })) }
                            onBack()
                        }
                    },
                )
                PrimaryButton("Спросить Петровича", onAsk, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricHero(type: WidgetType, summary: WidgetSummary, periodLabel: String) {
    val c = Petrovich.colors
    val change = summary.changePercent
    val softBorder = when {
        type == WidgetType.FUEL && change != null && change >= 0 -> c.medSoft
        type == WidgetType.FUEL && change != null && change < 0 -> c.okSoft
        change != null && abs(change) >= 10 && change >= 0 -> c.medSoft
        change != null && abs(change) >= 10 -> c.okSoft
        else -> null
    }
    SurfaceCard(
        modifier = Modifier.fillMaxWidth().then(
            if (softBorder != null) Modifier.border(2.dp, softBorder, HeroShape) else Modifier,
        ),
        shape = HeroShape,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailHeroHeader(type, periodLabel)
            if (type == WidgetType.FUEL && change != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${if (change >= 0) "+" else "−"}${formatCount(abs(change))} %",
                        fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp,
                        color = if (change >= 0) c.med else c.ok,
                    )
                    Text(
                        "к прошлому периоду · ${formatCount(summary.total)} ${summary.unit}",
                        style = MaterialTheme.typography.bodyMedium, color = c.muted,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            } else {
                Text(
                    "${formatCount(summary.total)}${if (summary.unit.isNotEmpty()) " ${summary.unit}" else ""}",
                    fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp,
                )
                Text(
                    change?.let { "${if (it >= 0) "↑" else "↓"} ${formatCount(abs(it))}% к прошлому периоду" }
                        ?: "Нет данных за прошлый период для сравнения",
                    style = MaterialTheme.typography.bodyMedium,
                    color = change?.let { if (it >= 0) c.med else c.ok } ?: c.muted,
                )
            }
            if (summary.points.isNotEmpty()) {
                if (type == WidgetType.FUEL) {
                    DetailChartWithBaseline(summary.points, summary.usualDaily(), summary.unit)
                } else {
                    DetailChart(summary.points, summary.unit)
                }
            }
        }
    }
}

@Composable
private fun DetailHeroHeader(type: WidgetType, periodLabel: String) {
    val c = Petrovich.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(type.icon, null, Modifier.size(20.dp), tint = c.accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(type.title, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1)
            Text(periodLabel, style = MaterialTheme.typography.labelSmall, color = c.muted)
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Petrovich.colors.accent)
    }
}

@Composable
private fun DetailChart(points: List<WidgetPoint>, unit: String) {
    val c = Petrovich.colors
    var selected by remember(points) { mutableIntStateOf(points.lastIndex.coerceAtLeast(0)) }
    val max = (points.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(0.01)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(100.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            points.forEachIndexed { i, p ->
                val selectedBar = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { selected = i },
                        ),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier.fillMaxWidth()
                            .fillMaxHeight((p.value / max).toFloat().coerceIn(0.04f, 1f))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (selectedBar) c.accent else c.accentSoft),
                    )
                }
            }
        }
        ChartSelectionLabel(points.getOrNull(selected), unit)
        if (points.size <= 14) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                points.forEachIndexed { i, p ->
                    Text(
                        p.label,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == selected) c.ink else c.muted,
                        maxLines = 1,
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** Столбцы с линией «обычно» — как WeekBarsWithBaseline на главной; тап показывает значение дня. */
@Composable
private fun DetailChartWithBaseline(points: List<WidgetPoint>, baseline: Double?, unit: String) {
    val c = Petrovich.colors
    var selected by remember(points) { mutableIntStateOf(points.lastIndex.coerceAtLeast(0)) }
    val max = listOfNotNull(points.maxOfOrNull { it.value }, baseline).maxOrNull()?.coerceAtLeast(0.01) ?: 0.01
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(100.dp)) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                points.forEachIndexed { i, p ->
                    val above = baseline != null && p.value > baseline * 1.02
                    val selectedBar = i == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { selected = i },
                            ),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .fillMaxHeight((p.value / max).toFloat().coerceIn(0.04f, 1f))
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    when {
                                        selectedBar -> c.accent
                                        above -> c.med
                                        else -> c.accentSoft
                                    },
                                ),
                        )
                    }
                }
            }
            if (baseline != null && baseline > 0) {
                val yFrac = (baseline / max).toFloat().coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxSize()) {
                    val y = size.height * (1f - yFrac)
                    drawLine(
                        color = c.muted.copy(alpha = .7f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                    )
                }
            }
        }
        ChartSelectionLabel(points.getOrNull(selected), unit)
        if (points.size <= 14) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                points.forEachIndexed { i, p ->
                    Text(
                        p.label,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (i == selected) c.ink else c.muted,
                        maxLines = 1,
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartSelectionLabel(point: WidgetPoint?, unit: String) {
    val c = Petrovich.colors
    if (point == null) return
    val value = "${formatCount(point.value)}${if (unit.isNotEmpty()) " $unit" else ""}"
    Text(
        "${point.label} · $value",
        style = MaterialTheme.typography.labelLarge,
        color = c.ink,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun LeaderRow(
    name: String,
    value: String,
    onClick: () -> Unit,
    marker: Color = Petrovich.colors.accent,
    valueColor: Color = Petrovich.colors.muted,
    subtitle: String? = null,
) {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth(), onClick = onClick, shape = RoundedCornerShape(14.dp)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(marker))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
                }
            }
            Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp), tint = c.muted)
        }
    }
}

@Composable
private fun CommentCard(text: String) {
    val c = Petrovich.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accentSoft).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Text("П", color = c.onAccent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/**
 * Короткий, честный комментарий — только из того, что реально посчитано (число, изменение, лидер).
 * Без домыслов вроде «в основном на трассе М-5»: маршрутов у нас нет, выдумывать нельзя.
 */
private fun comment(s: WidgetSummary): String {
    val leader = s.leaders.firstOrNull()?.let {
        val who = listOfNotNull(it.name, it.subtitle).joinToString(" · ")
        " Больше всего у $who — ${formatCount(it.value)}${if (s.unit.isNotEmpty()) " ${s.unit}" else ""}."
    } ?: ""
    val change = s.changePercent
    return when {
        change == null -> "Данных за прошлый период пока нет — сравнивать не с чем.$leader"
        abs(change) < 10 -> "Без резких скачков, разница ${formatCount(abs(change))}%.$leader"
        change > 0 -> "Рост на ${formatCount(change)}% к прошлому периоду. Это не обязательно проблема, но я слежу.$leader"
        else -> "Снижение на ${formatCount(abs(change))}% к прошлому периоду.$leader"
    }
}
