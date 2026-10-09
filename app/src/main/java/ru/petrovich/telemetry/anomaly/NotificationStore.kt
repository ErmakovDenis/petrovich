package ru.petrovich.telemetry.anomaly

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import ru.petrovich.telemetry.data.api.ApiFactory
import java.io.File

/**
 * Одна строка истории уведомлений (3.3 «Уведомления»). Заголовок/подзаголовок — уже готовый текст
 * (как в системном уведомлении), поэтому экран истории не должен ничего досочинять.
 */
@Serializable
data class NotificationItem(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Epoch millis. */
    val at: Long,
    val read: Boolean = false,
    /** id аномалии, если уведомление ведёт на карточку события. */
    val targetAnomalyId: String? = null,
)

/** Хранит историю уведомлений в JSON-файле — тот же шаблон, что и [AnomalyStore]. */
class NotificationStore(context: Context) {
    private val file = File(context.filesDir, "notifications.json")
    private val serializer = ListSerializer(NotificationItem.serializer())
    private val mutex = Mutex()
    private val maxItems = 200

    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<NotificationItem>> = _items.asStateFlow()

    private fun load(): List<NotificationItem> = runCatching {
        if (file.exists()) ApiFactory.json.decodeFromString(serializer, file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    private suspend fun save(list: List<NotificationItem>) = withContext(Dispatchers.IO) {
        _items.value = list
        // Пишем во временный файл и переименовываем: сбой посреди записи не сотрёт историю.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(ApiFactory.json.encodeToString(serializer, list))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Добавляет уведомления, пропуская уже известные id. */
    suspend fun addAll(notifications: List<NotificationItem>) = mutex.withLock {
        if (notifications.isEmpty()) return@withLock
        val current = _items.value
        val known = current.mapTo(HashSet()) { it.id }
        val fresh = notifications.filter { it.id !in known }
        if (fresh.isNotEmpty()) {
            save((fresh + current).sortedByDescending { it.at }.take(maxItems))
        }
    }

    suspend fun markRead(id: String) = mutex.withLock {
        save(_items.value.map { if (it.id == id) it.copy(read = true) else it })
    }

    suspend fun markAllRead() = mutex.withLock {
        save(_items.value.map { it.copy(read = true) })
    }
}
