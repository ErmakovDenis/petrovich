package ru.petrovich.telemetry.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.plural
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import kotlin.math.abs

/**
 * «Подробная сводка виджета» (3.15): раскрывает цифру с «Главной» — как менялась и за счёт каких машин.
 * У «Без связи» выбора периода нет — там всегда «сейчас» (см. page-17/page-18 макета).
 *
 * @param onOpenVehicle тап по машине-лидеру → карточка машины (3.13, ui/park/VehicleCardScreen — не мой файл);
 * по умолчанию ничего не делает, если вызывающий экран не передал реальный переход.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetDetailScreen(
    type: WidgetType,
    onBack: () -> Unit,
    onAsk: () -> Unit,
    onOpenVehicle: (String) -> Unit = {},
) {
    val c = Petrovich.colors
    val scope = rememberCoroutineScope()
    var period by remember { mutableStateOf(WidgetPeriod.WEEK) }
    var summary by remember(type) { mutableStateOf<WidgetSummary?>(null) }
    var offline by remember(type) { mutableStateOf<OfflineSummary?>(null) }
    var removed by remember { mutableStateOf(false) }

    LaunchedEffect(type, period) {
        summary = null
        offline = null
        if (type == WidgetType.OFFLINE) {
            offline = runCatchingCancellable { loadFleetWeek().offline() }.getOrNull()
        } else {
            summary = runCatchingCancellable { loadWidgetSummary(type, period) }.getOrNull()
        }
    }

    Scaffold(topBar = { AppTopBar(type.title, onBack = onBack) }, containerColor = c.bg) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (type != WidgetType.OFFLINE) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    WidgetPeriod.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = p == period,
                            onClick = { period = p },
                            shape = SegmentedButtonDefaults.itemShape(i, WidgetPeriod.entries.size),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = c.surface, activeContentColor = c.ink, activeBorderColor = c.line,
                                inactiveContainerColor = c.surface2, inactiveContentColor = c.muted, inactiveBorderColor = c.line,
                            ),
                            label = { Text(p.title) },
                        )
                    }
                }
            } else {
                Text("сейчас", style = MaterialTheme.typography.labelMedium, color = c.muted)
            }

            if (type == WidgetType.OFFLINE) {
                val o = offline
                if (o == null) Loading() else {
                    SurfaceCard(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${o.count}", fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp)
                            Text(if (o.count == 0) "Все машины на связи" else "без связи сейчас", style = MaterialTheme.typography.bodyMedium, color = c.muted)
                        }
                    }
                    if (o.silent.isNotEmpty()) {
                        SectionLabel("Молчат")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            o.silent.forEach { entry -> LeaderRow(entry.name, entry.status, onClick = { onOpenVehicle(entry.vehicleId) }) }
                        }
                    }
                    CommentCard(if (o.count == 0) "Все машины на связи — молчащих нет." else "${o.count} ${plural(o.count, "машина", "машины", "машин")} не передают данные дольше 45 минут.")
                }
            } else {
                val s = summary
                if (s == null) Loading() else {
                    SurfaceCard(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column {
                                Text(
                                    "${formatCount(s.total)}${if (s.unit.isNotEmpty()) " ${s.unit}" else ""}",
                                    fontFamily = Petrovich.display, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp,
                                )
                                Text(
                                    s.changePercent?.let { "${if (it >= 0) "↑" else "↓"} ${formatCount(abs(it))}% к прошлому периоду" }
                                        ?: "Нет данных за прошлый период для сравнения",
                                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                                )
                            }
                            DetailChart(s.points)
                        }
                    }
                    if (s.leaders.isNotEmpty()) {
                        SectionLabel("Больше всего")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.leaders.forEach { leader ->
                                LeaderRow(leader.name, "${formatCount(leader.value)}${if (s.unit.isNotEmpty()) " ${s.unit}" else ""}", onClick = { onOpenVehicle(leader.vehicleId) })
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
private fun Loading() {
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Petrovich.colors.accent)
    }
}

@Composable
private fun DetailChart(points: List<WidgetPoint>) {
    val c = Petrovich.colors
    val max = (points.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(0.01)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(90.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            points.forEachIndexed { i, p ->
                Box(
                    Modifier.weight(1f)
                        .fillMaxHeight((p.value / max).toFloat().coerceIn(0.03f, 1f))
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(if (i == points.lastIndex) c.accent else c.accentSoft),
                )
            }
        }
        if (points.size <= 10) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                points.forEach { p -> Text(p.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun LeaderRow(name: String, value: String, onClick: () -> Unit) {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth(), onClick = onClick, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
            Text(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = c.muted)
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
    val leader = s.leaders.firstOrNull()?.let { " Больше всего у ${it.name} — ${formatCount(it.value)}${if (s.unit.isNotEmpty()) " ${s.unit}" else ""}." } ?: ""
    val change = s.changePercent
    return when {
        change == null -> "Данных за прошлый период пока нет — сравнивать не с чем.$leader"
        abs(change) < 10 -> "Без резких скачков, разница ${formatCount(abs(change))}%.$leader"
        change > 0 -> "Рост на ${formatCount(change)}% к прошлому периоду. Это не обязательно проблема, но я слежу.$leader"
        else -> "Снижение на ${formatCount(abs(change))}% к прошлому периоду.$leader"
    }
}
