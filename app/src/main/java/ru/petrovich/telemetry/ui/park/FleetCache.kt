package ru.petrovich.telemetry.ui.park

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import ru.petrovich.telemetry.anomaly.VehicleActivity
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.settings.SettingsRepository
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.LocalDateTime

/**
 * Общий снимок парка для «Главной» и «Парка»: список машин и статус за сегодня.
 * Живёт в [ru.petrovich.telemetry.ServiceLocator], поэтому не сбрасывается при смене вкладки
 * (NavHost уничтожает composable, а кэш остаётся). Плитки «на линии / стоят / без связи»
 * обновляются здесь же — не нужно ждать полный скан аномалий.
 */
class FleetCache(
    private val repository: TelemetryRepository,
    private val settings: SettingsRepository,
) {
    data class Snapshot(
        val vehicles: List<Vehicle> = emptyList(),
        val statsById: Map<String, VehicleStats?> = emptyMap(),
        val updatedAt: Long = 0L,
        val loading: Boolean = false,
        val error: String? = null,
    )

    private val mutex = Mutex()
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun clear() {
        _state.value = Snapshot()
    }

    /** Вернуть кэш, если он ещё свежий или уже грузится; иначе перезагрузить. */
    suspend fun ensureFresh(maxAgeMs: Long = FRESH_MS) {
        val snap = _state.value
        if (snap.loading) return
        val age = System.currentTimeMillis() - snap.updatedAt
        if (snap.vehicles.isNotEmpty() && snap.updatedAt > 0L && age in 0 until maxAgeMs) return
        refresh()
    }

    /** Принудительное обновление (кнопка «обновить» на Парке). */
    suspend fun refresh() = mutex.withLock {
        _state.update { it.copy(loading = true, error = null) }
        val result = runCatchingCancellable {
            val list = repository.vehicles()
            // Список сразу — экран может показать машины, пока статусы ещё догружаются.
            _state.update { it.copy(vehicles = list, statsById = emptyMap()) }
            val stats = loadStats(list)
            list to stats
        }
        result.onSuccess { (list, stats) ->
            val now = System.currentTimeMillis()
            _state.value = Snapshot(vehicles = list, statsById = stats, updatedAt = now, loading = false, error = null)
            publishFleetCounts(stats.values.toList(), fetchFailures = list.size - stats.values.count { it != null }, at = now)
        }.onFailure { e ->
            _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }

    private suspend fun loadStats(vehicles: List<Vehicle>): Map<String, VehicleStats?> = coroutineScope {
        val now = LocalDateTime.now()
        val from = now.toLocalDate().atStartOfDay()
        val limit = Semaphore(3)
        vehicles.map { v ->
            async {
                val stats = limit.withPermit {
                    runCatchingCancellable { repository.telemetry(v, from, now) }.getOrNull()?.let { computeVehicleStats(it, now) }
                }
                // По мере ответа обновляем снимок — повторный заход на Парк видит уже частично заполненный кэш.
                _state.update { it.copy(statsById = it.statsById + (v.id to stats)) }
                v.id to stats
            }
        }.awaitAll().toMap()
    }

    private suspend fun publishFleetCounts(stats: List<VehicleStats?>, fetchFailures: Int, at: Long) {
        val activity = stats.filterNotNull().map { it.activity }
        if (activity.isEmpty() && fetchFailures == 0) return
        settings.update {
            it.copy(
                fleetOnline = activity.count { a -> a == VehicleActivity.ON_LINE },
                fleetIdle = activity.count { a -> a == VehicleActivity.STOPPED },
                fleetOffline = activity.count { a -> a == VehicleActivity.OFFLINE } + fetchFailures,
                fleetUpdatedAt = at,
            )
        }
    }

    companion object {
        /** Между переключениями вкладок считаем снимок свежим; принудительное обновление — через refresh(). */
        const val FRESH_MS = 5 * 60_000L
    }
}
