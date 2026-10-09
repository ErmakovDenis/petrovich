package ru.petrovich.telemetry.ui.action

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Resolution
import ru.petrovich.telemetry.data.settings.Contact
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.FalseAlarmSheet
import ru.petrovich.telemetry.ui.common.MessageBox
import ru.petrovich.telemetry.ui.common.NearbySignals
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.SoftButton
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.color
import ru.petrovich.telemetry.ui.common.formatValue
import ru.petrovich.telemetry.ui.common.hhmm
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.ui.common.loadNearbySignals
import ru.petrovich.telemetry.ui.common.previousOccurrence
import ru.petrovich.telemetry.ui.common.securityContact
import ru.petrovich.telemetry.ui.common.softColor
import ru.petrovich.telemetry.ui.common.time
import ru.petrovich.telemetry.ui.common.title
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.MailIntent
import ru.petrovich.telemetry.util.runCatchingCancellable

/**
 * «Разбор»: статичный экран по макету — Петрович показывает, что собрал, и предлагает три
 * действия строками. Никаких реплик — это не чат, а карточка с решением.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionScreen(anomalyId: String, onBack: () -> Unit, onMail: (String) -> Unit, onResolved: (String) -> Unit) {
    val store = ServiceLocator.anomalyStore
    val anomalies by store.anomalies.collectAsStateWithLifecycle()
    val anomaly = anomalies.firstOrNull { it.id == anomalyId }
    val settings by ServiceLocator.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val c = Petrovich.colors
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var callTarget by remember { mutableStateOf<Contact?>(null) }
    var reasons by remember { mutableStateOf(false) }
    var signals by remember(anomalyId) { mutableStateOf<NearbySignals?>(null) }

    LaunchedEffect(anomaly?.id) {
        val at = anomaly?.time ?: return@LaunchedEffect
        signals = runCatchingCancellable { loadNearbySignals(anomaly, at) }.getOrNull()
    }

    Scaffold(
        topBar = { AppTopBar(title = "Разбор · ${anomaly?.vehicleName ?: ""}", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (anomaly == null) {
            Box(Modifier.padding(padding)) { MessageBox("Эта аномалия больше не хранится в истории") }
            return@Scaffold
        }
        val contacts = settings?.contacts.orEmpty()
        val callContact = securityContact(contacts)
        val prev = anomalies.previousOccurrence(anomaly)

        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Собрал данные. ${anomaly.description}", style = MaterialTheme.typography.bodyLarge)

            SurfaceCard(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(anomaly.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        Pill(anomaly.vehicleName, c.muted, c.surface2)
                    }
                    HorizontalDivider(color = c.line)
                    Fact("Когда", anomaly.time?.let { "${it.toLocalDate().title()}, ${it.hhmm()}" } ?: anomaly.eventTime)
                    signals?.ignitionOn?.let { Fact("Двигатель", if (it) "включен" else "выключен") }
                    signals?.movingKmh?.let { Fact("Машина", if (it < 1.0) "стояла" else "в пути · ${it.formatValue()} км/ч") }
                    Fact("Датчик", if (anomaly.kind == "nodata") "молчит" else "исправен")
                }
            }

            if (prev != null) {
                Text(
                    "Помню: ${prev.time?.toLocalDate()?.title() ?: prev.eventTime} уже было похожее на этой машине. Что делаем?",
                    style = MaterialTheme.typography.bodyMedium, color = c.accent,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionRow(
                    "Позвонить начальнику СБ", Icons.Outlined.Call,
                    enabled = callContact != null,
                    onClick = { callTarget = callContact },
                )
                ActionRow("Написать в СБ", Icons.Outlined.MailOutline, enabled = true, onClick = { onMail(anomaly.id) })
                ActionRow("Это ложная тревога", Icons.Outlined.Flag, enabled = true, onClick = { reasons = true })
            }
        }

        callTarget?.let { contact ->
            CallConfirmSheet(
                contact = contact,
                onDismiss = { callTarget = null },
                onCall = {
                    val ok = contact.phone.isNotBlank() && MailIntent.dial(context, contact.phone)
                    if (!ok) Toast.makeText(context, "Не удалось открыть номеронабиратель", Toast.LENGTH_SHORT).show()
                    scope.launch {
                        store.markInProgress(anomaly.id, "${contact.name} · ${contact.role}")
                        store.appendStep(anomaly.id, "Звонок ${contact.role.lowercase()}")
                    }
                    callTarget = null
                    onResolved(anomaly.id)
                },
            )
        }

        if (reasons) {
            FalseAlarmSheet(
                onDismiss = { reasons = false },
                onPick = { reason ->
                    reasons = false
                    scope.launch { store.resolve(anomaly.id, Resolution.FALSE_ALARM, reason) }
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label:", style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Petrovich.colors.muted)
    }
}

/** Кнопка-строка на всю ширину — три равноправных действия разбора, а не иерархия «главное/второстепенное». */
@Composable
private fun ActionRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val c = Petrovich.colors
    SurfaceCard(Modifier.fillMaxWidth(), onClick = if (enabled) onClick else null, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, null, tint = if (enabled) c.accent else c.faint)
            Text(
                if (enabled) text else "$text · нет контакта",
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) c.ink else c.faint,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallConfirmSheet(contact: Contact, onDismiss: () -> Unit, onCall: () -> Unit) {
    val c = Petrovich.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.bg) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Позвонить начальнику СБ?", style = MaterialTheme.typography.headlineSmall)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Call, null, tint = c.accent)
                }
                Column {
                    Text("${contact.name} · ${contact.role}", style = MaterialTheme.typography.titleSmall)
                    Text(contact.phone.ifBlank { "Телефон не указан" }, style = MaterialTheme.typography.bodyMedium, color = c.muted)
                }
            }
            Text("Петрович соединит вас и заранее отправит сотруднику карточку события.", style = MaterialTheme.typography.bodyMedium, color = c.muted)
            // Кнопка звонка — зелёная с иконкой трубки, отдельно от словаря кнопок макета (4. Общие элементы: «звонок — зелёная»).
            SoftButton(
                "Позвонить", onCall, Modifier.fillMaxWidth(), enabled = contact.phone.isNotBlank(),
                container = c.ok, content = c.onAccent, leading = Icons.Outlined.Call,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Отмена", color = c.muted, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
