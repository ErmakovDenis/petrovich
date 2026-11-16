package ru.petrovich.telemetry.anomaly

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ru.petrovich.telemetry.data.TelemetryRepository
import ru.petrovich.telemetry.data.VehicleTelemetry
import ru.petrovich.telemetry.data.settings.SettingsRepository
import ru.petrovich.telemetry.ui.common.label
import ru.petrovich.telemetry.ui.common.shown
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** «На линии» / «стоят» / «без связи» — честная оценка по тем же данным, что уже загружены для поиска аномалий. */
enum class VehicleActivity { ON_LINE, STOPPED, OFFLINE }

/** Свежими считаем данные не старше этого — иначе машина показывается «без связи». */
private val FreshnessWindow: Duration = Duration.ofMinutes(45)
private const val MOVING_SPEED_KMH = 3.0

/** Последняя точка с хоть одним значением → по ней решаем, едет машина, стоит или давно не на связи. */
fun classifyActivity(t: VehicleTelemetry, now: LocalDateTime = LocalDateTime.now()): VehicleActivity {
    val columns = t.tables.values.flatMap { it.columns }
    val timestamps = t.tables.values.firstOrNull()?.timestamps ?: return VehicleActivity.OFFLINE
    val lastIdx = timestamps.indices.lastOrNull { i -> columns.any { c -> c.values.getOrNull(i) != null } } ?: return VehicleActivity.OFFLINE
    if (Duration.between(timestamps[lastIdx], now) > FreshnessWindow) return VehicleActivity.OFFLINE
    val speed = columns.firstOrNull { it.parameter.name == "Speed" }?.values?.getOrNull(lastIdx)
    return if ((speed ?: 0.0) >= MOVING_SPEED_KMH) VehicleActivity.ON_LINE else VehicleActivity.STOPPED
}

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
    private val notificationStore: NotificationStore,
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

        // Источник с готовой лентой (API детекции) отдаёт события парка одним запросом — машины по очереди
        // не проверяем. null — источник по машинам, старый путь; при своём сбое API тоже отдаёт null
        // и уходит в старый путь сам (SwitchingVehicleChecker). Ошибка, долетевшая сюда, — источник
        // отказался совсем: проверки не было, но телеметрию для статуса парка тянем всё равно.
        var fleetFailed = false
        val fleet = runCatchingCancellable { checker.checkAll(from, to) }
            .onFailure { fleetFailed = true; synchronized(errors) { errors += it.message ?: it.toString() } }
            .getOrNull()

        // Аномалии ищет [checker] (устройство или стенд). Телеметрию берём отдельно: она даёт честный статус
        // парка (едет / стоит / давно не на связи) для «Главной».
        val perVehicle = vehicles.map { v ->
            async {
                limit.withPermit {
                    val anomalies = when {
                        fleet != null || fleetFailed -> emptyList()
                        else -> runCatchingCancellable { checker.check(v, from, to) }
                            .onFailure { synchronized(errors) { errors += "${v.name}: ${it.message}" } }
                            .getOrDefault(emptyList())
                    }
                    val telemetry = runCatchingCancellable { repository.telemetry(v, from, to) }.getOrNull()
                    anomalies to telemetry
                }
            }
        }.awaitAll()

        val found = fleet ?: perVehicle.flatMap { it.first }

        val fresh = if (onStand) {
            // Не вышло — кэш как был, новые придут со следующим обновлением (их время обнаружения позже отметки).
            runCatchingCancellable { sync?.refresh(advance = true) }.getOrNull().orEmpty()
        } else {
            store.addAll(found)
        }
        remember(fresh)
        // Если ни одна машина не ответила — проверкой это считать нельзя, иначе сводка скажет «всё в порядке».
        // При фоновой проверке на стенде время проверки — со стенда (его ставит обновление ленты).
        // Лента API не пришла — проверки не было, даже если телеметрия машин загрузилась.
        if ((vehicles.isEmpty() || errors.size < vehicles.size) && !settings.current().backgroundOnServer && !fleetFailed) {
            settings.update { it.copy(lastScanAt = System.currentTimeMillis()) }
        }
        val telemetries = perVehicle.map { it.second }
        val activity = telemetries.filterNotNull().map { classifyActivity(it, to) }
        if (activity.isNotEmpty()) {
            val offlineFetchFailures = telemetries.count { it == null }
            settings.update {
                it.copy(
                    fleetOnline = activity.count { a -> a == VehicleActivity.ON_LINE },
                    fleetIdle = activity.count { a -> a == VehicleActivity.STOPPED },
                    fleetOffline = activity.count { a -> a == VehicleActivity.OFFLINE } + offlineFetchFailures,
                    fleetUpdatedAt = System.currentTimeMillis(),
                )
            }
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
        remember(fresh)
        notifyFresh(fresh)
        return ScanResult(0, fresh, emptyList())
    }

    /** История уведомлений (экран «Уведомления»). */
    private suspend fun remember(fresh: List<Anomaly>) {
        val visible = fresh.shown(settings.current().mutedKinds)
        if (visible.isEmpty()) return
        notificationStore.addAll(visible.map {
            NotificationItem(
                id = it.id,
                title = "${it.severity.label()} · ${it.vehicleName}: ${it.title}",
                subtitle = it.description,
                at = it.detectedAt,
                targetAnomalyId = it.id,
            )
        })
    }

    private suspend fun notifyFresh(fresh: List<Anomaly>) {
        val prefs = settings.current()
        // Выключенный тип не приходит совсем, даже срочный — как в Telegram.
        val fresh = fresh.shown(prefs.mutedKinds)
        if (fresh.isEmpty()) return
        val today = LocalDate.now().toString()
        val sentToday = if (prefs.pushCountDate == today) prefs.pushCountToday else 0
        // В тихие часы и сверх дневного лимита продолжают идти только срочные — остальное видно в «Проблемах».
        // Окно берётся из настроек, как у Telegram-бота (некритичное пропускается и потом не досылается).
        val quiet = prefs.isQuietAt(LocalDateTime.now().toLocalTime())
        val overLimit = prefs.limitDailyPush && sentToday >= 3
        val pushWarning = prefs.pushWarning && !quiet && !overLimit
        val toNotify = if (prefs.backgroundOnServer && background != null) {
            // Фоновая проверка на стенде: одно уведомление на пользователя — стенд отмечает, что уже показано на
            // других его устройствах. Уведомить нечем (нет разрешения) — не отмечаем: пусть уведомит другое устройство.
            val wanted = AnomalyNotifications.wanted(fresh, prefs.pushCritical, pushWarning)
            if (wanted.isEmpty() || !notifier.canNotify()) return
            background.claim(wanted)
        } else {
            fresh
        }
        if (toNotify.isEmpty()) return
        notifier.notify(toNotify, prefs.pushCritical, pushWarning)
        val pushedWarnings = if (pushWarning) toNotify.count { it.severity == Severity.WARNING } else 0
        if (pushedWarnings > 0) {
            settings.update { it.copy(pushCountDate = today, pushCountToday = sentToday + pushedWarnings) }
        }
    }
}
