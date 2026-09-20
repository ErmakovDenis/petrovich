package ru.petrovich.telemetry.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.ui.theme.Petrovich

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    subtitle: String? = null,
    onOpenSettings: (() -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        },
        actions = {
            actions()
            if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Обновить") }
            if (onOpenSettings != null) IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Настройки") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** Белая (в тёмной теме — тёмно-зелёная) карточка без тени: основной строительный блок экранов. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = Petrovich.colors.surface,
    shape: Shape = RoundedCornerShape(18.dp),
    content: @Composable () -> Unit,
) {
    if (onClick != null) Surface(onClick = onClick, modifier = modifier, color = color, shape = shape, content = content)
    else Surface(modifier = modifier, color = color, shape = shape, content = content)
}

/** Плашка со статусом: «Срочно», «Подтверждено» и т. п. */
@Composable
fun Pill(text: String, color: Color, background: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(background).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(13.dp), tint = color)
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = TextUnit(0.07f, TextUnitType.Em),
    )
}

/** Чип-фильтр как в прототипе: выбранный — тёмный. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null) {
    val c = Petrovich.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { Text(label, fontWeight = FontWeight.Medium) },
        leadingIcon = leading?.let { { Icon(it, null, Modifier.size(16.dp)) } },
        shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.surface,
            labelColor = c.ink,
            iconColor = c.muted,
            selectedContainerColor = c.ink,
            selectedLabelColor = c.bg,
            selectedLeadingIconColor = c.bg,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected, borderColor = c.line, selectedBorderColor = c.ink,
        ),
    )
}

/** Крупная зелёная кнопка действия. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, leading: ImageVector? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Petrovich.colors.accent, contentColor = Petrovich.colors.onAccent),
    ) {
        if (leading != null) { Icon(leading, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Вторичная кнопка на светлой карточке. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = Petrovich.colors.surface,
    content: Color = Petrovich.colors.ink,
    leading: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        border = BorderStroke(1.dp, Petrovich.colors.line),
    ) {
        if (leading != null) { Icon(leading, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodSelector(selected: Period, onSelect: (Period) -> Unit, modifier: Modifier = Modifier) {
    val c = Petrovich.colors
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Period.entries.forEachIndexed { i, p ->
            SegmentedButton(
                selected = p == selected,
                onClick = { onSelect(p) },
                shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = c.surface, activeContentColor = c.ink, activeBorderColor = c.line,
                    inactiveContainerColor = c.surface2, inactiveContentColor = c.muted, inactiveBorderColor = c.line,
                ),
                label = { Text(p.title) },
            )
        }
    }
}

/** Выбор машины + периода + индикатор загрузки. Общий заголовок для «Данных» и «Графиков». */
@Composable
fun TelemetryHeader(state: TelemetryUiState, vm: TelemetryViewModel) {
    Column(Modifier.fillMaxWidth()) {
        VehiclePicker(state.vehicles, state.selectedVehicleId, vm::selectVehicle)
        Spacer(Modifier.height(8.dp))
        PeriodSelector(state.period, vm::selectPeriod)
        Spacer(Modifier.height(8.dp))
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Petrovich.colors.accent) else Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun MessageBox(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(12.dp))
                PrimaryButton(actionLabel, onAction)
            }
        }
    }
}

/** Цветная вертикальная полоска срочности слева от карточки. */
@Composable
fun SeverityStripe(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(color))
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** Пунктирная рамка со скруглением — для «+ Добавить». */
fun Modifier.dashedBorder(color: Color, radius: Dp = 18.dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
    )
}
