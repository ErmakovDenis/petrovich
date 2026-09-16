package ru.petrovich.telemetry.anomaly

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.petrovich.telemetry.data.TelemetryRepository
import java.time.LocalDateTime

data class ScanResult(val checkedVehicles: Int, val newAnomalies: List<Anomaly>, val errors: List<String>)

/** Загружает данные по всем машинам, прогоняет через детектор и сохраняет новые аномалии. */
class AnomalyScanner(
    private val repository: TelemetryRepository,
    private val detector: AnomalyDetector,
    private val store: AnomalyStore,
    private val notifier: AnomalyNotifier,
) {
    suspend fun scan(lookbackHours: Long = 24, notify: Boolean = true): ScanResult = coroutineScope {
        val to = LocalDateTime.now()
        val from = to.minusHours(lookbackHours)
        val vehicles = repository.vehicles()
        val limit = Semaphore(2)
        val errors = mutableListOf<String>()

        val found = vehicles.map { v ->
            async {
                limit.withPermit {
                    runCatching { detector.detect(repository.telemetry(v, from, to)) }
                        .onFailure { synchronized(errors) { errors += "${v.name}: ${it.message}" } }
                        .getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()

        val fresh = store.addAll(found)
        if (notify && fresh.isNotEmpty()) notifier.notify(fresh)
        ScanResult(vehicles.size, fresh, errors)
    }
}
