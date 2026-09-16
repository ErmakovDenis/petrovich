package ru.petrovich.telemetry.ui.charts

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
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

private data class YRange(val min: Double, val max: Double)

private val AxisWidth = 44.dp

/**
 * Лёгкий линейный график на Canvas: сетка, подписи осей, маркеры аномалий,
 * просмотр значения касанием/перетаскиванием по горизонтали.
 *
 * Выбранная точка хранится в отдельном состоянии и читается только в подписи и на этапе отрисовки,
 * поэтому движение пальца не перекомпоновывает график и не перестраивает линию.
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
    val selected = remember(values) { mutableStateOf<Int?>(null) }

    val range = remember(values) {
        val present = values.filterNotNull()
        if (present.isEmpty()) return@remember null
        var min = present.min()
        var max = present.max()
        if (max - min < 1e-6) { min -= 1; max += 1 }
        val pad = (max - min) * 0.08
        // Не уводим ось в минус, если сами данные неотрицательные (обороты, скорость и т.п.).
        YRange(if (min >= 0 && min - pad < 0) 0.0 else min - pad, max + pad)
    }
    if (range == null || times.size < 2) {
        Text("Нет данных", style = MaterialTheme.typography.bodySmall, color = labelColor)
        return
    }

    Column(modifier) {
        SelectedValueLabel(selected, times, values, unit)
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .pointerInput(values) {
                    detectTapGestures { selected.value = indexAt(it.x, values.lastIndex) }
                }
                // Только горизонтальный жест: вертикальный свайп прокручивает список графиков.
                .pointerInput(values) {
                    detectHorizontalDragGestures(
                        onDragStart = { selected.value = indexAt(it.x, values.lastIndex) },
                        onHorizontalDrag = { change, _ -> selected.value = indexAt(change.position.x, values.lastIndex) },
                    )
                }
                .drawWithCache {
                    val left = AxisWidth.toPx()
                    val bottom = size.height - 18.dp.toPx()
                    val top = 6.dp.toPx()
                    val w = size.width - left
                    val h = bottom - top
                    fun x(i: Int) = left + w * i / values.lastIndex
                    fun y(v: Double) = (bottom - h * ((v - range.min) / (range.max - range.min))).toFloat()

                    // Линия с разрывами там, где нет данных — строится один раз на размер/данные.
                    val path = Path()
                    var penDown = false
                    for (i in values.indices) {
                        val v = values[i]
                        if (v == null) penDown = false
                        else if (!penDown) { path.moveTo(x(i), y(v)); penDown = true }
                        else path.lineTo(x(i), y(v))
                    }
                    val yLabels = (0..3).map { k ->
                        val v = range.min + (range.max - range.min) * k / 3
                        y(v) to measurer.measure(v.formatValue(), labelStyle)
                    }
                    val xLabels = listOf(0, values.size / 2, values.lastIndex).map { i ->
                        x(i) to measurer.measure(times[i].format(TimeShort), labelStyle)
                    }
                    val lineStroke = Stroke(width = 2.dp.toPx())
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))

                    onDrawBehind {
                        yLabels.forEach { (yy, text) ->
                            drawLine(gridColor, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
                            drawText(text, topLeft = Offset(left - text.size.width - 4.dp.toPx(), yy - text.size.height / 2f))
                        }
                        xLabels.forEach { (xx, text) ->
                            val tx = (xx - text.size.width / 2f).coerceIn(left, size.width - text.size.width)
                            drawText(text, topLeft = Offset(tx, bottom + 3.dp.toPx()))
                        }
                        drawPath(path, lineColor, style = lineStroke)
                        markers.forEach { m ->
                            values.getOrNull(m.index)?.let { v ->
                                drawCircle(m.color, radius = 5.dp.toPx(), center = Offset(x(m.index), y(v)))
                            }
                        }
                        selected.value?.let { s ->
                            drawLine(labelColor, Offset(x(s), top), Offset(x(s), bottom), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                            values[s]?.let { v -> drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x(s), y(v))) }
                        }
                    }
                },
        )
    }
}

/** Индекс точки под координатой x внутри области графика. */
private fun PointerInputScope.indexAt(x: Float, lastIndex: Int): Int {
    val left = AxisWidth.toPx()
    return (((x - left) / (size.width - left)) * lastIndex).roundToInt().coerceIn(0, lastIndex)
}

@Composable
private fun SelectedValueLabel(
    selected: MutableState<Int?>,
    times: List<LocalDateTime>,
    values: List<Double?>,
    unit: String?,
) {
    val sel = selected.value
    Text(
        if (sel != null) "${times[sel].format(TimeShort)} — ${values[sel].formatValue()} ${unit.orEmpty()}"
        else "Коснитесь графика, чтобы увидеть значение",
        style = MaterialTheme.typography.labelMedium,
        color = if (sel != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
