package ru.petrovich.telemetry.ui.home

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
import ru.petrovich.telemetry.util.runCatchingCancellable

/**
 * Общий снимок виджетов за день / неделю / месяц.
 * Живёт в [ru.petrovich.telemetry.ServiceLocator], поэтому не сбрасывается при смене вкладки.
 * Свой произвольный период сюда не кладём — он грузится по запросу.
 */
class WidgetPeriodCache {
    data class Entry(val fleet: FleetWeek, val updatedAt: Long = System.currentTimeMillis())

    data class Snapshot(
        val byPeriod: Map<WidgetPeriod, Entry> = emptyMap(),
        val loading: Set<WidgetPeriod> = emptySet(),
    )

    private val loadLocks = WidgetPeriod.entries.associateWith { Mutex() }
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun clear() {
        _state.value = Snapshot()
    }

    /** Свежий снимок периода или null, если ещё не грузили / устарел. */
    fun peek(period: WidgetPeriod, maxAgeMs: Long = FRESH_MS): FleetWeek? {
        val entry = _state.value.byPeriod[period] ?: return null
        val age = System.currentTimeMillis() - entry.updatedAt
        return entry.fleet.takeIf { age in 0 until maxAgeMs }
    }

    /** Вернуть кэш, если свежий; иначе загрузить. */
    suspend fun ensureFresh(period: WidgetPeriod, maxAgeMs: Long = FRESH_MS): FleetWeek? {
        peek(period, maxAgeMs)?.let { return it }
        return loadLocks.getValue(period).withLock {
            peek(period, maxAgeMs)?.let { return it }
            loadAndStore(period)
        }
    }

    /** Принудительная перезагрузка одного периода. */
    suspend fun refresh(period: WidgetPeriod): FleetWeek? =
        loadLocks.getValue(period).withLock { loadAndStore(period) }

    private suspend fun loadAndStore(period: WidgetPeriod): FleetWeek? {
        _state.update { it.copy(loading = it.loading + period) }
        val fleet = runCatchingCancellable { load(period) }.getOrNull()
        _state.update { snap ->
            val byPeriod = if (fleet != null) snap.byPeriod + (period to Entry(fleet)) else snap.byPeriod
            snap.copy(byPeriod = byPeriod, loading = snap.loading - period)
        }
        return fleet
    }

    /**
     * Прогрев DAY / WEEK / MONTH / QUARTER: сначала неделя (главная), затем остальные.
     * Не больше двух параллельных загрузок.
     */
    suspend fun prefetch(
        periods: List<WidgetPeriod> = listOf(
            WidgetPeriod.WEEK, WidgetPeriod.DAY, WidgetPeriod.MONTH, WidgetPeriod.QUARTER,
        ),
    ) = coroutineScope {
        val ordered = periods.distinct()
        val limit = Semaphore(2)
        // Неделя первой — главная ждёт её сразу.
        if (WidgetPeriod.WEEK in ordered) {
            limit.withPermit { ensureFresh(WidgetPeriod.WEEK) }
        }
        ordered.filter { it != WidgetPeriod.WEEK }.map { period ->
            async { limit.withPermit { ensureFresh(period) } }
        }.awaitAll()
    }

    private suspend fun load(period: WidgetPeriod): FleetWeek = when (period) {
        WidgetPeriod.DAY -> loadFleetRange(period.toRange())
        WidgetPeriod.WEEK, WidgetPeriod.MONTH, WidgetPeriod.QUARTER -> loadFleetPeriod(period)
    }

    companion object {
        /** Между открытиями экранов снимок свежий; сеть — только по истечении TTL. */
        const val FRESH_MS = 5 * 60_000L
    }
}
