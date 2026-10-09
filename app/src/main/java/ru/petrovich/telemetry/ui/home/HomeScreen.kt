package ru.petrovich.telemetry.ui.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.dashedBorder
import ru.petrovich.telemetry.ui.common.greeting
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.plural
import ru.petrovich.telemetry.ui.common.shortDate
import ru.petrovich.telemetry.ui.problems.ProblemsTab
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

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

    val s = settings ?: return
    val scanned = s.lastScanAt > 0
    val data = remember(anomalies, vehicleCount, scanned) { HomeData(anomalies, vehicleCount, scanned) }
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

    // Данные «Сводки» (топливо/холостой ход/пробег/...) — одна загрузка на все виджеты главной сразу.
    var fleetWeek by remember { mutableStateOf<FleetWeek?>(null) }
    LaunchedEffect(Unit) { fleetWeek = runCatchingCancellable { loadFleetWeek() }.getOrNull() }

    val layout = s.layout

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        HeaderBand(vehicleCount, data, s, unreadNotifications, onOpenNotifications, onOpenChat, onOpenFleet)

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            QuickActionsRow(data.pendingCount, onOpenChat, onOpenReport, { onOpenProblems(ProblemsTab.NEW) }, onOpenWidgets)

            ProblemsOrOkCard(data, scanning, scanError, onScan = ::scan, onOpenCard, onOpenProblems)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Сводка", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenWidgets) { Text("Изменить", color = Petrovich.colors.accent, fontWeight = FontWeight.SemiBold) }
            }
            if (layout.isEmpty()) {
                EmptyWidgets(onOpenWidgets)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    layout.forEach { slot -> MetricWidgetCard(slot.type, fleetWeek, onClick = { onOpenWidgetDetail(slot.type) }) }
                }
            }

            SectionLabel("Данные по машинам")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinkCard("Таблицы", "Значения по времени", Icons.Outlined.TableChart, onOpenTables, Modifier.weight(1f))
                LinkCard("Графики", "Линии и аномалии", Icons.Outlined.BarChart, onOpenCharts, Modifier.weight(1f))
            }

            ReportLinkCard(s.reportTime, s.voiceReportEnabled, onOpenReport)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Тёмно-синяя плашка шапки со скруглённым низом — как в макете 3.2: дата/колокольчик/аватар,
 * приветствие, статус-строка, свежесть данных, плитки статуса и кнопка «Все N машин» — всё на
 * акцентном фоне. Ниже неё экран возвращается на обычный кремовый фон.
 */
@Composable
private fun HeaderBand(
    vehicleCount: Int?,
    data: HomeData,
    s: ru.petrovich.telemetry.data.settings.AppSettings,
    unreadNotifications: Int,
    onOpenNotifications: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenFleet: () -> Unit,
) {
    val c = Petrovich.colors
    Column(
        Modifier.fillMaxWidth()
            .background(c.accent, RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(LocalDate.now().shortDate(), style = MaterialTheme.typography.labelMedium, color = c.onAccent.copy(alpha = .7f), modifier = Modifier.weight(1f))
            NotificationBell(unreadNotifications, onOpenNotifications)
            Spacer(Modifier.width(10.dp))
            AvatarP(onOpenChat)
        }
        Text(greeting(), fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = c.onAccent)
        Text(
            if (vehicleCount != null) "Парк сегодня · $vehicleCount ${plural(vehicleCount, "машина", "машины", "машин")}" else "Парк сегодня",
            style = MaterialTheme.typography.bodyMedium, color = c.onAccent.copy(alpha = .7f),
        )
        StatusLine(data)
        FreshnessLine(s.fleetUpdatedAt)
        Spacer(Modifier.height(8.dp))
        FleetTiles(s, onOpenFleet)
        Spacer(Modifier.height(10.dp))
        FleetButton(
            "Все ${vehicleCount ?: "—"} ${vehicleCount?.let { plural(it, "машина", "машины", "машин") } ?: "машин"} и их статус",
            onOpenFleet,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** Бордерная кнопка на синей плашке — тот же размер/форма, что у [SoftButton], но белая обводка на акцентном фоне. */
@Composable
private fun FleetButton(text: String, onClick: () -> Unit) {
    val c = Petrovich.colors
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = c.onAccent),
        border = BorderStroke(1.dp, c.onAccent.copy(alpha = .35f)),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp).padding(start = 6.dp))
    }
}

