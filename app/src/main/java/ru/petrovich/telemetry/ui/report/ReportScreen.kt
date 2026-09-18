package ru.petrovich.telemetry.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.plural
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.feed.AnomalyRow
import ru.petrovich.telemetry.ui.home.AnomalyOrder
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.LocalDateTime

/** Отчёт за последние 24 часа: сводка словами и все случаи списком. */
@Composable
fun ReportScreen(onBack: () -> Unit, onOpenCard: (String) -> Unit, onAsk: () -> Unit) {
    val all by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val c = Petrovich.colors
    val list = remember(all) {
        val since = LocalDateTime.now().minusHours(24)
        all.filter { a -> a.resolution != Resolution.FALSE_ALARM && a.time?.let { it.isAfter(since) } == true }.sortedWith(AnomalyOrder)
    }
    val vehicles = list.map { it.vehicleId }.distinct().size

    Scaffold(topBar = { AppTopBar("Отчёт за сутки", onBack = onBack) }, containerColor = c.bg) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SurfaceCard(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Последние 24 часа", style = MaterialTheme.typography.labelMedium, color = c.muted)
                    Column {
                        Text("${list.size}", fontFamily = Petrovich.display, fontWeight = FontWeight.Bold, fontSize = 28.sp)
                        Text(plural(list.size, "аномалия", "аномалии", "аномалий"), style = MaterialTheme.typography.labelMedium, color = c.muted)
                    }
                    Text(summary(list.size, vehicles, list.firstOrNull()?.let { "${it.vehicleName} — ${it.title.lowercase()}" }, list.count { it.resolution == null }),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (list.isNotEmpty()) {
                SectionLabel("Все случаи")
                list.forEach { a -> AnomalyRow(a, onClick = { onOpenCard(a.id) }) }
            }
            SoftButton("Спросить у Петровича", onAsk, Modifier.fillMaxWidth(), container = c.ink, content = c.bg, leading = Icons.Outlined.ChatBubbleOutline)
        }
    }
}

private fun summary(count: Int, vehicles: Int, top: String?, pending: Int): String {
    if (count == 0) return "За сутки замечаний нет: ни сливов топлива, ни перегрева, ни других отклонений."
    return buildString {
        append("За сутки найдено $count ${plural(count, "аномалия", "аномалии", "аномалий")} на $vehicles ${plural(vehicles, "машине", "машинах", "машинах")}. ")
        if (top != null) append("Главное: $top. ")
        append(if (pending == 0) "Всё уже разобрано." else "Ждут решения: $pending.")
    }
}
