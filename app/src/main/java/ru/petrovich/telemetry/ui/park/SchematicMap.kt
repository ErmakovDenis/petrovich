package ru.petrovich.telemetry.ui.park

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.petrovich.telemetry.data.GeoZone
import ru.petrovich.telemetry.data.RoutePoint
import ru.petrovich.telemetry.ui.common.Dot
import ru.petrovich.telemetry.ui.theme.Petrovich

/**
 * Схематичная карта (3.14): НЕ настоящая карта — ни один картографический сервис не подключён.
 * Рисуем сеткой плюс пунктирной геозоной базы и маршрутом по условным координатам 0..1,
 * ровно в стиле самого макета (см. примечание на стр. 20: «Карта в прототипе — схема»).
 */
@Composable
fun SchematicMap(
    base: GeoZone?,
    points: List<RoutePoint>,
    modifier: Modifier = Modifier,
    anomalyLabel: String? = null,
) {
    val c = Petrovich.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = c.muted, fontFamily = MaterialTheme.typography.labelSmall.fontFamily)
    val chipTextStyle = TextStyle(fontSize = 11.sp, color = c.onAccent, fontFamily = MaterialTheme.typography.labelSmall.fontFamily)

    Canvas(modifier.fillMaxWidth().aspectRatio(1.15f)) {
        val pad = 10.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        fun px(x: Float) = pad + x * w
        fun py(y: Float) = pad + y * h

        // Лёгкая сетка — просто текстура схемы, не данные.
        drawRect(c.surface2, topLeft = Offset.Zero, size = size)
        val step = 28.dp.toPx()
        var gx = 0f
        while (gx < size.width) { drawLine(c.line, Offset(gx, 0f), Offset(gx, size.height), 1f); gx += step }
        var gy = 0f
        while (gy < size.height) { drawLine(c.line, Offset(0f, gy), Offset(size.width, gy), 1f); gy += step }

        // Геозона базы — пунктирный прямоугольник.
        if (base != null) {
            val topLeft = Offset(px(base.x), py(base.y))
            val boxSize = androidx.compose.ui.geometry.Size(base.width * w, base.height * h)
            drawRoundRect(
                color = c.muted,
                topLeft = topLeft,
                size = boxSize,
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
            val label = measurer.measure(base.label, labelStyle)
            drawText(label, topLeft = Offset(topLeft.x, topLeft.y - label.size.height - 4.dp.toPx()))
        }

        // Маршрут: ломаная линия по точкам трека.
        if (points.size > 1) {
            val path = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(px(p.x), py(p.y)) else lineTo(px(p.x), py(p.y)) }
            }
            drawPath(path, c.accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // Точка выезда — зелёная; текущая/последняя точка — тёмная с кольцом.
        points.firstOrNull()?.let { drawCircle(c.ok, 6.dp.toPx(), Offset(px(it.x), py(it.y))) }
        points.lastOrNull()?.let {
            val center = Offset(px(it.x), py(it.y))
            drawCircle(c.ink, 9.dp.toPx(), center, style = Stroke(2.5.dp.toPx()))
            drawCircle(c.ink, 5.dp.toPx(), center)
        }

        // Метка аномалии (например, слив топлива) — красная точка с подписью-плашкой.
        if (anomalyLabel != null) {
            val anchor = points.lastOrNull() ?: base?.let { RoutePoint(it.x + it.width / 2, it.y + it.height / 2, points.firstOrNull()?.at ?: java.time.LocalDateTime.now()) }
            if (anchor != null) {
                val center = Offset(px(anchor.x), py(anchor.y))
                drawCircle(c.high, 6.dp.toPx(), center)
                val chip = measurer.measure(anomalyLabel, chipTextStyle)
                val chipPad = 6.dp.toPx()
                val chipTop = Offset(center.x + 10.dp.toPx(), center.y - chip.size.height / 2 - chipPad)
                drawRoundRect(
                    color = c.high,
                    topLeft = chipTop,
                    size = androidx.compose.ui.geometry.Size(chip.size.width + chipPad * 2, chip.size.height + chipPad * 1.4f),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                )
                drawText(chip, topLeft = Offset(chipTop.x + chipPad, chipTop.y + chipPad * 0.7f))
            }
        }
    }
}

/** Легенда под картой: ● выезд · ● сейчас · — маршрут — как в макете. */
@Composable
fun RouteLegend(modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendDot(c.ok, "выезд")
        LegendDot(c.ink, "сейчас")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(16.dp).height(2.dp).background(c.accent))
            Text("маршрут", style = MaterialTheme.typography.labelSmall, color = c.muted)
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Dot(color)
        Text(text, style = MaterialTheme.typography.labelSmall, color = Petrovich.colors.muted)
    }
}
