package ru.petrovich.telemetry.anomaly

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import ru.petrovich.telemetry.data.api.ApiFactory
import java.io.File

/** Хранит найденные аномалии в JSON-файле. */
class AnomalyStore(context: Context) {
    private val file = File(context.filesDir, "anomalies.json")
    private val serializer = ListSerializer(Anomaly.serializer())
    private val mutex = Mutex()
    private val maxItems = 500

    private val _anomalies = MutableStateFlow(load())
    val anomalies: StateFlow<List<Anomaly>> = _anomalies.asStateFlow()

    private fun load(): List<Anomaly> = runCatching {
        if (file.exists()) ApiFactory.json.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private suspend fun save(list: List<Anomaly>) = withContext(Dispatchers.IO) {
        _anomalies.value = list
        file.writeText(ApiFactory.json.encodeToString(serializer, list))
    }

    /** Добавляет аномалии и возвращает только новые (ранее не встречавшиеся). */
    suspend fun addAll(found: List<Anomaly>): List<Anomaly> = mutex.withLock {
        val current = _anomalies.value
        val known = current.mapTo(HashSet()) { it.id }
        val fresh = found.filter { it.id !in known }
        if (fresh.isNotEmpty()) {
            save((fresh + current).sortedByDescending { it.eventTime }.take(maxItems))
        }
        fresh
    }

    suspend fun acknowledge(id: String) = mutex.withLock {
        save(_anomalies.value.map { if (it.id == id) it.copy(acknowledged = true) else it })
    }

    suspend fun acknowledgeAll() = mutex.withLock {
        save(_anomalies.value.map { it.copy(acknowledged = true) })
    }

    suspend fun clear() = mutex.withLock { save(emptyList()) }
}
