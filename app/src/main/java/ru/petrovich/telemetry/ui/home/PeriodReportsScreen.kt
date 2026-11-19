package ru.petrovich.telemetry.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.PeriodChips
import ru.petrovich.telemetry.ui.common.PeriodPreset
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable

private val ReportPeriodPresets = listOf(
    PeriodPreset("day", "День") { WidgetPeriod.DAY.toRange() },
    PeriodPreset("week", "Неделя") { WidgetPeriod.WEEK.toRange() },
    PeriodPreset("month", "Месяц") { WidgetPeriod.MONTH.toRange() },
    PeriodPreset("quarter", "3 месяца") { WidgetPeriod.QUARTER.toRange() },
)

/**
 * Отчёты по периодам: те же виджеты, что на главной, с выбором дня / недели / месяца / 3 месяцев / своего периода.
 */
@Composable
fun PeriodReportsScreen(
    onBack: () -> Unit,
    onOpenWidgets: () -> Unit,
    onOpenWidgetDetail: (WidgetType, WidgetPeriod?) -> Unit,
) {
    val c = Petrovich.colors
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return
    val layout = s.layout

    val periodSel by ServiceLocator.reportsPeriod.selection.collectAsStateWithLifecycle()
    val selectedPresetId = periodSel.presetId
    val customRange = periodSel.customRange
    var customFleet by remember { mutableStateOf<FleetWeek?>(null) }
    val lockedPeriod = periodSel.lockedWidgetPeriod
    val widgetCache by ServiceLocator.widgetPeriodCache.state.collectAsStateWithLifecycle()
    val fleetWeek = when {
        customRange != null && selectedPresetId == null -> customFleet
        lockedPeriod != null -> widgetCache.byPeriod[lockedPeriod]?.fleet
        else -> widgetCache.byPeriod[WidgetPeriod.WEEK]?.fleet
    }

    LaunchedEffect(s.telemetryOnServer, selectedPresetId, customRange) {
        val custom = customRange?.takeIf { selectedPresetId == null }
        if (custom != null) {
            customFleet = null
            customFleet = runCatchingCancellable { loadFleetRange(custom) }.getOrNull()
        } else {
            customFleet = null
            val period = lockedPeriod ?: WidgetPeriod.WEEK
            runCatchingCancellable { ServiceLocator.widgetPeriodCache.ensureFresh(period) }
        }
    }

    val periodLabel = when {
        selectedPresetId == "day" -> "день"
        selectedPresetId == "quarter" -> "3 месяца"
        selectedPresetId == "month" -> "месяц"
        selectedPresetId == "week" -> "неделя"
        customRange != null -> customRange.shortLabel
        else -> "неделя"
    }

    Scaffold(
        topBar = { AppTopBar("Отчёты по периодам", onBack = onBack) },
        containerColor = c.bg,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Сводка парка за выбранный отрезок. Тот же период открывается в карточке виджета.",
                style = MaterialTheme.typography.bodySmall,
                color = c.muted,
            )
            Text(
                reportsRangeCaption(selectedPresetId, customRange),
                style = MaterialTheme.typography.labelMedium,
                color = c.muted,
            )
            PeriodChips(
                presets = ReportPeriodPresets,
                selectedPresetId = selectedPresetId,
                customRange = customRange,
                onSelectPreset = { ServiceLocator.reportsPeriod.selectPreset(it.id) },
                onSelectCustom = { ServiceLocator.reportsPeriod.selectCustom(it) },
                contentPadding = PaddingValues(0.dp),
            )
            HomeWidgetsGrid(
                layout = layout,
                fleetWeek = fleetWeek,
                periodLabel = periodLabel,
                anomalies = anomalies,
                onOpenWidgetDetail = { onOpenWidgetDetail(it, lockedPeriod) },
                onOpenWidgets = onOpenWidgets,
            )
        }
    }
}
