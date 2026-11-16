package ru.petrovich.telemetry.ui.home

import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.dashedBorder
import ru.petrovich.telemetry.ui.common.greeting
import ru.petrovich.telemetry.ui.common.headline
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.ui.common.plural
import ru.petrovich.telemetry.ui.common.shortDate
import ru.petrovich.telemetry.ui.common.shown
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.problems.ProblemsTab
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

@Composable
fun HomeScreen(
    vehicleCount: Int?,
    onOpenProblems: (ProblemsTab) -> Unit,
    onOpenCard: (String) -> Unit,
    onOpenReport: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenWidgets: () -> Unit,
    onOpenTables: () -> Unit,
    onOpenCharts: () -> Unit,
    unreadNotifications: Int = 0,
    onOpenNotifications: () -> Unit = {},
    /** «Все N машин и их статус» / плитки статуса — экран «Парк» (3.12) пока не входит в мои файлы. */
    onOpenFleet: () -> Unit = {},
    /** Тап по виджету «Сводки» → «Подробная сводка виджета» (3.15, см. [WidgetDetailScreen]). */
    onOpenWidgetDetail: (WidgetType) -> Unit = {},
) {
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    // Часы раз в минуту: «стенд давно не проверял» появится, даже если экран открыт и больше ничего не меняется.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }

    val s = settings ?: return
    val scanned = s.lastScanAt > 0
    val data = remember(anomalies, s.mutedKinds, vehicleCount, scanned) {
        HomeData(anomalies.shown(s.mutedKinds), vehicleCount, scanned)
    }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    fun scan() {
        if (scanning) return
        scanning = true
        scanError = null
        scope.launch {
            runCatchingCancellable { ServiceLocator.anomalyScanner.scan(lookbackHours = 24) }
                .onSuccess { r -> if (r.checkedVehicles > 0 && r.errors.size >= r.checkedVehicles) scanError = r.errors.first() }
                .onFailure { scanError = it.message ?: it.toString() }
            scanning = false
        }
    }
    // Первая проверка запускается сама: пока её нет, сводке нечего сказать.
    LaunchedEffect(scanned) { if (!scanned) scan() }

    // Снимок парка для плиток «на линии / стоят / без связи» — общий с вкладкой «Парк», без ожидания скана аномалий.
    LaunchedEffect(Unit) { runCatchingCancellable { ServiceLocator.fleetCache.ensureFresh() } }

    // «Неделя в цифрах»: один запрос на период; при переключении на месяц — отдельная подгрузка.
    var homePeriod by remember { mutableStateOf(WidgetPeriod.WEEK) }
    var fleetWeek by remember { mutableStateOf<FleetWeek?>(null) }
    LaunchedEffect(s.telemetryOnServer, homePeriod) {
        fleetWeek = null
        do {
            fleetWeek = runCatchingCancellable { loadFleetPeriod(homePeriod) }.getOrNull()
            if (!s.telemetryOnServer) break
            delay(15_000)
        } while (true)
    }

    // Хранилище на стенде: свежая лента для сводки; без сети — последняя загруженная (ошибку покажет лента).
    LaunchedEffect(Unit) { runCatchingCancellable { ServiceLocator.anomalySync.refresh() } }
    val layout = s.layout

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HomeHeader(
            unreadNotifications = unreadNotifications,
            onOpenNotifications = onOpenNotifications,
            onAsk = onOpenChat,
        )

        StatusBanner(
            data = data,
            fleetUpdatedAt = s.fleetUpdatedAt,
            vehicleCount = vehicleCount,
            onOpenMainProblem = { id -> onOpenCard(id) },
            onOpenProblems = { onOpenProblems(ProblemsTab.NEW) },
        )

        FleetTiles(s, onOpenFleet)

        // Фоновая проверка на стенде: отметка — со стенда и не двигается, пока AutoGRAPH или стенд недоступны.
        if (s.backgroundOnServer && s.lastScanAt > 0 && now - s.lastScanAt > STALE_INTERVALS * s.standScanIntervalMinutes * 60_000L) {
            StaleScanCard(s.lastScanAt)
        }
        // API детекции недоступен: сверка сейчас идёт пороговыми правилами (отметку ставит checker, снимает при восстановлении API).
        if (s.anomaliesViaApi && s.anomalyApiDegraded) {
            ApiDegradedCard()
        }

        WeekInNumbersHeader(homePeriod, onPeriod = { homePeriod = it })
        if (layout.isEmpty()) {
            EmptyWidgets(onOpenWidgets)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                widgetRows(layout).forEach { row ->
                    if (row.size == 1) {
                        MetricWidgetCard(row[0].type, fleetWeek, homePeriod, anomalies, Modifier.fillMaxWidth(), onClick = { onOpenWidgetDetail(row[0].type) })
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { slot ->
                                MetricWidgetCard(slot.type, fleetWeek, homePeriod, anomalies, Modifier.weight(1f), onClick = { onOpenWidgetDetail(slot.type) })
                            }
                        }
                    }
                }
                AddWidgetButton("+ Добавить или убрать виджеты", onOpenWidgets)
            }
        }

        SectionLabel("Данные по машинам")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinkCard("Таблицы", "Значения по времени", Icons.Outlined.TableChart, onOpenTables, Modifier.weight(1f))
            LinkCard("Графики", "Линии и аномалии", Icons.Outlined.BarChart, onOpenCharts, Modifier.weight(1f))
        }

        ReportLinkCard(s.reportTime, onOpenReport)
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Светлая шапка по карте экранов: дата и приветствие слева, «Спросить» справа.
 * Статус парка — отдельная цветная плашка ниже (не синяя шапка).
 */
