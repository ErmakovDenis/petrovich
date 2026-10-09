package ru.petrovich.telemetry.ui.park

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.anomaly.Severity
import ru.petrovich.telemetry.anomaly.VehicleActivity
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.ui.common.AppTopBar
import ru.petrovich.telemetry.ui.common.MessageBox
import ru.petrovich.telemetry.ui.common.PChip
import ru.petrovich.telemetry.ui.common.Pill
import ru.petrovich.telemetry.ui.common.SurfaceCard
import ru.petrovich.telemetry.ui.common.isNew
import ru.petrovich.telemetry.ui.common.plural
import ru.petrovich.telemetry.ui.theme.Petrovich
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.LocalDateTime

private enum class ParkStatusFilter(val title: String) {
    ALL("Все"), PROBLEM("Проблемы"), ON_LINE("На линии"), STOPPED("Стоят"), OFFLINE("Без связи"),
}

private data class ParkRow(val vehicle: Vehicle, val stats: VehicleStats?) {
    val bucket: ParkStatusFilter get() = when {
        stats == null -> ParkStatusFilter.OFFLINE
        worstSeverity != null -> ParkStatusFilter.PROBLEM
        stats.activity == VehicleActivity.ON_LINE -> ParkStatusFilter.ON_LINE
        stats.activity == VehicleActivity.STOPPED -> ParkStatusFilter.STOPPED
        else -> ParkStatusFilter.OFFLINE
    }
    var worstSeverity: Severity? = null
}

/**
 * 3.12 «Парк»: список всех машин с поиском, фильтром по статусу (честно — тот же [VehicleActivity],
 * что и на «Главной») и по базе ([Vehicle.group]). Строка кликабельна целиком → карточка машины (3.13).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParkScreen(onOpenVehicle: (vehicleId: String) -> Unit) {
    val c = Petrovich.colors
    val anomalies by ServiceLocator.anomalyStore.anomalies.collectAsStateWithLifecycle()
    var vehicles by remember { mutableStateOf<List<Vehicle>>(emptyList()) }
    var statsById by remember { mutableStateOf<Map<String, VehicleStats?>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(ParkStatusFilter.ALL) }
    var baseFilter by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        if (vehicles.isEmpty()) loading = true else refreshing = true
        error = null
        runCatchingCancellable {
            val repo = ServiceLocator.telemetry
            val list = repo.vehicles()
            val stats = loadParkStats(list, repo)
            list to stats
        }.onSuccess { (list, stats) ->
            vehicles = list
            statsById = stats
            loading = false
            refreshing = false
        }.onFailure { e ->
            error = e.message ?: e.toString()
            loading = false
            refreshing = false
        }
    }

    val pendingSeverity = remember(anomalies) {
        anomalies.filter { it.isNew && it.severity != Severity.INFO }
            .groupBy { it.vehicleId }
            .mapValues { (_, list) -> list.maxByOrNull { it.severity.ordinal }!!.severity }
    }

    val rows = remember(vehicles, statsById, pendingSeverity) {
        vehicles.map { v -> ParkRow(v, statsById[v.id]).also { it.worstSeverity = pendingSeverity[v.id] } }
    }
    val groups = remember(vehicles) { vehicles.mapNotNull { it.group }.distinct().sorted() }

    fun normalize(s: String) = s.uppercase().map { ch -> LookAlike[ch] ?: ch }.joinToString("")
    val filtered = remember(rows, query, statusFilter, baseFilter) {
        val q = normalize(query.trim())
        rows.filter { row ->
            (statusFilter == ParkStatusFilter.ALL || row.bucket == statusFilter) &&
                (baseFilter == null || row.vehicle.group == baseFilter) &&
                (q.isEmpty() || normalize(row.vehicle.name).contains(q))
        }.sortedWith(compareBy({ bucketOrder(it.bucket) }, { it.vehicle.name }))
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Парк",
                subtitle = if (vehicles.isNotEmpty()) "${vehicles.size} ${plural(vehicles.size, "машина", "машины", "машин")}" else null,
                onRefresh = { refreshKey++ },
            )
        },
        containerColor = c.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (loading) {
            Box(Modifier.padding(padding).fillMaxSize()) { MessageBox("Загружаю парк…") }
            return@Scaffold
        }
        if (error != null && vehicles.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                MessageBox("Не удалось загрузить парк: $error", actionLabel = "Повторить") { refreshKey++ }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Номер машины, например X452") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Очистить") } },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.line,
                ),
            )

            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(ParkStatusFilter.entries) { f ->
                    val count = if (f == ParkStatusFilter.ALL) rows.size else rows.count { it.bucket == f }
                    PChip("${f.title} $count", selected = statusFilter == f, onClick = { statusFilter = f })
                }
            }
            Box(Modifier.padding(top = 6.dp)) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        PChip("Все базы · ${rows.size}", selected = baseFilter == null, onClick = { baseFilter = null })
                    }
                    items(groups) { g ->
                        val count = rows.count { it.vehicle.group == g }
                        PChip("База «$g» · $count", selected = baseFilter == g, onClick = { baseFilter = g })
                    }
                }
            }

            if (refreshing) CircularProgressIndicator(Modifier.padding(top = 8.dp).size(18.dp).align(Alignment.CenterHorizontally), strokeWidth = 2.dp, color = c.accent)

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize()) { MessageBox("Ничего не нашлось по этому фильтру") }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.vehicle.id }) { row -> ParkVehicleRow(row, onClick = { onOpenVehicle(row.vehicle.id) }) }
                }
            }
        }
    }
}

private fun bucketOrder(b: ParkStatusFilter) = when (b) {
    ParkStatusFilter.PROBLEM -> 0
    ParkStatusFilter.OFFLINE -> 1
    ParkStatusFilter.STOPPED -> 2
    ParkStatusFilter.ON_LINE -> 3
    ParkStatusFilter.ALL -> 4
}

/** x452 → X452: латиница/кириллица визуально неотличимы — ищем без разницы между ними. */
private val LookAlike: Map<Char, Char> = mapOf(
    'X' to 'Х', 'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е', 'H' to 'Н',
    'K' to 'К', 'M' to 'М', 'O' to 'О', 'P' to 'Р', 'T' to 'Т', 'Y' to 'У',
)

