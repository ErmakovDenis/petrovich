package ru.petrovich.telemetry.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import ru.petrovich.telemetry.anomaly.Anomaly
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.MetricCategory
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Instant
import java.time.ZoneId
import androidx.compose.runtime.LaunchedEffect
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.dashedBorder
import ru.petrovich.telemetry.ui.common.plural
import ru.petrovich.telemetry.ui.common.weekdayTitle
import ru.petrovich.telemetry.ui.feed.FeedFilter
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    vehicleCount: Int?,
    onOpenFeed: (FeedFilter) -> Unit,
    onOpenCard: (String) -> Unit,
    onOpenReport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTables: () -> Unit,
    onOpenCharts: () -> Unit,
) {
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val speaker = rememberSpeaker()
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(false) }
    var speechHint by remember { mutableStateOf<String?>(null) }

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
    val layout = s.layout
    fun saveLayout(new: List<WidgetSlot>) {
        scope.launch { ServiceLocator.settings.update { it.copy(widgetLayout = serializeLayout(new)) } }
    }

    val name = when {
        s.demoMode -> "Демо-парк"
        s.schemaName.isNotBlank() -> s.schemaName
        else -> "Автопарк"
    }
    val subtitle = buildString {
        append(LocalDate.now().weekdayTitle())
        if (vehicleCount != null && vehicleCount > 0) append(" · $vehicleCount ${plural(vehicleCount, "машина", "машины", "машин")}")
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Petrovich.colors.muted)
                Text(name, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1)
            }
            TuneButton(edit) { edit = !edit }
            if (!edit) IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, "Настройки")
            }
        }

        if (!edit) {
            BriefCard(
                data = data,
                lastScanAt = s.lastScanAt,
                scanning = scanning,
                scanError = scanError,
                onScan = ::scan,
                speaking = speaker.speaking,
                onListen = {
                    if (speaker.speaking) speaker.stop()
                    else {
                        speaker.speak(data.speech())
                        speechHint = if (speaker.available) null else "На устройстве нет русского синтезатора речи"
                    }
                },
                onReport = onOpenReport,
            )
            speechHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Petrovich.colors.muted) }
        } else {
            Text(
                "Меняйте порядок стрелками, размер — кнопкой, лишнее уберите крестиком. Доклад Петровича закреплён сверху.",
                style = MaterialTheme.typography.bodySmall, color = Petrovich.colors.muted,
            )
        }

        WidgetGrid(
            layout = layout,
            data = data,
            edit = edit,
            onOpen = { type ->
                when (type) {
                    WidgetType.DECISIONS -> onOpenFeed(FeedFilter.NEW)
                    WidgetType.URGENT -> onOpenFeed(FeedFilter.HIGH)
                    WidgetType.VEHICLES -> onOpenTables()
                    WidgetType.FUEL -> onOpenCharts()
                    else -> onOpenFeed(FeedFilter.ALL)
                }
            },
            onOpenCard = onOpenCard,
            onMove = { i, d ->
                val j = i + d
                if (j in layout.indices) saveLayout(layout.toMutableList().also { it[i] = layout[j]; it[j] = layout[i] })
            },
            onResize = { i ->
                val slot = layout[i]
                val sizes = slot.type.sizes
                saveLayout(layout.toMutableList().also { it[i] = slot.copy(size = sizes[(sizes.indexOf(slot.size) + 1) % sizes.size]) })
            },
            onRemove = { i -> saveLayout(layout.filterIndexed { k, _ -> k != i }) },
        )

        if (edit) {
            AddWidgetButton("+ Добавить виджет") { catalog = true }
            TextButton(onClick = { saveLayout(DefaultLayout) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Вернуть как было", color = Petrovich.colors.accent)
            }
        } else {
            SectionLabel("Данные по машинам")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinkCard("Таблицы", "Значения по времени", Icons.Outlined.TableChart, onOpenTables, Modifier.weight(1f))
                LinkCard("Графики", "Линии и аномалии", Icons.Outlined.BarChart, onOpenCharts, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (catalog) {
        WidgetCatalog(
            layout = layout,
            onAdd = { type ->
                saveLayout(layout + WidgetSlot(type, type.sizes.first()))
                catalog = false
            },
            onDismiss = { catalog = false },
        )
    }
}

@Composable
private fun TuneButton(edit: Boolean, onClick: () -> Unit) {
    val c = Petrovich.colors
    Row(
        Modifier.clip(RoundedCornerShape(20.dp))
            .background(if (edit) c.ink else c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!edit) Icon(Icons.Filled.Tune, null, Modifier.size(16.dp), tint = c.ink)
        Text(if (edit) "Готово" else "Настроить", color = if (edit) c.bg else c.ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BriefCard(
    data: HomeData,
    lastScanAt: Long,
    scanning: Boolean,
    scanError: String?,
    onScan: () -> Unit,
    speaking: Boolean,
    onListen: () -> Unit,
    onReport: () -> Unit,
) {
    val c = Petrovich.colors
    val (bg, dot) = when (data.tone) {
        BriefTone.RED -> c.highSoft to c.high
        BriefTone.YELLOW -> c.medSoft to c.med
        BriefTone.GREEN -> c.okSoft to c.ok
        BriefTone.NEUTRAL -> c.surface2 to c.faint
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(bg).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp)) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Text("П", color = c.onAccent, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Box(Modifier.align(Alignment.BottomEnd).size(13.dp).clip(CircleShape).background(c.bg).padding(2.dp).clip(CircleShape).background(dot))
            }
            Column {
                Text("Петрович докладывает", style = MaterialTheme.typography.labelLarge, fontSize = 13.sp)
                Text(
                    if (lastScanAt > 0) "проверено в ${Instant.ofEpochMilli(lastScanAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale("ru")))}"
                    else "данные ещё не проверялись",
                    style = MaterialTheme.typography.labelSmall, color = c.muted,
                )
            }
        }
        Text(if (data.tone == BriefTone.NEUTRAL && scanning) "Проверяю данные…" else data.verdict, fontSize = 21.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, color = c.ink)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (data.tone == BriefTone.NEUTRAL) {
                BriefLine(buildAnnotatedString {
                    append(scanError?.let { "Не удалось получить данные: $it" } ?: "Петрович проверит парк и расскажет, что нашёл.")
                })
            } else if (data.tone == BriefTone.GREEN) {
                BriefLine(buildAnnotatedString {
                    if (data.vehicleCount != null && data.vehicleCount > 0) {
                        append("Все "); bold("${data.vehicleCount} ${plural(data.vehicleCount, "машина", "машины", "машин")}"); append(" без замечаний.")
                    } else append("Замечаний нет.")
                })
                BriefLine(buildAnnotatedString { append("Слива топлива, перегрева и других отклонений не найдено.") })
            } else {
                data.topLines.forEach { (vehicle, what) ->
                    BriefLine(buildAnnotatedString { bold(vehicle); append(" — $what") })
                }
                if (data.moreCount > 0) BriefLine(buildAnnotatedString { append("И ещё ${data.moreCount} — в ленте.") })
            }
        }
        if (data.tone == BriefTone.NEUTRAL) {
            PrimaryButton(if (scanning) "Проверяю…" else "Проверить сейчас", onScan, Modifier.fillMaxWidth(), enabled = !scanning)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(16.dp)).background(c.accent).clickable(onClick = onListen),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
            ) {
                Icon(if (speaking) Icons.Filled.Stop else Icons.Filled.PlayArrow, null, Modifier.size(20.dp), tint = c.onAccent)
                Spacer(Modifier.width(8.dp))
                Text(if (speaking) "Остановить" else "Слушать", color = c.onAccent, style = MaterialTheme.typography.labelLarge, fontSize = 16.sp)
            }
            Box(
                Modifier.height(50.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).clickable(onClick = onReport).padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Отчёт", style = MaterialTheme.typography.labelLarge, fontSize = 16.sp) }
        }
    }
}