@Composable
private fun HomeHeader(
    unreadNotifications: Int,
    onOpenNotifications: () -> Unit,
    onAsk: () -> Unit,
) {
    val c = Petrovich.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(LocalDate.now().shortDate(), style = MaterialTheme.typography.labelMedium, color = c.muted)
            Text(greeting(), fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = c.ink)
        }
        if (unreadNotifications > 0) {
            NotificationBell(unreadNotifications, onOpenNotifications)
            Spacer(Modifier.width(8.dp))
        }
        AskPill(onAsk)
    }
}

@Composable
private fun AskPill(onClick: () -> Unit) {
    val c = Petrovich.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Mic, null, Modifier.size(16.dp), tint = c.ink)
        Text("Спросить", style = MaterialTheme.typography.labelLarge, color = c.ink, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Цветная плашка статуса белым текстом, крупнее приветствия; главная проблема — белой
 * карточкой сразу под статусом с кнопкой «Разобрать».
 */
@Composable
private fun StatusBanner(
    data: HomeData,
    fleetUpdatedAt: Long,
    vehicleCount: Int?,
    onOpenMainProblem: (String) -> Unit,
    onOpenProblems: () -> Unit,
) {
    val c = Petrovich.colors
    val bg = when (data.tone) {
        BriefTone.RED -> c.high
        BriefTone.YELLOW -> c.med
        BriefTone.GREEN -> c.ok
        BriefTone.NEUTRAL -> c.surface2
    }
    val on = if (data.tone == BriefTone.NEUTRAL) c.ink else Color.White
    val main = data.pending.firstOrNull()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(bg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (data.tone == BriefTone.RED || data.tone == BriefTone.YELLOW) {
                Icon(Icons.Outlined.WarningAmber, null, Modifier.size(28.dp).padding(top = 4.dp), tint = on)
            }
            Text(
                when (data.tone) {
                    BriefTone.GREEN -> "Всё в порядке"
                    BriefTone.NEUTRAL -> "Ещё не проверял данные"
                    else -> "${data.pendingCount} ${plural(data.pendingCount, "проблема ждёт", "проблемы ждут", "проблем ждут")} решения"
                },
                fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 30.sp, color = on,
                modifier = Modifier.weight(1f).clickable(enabled = data.pendingCount > 0, onClick = onOpenProblems),
            )
        }
        val restOk = vehicleCount?.let { (it - data.vehiclesWithIssues).coerceAtLeast(0) }
        val freshness = fleetFreshness(fleetUpdatedAt)
        Text(
            buildString {
                if (data.tone == BriefTone.GREEN || data.tone == BriefTone.NEUTRAL) {
                    if (vehicleCount != null) append("Парк · $vehicleCount ${plural(vehicleCount, "машина", "машины", "машин")}")
                } else if (restOk != null && restOk > 0) {
                    append("Остальные $restOk ${plural(restOk, "машина в порядке", "машины в порядке", "машин в порядке")}")
                } else {
                    append("Требует внимания")
                }
                if (freshness != null) append(" · $freshness")
            },
            style = MaterialTheme.typography.bodyMedium, color = on.copy(alpha = .85f),
        )
        if (main != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    buildString {
                        append(main.severity.label().uppercase())
                        main.time?.hhmm()?.let { append(" · $it") }
                        if (main.vehicleName.isNotBlank()) append(" · ${main.vehicleName}")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (main.severity == ru.petrovich.telemetry.anomaly.Severity.CRITICAL) c.high else c.med,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(main.headline(), style = MaterialTheme.typography.titleSmall, color = c.ink)
                main.time?.toLocalDate()?.takeIf { it != LocalDate.now() }?.let {
                    Text(it.title(), style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                PrimaryButton("Разобрать", onClick = { onOpenMainProblem(main.id) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private fun fleetFreshness(updatedAtMillis: Long): String? {
    if (updatedAtMillis <= 0) return null
    val minutes = Duration.between(
        Instant.ofEpochMilli(updatedAtMillis).atZone(ZoneId.systemDefault()).toLocalDateTime(),
        LocalDateTime.now(),
    ).toMinutes().coerceAtLeast(0)
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "проверено $minutes мин назад"
        else -> "проверено ${minutes / 60} ч назад"
    }
}

@Composable
private fun NotificationBell(count: Int, onClick: () -> Unit) {
    val c = Petrovich.colors
    // Бейдж — сосед круглой кнопки, а не её ребёнок: иначе clip(CircleShape) родителя обрезает
    // бейдж там, где он торчит за пределы вписанной окружности (у самого угла).
    Box(Modifier.size(40.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.surface2).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.NotificationsNone, "Уведомления", Modifier.size(20.dp), tint = c.ink) }
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(16.dp).clip(CircleShape).background(c.high),
                contentAlignment = Alignment.Center,
            ) { Text(if (count > 9) "9+" else "$count", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Сколько интервалов фоновой проверки на стенде может пройти без новой отметки, пока сводка не скажет, что отстала. */
private const val STALE_INTERVALS = 3

/** Стенд давно не проверял: «Всё в порядке» ниже может быть устаревшим. */
@Composable
private fun StaleScanCard(lastScanAt: Long) {
    val c = Petrovich.colors
    val at = Instant.ofEpochMilli(lastScanAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
    SurfaceCard(Modifier.fillMaxWidth().border(2.dp, c.highSoft, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Стенд давно не проверял данные", style = MaterialTheme.typography.titleSmall, color = c.high)
            Text(
                "Последняя проверка — ${at.toLocalDate().title()}, ${at.hhmm()}. AutoGRAPH или стенд недоступны, сведения ниже могут быть устаревшими.",
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
        }
    }
}

/** API прогнозирования аномалий недоступен: сверка сейчас идёт пороговыми правилами на устройстве, как до включения API. */
@Composable
private fun ApiDegradedCard() {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth().border(2.dp, c.highSoft, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Прогнозирование аномалий недоступно", style = MaterialTheme.typography.titleSmall, color = c.high)
            Text(
                "API детекции не отвечает — аномалии ищет устройство по пороговым правилам, как раньше. API будет проверен снова автоматически.",
                style = MaterialTheme.typography.bodySmall, color = c.muted,
            )
        }
    }
}

@Composable
private fun ProblemsOrOkCard(
    data: HomeData,
    scanning: Boolean,
    scanError: String?,
    onScan: () -> Unit,
    onOpenCard: (String) -> Unit,
    onOpenProblems: (ProblemsTab) -> Unit,
) {
    val c = Petrovich.colors
    when (data.tone) {
        BriefTone.NEUTRAL -> SurfaceCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (scanning) "Проверяю данные…" else "Ещё не проверял данные", style = MaterialTheme.typography.titleMedium)
                Text(
                    scanError?.let { "Не удалось получить данные: $it" } ?: "Петрович проверит парк и расскажет, что нашёл.",
                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                )
                PrimaryButton(if (scanning) "Проверяю…" else "Проверить сейчас", onScan, Modifier.fillMaxWidth(), enabled = !scanning)
            }
        }
        BriefTone.GREEN -> SurfaceCard(Modifier.fillMaxWidth().border(2.dp, c.okSoft, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(28.dp), tint = c.ok)
                Column {
                    Text("Всё в порядке", style = MaterialTheme.typography.titleSmall)
                    Text("Петрович следит за парком", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
            }
        }
        BriefTone.RED, BriefTone.YELLOW -> {
            val border = if (data.tone == BriefTone.RED) c.highSoft else c.medSoft
            SurfaceCard(Modifier.fillMaxWidth().border(2.dp, border, RoundedCornerShape(20.dp)), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Требует внимания", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Pill("${data.pendingCount}", if (data.tone == BriefTone.RED) c.high else c.med, if (data.tone == BriefTone.RED) c.highSoft else c.medSoft)
                    }
                    data.pending.take(2).forEach { a -> ProblemLine(a, onClick = { onOpenCard(a.id) }) }
                    PrimaryButton("Разобрать с Петровичем", onClick = { onOpenProblems(ProblemsTab.NEW) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ProblemLine(a: Anomaly, onClick: () -> Unit) {
    val c = Petrovich.colors
    val bg = if (a.severity == ru.petrovich.telemetry.anomaly.Severity.CRITICAL) c.highSoft else c.medSoft
    val dot = if (a.severity == ru.petrovich.telemetry.anomaly.Severity.CRITICAL) c.high else c.med
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(a.vehicleName, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp), tint = c.muted)
    }
}

/**
 * «На линии / стоят / без связи» — белые плитки на светлом фоне, цвет только у цифры
 * (зелёный / обычный / оранжевый). Маршрут «Парк» фильтра пока не принимает — плитки
 * ведут в «Парк» через [onOpenFleet].
 */
@Composable
private fun FleetTiles(settings: ru.petrovich.telemetry.data.settings.AppSettings, onOpenFleet: () -> Unit) {
    val c = Petrovich.colors
    val known = settings.fleetUpdatedAt > 0
    val offlineValue = if (known) "${settings.fleetOffline}" else "нет данных"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FleetTile(
            "На линии", if (known) "${settings.fleetOnline}" else "—",
            if (known && settings.fleetOnline > 0) c.ok else c.ink,
            Modifier.weight(1f), onOpenFleet,
        )
        FleetTile("Стоят", if (known) "${settings.fleetIdle}" else "—", c.ink, Modifier.weight(1f), onOpenFleet)
        FleetTile(
            "Без связи", offlineValue,
            if (known && settings.fleetOffline > 0) c.med else c.ink,
            Modifier.weight(1f), onOpenFleet,
            valueSize = if (known) 24.sp else 14.sp,
        )
    }
}

@Composable
private fun FleetTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier,
    onClick: () -> Unit,
    valueSize: androidx.compose.ui.unit.TextUnit = 24.sp,
) {
    val c = Petrovich.colors
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = valueSize, color = valueColor, maxLines = 2)
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
}

@Composable
private fun ReportLinkCard(reportTime: String, onClick: () -> Unit) {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth(), onClick = onClick, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Description, null, Modifier.size(20.dp), tint = c.accent)
            }
            Text("Доклад на $reportTime готов", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
            Text("Открыть", style = MaterialTheme.typography.labelLarge, color = c.accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun EmptyWidgets(onOpenWidgets: () -> Unit) {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CheckCircle, null, tint = c.ok)
            Text("Здесь пусто", style = MaterialTheme.typography.titleSmall)
            AddWidgetButton("+ Добавить или убрать виджеты", onOpenWidgets)
        }
    }
}

@Composable
private fun AddWidgetButton(text: String, onClick: () -> Unit) {
    val c = Petrovich.colors
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick)
            .dashedBorder(c.line)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = c.accent, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun LinkCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    SurfaceCard(modifier = modifier, onClick = onClick) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = Petrovich.colors.accent)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Petrovich.colors.muted)
        }
    }
}