private suspend fun loadParkStats(vehicles: List<Vehicle>, repo: TelemetryRepository): Map<String, VehicleStats?> = coroutineScope {
    val now = LocalDateTime.now()
    val from = now.toLocalDate().atStartOfDay()
    val limit = Semaphore(3)
    vehicles.map { v ->
        async {
            val stats = limit.withPermit {
                runCatchingCancellable { repo.telemetry(v, from, now) }.getOrNull()?.let { computeVehicleStats(it, now) }
            }
            v.id to stats
        }
    }.awaitAll().toMap()
}

@Composable
private fun ParkVehicleRow(row: ParkRow, onClick: () -> Unit) {
    val c = Petrovich.colors
    val stats = row.stats
    val (pillText, pillColor, pillBg) = statusPill(stats?.activity ?: VehicleActivity.OFFLINE, row.worstSeverity)
    SurfaceCard(Modifier.fillMaxWidth(), onClick = onClick, shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(pillBg), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.LocalShipping, null, tint = pillColor)
            }
            Column(Modifier.weight(1f)) {
                Text(row.vehicle.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(secondLine(row), style = MaterialTheme.typography.bodySmall, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Pill(pillText, pillColor, pillBg)
        }
    }
}

/**
 * «Где сейчас и пробег за сегодня (или время последней связи)» — честно из того, что знаем:
 * группу (базу), статус активности и посчитанный по телеметрии пробег. Названий улиц/объектов
 * у реального АвтоГРАФ здесь нет (см. отчёт агента), поэтому не выдумываем — координат нет совсем.
 */
private fun secondLine(row: ParkRow): String {
    val s = row.stats
    val base = row.vehicle.group?.let { "База «$it»" }
    return when {
        s == null || s.activity == VehicleActivity.OFFLINE -> s?.lastContact?.let { "связь: ${relativeAgo(it)}" } ?: "нет данных сегодня"
        s.activity == VehicleActivity.ON_LINE -> {
            val km = s.mileageKm?.let { " · ${it.toInt()} км сегодня" } ?: ""
            "в пути$km"
        }
        else -> base ?: "стоит"
    }
}

private fun relativeAgo(t: LocalDateTime): String {
    val mins = Duration.between(t, LocalDateTime.now()).toMinutes().coerceAtLeast(0)
    return when {
        mins < 60 -> "$mins мин назад"
        mins < 24 * 60 -> "${mins / 60} ч назад"
        else -> "${mins / (24 * 60)} дн назад"
    }
}
