package com.syncdroid.app.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.syncdroid.app.MainActivity
import com.syncdroid.app.R
import com.syncdroid.app.storage.StorageSyncWarning
import com.syncdroid.app.storage.formatStorageBytes

class SyncNotificationCenter(context: Context) {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)
    private val notificationState = appContext.getSharedPreferences(
        "sync_notification_state",
        Context.MODE_PRIVATE,
    )
    private val failureStateLock = Any()
    private var lastStorageWarningKey: String? = null

    init { createChannels() }

    @SuppressLint("MissingPermission")
    fun showSyncStarted(peerName: String) {
        if (!canNotify()) return
        manager.notify(SYNC_NOTIFICATION_ID, NotificationCompat.Builder(appContext, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_syncdroid)
            .setContentTitle("Syncing with $peerName")
            .setContentText("Comparing indexes and transferring changed files")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build())
    }

    @SuppressLint("MissingPermission")
    fun showSyncComplete(peerId: String, peerName: String) {
        if (!canNotify()) return
        synchronized(failureStateLock) {
            val failures = notificationState.getStringSet(ACTIVE_FAILURE_PEERS_KEY, emptySet())
                .orEmpty().toMutableSet()
            if (failures.remove(peerId)) {
                notificationState.edit().putStringSet(ACTIVE_FAILURE_PEERS_KEY, failures).apply()
            }
        }
        manager.notify(SYNC_NOTIFICATION_ID, NotificationCompat.Builder(appContext, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_syncdroid)
            .setContentTitle("Sync complete")
            .setContentText("Files are up to date with $peerName")
            .setAutoCancel(true)
            .setTimeoutAfter(8_000)
            .setContentIntent(openAppIntent())
            .build())
    }

    @SuppressLint("MissingPermission")
    fun showSyncFailed(peerId: String, peerName: String) {
        if (!canNotify()) return
        val shouldAlert = synchronized(failureStateLock) {
            val failures = notificationState.getStringSet(ACTIVE_FAILURE_PEERS_KEY, emptySet())
                .orEmpty().toMutableSet()
            if (!failures.add(peerId)) {
                false
            } else {
                notificationState.edit().putStringSet(ACTIVE_FAILURE_PEERS_KEY, failures).apply()
                true
            }
        }
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ACTIONS)
            .setSmallIcon(R.drawable.ic_syncdroid)
            .setContentTitle("Sync needs attention")
            .setContentText("Could not finish syncing with $peerName. Tap to review.")
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
        if (!shouldAlert) builder.setSilent(true)
        manager.notify(SYNC_NOTIFICATION_ID, builder.build())
    }

    @SuppressLint("MissingPermission")
    fun showChatMessages(count: Int, authorName: String, preview: String) {
        if (!canNotify()) return
        val title = if (count == 1) authorName else "$count new mesh messages"
        manager.notify(CHAT_NOTIFICATION_ID, NotificationCompat.Builder(appContext, CHANNEL_CHAT)
            .setSmallIcon(R.drawable.ic_syncdroid)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setNumber(count)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build())
    }

    @SuppressLint("MissingPermission")
    fun updateActionItems(conflicts: Int, foldersToConfigure: Int) {
        if (conflicts == 0 && foldersToConfigure == 0) {
            manager.cancel(ACTION_NOTIFICATION_ID)
            return
        }
        if (!canNotify()) return
        val folderText = "$foldersToConfigure folder${if (foldersToConfigure == 1) " needs" else "s need"} configuring"
        val conflictText = "$conflicts sync conflict${if (conflicts == 1) " needs" else "s need"} review"
        val detail = when {
            foldersToConfigure > 0 && conflicts > 0 -> "$conflictText · Tap to open Folders."
            foldersToConfigure > 0 -> "Tap to open Folders."
            else -> "Tap to review."
        }
        manager.notify(ACTION_NOTIFICATION_ID, NotificationCompat.Builder(appContext, CHANNEL_ACTIONS)
            .setSmallIcon(R.drawable.ic_syncdroid)
            .setContentTitle(if (foldersToConfigure > 0) folderText else conflictText)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setNumber(conflicts + foldersToConfigure)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(openFolders = foldersToConfigure > 0))
            .build())
    }

    @SuppressLint("MissingPermission")
    fun showStorageWarning(warning: StorageSyncWarning) {
        if (!canNotify()) return
        if (lastStorageWarningKey != null && lastStorageWarningKey != warning.key) {
            manager.cancel(STORAGE_NOTIFICATION_ID)
        }
        lastStorageWarningKey = warning.key
        val lowestAvailable = warning.destinations.minOfOrNull { it.availableBytes } ?: 0L
        val full = warning is StorageSyncWarning.Full
        val title = if (full) "Incoming sync paused · storage full" else "Low storage · approval required"
        val detail = if (full) {
            "Free storage space before receiving more files."
        } else {
            "${formatStorageBytes(lowestAvailable)} available. Tap to approve or pause incoming sync."
        }
        manager.notify(STORAGE_NOTIFICATION_ID, NotificationCompat.Builder(appContext, CHANNEL_ACTIONS)
            .setSmallIcon(R.drawable.ic_syncdroid)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build())
    }

    fun clearStorageWarning() {
        lastStorageWarningKey = null
        manager.cancel(STORAGE_NOTIFICATION_ID)
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(openFolders: Boolean = false): PendingIntent = PendingIntent.getActivity(
        appContext,
        if (openFolders) 1 else 0,
        Intent(appContext, MainActivity::class.java)
            .apply { if (openFolders) action = MainActivity.ACTION_OPEN_FOLDERS }
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = appContext.getSystemService(NotificationManager::class.java)
        system.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_SYNC, "Sync progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Current local mesh synchronization progress"
            },
            NotificationChannel(CHANNEL_ACTIONS, "Action required", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Conflicts, folder configuration and failed synchronization"
            },
            NotificationChannel(CHANNEL_CHAT, "Mesh chat", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Messages from trusted devices in your local mesh"
            },
        ))
    }

    private companion object {
        const val CHANNEL_SYNC = "sync_progress"
        const val CHANNEL_ACTIONS = "sync_actions"
        const val CHANNEL_CHAT = "mesh_chat"
        const val SYNC_NOTIFICATION_ID = 1001
        const val ACTION_NOTIFICATION_ID = 1002
        const val CHAT_NOTIFICATION_ID = 1003
        const val STORAGE_NOTIFICATION_ID = 1004
        const val ACTIVE_FAILURE_PEERS_KEY = "active_failure_peers"
    }
}