// ---------- «Неделя в цифрах»: период, сетка широких/узких, карточки по макету ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekInNumbersHeader(period: WidgetPeriod, onPeriod: (WidgetPeriod) -> Unit) {
    val c = Petrovich.colors
    val choices = listOf(WidgetPeriod.WEEK, WidgetPeriod.MONTH)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Неделя в цифрах", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            choices.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = p == period,
                    onClick = { onPeriod(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = c.surface, activeContentColor = c.ink, activeBorderColor = c.line,
                        inactiveContainerColor = c.surface2, inactiveContentColor = c.muted, inactiveBorderColor = c.line,
                    ),
                ) { Text(p.title, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun MetricWidgetCard(
    type: WidgetType,
    fleetWeek: FleetWeek?,
    period: WidgetPeriod,
    anomalies: List<Anomaly>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val periodLabel = when (type) {
        WidgetType.OFFLINE, WidgetType.ALARMS -> "сейчас"
        else -> if (period == WidgetPeriod.MONTH) "месяц" else "неделя"
    }
    SurfaceCard(modifier, onClick = onClick, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (type) {
                WidgetType.FUEL -> FuelWidgetBody(fleetWeek?.summary(type), periodLabel)
                WidgetType.OVERCONSUMERS -> OverconsumersBody(fleetWeek?.summary(WidgetType.FUEL), periodLabel)
                WidgetType.OFFLINE -> OfflineWidgetBody(fleetWeek?.offline())
                WidgetType.ALARMS -> AlarmsWidgetBody(anomalies)
                else -> {
                    val summary = fleetWeek?.summary(type)
                    WidgetHeader(type, periodLabel, badge = null)
                    BigNumber(summary?.let { "${formatCount(it.total)}${if (it.unit.isNotEmpty()) " ${it.unit}" else ""}" } ?: "—")
                    val change = summary?.changePercent
                    Sub(
                        when {
                            summary == null -> "Загружаю…"
                            change == null -> "Нет данных за прошлый период для сравнения"
                            else -> "${if (change >= 0) "↑" else "↓"} ${formatCount(abs(change))}% к прошлому периоду"
                        },
                    )
                    summary?.points?.takeIf { it.isNotEmpty() }?.let { WeekBars(it) }
                }
            }
        }
    }
}

@Composable
private fun FuelWidgetBody(summary: WidgetSummary?, periodLabel: String) {
    val c = Petrovich.colors
    WidgetHeader(WidgetType.FUEL, periodLabel, badge = "НОВОЕ")
    if (summary == null) {
        BigNumber("—")
        Sub("Загружаю…")
        return
    }
    val change = summary.changePercent
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (change != null) {
            Text(
                "${if (change >= 0) "+" else "−"}${formatCount(abs(change))} %",
                fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, color = if (change >= 0) c.med else c.ok,
            )
            Text("к обычному · ${formatCount(summary.total)} ${summary.unit}", style = MaterialTheme.typography.bodyMedium, color = c.muted, modifier = Modifier.padding(bottom = 4.dp))
        } else {
            BigNumber("${formatCount(summary.total)} ${summary.unit}")
        }
    }
    if (change == null) Sub("Нет данных за прошлый период для сравнения")
    if (summary.points.isNotEmpty()) {
        WeekBarsWithBaseline(summary.points, summary.usualDaily())
    }
    summary.leaders.firstOrNull()?.let { top ->
        val avg = summary.total / summary.leaders.size.coerceAtLeast(1)
        val excess = if (avg > 0.5) ((top.value - avg) / avg * 100.0) else null
        Text(
            buildString {
                append("Больше всех: ${top.name}")
                if (excess != null && excess > 0) append(" — +${formatCount(excess)} %")
            },
            style = MaterialTheme.typography.bodySmall, color = c.ink,
        )
    }
}

