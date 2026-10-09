package ru.petrovich.telemetry.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.petrovich.telemetry.ui.theme.Petrovich

/** Причины ложной тревоги — общий список для карточки события и экрана «Разбор». */
val FalseAlarmReasons = listOf("Штатная работа", "Ошибка датчика", "Уже известно", "Другое")

/** Шторка «Почему ложная тревога?» — переиспользуется карточкой события (3.6) и экраном «Разбор» (3.7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FalseAlarmSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = Petrovich.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Почему ложная тревога?", style = MaterialTheme.typography.titleLarge)
            Text("Причина сохранится вместе с аномалией.", style = MaterialTheme.typography.bodySmall, color = c.muted)
            FalseAlarmReasons.forEach { reason ->
                SurfaceCard(
                    Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    onClick = { onPick(reason) },
                ) { Text(reason, Modifier.padding(15.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
            }
        }
    }
}
