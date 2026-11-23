package ru.petrovich.telemetry.ui.problems

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.OilBarrel
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.SeverityStripe
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.headline
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.ui.common.resolutionLabel
import ru.petrovich.telemetry.ui.common.softColor
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.LocalDate

/** Иконка по типу события — честно по тому же правилу, что завело аномалию (см. BaselineAnomalyDetector). */
fun Anomaly.kindIcon(): ImageVector = when (kind) {
    "drain", "drop", "fuel_drain" -> Icons.Outlined.LocalGasStation
    "power" -> Icons.Outlined.ElectricBolt
    "volt" -> Icons.Outlined.BatteryAlert
    "overheat" -> Icons.Outlined.Thermostat
    "oil" -> Icons.Outlined.OilBarrel
    "brake" -> Icons.Outlined.Compress
    "towing", "speed_without_movement" -> Icons.Outlined.ReportProblem
    "nodata", "data_gap", "data_gap_channel_unavailable", "data_gap_all_channels_unavailable",
    "data_gap_signal_unavailable", "data_gap_gps_unavailable", "telemetry_gap",
    -> Icons.Outlined.WifiOff
    else -> Icons.Outlined.ReportProblem
}

/** «сегодня, 03:40» / «вчера» — для строки важности и времени. */
fun Anomaly.whenLabel(): String {
    val t = time ?: return eventTime
    val day = t.toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> t.hhmm()
        today.minusDays(1) -> "вчера"
        else -> day.title()
    }
}

/** Первая фраза описания — короткий контекст под заголовком. */
private fun String.oneLine(): String = substringBefore(" — ").substringBefore(". ").trimEnd('.')

@Composable
private fun StatusPill(a: Anomaly, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    val (text, color, soft) = when {
        a.kind == "nodata" || a.kind.startsWith("data_gap") || a.kind == "telemetry_gap" ->
            Triple("В работе", c.muted, c.surface2)
        a.resolution == Resolution.IN_PROGRESS -> Triple(resolutionLabel(a) ?: "В работе", c.muted, c.surface2)
        a.resolution == Resolution.CONFIRMED -> Triple("Закрыто", c.ok, c.okSoft)
        a.resolution == Resolution.FALSE_ALARM -> Triple(
            "Ложная тревога" + (a.falseAlarmReason?.let { " · $it" } ?: ""), c.muted, c.surface2,
        )
        else -> Triple(a.severity.label(), a.severity.color(), a.severity.softColor())
    }
    Pill(text, color, soft, modifier)
}

/**
 * Карточка новой проблемы по карте экранов: «Срочно · 06:40», headline с цифрой, машина · контекст.
 */
@Composable
fun NewProblemCard(a: Anomaly, onOpen: () -> Unit, onAsk: () -> Unit, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    SurfaceCard(modifier.fillMaxWidth(), onClick = onOpen, shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(a.severity.softColor()), contentAlignment = Alignment.Center) {
                Icon(a.kindIcon(), null, Modifier.size(20.dp), tint = a.severity.color())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${a.severity.label()} · ${a.whenLabel()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = a.severity.color(),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(a.headline(), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(a.vehicleName, a.description.oneLine().takeIf { it.isNotBlank() && it != a.title }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = c.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Строка для вкладок «В работе» и «Закрыты». */
@Composable
fun ProblemRow(a: Anomaly, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    val closed = a.resolution == Resolution.CONFIRMED || a.resolution == Resolution.FALSE_ALARM
    SurfaceCard(modifier = modifier.fillMaxWidth().alpha(if (closed) .72f else 1f), onClick = onClick, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.height(IntrinsicSize.Min).padding(end = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SeverityStripe(a.severity.color(), Modifier.fillMaxHeight().width(5.dp))
            Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(a.headline(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(a.vehicleName, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (a.resolution) {
                    Resolution.IN_PROGRESS -> Text(
                        resolutionLabel(a) ?: "В работе", style = MaterialTheme.typography.labelSmall,
                        color = c.muted, fontWeight = FontWeight.SemiBold,
                    )
                    Resolution.CONFIRMED -> Pill("Закрыто", c.ok, c.okSoft)
                    Resolution.FALSE_ALARM -> Pill(
                        "Ложная тревога" + (a.falseAlarmReason?.let { " · $it" } ?: ""), c.muted, c.surface2,
                    )
                    null -> {}
                }
            }
            a.time?.let {
                Text(it.hhmm(), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelSmall, color = c.faint, fontWeight = FontWeight.Medium)
            }
        }
    }
}