@Composable
private fun OverconsumersBody(summary: WidgetSummary?, periodLabel: String) {
    val c = Petrovich.colors
    WidgetHeader(WidgetType.OVERCONSUMERS, periodLabel, badge = null)
    if (summary == null) {
        BigNumber("—")
        Sub("Загружаю…")
        return
    }
    val leaders = summary.leaders
    if (leaders.isEmpty()) {
        BigNumber("0")
        Sub("Нет перерасхода за период")
        return
    }
    val max = leaders.maxOf { it.value }.coerceAtLeast(0.01)
    val mean = leaders.map { it.value }.average()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        leaders.forEach { leader ->
            val excess = if (mean > 0.5) (leader.value - mean) / mean * 100.0 else 0.0
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(leader.name, style = MaterialTheme.typography.labelMedium, color = c.ink, modifier = Modifier.weight(1f), maxLines = 1)
                    Text(
                        if (excess >= 0) "+${formatCount(excess)} %" else "${formatCount(excess)} %",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (excess >= 5) c.high else c.muted,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2)) {
                    Box(
                        Modifier.fillMaxWidth((leader.value / max).toFloat().coerceIn(0.04f, 1f)).fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (excess >= 5) c.high else c.accentSoft),
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineWidgetBody(offline: OfflineSummary?) {
    WidgetHeader(WidgetType.OFFLINE, "сейчас", badge = null)
    BigNumber(offline?.count?.toString() ?: "—")
    Sub(
        when {
            offline == null -> "Загружаю…"
            offline.count == 0 -> "Все машины на связи"
            else -> offline.silent.joinToString("; ") { "${it.name} — ${it.status}" }
        },
    )
}

@Composable
private fun AlarmsWidgetBody(anomalies: List<Anomaly>) {
    val c = Petrovich.colors
    WidgetHeader(WidgetType.ALARMS, "сейчас", badge = null)
    val decided = anomalies.filter { it.resolution == Resolution.CONFIRMED || it.resolution == Resolution.FALSE_ALARM }
    val confirmed = decided.count { it.resolution == Resolution.CONFIRMED }
    if (decided.isEmpty()) {
        BigNumber("—")
        Sub("Пока нет закрытых тревог")
    } else {
        BigNumber("$confirmed из ${decided.size}")
        Sub("${formatCount(confirmed * 100.0 / decided.size)} % подтвердились")
        val ratio = confirmed.toFloat() / decided.size
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2)) {
            Box(Modifier.fillMaxWidth(ratio.coerceIn(0.04f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(c.ok))
        }
    }
}

@Composable
private fun WidgetHeader(type: WidgetType, periodLabel: String, badge: String?) {
    val c = Petrovich.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(type.icon, null, Modifier.size(18.dp), tint = c.muted)
        Spacer(Modifier.width(6.dp))
        Text(type.title, style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f), maxLines = 1)
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = c.accent,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(c.accentSoft)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(periodLabel, style = MaterialTheme.typography.labelSmall, color = c.muted)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(12.dp), tint = c.muted)
        }
    }
}