@Composable
private fun NotificationBell(count: Int, onClick: () -> Unit) {
    val c = Petrovich.colors
    // Бейдж — сосед круглой кнопки, а не её ребёнок: иначе clip(CircleShape) родителя обрезает
    // бейдж там, где он торчит за пределы вписанной окружности (у самого угла).
    Box(Modifier.size(40.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(c.onAccent.copy(alpha = .14f)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.NotificationsNone, "Уведомления", Modifier.size(20.dp), tint = c.onAccent) }
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp).size(16.dp).clip(CircleShape).background(c.high),
                contentAlignment = Alignment.Center,
            ) { Text(if (count > 9) "9+" else "$count", color = c.onAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** На синей шапке аватар инвертирован (белый кружок, тёмная буква) — иначе он сливается с фоном. */
@Composable
private fun AvatarP(onClick: () -> Unit) {
    val c = Petrovich.colors
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.onAccent).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text("П", color = c.accent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** Большая цветная строка статуса в шапке — не карточка, просто текст: «2 проблемы» / «Всё в порядке». */
@Composable
private fun StatusLine(data: HomeData) {
    val c = Petrovich.colors
    val (text, color) = when (data.tone) {
        BriefTone.GREEN -> "Всё в порядке" to c.ok
        BriefTone.NEUTRAL -> "Ещё не проверял данные" to c.muted
        BriefTone.RED -> "${data.pendingCount} ${plural(data.pendingCount, "проблема", "проблемы", "проблем")}" to c.high
        BriefTone.YELLOW -> "${data.pendingCount} ${plural(data.pendingCount, "проблема", "проблемы", "проблем")}" to c.med
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (data.tone != BriefTone.NEUTRAL) Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(text, fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = color)
    }
}

/** «данные на 09:12 · обновлено 2 мин назад» — честная метка свежести того же снимка, что и плитки статуса. */
@Composable
private fun FreshnessLine(updatedAtMillis: Long) {
    if (updatedAtMillis <= 0) return
    val dt = Instant.ofEpochMilli(updatedAtMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val minutes = Duration.between(dt, LocalDateTime.now()).toMinutes().coerceAtLeast(0)
    val ago = when {
        minutes < 1 -> "только что"
        minutes < 60 -> "обновлено $minutes мин назад"
        else -> "обновлено ${minutes / 60} ч назад"
    }
    Text("данные на ${dt.hhmm()} · $ago", style = MaterialTheme.typography.bodySmall, color = Petrovich.colors.onAccent.copy(alpha = .6f))
}

@Composable
private fun QuickActionsRow(pendingCount: Int, onAsk: () -> Unit, onReport: () -> Unit, onProblems: () -> Unit, onWidgets: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickAction(Icons.Filled.Mic, "Спросить", onAsk, Modifier.weight(1f))
        QuickAction(Icons.Filled.PlayArrow, "Доклад", onReport, Modifier.weight(1f))
        QuickAction(Icons.Outlined.WarningAmber, "Проблемы", onProblems, Modifier.weight(1f), badge = pendingCount.takeIf { it > 0 })
        QuickAction(Icons.Filled.Add, "Сводка", onWidgets, Modifier.weight(1f))
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier, badge: Int? = null) {
    val c = Petrovich.colors
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(52.dp)) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(22.dp), tint = c.ink)
            }
            if (badge != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(c.high),
                    contentAlignment = Alignment.Center,
                ) { Text(if (badge > 9) "9+" else "$badge", color = c.onAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.ink, maxLines = 1)
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
 * «На линии / стоят / без связи» — те же три плашки, что в макете, посчитанные по телеметрии
 * с последней проверки (см. [ru.petrovich.telemetry.anomaly.classifyActivity]), а не живой статус
 * трекера: обновляются вместе с проверкой аномалий, не постоянно.
 *
 * Макет (3.2) просит открывать «Парк» с фильтром по нажатой плитке; маршрут «Парк», которым
 * управляет интегратор (AppRoot.kt, не мой файл), фильтра пока не принимает — поэтому здесь плитки
 * ведут в «Парк» без фильтра через тот же [onOpenFleet], что и кнопка «Все N машин», а не никуда.
 */
@Composable
private fun FleetTiles(settings: ru.petrovich.telemetry.data.settings.AppSettings, onOpenFleet: () -> Unit) {
    val c = Petrovich.colors
    val known = settings.fleetUpdatedAt > 0
    val dash = "—"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FleetTile(
            "на линии", if (known) "${settings.fleetOnline}" else dash,
            if (known && settings.fleetOnline > 0) c.onAccent.copy(alpha = .12f) to c.ok else null,
            Modifier.weight(1f), onOpenFleet,
        )
        FleetTile("стоят", if (known) "${settings.fleetIdle}" else dash, null, Modifier.weight(1f), onOpenFleet)
        FleetTile(
            "без связи", if (known) "${settings.fleetOffline}" else dash,
            if (known && settings.fleetOffline > 0) c.onAccent.copy(alpha = .12f) to c.med else null,
            Modifier.weight(1f), onOpenFleet,
        )
    }
}

@Composable
private fun FleetTile(label: String, value: String, accent: Pair<Color, Color>?, modifier: Modifier, onClick: () -> Unit) {
    val c = Petrovich.colors
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(accent?.first ?: c.onAccent.copy(alpha = .12f)).clickable(onClick = onClick).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = accent?.second ?: c.onAccent)
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.onAccent.copy(alpha = .75f))
    }
}

