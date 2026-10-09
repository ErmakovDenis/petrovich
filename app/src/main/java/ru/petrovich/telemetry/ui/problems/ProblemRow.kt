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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.PrimaryButton
import ru.petrovich.telemetry.ui.common.SeverityStripe
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.ui.common.resolutionLabel
import ru.petrovich.telemetry.ui.common.softColor
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.theme.Petrovich
import java.time.LocalDate

/** Иконка по типу события — честно по тому же правилу, что завело аномалию (см. BaselineAnomalyDetector). */
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

/** «X452 · сегодня, 03:40» — как в макете 3.5; без точного времени честно показываем сырое значение. */
private fun Anomaly.timeLabel(): String {
    val t = time ?: return eventTime
    val day = t.toLocalDate()
    val today = LocalDate.now()
    val dayText = when (day) {
        today -> "сегодня"
        today.minusDays(1) -> "вчера"
        else -> day.title()
    }
    return "$dayText, ${t.hhmm()}"
}

/** Первая фраза длинного честного описания — для короткой «подробности» под заголовком карточки. */
private fun String.oneLine(): String = substringBefore(" — ").substringBefore(". ").trimEnd('.')

/**
 * Статус-метка по единому словарю макета (4. Общие элементы): «Срочно» — красная, «Внимание» — жёлтая,
 * «Закрыто» — зелёная, «В работе» и «Ложная тревога» — серые. «Подтверждено» в словаре нет — закрытая
 * подтверждённая аномалия — это «Закрыто». Молчащий датчик показываем как «В работе»: это не разовое
 * срочное событие, а продолжающаяся проблема, как и в макете карточки события.
 */
@Composable
private fun StatusPill(a: Anomaly, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    val (text, color, soft) = when {
        a.kind == "nodata" -> Triple("В работе", c.muted, c.surface2)
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
 * Карточка неразобранной проблемы — 1:1 с макетом 3.5: иконка, машина и время, метка, вывод одной фразой,
 * подробность и кнопка на всю ширину («Разберись» для срочных, «Посмотреть» — для остальных).
 * Кликабельна целиком — открывает карточку события (3.6), как требует правило «Кликабельность».
 */
@Composable
fun NewProblemCard(a: Anomaly, onOpen: () -> Unit, onAsk: () -> Unit, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    val urgent = a.severity == Severity.CRITICAL
    SurfaceCard(modifier.fillMaxWidth(), onClick = onOpen, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(a.severity.softColor()), contentAlignment = Alignment.Center) {
                    Icon(a.icon(), null, Modifier.size(19.dp), tint = a.severity.color())
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${a.vehicleName} · ${a.timeLabel()}", style = MaterialTheme.typography.labelMedium, color = c.muted)
                    StatusPill(a)
                }
            }
            Text(a.title, style = MaterialTheme.typography.titleMedium)
            Text(
                a.description.oneLine(), style = MaterialTheme.typography.bodyMedium, color = c.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (urgent) PrimaryButton("Разберись", onOpen, Modifier.fillMaxWidth())
            else SoftButton("Посмотреть", onOpen, Modifier.fillMaxWidth())
        }
    }
}

/** Строка для вкладок «В работе» и «Закрыты»: статус-точка, заголовок, кто/что решил. */
@Composable
fun ProblemRow(a: Anomaly, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    val closed = a.resolution == Resolution.CONFIRMED || a.resolution == Resolution.FALSE_ALARM
    SurfaceCard(modifier = modifier.fillMaxWidth().alpha(if (closed) .72f else 1f), onClick = onClick, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.height(IntrinsicSize.Min).padding(end = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SeverityStripe(a.severity.color(), Modifier.fillMaxHeight().width(5.dp))
            Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.vehicleName, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (a.resolution) {
                    // «В работе» — серый текст, как «Передано механику · 27.09» во 2-й (вспомогательной) версии макета.
                    Resolution.IN_PROGRESS -> Text(
                        resolutionLabel(a) ?: "В работе", style = MaterialTheme.typography.labelSmall,
                        color = c.muted, fontWeight = FontWeight.SemiBold,
                    )
                    // «Подтверждено» нет в словаре статусов макета (4. Общие элементы) — закрытая аномалия это «Закрыто», зелёным.
                    Resolution.CONFIRMED -> Pill("Закрыто", c.ok, c.okSoft)
                    Resolution.FALSE_ALARM -> Pill("Ложная тревога" + (a.falseAlarmReason?.let { " · $it" } ?: ""), c.muted, c.surface2)
                    null -> {}
                }
            }
            a.time?.let {
                Text(it.hhmm(), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelSmall, color = c.faint, fontWeight = FontWeight.Medium)
            }
        }
    }
}
