package ru.petrovich.telemetry.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.petrovich.telemetry.anomaly.Anomaly
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.SeverityStripe
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.theme.Petrovich

/** Строка аномалии: цветная полоска срочности, что случилось, на какой машине, когда и что решено. */
@Composable
fun AnomalyRow(a: Anomaly, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    SurfaceCard(modifier = modifier.fillMaxWidth().alpha(if (a.isNew) 1f else .62f), onClick = onClick, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.height(IntrinsicSize.Min).padding(end = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SeverityStripe(a.severity.color(), Modifier.fillMaxHeight().width(5.dp))
            Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(a.title, style = MaterialTheme.typography.titleSmall)
                Text(a.vehicleName, style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.category.title, style = MaterialTheme.typography.labelSmall, color = c.faint)
            }
            Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
                when (a.resolution) {
                    Resolution.CONFIRMED -> Pill("Подтверждено", c.high, c.highSoft)
                    Resolution.FALSE_ALARM -> Pill("Ложная", c.muted, c.surface2)
                    null -> {}
                }
                a.time?.let { Text(it.hhmm(), style = MaterialTheme.typography.labelSmall, color = c.faint, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