@Composable
private fun ReportLinkCard(reportTime: String, voiceEnabled: Boolean, onClick: () -> Unit) {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth(), onClick = onClick, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Доклад на $reportTime", style = MaterialTheme.typography.titleSmall)
                Text(if (voiceEnabled) "Читать или слушать" else "Читать", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            Box(Modifier.size(48.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, tint = c.accent)
            }
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
            AddWidgetButton("+ Добавить виджет", onOpenWidgets)
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

// ---------- виджеты «Сводки» (3.2/3.16): категория, число, изменение к прошлой неделе, мини-график ----------

@Composable
private fun MetricWidgetCard(type: WidgetType, fleetWeek: FleetWeek?, onClick: () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth(), onClick = onClick, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (type == WidgetType.OFFLINE) {
                val offline = fleetWeek?.offline()
                WidgetHeader(type, "сейчас")
                BigNumber(offline?.count?.toString() ?: "—")
                Sub(
                    when {
                        offline == null -> "Загружаю…"
                        offline.count == 0 -> "Все машины на связи"
                        else -> offline.silent.joinToString("; ") { "${it.name} — ${it.status}" }
                    },
                )
            } else {
                val summary = fleetWeek?.summary(type)
                WidgetHeader(type, "неделя")
                BigNumber(summary?.let { "${formatCount(it.total)}${if (it.unit.isNotEmpty()) " ${it.unit}" else ""}" } ?: "—")
                val change = summary?.changePercent
                Sub(
                    when {
                        summary == null -> "Загружаю…"
                        change == null -> "Нет данных за прошлую неделю для сравнения"
                        else -> "${if (change >= 0) "↑" else "↓"} ${formatCount(kotlin.math.abs(change))}% к прошлой неделе"
                    },
                )
                summary?.points?.let { WeekBars(it) }
            }
        }
    }
}

@Composable
private fun WidgetHeader(type: WidgetType, periodLabel: String) {
    val c = Petrovich.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(type.icon, null, Modifier.size(18.dp), tint = c.muted)
        Spacer(Modifier.width(8.dp))
        Text(type.title, style = MaterialTheme.typography.labelMedium, color = c.muted, modifier = Modifier.weight(1f))
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

/** Мини-столбчатый график за неделю — последний (сегодняшний) столбец выделен акцентным цветом. */
@Composable
private fun WeekBars(points: List<WidgetPoint>) {
    val c = Petrovich.colors
    val max = (points.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(0.01)
    Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
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
