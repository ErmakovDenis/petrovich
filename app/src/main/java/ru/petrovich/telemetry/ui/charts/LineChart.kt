package ru.petrovich.telemetry.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.petrovich.telemetry.ui.common.TimeShort
import ru.petrovich.telemetry.ui.common.formatValue
import java.time.LocalDateTime
import kotlin.math.roundToInt

data class ChartMarker(val index: Int, val color: Color)

/**
 * Лёгкий линейный график на Canvas: сетка, подписи осей, маркеры аномалий,
 * просмотр значения касанием/перетаскиванием.
 */
@Composable
fun LineChart(
    times: List<LocalDateTime>,
    values: List<Double?>,
    unit: String?,
    lineColor: Color,
    markers: List<ChartMarker> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    var selected by remember(values) { mutableStateOf<Int?>(null) }

    val present = values.filterNotNull()
    if (present.isEmpty() || times.size < 2) {
        Text("Нет данных", style = MaterialTheme.typography.bodySmall, color = labelColor)
        return
    }
    var min = present.min()
    var max = present.max()
    if (max - min < 1e-6) { min -= 1; max += 1 }
    val pad = (max - min) * 0.08
    // Не уводим ось в минус, если сами данные неотрицательные (обороты, скорость и т.п.).
    min = if (min >= 0 && min - pad < 0) 0.0 else min - pad
    max += pad

    Column(modifier) {
        val sel = selected
        Text(
            if (sel != null) "${times[sel].format(TimeShort)} — ${values[sel].formatValue()} ${unit.orEmpty()}"
            else "Коснитесь графика, чтобы увидеть значение",
            style = MaterialTheme.typography.labelMedium,
            color = if (sel != null) MaterialTheme.colorScheme.onSurface else labelColor,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(values) {
                    val left = 44.dp.toPx()
                    fun indexAt(x: Float) =
                        (((x - left) / (size.width - left)) * (values.size - 1)).roundToInt().coerceIn(0, values.size - 1)
                    detectTapGestures { selected = indexAt(it.x) }
                }
                .pointerInput(values) {
                    val left = 44.dp.toPx()
                    fun indexAt(x: Float) =
                        (((x - left) / (size.width - left)) * (values.size - 1)).roundToInt().coerceIn(0, values.size - 1)
                    detectDragGestures(
                        onDragStart = { selected = indexAt(it.x) },
                        onDrag = { change, _ -> selected = indexAt(change.position.x) },
                    )
                },
        ) {
            val left = 44.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val top = 6.dp.toPx()
            val w = size.width - left
            val h = bottom - top
            fun x(i: Int) = left + w * i / (values.size - 1)
            fun y(v: Double) = (bottom - h * ((v - min) / (max - min))).toFloat()

            // Сетка и подписи по Y.
            for (k in 0..3) {
                val v = min + (max - min) * k / 3
                val yy = y(v)
                drawLine(gridColor, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
                val text = measurer.measure(v.formatValue(), labelStyle)
                drawText(text, topLeft = Offset(left - text.size.width - 4.dp.toPx(), yy - text.size.height / 2f))
            }
            // Подписи по X: начало, середина, конец.
            listOf(0, values.size / 2, values.size - 1).forEach { i ->
                val text = measurer.measure(times[i].format(TimeShort), labelStyle)
                val tx = (x(i) - text.size.width / 2f).coerceIn(left, size.width - text.size.width)
                drawText(text, topLeft = Offset(tx, bottom + 3.dp.toPx()))
            }

            // Линия; разрывы там, где нет данных. При большом числе точек рисуем с шагом.
            val stride = maxOf(1, values.size / (w / 1.5f).toInt().coerceAtLeast(1))
            val path = Path()
            var penDown = false
            val indices = (0 until values.size step stride) + (values.size - 1)
            for (i in indices.distinct()) {
                val v = values[i]
                if (v == null) penDown = false
                else if (!penDown) { path.moveTo(x(i), y(v)); penDown = true }
                else path.lineTo(x(i), y(v))
            }
            drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))

            markers.forEach { m ->
                values.getOrNull(m.index)?.let { v ->
                    drawCircle(m.color, radius = 5.dp.toPx(), center = Offset(x(m.index), y(v)))
                }
            }

            selected?.let { s ->
                drawLine(
                    labelColor, Offset(x(s), top), Offset(x(s), bottom), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                )
                values[s]?.let { v -> drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x(s), y(v))) }
            }
        }
    }
}
