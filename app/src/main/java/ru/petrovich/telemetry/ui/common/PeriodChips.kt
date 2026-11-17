package ru.petrovich.telemetry.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Ряд пресетов + чип «Свой» / активный диапазон. Тап по «Свой» или по выбранному своему
 * периоду открывает [CustomPeriodDialog].
 */
@Composable
fun PeriodChips(
    presets: List<PeriodPreset>,
    selectedPresetId: String?,
    customRange: TimeRange?,
    onSelectPreset: (PeriodPreset) -> Unit,
    onSelectCustom: (TimeRange) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var dialog by remember { mutableStateOf(false) }
    val customSelected = selectedPresetId == null && customRange != null
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(presets, key = { it.id }) { preset ->
            PChip(
                label = preset.title,
                selected = selectedPresetId == preset.id,
                onClick = { onSelectPreset(preset) },
            )
        }
        item(key = "custom") {
            PChip(
                label = customRange?.takeIf { customSelected }?.label ?: "Свой",
                selected = customSelected,
                onClick = { dialog = true },
            )
        }
    }
    if (dialog) {
        CustomPeriodDialog(
            initial = defaultCustomDraft(customRange),
            onConfirm = { onSelectCustom(it); dialog = false },
            onDismiss = { dialog = false },
        )
    }
}

data class PeriodPreset(val id: String, val title: String, val range: () -> TimeRange)
