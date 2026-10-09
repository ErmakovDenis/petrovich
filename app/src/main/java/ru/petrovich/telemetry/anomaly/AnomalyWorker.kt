package ru.petrovich.telemetry.anomaly

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.petrovich.telemetry.ServiceLocator
import ru.petrovich.telemetry.ui.home.HomeData
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Периодическая фоновая проверка телеметрии на аномалии +, раз в день, доклад. При фоновой проверке на стенде
 * устройство не проверяет само: обновляет стенду доступ и забирает новые аномалии ([AnomalyScanner.poll]).
 */
class AnomalyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatchingCancellable {
        val s = ServiceLocator.settings.current()
        // Пока не выбран источник данных (первый запуск), проверять нечего.
        if (!s.onboarded) return@runCatchingCancellable
        if (s.backgroundOnServer) {
            ServiceLocator.anomalyScanner.poll()
        } else {
            // Фоновую проверку на стенде выключили, а отозвать доступ сразу не вышло (нет сети) — повторяем.
            if (s.standAccessGranted) runCatchingCancellable { ServiceLocator.backgroundAccess.sync() }
            ServiceLocator.anomalyScanner.scan(lookbackHours = 3)
        }
        maybeSendDailyReport()
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() },
    )

    /**
     * Тик раз в 15 минут — доклад приходит не секунда в секунду, а в первом тике после назначенного
     * времени (точность — до четверти часа), и только если сегодня его ещё не отправляли.
     */
    private suspend fun maybeSendDailyReport() {
        val prefs = ServiceLocator.settings.current()
        val now = java.time.LocalDateTime.now()
        val today = now.toLocalDate()
        if (today.dayOfWeek !in prefs.reportDays || prefs.lastReportDate == today.toString()) return
        val reportTime = runCatching { LocalTime.parse(prefs.reportTime) }.getOrDefault(LocalTime.of(8, 0))
        if (now.toLocalTime().isBefore(reportTime)) return
        val anomalies = ServiceLocator.anomalyStore.anomalies.value
        val vehicleCount = runCatching { ServiceLocator.telemetry.vehicles().size }.getOrNull()?.takeIf { it > 0 }
        ServiceLocator.notifier.notifyDailyReport(HomeData(anomalies, vehicleCount, scanned = true, today = today))
        ServiceLocator.settings.update { it.copy(lastReportDate = today.toString()) }
    }

    companion object {
        private const val NAME = "anomaly-scan"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AnomalyWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
