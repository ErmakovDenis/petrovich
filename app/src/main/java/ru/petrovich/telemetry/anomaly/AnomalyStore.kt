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

/**
 * Хранит найденные аномалии в JSON-файле. При хранилище на стенде ([AnomalySync]) — локальный кэш ленты со стенда
 * для показа без сети; отметка «просмотрено» ([Anomaly.acknowledged]) остаётся локальной.
 */
class AnomalyStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "anomalies.json"))

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
        // Пишем во временный файл и переименовываем: сбой посреди записи не сотрёт историю.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(ApiFactory.json.encodeToString(serializer, list))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
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

    /** Лента со стенда целиком вместо кэша; отметки «просмотрено» сохраняются по id. */
    suspend fun replaceAll(items: List<Anomaly>) = mutex.withLock {
        val seen = _anomalies.value.filter { it.acknowledged }.mapTo(HashSet()) { it.id }
        save(items.map { it.copy(acknowledged = it.acknowledged || it.id in seen) }
            .sortedByDescending { it.eventTime }.take(maxItems))
    }

    /**
     * Обновляет аномалию (например, после решения на стенде) или добавляет её. [acknowledged] — новая отметка
     * «просмотрено»; null — как была.
     */
    suspend fun upsert(anomaly: Anomaly, acknowledged: Boolean? = null) = mutex.withLock {
        val current = _anomalies.value
        val old = current.firstOrNull { it.id == anomaly.id }
        val updated = anomaly.copy(acknowledged = acknowledged ?: (anomaly.acknowledged || old?.acknowledged == true))
        save(if (old == null) (listOf(updated) + current).sortedByDescending { it.eventTime }.take(maxItems)
             else current.map { if (it.id == anomaly.id) updated else it })
    }

    /** Убирает записи по id (локальная история, перенесённая на стенд). */
    suspend fun removeAll(ids: Set<String>) = mutex.withLock {
        save(_anomalies.value.filterNot { it.id in ids })
    }

    suspend fun acknowledge(id: String) = mutex.withLock {
        save(_anomalies.value.map { if (it.id == id) it.copy(acknowledged = true) else it })
    }

    suspend fun resolve(id: String, resolution: Resolution, reason: String? = null) = mutex.withLock {
        save(_anomalies.value.map {
            if (it.id == id) it.copy(acknowledged = true, resolution = resolution, falseAlarmReason = reason) else it
        })
    }

    /** Возвращает аномалию в «ждут решения». */
    suspend fun reopen(id: String) = mutex.withLock {
        save(_anomalies.value.map {
            if (it.id == id) it.copy(acknowledged = false, resolution = null, falseAlarmReason = null) else it
        })
    }

    suspend fun acknowledgeAll() = mutex.withLock {
        save(_anomalies.value.map { it.copy(acknowledged = true) })
    }

    suspend fun clear() = mutex.withLock { save(emptyList()) }
}
