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
 *
 * При фоновой проверке на стенде ([background], [poll]) в фоне устройство не проверяет само, а забирает новые
 * аномалии со стенда; уведомляет только о тех, о которых пользователь не получал уведомления на других устройствах.
 */
class AnomalyScanner(
    private val repository: TelemetryRepository,
    private val checker: VehicleChecker,
    private val store: AnomalyStore,
    private val notifier: AnomalyNotifications,
    private val settings: SettingsRepository,
    private val sync: AnomalySync? = null,
    private val background: BackgroundAccess? = null,
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
            runCatchingCancellable { sync?.refresh(advance = true) }.getOrNull().orEmpty()
        } else {
            store.addAll(found)
        }
        // Если ни одна машина не ответила — проверкой это считать нельзя, иначе сводка скажет «всё в порядке».
        // При фоновой проверке на стенде время проверки — со стенда (его ставит обновление ленты).
        if ((vehicles.isEmpty() || errors.size < vehicles.size) && !settings.current().backgroundOnServer) {
            settings.update { it.copy(lastScanAt = System.currentTimeMillis()) }
        }
        if (notify) notifyFresh(fresh)
        ScanResult(vehicles.size, fresh, errors)
    }

    /**
     * Фоновое обновление при фоновой проверке на стенде: обновить доступ стенда (свежий токен), забрать ленту
     * и уведомить о новом. Стенд не принял доступ (фоновая проверка там не включена, стенд недоступен) — проверка,
     * как раньше, по запросу с телефона: иначе машины не проверял бы никто. Стенд недоступен — исключение
     * (WorkManager повторит).
     */
    suspend fun poll(): ScanResult {
        val access = background?.let { runCatchingCancellable { it.sync() }.getOrNull() }
        if (access == null || !access.enabled) return scan(lookbackHours = 3)
        val fresh = sync?.refresh(advance = true).orEmpty()
        notifyFresh(fresh)
        return ScanResult(0, fresh, emptyList())
    }

    private suspend fun notifyFresh(fresh: List<Anomaly>) {
        val prefs = settings.current()
        val wanted = AnomalyNotifications.wanted(fresh, prefs.pushCritical, prefs.pushWarning)
        // Уведомить нечем (нет разрешения) — не отмечаем на стенде: пусть уведомит другое устройство пользователя.
        if (wanted.isEmpty() || !notifier.canNotify()) return
        val mine = background?.claim(wanted) ?: wanted
        if (mine.isNotEmpty()) notifier.notify(mine, prefs.pushCritical, prefs.pushWarning)
    }
}
