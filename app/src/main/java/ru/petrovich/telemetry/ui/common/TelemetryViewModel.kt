package ru.petrovich.telemetry.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.Vehicle
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.LocalDateTime

enum class Period(val title: String, val hours: Long) {
    H6("6 ч", 6), H24("24 ч", 24), D3("3 дня", 72), D7("7 дней", 168)
}

data class TelemetryUiState(
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: String? = null,
    val period: Period = Period.H24,
    val telemetry: VehicleTelemetry? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val selectedVehicle: Vehicle? get() = vehicles.firstOrNull { it.id == selectedVehicleId }
}

class TelemetryViewModel(
    private val repository: TelemetryRepository = ServiceLocator.telemetry,
) : ViewModel() {

    private val _state = MutableStateFlow(TelemetryUiState())
    val state: StateFlow<TelemetryUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        reloadVehicles()
    }

    fun reloadVehicles() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatchingCancellable { repository.vehicles() }
                .onSuccess { list ->
                    val keep = _state.value.selectedVehicleId?.takeIf { id -> list.any { it.id == id } }
                    _state.update { it.copy(vehicles = list, selectedVehicleId = keep ?: list.firstOrNull()?.id, telemetry = null) }
                    loadTelemetry()
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e.toString(), vehicles = emptyList(), telemetry = null) } }
        }
    }

    fun selectVehicle(id: String) {
        if (id == _state.value.selectedVehicleId) return
        _state.update { it.copy(selectedVehicleId = id, telemetry = null) }
        refresh()
    }

    fun selectPeriod(period: Period) {
        if (period == _state.value.period) return
        _state.update { it.copy(period = period) }
        refresh()
    }

    fun refresh() {
        if (_state.value.vehicles.isEmpty()) return reloadVehicles()
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadTelemetry() }
    }

    private suspend fun loadTelemetry() {
        val s = _state.value
        val vehicle = s.selectedVehicle ?: run {
            _state.update { it.copy(loading = false) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        val to = LocalDateTime.now()
        runCatchingCancellable { repository.telemetry(vehicle, to.minusHours(s.period.hours), to) }
            .onSuccess { t -> _state.update { it.copy(telemetry = t, loading = false) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: e.toString()) } }
    }
}
