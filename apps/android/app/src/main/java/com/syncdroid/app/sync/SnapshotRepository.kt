package com.syncdroid.app.sync

import com.syncdroid.shared.sync.isSyncIgnoredPath
import android.content.Context
import android.net.Uri
import com.syncdroid.app.data.FileVersionEntity
import com.syncdroid.app.data.FolderIndexStateEntity
import com.syncdroid.app.data.SnapshotEntity
import com.syncdroid.app.data.SnapshotFileEntity
import com.syncdroid.app.data.SyncDao
import com.syncdroid.app.data.SyncDroidDatabase
import com.syncdroid.app.mesh.DeviceSigner
import com.syncdroid.app.storage.SyncFilterRules
import com.syncdroid.shared.sync.shouldFinalizeOverwriteOnlyException
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID

class SnapshotRepository(
    database: SyncDroidDatabase,
    signer: DeviceSigner,
    private val scanner: DirectFolderScanner = DirectFolderScanner(),
    private val epochSource: () -> Long = ::randomIndexEpoch,
) {
    private val syncDao: SyncDao = database.syncDao()
    private val meshDao = database.meshDao()
    private val activityDao = database.activityDao()
    private val exceptionRepository = FolderExceptionRepository(database, signer)
    suspend fun scanDirectFolder(
        folderId: String,
        originDeviceId: String,
        rootDirectory: File,
        rules: SyncFilterRules,
        nowMillis: Long = System.currentTimeMillis(),
    ): SnapshotManifest? {
        val localExceptions = exceptionRepository.locallyActivePaths(folderId, originDeviceId)
        val presentPaths = scanner.listRelativeFilePaths(rootDirectory)
        val scannedFiles = scanner.scan(rootDirectory, rules, localExceptions)
        return persistScan(folderId, originDeviceId, scannedFiles, presentPaths, nowMillis)
    }

    suspend fun scanDocumentTree(
        context: Context,
        folderId: String,
        originDeviceId: String,
        treeUri: Uri,
        rules: SyncFilterRules,
        nowMillis: Long = System.currentTimeMillis(),
    ): SnapshotManifest? {
        val localExceptions = exceptionRepository.locallyActivePaths(folderId, originDeviceId)
        val treeScanner = DocumentTreeScanner(context)
        val presentPaths = treeScanner.listRelativeFilePaths(treeUri)
        val scannedFiles = treeScanner.scan(treeUri, rules, localExceptions)
        return persistScan(folderId, originDeviceId, scannedFiles, presentPaths, nowMillis)
    }

    private suspend fun persistScan(
        folderId: String,
        originDeviceId: String,
        scannedFiles: List<FileManifestEntry>,
        locallyPresentPaths: Set<String>,
        nowMillis: Long,
    ): SnapshotManifest? {
        val folder = requireNotNull(syncDao.getFolder(folderId)) { "Unknown mesh folder" }
        val policy = runCatching { FolderDeletionPolicy.valueOf(folder.deletionPolicy) }
            .getOrDefault(FolderDeletionPolicy.PROPAGATE)
        val localActiveExceptions = exceptionRepository.locallyActivePaths(folderId, originDeviceId)
        val awaitingRemoteResolution = syncDao.pathsAwaitingRemoteResolution(folderId).toSet()
        val previousSnapshot = syncDao.latestSnapshot(folderId)
        val previousFiles = existingFileVersions(folderId, previousSnapshot)
            .associateBy(FileVersionEntity::relativePath)
        val existingLocalIndex = syncDao.folderIndexState(folderId, originDeviceId)
        val localIndex = existingLocalIndex ?: FolderIndexStateEntity(
            folderId = folderId,
            deviceId = originDeviceId,
            indexEpoch = epochSource().nonZeroEpoch(),
            maxSequence = 0,
            metadataReceivedSequence = 0,
            contentAppliedSequence = 0,
            updatedAtMillis = nowMillis,
        )

        var nextSequence = localIndex.maxSequence
        var changed = false
        val updated = linkedMapOf<String, FileVersionEntity>()
        val historyEvents = mutableListOf<com.syncdroid.app.data.ActivityEventEntity>()
        val resolvedExceptionPaths = mutableListOf<String>()

        for (file in scannedFiles) {
            if (file.relativePath in awaitingRemoteResolution) continue
            val previous = previousFiles[file.relativePath]
            val unchanged = previous != null && previous.localSequence > 0 && !previous.deleted &&
                previous.sizeBytes == file.sizeBytes &&
                previous.contentSha256.equals(file.sha256, ignoreCase = true)
            val entry = if (unchanged) {
                previous
            } else {
                changed = true
                nextSequence++
                FileVersionEntity(
                    folderId = folderId,
                    relativePath = file.relativePath,
                    fileId = previous?.fileId?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString(),
                    sizeBytes = file.sizeBytes,
                    modifiedAtMillis = file.modifiedAtMillis,
                    contentSha256 = file.sha256.lowercase(),
                    previousContentSha256 = previous?.contentSha256?.takeIf(String::isNotBlank),
                    deleted = false,
                    versionVectorJson = (previous?.versionVectorJson?.let(VersionVector::fromJson) ?: VersionVector())
                        .increment(originDeviceId)
                        .toJson(),
                    originDeviceId = originDeviceId,
                    localSequence = nextSequence,
                ).also { current ->
                    val action = if (previous == null || previous.deleted) {
                        FileHistoryAction.ADDED
                    } else {
                        FileHistoryAction.UPDATED
                    }
                    historyEvents += fileHistoryEvent(
                        action = action,
                        folderId = folderId,
                        relativePath = current.relativePath,
                        sourceDeviceId = originDeviceId,
                        sizeBytes = current.sizeBytes,
                        modifiedAtMillis = current.modifiedAtMillis,
                        contentSha256 = current.contentSha256,
                        createdAtMillis = nowMillis,
                        title = if (action == FileHistoryAction.ADDED) "Added file" else "Updated file",
                    )
                }
            }
            updated[file.relativePath] = entry
        }

        val scannedPaths = scannedFiles.mapTo(mutableSetOf(), FileManifestEntry::relativePath)
        for ((path, previous) in previousFiles) {
            if (path in scannedPaths) continue
            when {
                isSyncIgnoredPath(path) -> updated[path] = previous
                path in awaitingRemoteResolution -> updated[path] = previous
                previous.deleted -> updated[path] = previous
                path in localActiveExceptions || policy == FolderDeletionPolicy.OVERWRITE_ONLY -> {
                    if (path !in locallyPresentPaths) {
                        exceptionRepository.recordLocalAbsenceIfNeeded(folderId, path, nowMillis)
                    }
                    if (
                        path !in locallyPresentPaths &&
                        shouldFinalizeException(folder.groupId, folderId, path, originDeviceId)
                    ) {
                        changed = true
                        nextSequence++
                        updated[path] = previous.copy(
                            sizeBytes = 0,
                            modifiedAtMillis = nowMillis,
                            contentSha256 = "",
                            previousContentSha256 = previous.contentSha256.takeIf(String::isNotBlank),
                            deleted = true,
                            versionVectorJson = VersionVector.fromJson(previous.versionVectorJson)
                                .increment(originDeviceId)
                                .toJson(),
                            originDeviceId = originDeviceId,
                            localSequence = nextSequence,
                        )
                        resolvedExceptionPaths += path
                        if (activityDao.activeDeletion(folderId, path, previous.contentSha256) == null) {
                            historyEvents += fileHistoryEvent(
                                action = FileHistoryAction.DELETED,
                                folderId = folderId,
                                relativePath = path,
                                sourceDeviceId = originDeviceId,
                                sizeBytes = previous.sizeBytes,
                                modifiedAtMillis = previous.modifiedAtMillis,
                                contentSha256 = previous.contentSha256,
                                createdAtMillis = nowMillis,
                                title = "Deleted from every device",
                            )
                        }
                    } else {
                        // Keep the last known live version until every participating device reports it absent.
                        updated[path] = previous
                    }
                }
                else -> {
                    changed = true
                    nextSequence++
                    updated[path] = previous.copy(
                        sizeBytes = 0,
                        modifiedAtMillis = nowMillis,
                        contentSha256 = "",
                        previousContentSha256 = previous.contentSha256.takeIf(String::isNotBlank),
                        deleted = true,
                        versionVectorJson = VersionVector.fromJson(previous.versionVectorJson)
                            .increment(originDeviceId)
                            .toJson(),
                        originDeviceId = originDeviceId,
                        localSequence = nextSequence,
                    )
                    if (activityDao.activeDeletion(folderId, path, previous.contentSha256) == null) {
                        historyEvents += fileHistoryEvent(
                            action = FileHistoryAction.DELETED,
                            folderId = folderId,
                            relativePath = path,
                            sourceDeviceId = originDeviceId,
                            sizeBytes = previous.sizeBytes,
                            modifiedAtMillis = previous.modifiedAtMillis,
                            contentSha256 = previous.contentSha256,
                            createdAtMillis = nowMillis,
                            title = "Deleted file",
                        )
                    }
                }
            }
        }

        if (!changed) {
            if (existingLocalIndex == null) syncDao.upsertFolderIndexState(localIndex)
            return null
        }

        val snapshotId = UUID.randomUUID().toString()
        val parentIds = listOfNotNull(previousSnapshot?.snapshotId)
        val files = updated.values.sortedBy(FileVersionEntity::relativePath).map(FileVersionEntity::toManifest)
        val snapshotVersion = files.fold(VersionVector()) { merged, file -> merged.merge(file.version) }
        val manifest = SnapshotManifest(
            snapshotId = snapshotId,
            folderId = folderId,
            originDeviceId = originDeviceId,
            createdAtMillis = nowMillis,
            version = snapshotVersion,
            parentSnapshotIds = parentIds,
            files = files,
        )
        val updatedIndex = localIndex.copy(
            maxSequence = nextSequence,
            metadataReceivedSequence = nextSequence,
            contentAppliedSequence = nextSequence,
            updatedAtMillis = nowMillis,
        )
        syncDao.insertVersionedSnapshot(
            SnapshotEntity(
                snapshotId = snapshotId,
                folderId = folderId,
                originDeviceId = originDeviceId,
                createdAtMillis = nowMillis,
                versionVectorJson = snapshotVersion.toJson(),
                parentSnapshotIdsJson = parentIds.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
                state = SnapshotState.Complete.name,
            ),
            files.map { it.toEntity(snapshotId) },
            updated.values.toList(),
            updatedIndex,
        )
        if (historyEvents.isNotEmpty()) activityDao.insertAll(historyEvents)
        resolvedExceptionPaths.forEach { path -> exceptionRepository.undo(folderId, path, nowMillis) }
        return manifest
    }

    private suspend fun shouldFinalizeException(
        groupId: String,
        folderId: String,
        relativePath: String,
        localDeviceId: String,
    ): Boolean {
        val trustedDeviceIds = meshDao.trustedDevices(groupId).mapTo(mutableSetOf()) { it.deviceId }
        if (localDeviceId !in trustedDeviceIds) trustedDeviceIds += localDeviceId
        val participantDeviceIds = syncDao.folderIndexStates(folderId)
            .mapTo(mutableSetOf()) { it.deviceId }
            .apply { add(localDeviceId) }
            .intersect(trustedDeviceIds)
        val absenceReporters = exceptionRepository.absenceReporterDeviceIds(folderId, relativePath)
        val tombstonedDeviceIds = syncDao.remoteFileVersionsForPath(folderId, relativePath)
            .filterTo(mutableListOf()) { it.deleted }
            .mapTo(mutableSetOf()) { it.deviceId }
        syncDao.fileVersion(folderId, relativePath)
            ?.takeIf { it.deleted }
            ?.let { tombstonedDeviceIds += localDeviceId }
        return shouldFinalizeOverwriteOnlyException(
            participantDeviceIds,
            absenceReporters,
            tombstonedDeviceIds,
        )
    }

    private suspend fun existingFileVersions(
        folderId: String,
        previousSnapshot: SnapshotEntity?,
    ): List<FileVersionEntity> {
        val current = syncDao.fileVersions(folderId)
        if (current.isNotEmpty() || previousSnapshot == null) return current
        val legacyVector = VersionVector.fromJson(previousSnapshot.versionVectorJson)
        return syncDao.filesForSnapshot(previousSnapshot.snapshotId).map { file ->
            FileVersionEntity(
                folderId = folderId,
                relativePath = file.relativePath,
                fileId = file.fileId.ifBlank { stableLegacyFileId(folderId, file.relativePath) },
                sizeBytes = file.sizeBytes,
                modifiedAtMillis = file.modifiedAtMillis,
                contentSha256 = file.sha256,
                previousContentSha256 = file.previousSha256,
                deleted = file.deleted,
                versionVectorJson = file.versionVectorJson.takeUnless { it == "{}" } ?: legacyVector.toJson(),
                originDeviceId = previousSnapshot.originDeviceId,
                localSequence = file.localSequence,
            )
        }
    }
}

