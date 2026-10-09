package ru.petrovich.telemetry.ui.park

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.TripEvent
import ru.petrovich.telemetry.data.TripEventKind
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.VehicleRoute
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.MessageBox
import ru.petrovich.telemetry.ui.common.PChip
import ru.petrovich.telemetry.ui.common.SectionLabel
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private enum class MapPeriod(val title: String) { TODAY("Сегодня"), YESTERDAY("Вчера") }

/**
 * 3.14 «Машина на карте» — НЕ настоящая карта (см. [SchematicMap]): схема с геозоной базы и маршрутом.
 * Источник координат есть только у демо-данных ([ru.petrovich.telemetry.data.DemoTelemetryRepository]);
 * для реального АвтоГРАФ координат нет вовсе — показываем честное пустое состояние.
 */
@Composable
fun VehicleMapScreen(vehicleId: String, onBack: () -> Unit) {
    val c = Petrovich.colors
    var vehicle by remember(vehicleId) { mutableStateOf<Vehicle?>(null) }
    var vehicleLoaded by remember(vehicleId) { mutableStateOf(false) }
    var period by remember { mutableStateOf(MapPeriod.TODAY) }
    var route by remember(vehicleId) { mutableStateOf<VehicleRoute?>(null) }
    var routeLoaded by remember(vehicleId) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vehicleId) {
        vehicleLoaded = false
        runCatchingCancellable { ServiceLocator.telemetry.vehicles().firstOrNull { it.id == vehicleId } }
            .onSuccess { vehicle = it; vehicleLoaded = true }
            .onFailure { error = it.message ?: it.toString(); vehicleLoaded = true }
    }

    LaunchedEffect(vehicleId, period, vehicle) {
        val v = vehicle ?: return@LaunchedEffect
        routeLoaded = false
        val day = if (period == MapPeriod.TODAY) LocalDate.now() else LocalDate.now().minusDays(1)
        runCatchingCancellable { ServiceLocator.telemetry.route(v, day) }
            .onSuccess { route = it; routeLoaded = true }
            .onFailure { error = it.message ?: it.toString(); route = null; routeLoaded = true }
    }

    Scaffold(
        topBar = { AppTopBar(title = vehicle?.let { "${it.name} на карте" } ?: "Машина на карте", onBack = onBack) },
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MapPeriod.entries.forEach { p -> PChip(p.title, selected = period == p, onClick = { period = p }, modifier = Modifier.weight(1f)) }
            }

            when {
                !vehicleLoaded || !routeLoaded -> Box(Modifier.fillMaxSize()) { MessageBox("Загружаю маршрут…") }
                vehicle == null -> Box(Modifier.fillMaxSize()) { MessageBox(error?.let { "Не удалось загрузить машину: $it" } ?: "Эта машина не найдена") }
                route == null -> Box(Modifier.fillMaxSize()) {
                    MessageBox("Петрович пока не получает координаты от АвтоГРАФ для этой машины. Трек и геозоны появятся, когда источник начнёт их передавать.")
                }
                else -> RouteContent(route!!, period)
            }
        }
    }
}

@Composable
private fun RouteContent(route: VehicleRoute, period: MapPeriod) {
    val c = Petrovich.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!route.movedToday) {
            Text(
                if (period == MapPeriod.TODAY) "Сегодня не двигалась" else "Вчера не двигалась",
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium, color = c.muted, fontWeight = FontWeight.SemiBold,
            )
        }
        SurfaceCard(shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val anomalyLabel = route.events.firstOrNull { it.kind == TripEventKind.ANOMALY }?.detail
                SchematicMap(route.base, route.points, anomalyLabel = anomalyLabel)
                RouteLegend()
            }
        }

        SectionLabel(if (period == MapPeriod.TODAY) "Сегодня" else "Вчера")
        SurfaceCard(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(horizontal = 14.dp)) {
                if (route.events.isEmpty()) {
                    Text("Событий не было", Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.bodyMedium, color = c.muted)
                } else {
                    route.events.forEachIndexed { i, e ->
                        EventRow(e)
                        if (i != route.events.lastIndex) HorizontalDivider(color = c.line)
                    }
                }
            }
        }
        Box(Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun EventRow(e: TripEvent) {
    val c = Petrovich.colors
    val urgent = e.kind == TripEventKind.ANOMALY
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(timeRange(e), Modifier.padding(top = 1.dp), style = MaterialTheme.typography.labelMedium, color = c.muted)
        Text(
            e.text, style = MaterialTheme.typography.bodyMedium,
            color = if (urgent) c.high else c.ink,
            fontWeight = if (urgent) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Стоянка с полуночи до «сейчас»/конца дня — честно показываем «весь день», а не «00:00–HH:MM» (3.14). */
private fun timeRange(e: TripEvent): String {
    if (e.kind == TripEventKind.STOP && e.from.toLocalTime() == LocalTime.MIDNIGHT) return "весь день"
    val from = fmtHm(e.from)
    val to = e.to?.let { fmtHm(it) }
    return if (to != null) "$from–$to" else from
}

private fun fmtHm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)