private fun AnnotatedString.Builder.bold(text: String) =
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text) }

@Composable
private fun BriefLine(text: AnnotatedString) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.padding(top = 9.dp).size(6.dp).clip(CircleShape).background(Petrovich.colors.ink.copy(alpha = .4f)))
        Text(text, style = MaterialTheme.typography.bodyMedium)
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

// ---------- виджеты ----------

@Composable
private fun WidgetGrid(
    layout: List<WidgetSlot>,
    data: HomeData,
    edit: Boolean,
    onOpen: (WidgetType) -> Unit,
    onOpenCard: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onResize: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    // Маленькие виджеты встают по два в ряд, широкие — на всю ширину.
    val rows = mutableListOf<List<Int>>()
    var pending: Int? = null
    layout.forEachIndexed { i, slot ->
        if (slot.size == WidgetSize.L) {
            pending?.let { rows += listOf(it); pending = null }
            rows += listOf(i)
        } else if (pending == null) pending = i
        else { rows += listOf(pending!!, i); pending = null }
    }
    pending?.let { rows += listOf(it) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { i ->
                    val slot = layout[i]
                    val mod = if (slot.size == WidgetSize.L) Modifier.fillMaxWidth() else Modifier.weight(1f)
                    WidgetCard(
                        slot = slot, data = data, edit = edit,
                        canUp = i > 0, canDown = i < layout.lastIndex,
                        onOpen = { onOpen(slot.type) }, onOpenCard = onOpenCard,
                        onMove = { d -> onMove(i, d) }, onResize = { onResize(i) }, onRemove = { onRemove(i) },
                        modifier = mod,
                    )
                }
                if (row.size == 1 && layout[row[0]].size == WidgetSize.S) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun WidgetCard(
    slot: WidgetSlot,
    data: HomeData,
    edit: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onOpen: () -> Unit,
    onOpenCard: (String) -> Unit,
    onMove: (Int) -> Unit,
    onResize: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val c = Petrovich.colors
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).heightIn(min = 80.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WidgetBody(slot, data, onOpenCard, interactive = !edit)
            if (edit) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditButton(Icons.Filled.ArrowUpward, "Выше", canUp, Modifier.weight(1f)) { onMove(-1) }
                    EditButton(Icons.Filled.ArrowDownward, "Ниже", canDown, Modifier.weight(1f)) { onMove(1) }
                    if (slot.type.sizes.size > 1) {
                        EditButton(
                            if (slot.size == WidgetSize.L) Icons.Outlined.ViewAgenda else Icons.Outlined.ViewStream,
                            "Размер", true, Modifier.weight(1f), onClick = onResize,
                        )
                    }
                    EditButton(Icons.Filled.Close, "Убрать", true, Modifier.weight(1f), container = c.highSoft, tint = c.high, onClick = onRemove)
                }
            }
        }
    }
    if (edit) SurfaceCard(modifier = modifier, shape = RoundedCornerShape(18.dp), content = body, color = c.surface)
    else SurfaceCard(modifier = modifier, onClick = onOpen, content = body)
}

