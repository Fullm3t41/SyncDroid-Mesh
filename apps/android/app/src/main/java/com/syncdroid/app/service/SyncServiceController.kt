package com.syncdroid.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.syncdroid.app.storage.LowStorageApprovalStore
import com.syncdroid.app.storage.StorageSyncWarning
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SyncServiceSnapshot(
    val running: Boolean = false,
    val status: String = "Starting background sync",
    val activePeerIds: Set<String> = emptySet(),
    val onlinePeerIds: Set<String> = emptySet(),
    val peerSyncProgress: Map<String, Float?> = emptyMap(),
    val syncRevision: Int = 0,
    val policyRevision: Int = 0,
    val storageWarning: StorageSyncWarning? = null,
)

object SyncServiceController {
    private val mutableAppInForeground = MutableStateFlow(false)
    val appInForeground: StateFlow<Boolean> = mutableAppInForeground.asStateFlow()

    private val mutableSnapshot = MutableStateFlow(SyncServiceSnapshot())
    val snapshot: StateFlow<SyncServiceSnapshot> = mutableSnapshot.asStateFlow()

    fun start(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, SyncForegroundService::class.java),
            )
        }.onFailure {
            report(running = false, status = "Open SyncDroid-Mesh to start background sync")
        }
    }

    fun requestCloudSync(context: Context) {
        ContextCompat.startForegroundService(context.applicationContext,
            Intent(context.applicationContext, SyncForegroundService::class.java).setAction(SyncForegroundService.ACTION_CLOUD_SYNC))
    }

    fun requestRefresh(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, SyncForegroundService::class.java)
                    .setAction(SyncForegroundService.ACTION_REFRESH),
            )
        }.onFailure {
            report(running = false, status = "Open SyncDroid-Mesh to start background sync")
        }
    }

    fun propagateMembershipChange(context: Context, addedDeviceId: String) {
        runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, SyncForegroundService::class.java)
                    .setAction(SyncForegroundService.ACTION_PROPAGATE_MEMBERSHIP)
                    .putExtra(SyncForegroundService.EXTRA_ADDED_DEVICE_ID, addedDeviceId),
            )
        }.onFailure {
            requestRefresh(context)
        }
    }

    fun propagateChatChange(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, SyncForegroundService::class.java)
                    .setAction(SyncForegroundService.ACTION_PROPAGATE_CHAT),
            )
        }.onFailure {
            requestRefresh(context)
        }
    }

    fun approveLowStorageSync(context: Context, warning: StorageSyncWarning.Low) {
        LowStorageApprovalStore(context).approve(
            warning.destinations.mapTo(mutableSetOf()) { it.destinationKey },
        )
        report(clearStorageWarning = true)
        requestRefresh(context)
    }

    fun setAppInForeground(inForeground: Boolean) {
        mutableAppInForeground.value = inForeground
    }

    /** Updates only the fields given, atomically, so one report cannot undo another's changes. */
    internal fun report(
        running: Boolean? = null,
        status: String? = null,
        activePeerIds: Set<String>? = null,
        onlinePeerIds: Set<String>? = null,
        peerSyncProgress: Map<String, Float?>? = null,
        syncCompleted: Boolean = false,
        policyChanged: Boolean = false,
        storageWarning: StorageSyncWarning? = null,
        clearStorageWarning: Boolean = false,
    ) {
        mutableSnapshot.update { current ->
            current.copy(
                running = running ?: current.running,
                status = status ?: current.status,
                activePeerIds = activePeerIds ?: current.activePeerIds,
                onlinePeerIds = onlinePeerIds ?: current.onlinePeerIds,
                peerSyncProgress = peerSyncProgress ?: current.peerSyncProgress,
                syncRevision = current.syncRevision + if (syncCompleted) 1 else 0,
                policyRevision = current.policyRevision + if (policyChanged) 1 else 0,
                storageWarning = if (clearStorageWarning) null else storageWarning ?: current.storageWarning,
            )
        }
    }
}
