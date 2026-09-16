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
import ru.petrovich.telemetry.util.runCatchingCancellable
import java.util.concurrent.TimeUnit

/** Периодическая фоновая проверка телеметрии на аномалии. */
class AnomalyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = runCatchingCancellable {
        ServiceLocator.anomalyScanner.scan(lookbackHours = 3)
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() },
    )

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