private fun FileVersionEntity.toManifest() = FileManifestEntry(
    relativePath = relativePath,
    sizeBytes = sizeBytes,
    modifiedAtMillis = modifiedAtMillis,
    sha256 = contentSha256,
    deleted = deleted,
    fileId = fileId,
    previousSha256 = previousContentSha256,
    version = VersionVector.fromJson(versionVectorJson),
    localSequence = localSequence,
    originDeviceId = originDeviceId,
)

private fun FileManifestEntry.toEntity(snapshotId: String) = SnapshotFileEntity(
    snapshotId = snapshotId,
    relativePath = relativePath,
    sizeBytes = sizeBytes,
    modifiedAtMillis = modifiedAtMillis,
    sha256 = sha256,
    deleted = deleted,
    fileId = fileId,
    previousSha256 = previousSha256,
    versionVectorJson = version.toJson(),
    localSequence = localSequence,
)

private fun stableLegacyFileId(folderId: String, path: String): String = UUID.nameUUIDFromBytes(
    "$folderId\u0000$path".toByteArray(StandardCharsets.UTF_8),
).toString()

private fun randomIndexEpoch(): Long = SecureRandom().nextLong().nonZeroEpoch()
private fun Long.nonZeroEpoch(): Long = (this and Long.MAX_VALUE).coerceAtLeast(1)
