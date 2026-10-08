package ru.petrovich.telemetry.anomaly

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import ru.petrovich.telemetry.data.api.ApiFactory
import ru.petrovich.telemetry.data.settings.AppSettings
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/** Итог разового переноса локальной истории на стенд — для экрана настроек. */
@Serializable
data class ImportReport(
    /** Когда перенесено, epoch millis. */
    val at: Long,
    /** Сколько локальных записей отправлено. */
    val sent: Int,
    val result: ImportResponse,
    /** Машины, которые перенести не удалось (стенд не ответил): их записи остались на устройстве до следующей попытки. */
    val failed: List<String> = emptyList(),
)

/**
 * Лента и решения при хранилище на стенде ([AppSettings.anomalyStoreOnServer]). Источник — стенд, [AnomalyStore] —
 * локальный кэш для показа без сети. Выключено — всё, как раньше: решения только в [AnomalyStore]; записи стенда из
 * кэша убираются (у устройства другие id — иначе события задвоятся), на стенде они остаются.
 *
 * Локальная история (аномалии с id устройства, [Anomaly.isStandId] = false) переносится на стенд перед обновлением
 * ленты, по машине за запрос: стенд находит те же события у себя и переносит решения, несопоставленные решения —
 * в отчёте ([lastImport]). Записи машины убираются из кэша, когда её перенос прошёл без ошибок проверки; иначе
 * остаются и переносятся при следующем обновлении.
 */
class AnomalySync(
    private val settings: suspend () -> AppSettings,
    private val store: AnomalyStore,
    private val stand: StandAnomalies,
    private val reportFile: File,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    private val mutex = Mutex()
    private val _lastImport = MutableStateFlow(loadReport())

    /** Последний перенос локальной истории; null — ещё не было. */
    val lastImport: StateFlow<ImportReport?> = _lastImport.asStateFlow()

    suspend fun active(): Boolean = settings().anomalyStoreOnServer

    /**
     * Лента со стенда в кэш (перед этим — перенос локальной истории, если она есть). Возвращает аномалии, которые
     * появились на стенде после прошлого обновления на этом устройстве (для уведомлений); null — хранилище на стенде
     * выключено. Нет связи — исключение, кэш остаётся как был.
     *
     * Новизна — по времени первого обнаружения на стенде ([Anomaly.detectedAt], часы стенда) позже самого позднего
     * из уже полученных: так приходят и события, найденные другими пользователями схемы, а давние продолжающиеся
     * эпизоды не повторяются. Первое обновление (кэш без записей стенда) — только точка отсчёта, без уведомлений;
     * события старше суток уведомлений не дают (перенос чужой истории на стенд).
     */
    suspend fun refresh(): List<Anomaly>? = mutex.withLock {
        if (!active()) {
            val standIds = store.anomalies.value.filter { it.isStandId }.mapTo(HashSet()) { it.id }
            if (standIds.isNotEmpty()) store.removeAll(standIds)
            return@withLock null
        }
        importLocal()
        val cached = store.anomalies.value
        val known = cached.mapTo(HashSet()) { it.id }
        val watermark = cached.filter { it.isStandId }.maxOfOrNull { it.detectedAt }
        val items = stand.list(MAX_ITEMS).items
        // Не перенесённые (стенд не ответил) записи с id устройства остаются в ленте до следующей попытки.
        store.replaceAll(items + store.anomalies.value.filterNot { it.isStandId })
        if (watermark == null) return@withLock emptyList()
        val dayAgo = now().minusDays(1)
        items.filter { a ->
            a.id !in known && a.detectedAt > watermark &&
                (parseTime(a.eventTime)?.isAfter(dayAgo) ?: true)
        }
    }

    private suspend fun importLocal() {
        val local = store.anomalies.value.filterNot { it.isStandId }
        if (local.isEmpty()) return
        var total: ImportResponse? = null
        val failed = mutableListOf<String>()
        for ((vehicleId, items) in local.groupBy { it.vehicleId }) {
            val name = items.first().vehicleName.ifBlank { vehicleId }
            val attempt = runCatchingCancellable { stand.importHistory(items) }
            val result = attempt.getOrNull()
            if (result == null) {
                failed += "$name: ${attempt.exceptionOrNull()?.message}"
                continue
            }
            total = total?.plus(result) ?: result
            // Период не проверен — решения не сопоставлены по вине связи: записи остаются, перенос повторится.
            if (result.scanErrors.isEmpty()) store.removeAll(items.mapTo(HashSet()) { it.id })
        }
        val report = ImportReport(System.currentTimeMillis(), local.size, total ?: EMPTY_IMPORT, failed)
        saveReport(report)
        _lastImport.value = report
    }

    /** Решение владельца: на стенд (для всех пользователей схемы) и в кэш; без хранилища на стенде — только локально. */
    suspend fun resolve(id: String, resolution: Resolution, reason: String? = null) {
        if (onStand(id)) store.upsert(stand.resolve(id, resolution, reason), acknowledged = true)
        else store.resolve(id, resolution, reason)
    }

    /** Вернуть в «ждут решения». */
    suspend fun reopen(id: String) {
        if (onStand(id)) store.upsert(stand.resolve(id, null, null), acknowledged = false)
        else store.reopen(id)
    }

    // Запись с id устройства (ещё не перенесена — например, не было сети) решается локально и уедет при переносе.
    private suspend fun onStand(id: String): Boolean = active() && Anomaly.isStandId(id)

    private fun loadReport(): ImportReport? = runCatching {
        if (reportFile.exists()) ApiFactory.json.decodeFromString(ImportReport.serializer(), reportFile.readText()) else null
    }.getOrNull()

    private suspend fun saveReport(report: ImportReport) {
        runCatchingCancellable {
            withContext(Dispatchers.IO) { reportFile.writeText(ApiFactory.json.encodeToString(ImportReport.serializer(), report)) }
        }
    }

    companion object {
        /** Сколько последних аномалий держать в кэше — как в [AnomalyStore]. */
        const val MAX_ITEMS = 500

        private val EMPTY_IMPORT = ImportResponse(0, 0, 0, emptyList(), 0, 0)
    }
}

private fun parseTime(text: String): LocalDateTime? =
    try {
        LocalDateTime.parse(text)
    } catch (e: DateTimeParseException) {
        null
    }

private operator fun ImportResponse.plus(o: ImportResponse) = ImportResponse(
    decisions = decisions + o.decisions,
    applied = applied + o.applied,
    alreadyResolved = alreadyResolved + o.alreadyResolved,
    unmatched = unmatched + o.unmatched,
    restored = restored + o.restored,
    notFound = notFound + o.notFound,
    scanErrors = scanErrors + o.scanErrors,
)
