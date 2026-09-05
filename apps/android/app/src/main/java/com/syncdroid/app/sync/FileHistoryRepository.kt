package com.syncdroid.app.sync

import androidx.room.withTransaction
import android.content.Context
import android.net.Uri
import com.syncdroid.app.data.ActivityEventEntity
import com.syncdroid.app.data.FileVersionEntity
import com.syncdroid.app.data.LocalFolderBindingEntity
import com.syncdroid.app.data.RemoteFileVersionEntity
import com.syncdroid.app.data.SyncDroidDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

enum class FileHistoryAction { ADDED, UPDATED, SYNCED, DELETED, RECOVERED }

class FileHistoryRepository(
    context: Context,
    private val database: SyncDroidDatabase,
    private val localDeviceId: String,
) {
    private val appContext = context.applicationContext
    private val activityDao = database.activityDao()
    private val syncDao = database.syncDao()
    private val recoveryRoot = File(appContext.filesDir, "deleted-file-recovery")

    suspend fun deleteWithRecovery(
        binding: LocalFolderBindingEntity,
        versions: List<FileVersionEntity>,
        sourceDeviceId: String = localDeviceId,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        require(binding.state == "CONFIGURED" && !binding.localLocation.isNullOrBlank()) {
            "This folder is not configured on this device"
        }
        val liveVersions = versions.distinctBy(FileVersionEntity::relativePath).filterNot(FileVersionEntity::deleted)
        require(liveVersions.isNotEmpty()) { "No current files were selected" }
        cleanupExpired(nowMillis)

        val newlyArchived = mutableListOf<ArchivedDeletion>()
        val prepared = try {
            liveVersions.map { version ->
                val existing = activityDao.activeDeletion(
                    version.folderId,
                    version.relativePath,
                    version.contentSha256,
                )?.takeIf { event ->
                    event.recoveryPath?.let(::File)?.let { it.isFile && isInsideRecoveryRoot(it) } == true &&
                        (event.recoverableUntilMillis ?: 0L) > nowMillis
                }
                existing?.let { ArchivedDeletion(it, File(requireNotNull(it.recoveryPath)), newlyCreated = false) }
                    ?: archive(binding, version, sourceDeviceId, nowMillis).also(newlyArchived::add)
            }
        } catch (error: Throwable) {
            newlyArchived.forEach { it.file.delete() }
            throw error
        }

        prepared.forEach { archived ->
            val applier = fileApplier(binding, com.syncdroid.shared.sync.ExpectedFileContent(requireNotNull(archived.event.contentSha256)))
            applier.delete(requireNotNull(archived.event.relativePath))
            if (archived.newlyCreated) activityDao.insert(archived.event)
        }
    }

    suspend fun deleteFromAllDevices(binding: LocalFolderBindingEntity, paths: List<String>, permanent: Boolean = false) {
        require(paths.isNotEmpty()) { "Select a file" }
        for (path in paths.distinct()) {
            database.withTransaction {
                val version = requireNotNull(syncDao.fileVersion(binding.folderId, path)) { "Sync this file before deleting it" }
                if (version.deleted) return@withTransaction
                val state = requireNotNull(syncDao.folderIndexState(binding.folderId, localDeviceId))
                val now = System.currentTimeMillis()
                if (permanent) {
                    fileApplier(binding, com.syncdroid.shared.sync.ExpectedFileContent(version.contentSha256)).delete(path)
                    purgeRecoveries(binding.folderId, path)
                } else deleteWithRecovery(binding, listOf(version), localDeviceId, now)
                val sequence = state.maxSequence + 1
                syncDao.upsertFileVersion(version.copy(sizeBytes = 0, modifiedAtMillis = now,
                    contentSha256 = "", previousContentSha256 = version.contentSha256, deleted = true,
                    versionVectorJson = VersionVector.fromJson(version.versionVectorJson).increment(localDeviceId).toJson(),
                    originDeviceId = localDeviceId, localSequence = sequence, purgeRecovery = permanent))
                syncDao.upsertFolderIndexState(state.copy(maxSequence = sequence, metadataReceivedSequence = sequence,
                    contentAppliedSequence = sequence, updatedAtMillis = now))
            }
        }
    }

    suspend fun purgeRecoveries(folderId: String, relativePath: String) {
        activityDao.recoveriesForFile(folderId, relativePath).forEach { event ->
            val file = File(requireNotNull(event.recoveryPath))
            require(isInsideRecoveryRoot(file)) { "Invalid recovery location" }
            require(!file.exists() || file.delete()) { "Could not remove recovery copy" }
            activityDao.clearRecoveryPath(event.eventId)
        }
    }

    suspend fun recordRemoteApplied(
        remote: RemoteFileVersionEntity,
        wasNew: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (remote.deleted) return
        val verb = if (wasNew) "Synced new file" else "Synced updated file"
        activityDao.insert(fileHistoryEvent(
            action = FileHistoryAction.SYNCED,
            folderId = remote.folderId,
            relativePath = remote.relativePath,
            sourceDeviceId = remote.originDeviceId.ifBlank { remote.deviceId },
            sizeBytes = remote.sizeBytes,
            modifiedAtMillis = remote.modifiedAtMillis,
            contentSha256 = remote.contentSha256,
            createdAtMillis = nowMillis,
            title = verb,
        ))
    }

    suspend fun recover(eventId: String, nowMillis: Long = System.currentTimeMillis()): String {
        cleanupExpired(nowMillis)
        val event = requireNotNull(activityDao.event(eventId)) { "This history item is no longer available" }
        require(event.action == FileHistoryAction.DELETED.name) { "Only deleted files can be recovered" }
        require(event.recoveredAtMillis == null) { "This file has already been recovered" }
        require((event.recoverableUntilMillis ?: 0L) > nowMillis) { "The 30-day recovery window has expired" }
        val recoveryFile = event.recoveryPath?.let(::File)
        require(recoveryFile?.isFile == true && isInsideRecoveryRoot(recoveryFile)) {
            "The recovery copy is unavailable on this device"
        }
        val folderId = requireNotNull(event.folderId)
        val relativePath = requireNotNull(event.relativePath)
        val current = syncDao.fileVersion(folderId, relativePath)
        require(
            current == null || current.deleted || current.contentSha256.equals(event.contentSha256, true),
        ) { "A newer file already exists at this location" }
        val binding = requireNotNull(syncDao.getBinding(folderId, localDeviceId)) {
            "Configure this folder on this device before recovering the file"
        }
        val expectedHash = requireNotNull(event.contentSha256)
        FileInputStream(recoveryFile).use { input ->
            fileApplier(binding).apply(relativePath, input, expectedHash, event.modifiedAtMillis)
        }
        activityDao.markRecovered(eventId, nowMillis)
        recoveryFile.delete()
        activityDao.insert(fileHistoryEvent(
            action = FileHistoryAction.RECOVERED,
            folderId = folderId,
            relativePath = relativePath,
            sourceDeviceId = localDeviceId,
            sizeBytes = event.sizeBytes,
            modifiedAtMillis = event.modifiedAtMillis,
            contentSha256 = expectedHash,
            createdAtMillis = nowMillis,
            title = "Recovered file",
        ))
        return relativePath
    }

    suspend fun cleanupExpired(nowMillis: Long = System.currentTimeMillis()) {
        activityDao.expiredRecoveries(nowMillis).forEach { event ->
            event.recoveryPath?.let(::File)?.takeIf(::isInsideRecoveryRoot)?.delete()
            activityDao.clearRecoveryPath(event.eventId)
        }
        recoveryRoot.listFiles()?.filter { it.isFile && it.lastModified() < nowMillis - RECOVERY_RETENTION_MILLIS }
            ?.forEach(File::delete)
    }

    private fun archive(
        binding: LocalFolderBindingEntity,
        version: FileVersionEntity,
        sourceDeviceId: String,
        nowMillis: Long,
    ): ArchivedDeletion {
        require(recoveryRoot.isDirectory || recoveryRoot.mkdirs()) { "Could not create deleted-file recovery storage" }
        val eventId = UUID.randomUUID().toString()
        val target = File(recoveryRoot, "$eventId.bin")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            openFile(binding, version.relativePath).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                        }
                    }
                    output.fd.sync()
                }
            }
            val actualHash = digest.digest().toHex()
            require(actualHash.equals(version.contentSha256, true)) {
                "The file changed while its recovery copy was being created"
            }
            val event = fileHistoryEvent(
                eventId = eventId,
                action = FileHistoryAction.DELETED,
                folderId = version.folderId,
                relativePath = version.relativePath,
                sourceDeviceId = sourceDeviceId,
                sizeBytes = version.sizeBytes,
                modifiedAtMillis = version.modifiedAtMillis,
                contentSha256 = actualHash,
                createdAtMillis = nowMillis,
                title = "Deleted file",
                recoveryPath = target.absolutePath,
                recoverableUntilMillis = nowMillis + RECOVERY_RETENTION_MILLIS,
            )
            return ArchivedDeletion(event, target, newlyCreated = true)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun openFile(binding: LocalFolderBindingEntity, relativePath: String): InputStream {
        val location = requireNotNull(binding.localLocation)
        return if (location.startsWith("content://", true)) {
            requireNotNull(DocumentTreeFileApplier(appContext, Uri.parse(location)).open(relativePath)) {
                "The file is no longer available"
            }
        } else {
            FileInputStream(safeTarget(File(location), relativePath).also {
                require(it.isFile) { "The file is no longer available" }
            })
        }
    }

    private fun fileApplier(binding: LocalFolderBindingEntity, expected: com.syncdroid.shared.sync.ExpectedFileContent? = null): SyncFileApplier {
        val location = requireNotNull(binding.localLocation)
        return if (location.startsWith("content://", true)) {
            DocumentTreeFileApplier(appContext, Uri.parse(location), expected)
        } else {
            AtomicFileApplier(File(location), expected)
        }
    }

    private fun safeTarget(rootDirectory: File, relativePath: String): File {
        val root = rootDirectory.canonicalFile
        require(root.isDirectory) { "The configured folder is unavailable" }
        val target = File(root, normalizedRelativePath(relativePath)).canonicalFile
        require(target != root && target.toPath().startsWith(root.toPath())) { "Path is outside the configured folder" }
        return target
    }

    private fun isInsideRecoveryRoot(file: File): Boolean = runCatching {
        file.canonicalFile.toPath().startsWith(recoveryRoot.canonicalFile.toPath())
    }.getOrDefault(false)

    private data class ArchivedDeletion(
        val event: ActivityEventEntity,
        val file: File,
        val newlyCreated: Boolean,
    )

    companion object {
        const val RECOVERY_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}

fun fileHistoryEvent(
    action: FileHistoryAction,
    folderId: String,
    relativePath: String,
    sourceDeviceId: String,
    sizeBytes: Long?,
    modifiedAtMillis: Long?,
    contentSha256: String?,
    createdAtMillis: Long,
    title: String,
    eventId: String = UUID.randomUUID().toString(),
    recoveryPath: String? = null,
    recoverableUntilMillis: Long? = null,
): ActivityEventEntity = ActivityEventEntity(
    eventId = eventId,
    category = "FILE",
    title = title,
    detail = relativePath,
    createdAtMillis = createdAtMillis,
    action = action.name,
    folderId = folderId,
    relativePath = relativePath,
    sourceDeviceId = sourceDeviceId,
    sizeBytes = sizeBytes,
    modifiedAtMillis = modifiedAtMillis,
    contentSha256 = contentSha256,
    recoveryPath = recoveryPath,
    recoverableUntilMillis = recoverableUntilMillis,
)
