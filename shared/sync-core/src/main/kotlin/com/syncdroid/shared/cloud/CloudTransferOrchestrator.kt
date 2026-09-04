package com.syncdroid.shared.cloud

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CloudSyncTrigger { MANUAL, SCHEDULED_WINDOW, LOCAL_CHANGE }

data class CloudTransferResult(
    val uploadedFiles: Int = 0,
    val downloadedFiles: Int = 0,
    val conflicts: Int = 0,
    val transferredBytes: Long = 0,
) {
    operator fun plus(other: CloudTransferResult) = CloudTransferResult(
        uploadedFiles + other.uploadedFiles,
        downloadedFiles + other.downloadedFiles,
        conflicts + other.conflicts,
        transferredBytes + other.transferredBytes,
    )
}

fun interface CloudTransferRunner {
    suspend fun transfer(provider: CloudProvider, folderId: String): CloudTransferResult
}

/**
 * Single-flight orchestration shared by every desktop target. Scheduling remains
 * OS-specific, while the rules deciding what a scheduled run covers stay identical.
 */
class CloudTransferOrchestrator(
    private val policy: () -> CloudSyncPolicy,
    private val folderIds: () -> Collection<String>,
    private val connectedProviders: () -> Collection<CloudProvider>,
    private val runner: CloudTransferRunner,
    private val onProgress: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    @Volatile private var stopping = false

    suspend fun stopAndDrain() {
        stopping = true
        mutex.withLock { /* Wait for the admitted run to finish. */ }
    }

    suspend fun run(trigger: CloudSyncTrigger): CloudTransferResult = mutex.withLock {
        if (stopping) return@withLock CloudTransferResult()
        val currentPolicy = policy()
        if (currentPolicy.scope == CloudSyncScope.DISABLED) return@withLock CloudTransferResult()
        val folders = folderIds().filter(currentPolicy::isEnabledFor)
        val providers = connectedProviders().toList()
        if (folders.isEmpty() || providers.isEmpty()) return@withLock CloudTransferResult()

        var total = CloudTransferResult()
        providers.forEach { provider ->
            folders.forEach { folderId ->
                onProgress("${provider.displayName} · syncing cloud folder")
                total += runner.transfer(provider, folderId)
            }
        }
        onProgress(
            when {
                total.conflicts > 0 -> "Cloud sync finished · ${total.conflicts} item${if (total.conflicts == 1) "" else "s"} need review"
                total.uploadedFiles + total.downloadedFiles > 0 ->
                    "Cloud sync finished · ${total.uploadedFiles} up, ${total.downloadedFiles} down"
                else -> if (trigger == CloudSyncTrigger.MANUAL) "Cloud files are up to date" else "Scheduled cloud sync complete"
            },
        )
        total
    }
}