@Composable
private fun EditButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    modifier: Modifier,
    container: Color = Petrovich.colors.surface2,
    tint: Color = Petrovich.colors.ink,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(container)
            .let { if (enabled) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(18.dp), tint = if (enabled) tint else tint.copy(alpha = .3f))
    }
}

@Composable
private fun WidgetTitle(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = Petrovich.colors.muted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
        trailing?.invoke()
    }
}

@Composable
private fun BigNumber(text: String, color: Color = Petrovich.colors.ink) {
    Text(text, fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 26.sp, color = color)
}

@Composable
private fun Sub(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = Petrovich.colors.muted)

@Composable
private fun WidgetBody(slot: WidgetSlot, d: HomeData, onOpenCard: (String) -> Unit, interactive: Boolean) {
    val c = Petrovich.colors
    val large = slot.size == WidgetSize.L
    when (slot.type) {
        WidgetType.DECISIONS -> {
            WidgetTitle("Ждут решения")
            BigNumber("${d.pendingCount}")
            Sub(if (d.pendingCount == 0) "Всё разобрано" else "из них срочных: ${d.urgentCount}")
            if (large && d.pending.isNotEmpty()) MiniList(d.pending.take(3), onOpenCard, interactive)
        }
        WidgetType.URGENT -> {
            WidgetTitle("Срочные случаи") {
                Pill(if (d.urgentCount == 0) "нет" else "${d.urgentCount}", if (d.urgentCount == 0) c.ok else c.high, if (d.urgentCount == 0) c.okSoft else c.highSoft)
            }
            if (d.urgent.isEmpty()) Sub("Срочного ничего нет — можно не отвлекаться.")
            else MiniList(d.urgent.take(3), onOpenCard, interactive)
        }
        WidgetType.WEEK -> {
            WidgetTitle("Аномалии за неделю") { Text("${d.firstDay.dayOfMonth}–${d.today.dayOfMonth} ${shortMonth(d.today)}", style = MaterialTheme.typography.labelSmall, color = c.muted) }
            BigNumber("${d.weekTotal}")
            if (large) Sparkline(d.perDay, d) else Sub("${d.perDay.last()} сегодня")
        }
        WidgetType.BY_CATEGORY -> {
            WidgetTitle("По разделам за неделю")
            val max = (d.byCategory.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            d.byCategory.forEach { (cat, n) -> HBar(cat.title, n, max, categoryColor(cat)) }
        }
        WidgetType.TOP_VEHICLES -> {
            WidgetTitle("Больше всего аномалий")
            if (d.topVehicles.isEmpty()) Sub("За неделю аномалий нет.")
            val max = (d.topVehicles.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            d.topVehicles.forEachIndexed { i, (name, n) -> HBar(name, n, max, listOf(c.high, c.med, c.low)[i]) }
        }
        WidgetType.VEHICLES -> {
            WidgetTitle("Машины")
            BigNumber(d.vehicleCount?.toString() ?: "—")
            Sub(if (d.vehiclesWithIssues == 0) "замечаний нет" else "с замечаниями: ${d.vehiclesWithIssues}")
        }
        WidgetType.FUEL -> {
            WidgetTitle("Топливо")
            BigNumber("${d.fuelWeek}", if (d.fuelWeek > 0) c.high else c.ink)
            Sub(if (large) "сливов и резких падений уровня за неделю" else "событий за неделю")
        }
    }
}

private fun shortMonth(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("MMM", Locale("ru"))).trimEnd('.')

@Composable
private fun categoryColor(cat: MetricCategory): Color = with(Petrovich.colors) {
    when (cat) {
        MetricCategory.FUEL -> high
        MetricCategory.POWER -> med
        MetricCategory.ENGINE -> low
        MetricCategory.MOTION -> accent
    }
}

@Composable
private fun MiniList(list: List<Anomaly>, onOpenCard: (String) -> Unit, interactive: Boolean) {
    Column {
        list.forEachIndexed { i, a ->
            Row(
                Modifier.fillMaxWidth().let { if (interactive) it.clickable { onOpenCard(a.id) } else it }.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(5.dp).height(32.dp).clip(RoundedCornerShape(3.dp)).background(a.severity.color()))
                Column(Modifier.weight(1f)) {
                    Text(a.title, style = MaterialTheme.typography.labelLarge, fontSize = 13.sp, maxLines = 1)
                    Text(a.vehicleName, style = MaterialTheme.typography.bodySmall, color = Petrovich.colors.muted, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun HBar(label: String, value: Int, max: Int, color: Color) {
    val c = Petrovich.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.surface2)) {
            if (value > 0) Box(Modifier.fillMaxWidth(value.toFloat() / max).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        }
        Text("$value", Modifier.width(28.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}

@Composable
private fun Sparkline(values: List<Int>, d: HomeData) {
    val c = Petrovich.colors
    val measurer = rememberTextMeasurer()
    val weekday = DateTimeFormatter.ofPattern("EEE d", Locale("ru"))
    val first = d.firstDay.format(weekday)
    val last = "${d.today.format(weekday)} · ${values.last()}"
    val labelStyle = TextStyle(fontSize = 10.sp, color = c.muted, fontFamily = MaterialTheme.typography.bodySmall.fontFamily)
    val lastStyle = labelStyle.copy(color = c.high, fontWeight = FontWeight.SemiBold)
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val left = 4.dp.toPx(); val right = size.width - 12.dp.toPx()
        val top = 8.dp.toPx(); val bottom = size.height - 18.dp.toPx()
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
        fun x(i: Int) = left + i * (right - left) / (values.size - 1)
        fun y(v: Int) = bottom - (bottom - top) * v / max
        drawLine(c.line, Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
        val area = Path().apply { addPath(line); lineTo(x(values.lastIndex), bottom); lineTo(x(0), bottom); close() }
        drawPath(area, c.accent.copy(alpha = .1f))
        drawPath(line, c.accent, style = Stroke(2.2.dp.toPx(), join = StrokeJoin.Round))
        drawCircle(c.high, 4.dp.toPx(), Offset(x(values.lastIndex), y(values.last())))
        drawText(measurer, first, Offset(left, size.height - 14.dp.toPx()), labelStyle)
        val m = measurer.measure(last, lastStyle)
        drawText(m, topLeft = Offset(right - m.size.width, size.height - 14.dp.toPx()))
    }
}

// ---------- каталог ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WidgetCatalog(layout: List<WidgetSlot>, onAdd: (WidgetType) -> Unit, onDismiss: () -> Unit) {
    val c = Petrovich.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Добавить на главный экран", style = MaterialTheme.typography.titleLarge)
            Text("Выберите цифры, которые хотите видеть каждый день.", style = MaterialTheme.typography.bodySmall, color = c.muted)
            WidgetType.entries.groupBy { it.group }.forEach { (group, types) ->
                SectionLabel(group)
                types.forEach { type ->
                    val on = layout.any { it.type == type }
                    SurfaceCard(shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(type.title, style = MaterialTheme.typography.labelLarge)
                                Text(type.description, style = MaterialTheme.typography.bodySmall, color = c.muted)
                            }
                            if (on) Text("✓ Есть", style = MaterialTheme.typography.labelMedium, color = c.muted)
                            else Box(
                                Modifier.clip(RoundedCornerShape(12.dp)).background(c.accent).clickable { onAdd(type) }.padding(horizontal = 12.dp, vertical = 8.dp),
                            ) { Text("Добавить", color = c.onAccent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }
    }
}
