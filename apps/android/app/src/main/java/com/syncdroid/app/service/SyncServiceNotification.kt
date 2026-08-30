package com.syncdroid.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.syncdroid.app.MainActivity
import com.syncdroid.app.R
import com.syncdroid.app.scheduling.DiscoveryPolicy

class SyncServiceNotification(private val context: Context) {
    init { createChannel() }

    fun build(
        title: String,
        detail: String,
        policy: DiscoveryPolicy,
        syncing: Boolean = false,
        progress: Float? = null,
    ): Notification {
        val interval = formatInterval(policy.intervalMinutes)
        val window = formatWindow(policy.windowSeconds)
        val scheduleSummary = if (policy.alwaysOnDiscovery) {
            "Discovery always on"
        } else {
            "Discovery every $interval · window $window"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_syncdroid)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$detail\n$scheduleSummary\nUse the controls below to sync now or change the schedule.",
                ),
            )
            .setSubText(if (policy.alwaysOnDiscovery) "Discovery always on" else "Every $interval · $window window")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Interval: ${shortInterval(policy.intervalMinutes)}", serviceAction(
                requestCode = 2101,
                action = SyncForegroundService.ACTION_CYCLE_INTERVAL,
            ))
            .addAction(0, "Window: ${shortWindow(policy.windowSeconds)}", serviceAction(
                requestCode = 2102,
                action = SyncForegroundService.ACTION_CYCLE_WINDOW,
            ))
            .addAction(0, "Sync now", serviceAction(
                requestCode = 2103,
                action = SyncForegroundService.ACTION_REFRESH,
            ))
        if (syncing) {
            if (progress == null) builder.setProgress(0, 0, true)
            else builder.setProgress(100, (progress.coerceIn(0f, 1f) * 100).toInt(), false)
        }
        return builder.build()
    }

    private fun createChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Background mesh sync", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent local mesh status and discovery schedule controls"
                setShowBadge(false)
            },
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        2100,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun serviceAction(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getForegroundService(
            context,
            requestCode,
            Intent(context, SyncForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun shortInterval(minutes: Int): String = when {
        minutes == 7 * 24 * 60 -> "1w"
        minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)}d"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes}m"
    }
    private fun formatInterval(minutes: Int): String = when {
        minutes == 7 * 24 * 60 -> "1 week"
        minutes == 24 * 60 -> "24 hours"
        minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} days"
        minutes == 60 -> "1 hour"
        minutes % 60 == 0 -> "${minutes / 60} hours"
        else -> "$minutes minutes"
    }
    private fun shortWindow(seconds: Long): String = if (seconds < 60) "${seconds}s" else "${seconds / 60}m"
    private fun formatWindow(seconds: Long): String = when {
        seconds < 60 -> "$seconds seconds"
        seconds == 60L -> "1 minute"
        else -> "${seconds / 60} minutes"
    }

    companion object {
        const val NOTIFICATION_ID = 1100
        private const val CHANNEL_ID = "background_mesh_sync"
    }
}
