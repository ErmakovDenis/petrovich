package ru.petrovich.telemetry.anomaly

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ru.petrovich.telemetry.MainActivity
import ru.petrovich.telemetry.R

class AnomalyNotifier(private val context: Context) {

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_anomalies_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.channel_anomalies_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notify(all: List<Anomaly>) {
        // Информационные события (например, кратковременное пропадание питания) — только в списке, без уведомления.
        val anomalies = all.filter { it.severity >= Severity.WARNING }
        if (anomalies.isEmpty() || !canNotify()) return
        val manager = NotificationManagerCompat.from(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_ANOMALIES)
        }
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Отдельные уведомления для первых нескольких + сводное.
        anomalies.sortedByDescending { it.severity }.take(5).forEach { a ->
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${a.vehicleName}: ${a.title}")
                .setContentText(a.description)
                .setStyle(NotificationCompat.BigTextStyle().bigText(a.description))
                .setPriority(if (a.severity == Severity.CRITICAL) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setGroup(GROUP)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            manager.notify(a.id.hashCode(), n)
        }
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Выявлено аномалий: ${anomalies.size}")
            .setContentText(anomalies.map { it.vehicleName }.distinct().joinToString())
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(SUMMARY_ID, summary)
    }

    fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_ID = "anomalies"
        private const val GROUP = "ru.petrovich.telemetry.ANOMALIES"
        private const val SUMMARY_ID = 1
    }
}
