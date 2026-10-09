package ru.petrovich.telemetry.anomaly

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.data.settings.SettingsRepository
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Тихие часы — фиксированное ночное окно, как в настройках («Тихие часы 22:00–07:00»). */
private fun isQuietHour(hour: Int): Boolean = hour >= 22 || hour < 7

/** «На линии» / «стоят» / «без связи» — честная оценка по тем же данным, что уже загружены для поиска аномалий. */
enum class VehicleActivity { ON_LINE, STOPPED, OFFLINE }

/** Свежими считаем данные не старше этого — иначе машина показывается «без связи». */
private val FreshnessWindow: Duration = Duration.ofMinutes(45)
private const val MOVING_SPEED_KMH = 3.0

/** Последняя точка с хоть одним значением → по ней решаем, едет машина, стоит или давно не на связи. */
fun classifyActivity(t: VehicleTelemetry, now: LocalDateTime = LocalDateTime.now()): VehicleActivity {
    val columns = t.tables.values.flatMap { it.columns }
    val timestamps = t.tables.values.firstOrNull()?.timestamps ?: return VehicleActivity.OFFLINE
    val lastIdx = timestamps.indices.lastOrNull { i -> columns.any { c -> c.values.getOrNull(i) != null } } ?: return VehicleActivity.OFFLINE
    if (Duration.between(timestamps[lastIdx], now) > FreshnessWindow) return VehicleActivity.OFFLINE
    val speed = columns.firstOrNull { it.parameter.name == "Speed" }?.values?.getOrNull(lastIdx)
    return if ((speed ?: 0.0) >= MOVING_SPEED_KMH) VehicleActivity.ON_LINE else VehicleActivity.STOPPED
}

data class ScanResult(val checkedVehicles: Int, val newAnomalies: List<Anomaly>, val errors: List<String>)

/** Загружает данные по всем машинам, прогоняет через детектор и сохраняет новые аномалии. */
class AnomalyScanner(
    private val repository: TelemetryRepository,
    private val detector: AnomalyDetector,
    private val store: AnomalyStore,
    private val notifier: AnomalyNotifier,
    private val settings: SettingsRepository,
    private val notificationStore: NotificationStore,
) {
    suspend fun scan(lookbackHours: Long = 24, notify: Boolean = true): ScanResult = coroutineScope {
        val to = LocalDateTime.now()
        val from = to.minusHours(lookbackHours)
        val vehicles = repository.vehicles()
        val limit = Semaphore(2)
        val errors = mutableListOf<String>()

        // Телеметрию держим рядом с результатом детектора: она же, без повторного запроса,
        // даёт честный статус парка (едет / стоит / давно не на связи) для «Главной».
        val perVehicle = vehicles.map { v ->
            async {
                limit.withPermit {
                    runCatchingCancellable { repository.telemetry(v, from, to) }
                        .onFailure { synchronized(errors) { errors += "${v.name}: ${it.message}" } }
                        .getOrNull()
                }
            }
        }.awaitAll()

        val found = perVehicle.mapIndexed { i, t ->
            async { t?.let { runCatchingCancellable { detector.detect(it) }.getOrDefault(emptyList()) } ?: emptyList() }
        }.awaitAll().flatten()

        val fresh = store.addAll(found)
        if (fresh.isNotEmpty()) {
            notificationStore.addAll(fresh.map {
                NotificationItem(
                    id = it.id,
                    title = "${it.severity.label()} · ${it.vehicleName}: ${it.title}",
                    subtitle = it.description,
                    at = it.detectedAt,
                    targetAnomalyId = it.id,
                )
            })
        }
        // Если ни одна машина не ответила — проверкой это считать нельзя, иначе сводка скажет «всё в порядке».
        if (vehicles.isEmpty() || errors.size < vehicles.size) {
            settings.update { it.copy(lastScanAt = System.currentTimeMillis()) }
        }
        val activity = perVehicle.filterNotNull().map { classifyActivity(it, to) }
        if (activity.isNotEmpty()) {
            val offlineFetchFailures = perVehicle.count { it == null }
            settings.update {
                it.copy(
                    fleetOnline = activity.count { a -> a == VehicleActivity.ON_LINE },
                    fleetIdle = activity.count { a -> a == VehicleActivity.STOPPED },
                    fleetOffline = activity.count { a -> a == VehicleActivity.OFFLINE } + offlineFetchFailures,
                    fleetUpdatedAt = System.currentTimeMillis(),
                )
            }
        }
        if (notify && fresh.isNotEmpty()) {
            val prefs = settings.current()
            val today = LocalDate.now().toString()
            val sentToday = if (prefs.pushCountDate == today) prefs.pushCountToday else 0
            // Ночью и сверх дневного лимита продолжают идти только срочные — остальное видно в «Проблемах».
            val quiet = prefs.quietHoursEnabled && isQuietHour(LocalDateTime.now().hour)
            val overLimit = prefs.limitDailyPush && sentToday >= 3
            val pushWarning = prefs.pushWarning && !quiet && !overLimit
            notifier.notify(fresh, prefs.pushCritical, pushWarning)
            val pushedWarnings = if (pushWarning) fresh.count { it.severity == Severity.WARNING } else 0
            if (pushedWarnings > 0) {
                settings.update { it.copy(pushCountDate = today, pushCountToday = sentToday + pushedWarnings) }
            }
        }
        ScanResult(vehicles.size, fresh, errors)
    }
}
