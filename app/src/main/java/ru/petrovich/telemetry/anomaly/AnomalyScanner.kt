package ru.petrovich.telemetry.anomaly

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.settings.SettingsRepository
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.LocalDateTime

data class ScanResult(val checkedVehicles: Int, val newAnomalies: List<Anomaly>, val errors: List<String>)

/**
 * Проверяет все машины ([checker]: детектор на устройстве или стенд — по переключателю) и сохраняет новые аномалии.
 * Уведомления — на устройстве при любом источнике. Без хранилища на стенде новые — те, которых нет в [store]
 * (дедупликация по id). При хранилище на стенде ([sync]) проверка сохраняет результат на стенде, лента после неё
 * берётся со стенда, а новые — появившиеся на стенде после прошлого обновления ([AnomalySync.refresh]).
 */
class AnomalyScanner(
    private val repository: TelemetryRepository,
    private val checker: VehicleChecker,
    private val store: AnomalyStore,
    private val notifier: AnomalyNotifier,
    private val settings: SettingsRepository,
    private val sync: AnomalySync? = null,
) {
    suspend fun scan(lookbackHours: Long = 24, notify: Boolean = true): ScanResult = coroutineScope {
        val onStand = sync?.active() == true
        // Хранилище на стенде выключено: refresh только убирает его записи из кэша (id устройства другие).
        if (!onStand) sync?.refresh()
        val to = LocalDateTime.now()
        val from = to.minusHours(lookbackHours)
        val vehicles = repository.vehicles()
        val limit = Semaphore(2)
        val errors = mutableListOf<String>()

        val found = vehicles.map { v ->
            async {
                limit.withPermit {
                    runCatchingCancellable { checker.check(v, from, to) }
                        .onFailure { synchronized(errors) { errors += "${v.name}: ${it.message}" } }
                        .getOrDefault(emptyList())
                }
            }
        }.awaitAll().flatten()

        val fresh = if (onStand) {
            // Не вышло — кэш как был, новые придут со следующим обновлением (их время обнаружения позже отметки).
            runCatchingCancellable { sync?.refresh() }.getOrNull().orEmpty()
        } else {
            store.addAll(found)
        }
        // Если ни одна машина не ответила — проверкой это считать нельзя, иначе сводка скажет «всё в порядке».
        if (vehicles.isEmpty() || errors.size < vehicles.size) {
            settings.update { it.copy(lastScanAt = System.currentTimeMillis()) }
        }
        if (notify && fresh.isNotEmpty()) {
            val prefs = settings.current()
            notifier.notify(fresh, prefs.pushCritical, prefs.pushWarning)
        }
        ScanResult(vehicles.size, fresh, errors)
    }
}