@Composable
private fun BigNumber(text: String, color: Color = Petrovich.colors.ink) {
    Text(text, fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 23.sp, lineHeight = 26.sp, color = color)
}

@Composable
private fun Sub(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Petrovich.colors.muted)

/** Мини-график: столбцы выше «обычно» — тёплые, ниже — мягкий акцент; пунктир — обычный уровень. */
@Composable
private fun WeekBarsWithBaseline(points: List<WidgetPoint>, baseline: Double?) {
    val c = Petrovich.colors
    val max = listOfNotNull(points.maxOfOrNull { it.value }, baseline).maxOrNull()?.coerceAtLeast(0.01) ?: 0.01
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            points.forEach { p ->
                val above = baseline != null && p.value > baseline * 1.02
                Box(
                    Modifier.weight(1f)
                        .fillMaxHeight((p.value / max).toFloat().coerceIn(0.04f, 1f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (above) c.med else c.accentSoft),
                )
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
}

/** Обычный мини-график без линии «обычно». */
@Composable
private fun WeekBars(points: List<WidgetPoint>) {
    val c = Petrovich.colors
    val max = (points.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(0.01)
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        points.forEachIndexed { i, p ->
            Box(
                Modifier.weight(1f)
                    .fillMaxHeight((p.value / max).toFloat().coerceIn(0.04f, 1f))
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .background(if (i == points.lastIndex) c.accent else c.accentSoft),
            )
        }
    }
}
