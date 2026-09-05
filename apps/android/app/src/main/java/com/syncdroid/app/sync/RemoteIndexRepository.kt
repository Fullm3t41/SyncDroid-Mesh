package com.syncdroid.app.sync

import androidx.room.withTransaction
import com.syncdroid.app.data.ConflictEntity
import com.syncdroid.app.data.FileVersionEntity
import com.syncdroid.app.data.FolderIndexStateEntity
import com.syncdroid.app.data.RemoteFileVersionEntity
import com.syncdroid.app.data.SyncDroidDatabase
import com.syncdroid.shared.sync.FileSyncState
import com.syncdroid.shared.sync.decideFileSync as decideSharedFileSync
import com.syncdroid.shared.sync.validateFolderIndexUpdate
import java.security.SecureRandom
import java.nio.charset.StandardCharsets
import java.util.UUID

data class FileSyncPlan(
    val action: FileSyncAction,
    val relativePath: String,
    val local: FileVersionEntity?,
    val remote: RemoteFileVersionEntity,
    val reason: String,
    val remoteManifest: BlockManifest? = null,
)

fun decideFileSync(local: FileVersionEntity?, remote: RemoteFileVersionEntity): Pair<FileSyncAction, String> {
    require(!remote.purgeRecovery || remote.deleted) { "Recovery purge requires a deletion" }
    if (remote.purgeRecovery && local?.deleted != false && local?.purgeRecovery != true &&
        (local == null || VersionVector.fromJson(local.versionVectorJson).relationTo(VersionVector.fromJson(remote.versionVectorJson)) != com.syncdroid.shared.protocol.CausalRelation.After)) {
        return FileSyncAction.DownloadRemote to "Removing recovery copies for a permanent deletion"
    }
    val decision = decideSharedFileSync(
        local = local?.let {
            FileSyncState(it.deleted, it.contentSha256, it.previousContentSha256, VersionVector.fromJson(it.versionVectorJson))
        },
        remote = FileSyncState(
            remote.deleted,
            remote.contentSha256,
            remote.previousContentSha256,
            VersionVector.fromJson(remote.versionVectorJson),
        ),
    )
    return decision.action to decision.reason
}

class RemoteIndexRepository(
    private val database: SyncDroidDatabase,
    private val currentDeviceId: String,
) {
    private val syncDao = database.syncDao()
    private val indexStates = IndexStateRepository(syncDao)
    private val blockManifests = BlockManifestRepository(syncDao)

    suspend fun receive(remoteDeviceId: String, update: FolderIndexUpdate): Pair<IndexAcceptance, List<FileSyncPlan>> {
        validateFolderIndexUpdate(update)
        lateinit var acceptance: IndexAcceptance
        database.withTransaction {
            acceptance = indexStates.receiveMetadata(
                update.folderId,
                remoteDeviceId,
                update.indexEpoch,
                update.previousSequence,
                update.lastSequence,
                update.fullIndex,
            )
            if (acceptance is IndexAcceptance.Accepted) {
                if (update.fullIndex) syncDao.deleteRemoteFileVersions(update.folderId, remoteDeviceId)
                val remoteFiles = update.files.map { it.toEntity(update.folderId, remoteDeviceId) }
                if (remoteFiles.isNotEmpty()) syncDao.upsertRemoteFileVersions(remoteFiles)
            }
        }
        if (acceptance is IndexAcceptance.RequiresFullIndex) return acceptance to emptyList()
        update.files.forEach { record ->
            record.toBlockManifest(update.folderId)?.let { blockManifests.store(it) }
        }

        val remoteFiles = update.files.map { it.toEntity(update.folderId, remoteDeviceId) }
        val localByPath = syncDao.fileVersions(update.folderId).associateBy(FileVersionEntity::relativePath)
        val globallyActiveExceptions = syncDao.activeExceptions(update.folderId)
            .mapTo(mutableSetOf()) { it.relativePath }
        val exceptions = syncDao.syncExceptionEventsForDevice(update.folderId, currentDeviceId)
            .activePathsForDevice(currentDeviceId)
            .intersect(globallyActiveExceptions)
        val recordsByPath = update.files.associateBy { normalizedRelativePath(it.relativePath) }
        val plans = remoteFiles.map { remote ->
            val local = localByPath[remote.relativePath]
            val pendingResolution = syncDao.pendingRemoteResolution(update.folderId, remote.relativePath)
            val (action, reason) = if (pendingResolution?.matches(remote) == true) {
                FileSyncAction.DownloadRemote to if (pendingResolution.state == ConflictState.KeepBoth.name) {
                    "User kept both versions; restore the selected remote version at the original path"
                } else {
                    "User selected the remote version"
                }
            } else if (pendingResolution != null) {
                FileSyncAction.Nothing to "Waiting for the version selected by the user"
            } else if (!remote.deleted && remote.relativePath in exceptions) {
                FileSyncAction.Nothing to "This device has an active overwrite-only exception"
            } else {
                decideFileSync(local, remote)
            }
            if (action == FileSyncAction.Conflict) recordConflict(update.folderId, local, remote)
            val record = recordsByPath[remote.relativePath]
            val manifest = record?.toBlockManifest(update.folderId)
            FileSyncPlan(action, remote.relativePath, local, remote, reason, manifest)
        }
        return acceptance to plans
    }

    /**
     * Rebuilds work for metadata that was received but whose content was not applied.
     * This is what makes a transfer survive a disconnected or killed mesh session.
     * Block manifests are carried by the live update; older pending records safely
     * fall back to the hash-verified whole-file protocol.
     */
    suspend fun pendingPlans(remoteDeviceId: String, folderIds: Collection<String>): List<FileSyncPlan> = buildList {
        for (folderId in folderIds) {
            val state = syncDao.folderIndexState(folderId, remoteDeviceId) ?: continue
            if (state.contentAppliedSequence >= state.metadataReceivedSequence) continue
            val localByPath = syncDao.fileVersions(folderId).associateBy(FileVersionEntity::relativePath)
            val globallyActiveExceptions = syncDao.activeExceptions(folderId)
                .mapTo(mutableSetOf()) { it.relativePath }
            val exceptions = syncDao.syncExceptionEventsForDevice(folderId, currentDeviceId)
                .activePathsForDevice(currentDeviceId)
                .intersect(globallyActiveExceptions)
            val pending = syncDao.remoteFileVersions(folderId, remoteDeviceId)
                .filter { it.remoteSequence > state.contentAppliedSequence }
                .sortedBy(RemoteFileVersionEntity::remoteSequence)
            for (remote in pending) {
                val local = localByPath[remote.relativePath]
                val pendingResolution = syncDao.pendingRemoteResolution(folderId, remote.relativePath)
                val (action, reason) = if (pendingResolution?.matches(remote) == true) {
                    FileSyncAction.DownloadRemote to if (pendingResolution.state == ConflictState.KeepBoth.name) {
                        "User kept both versions; restore the selected remote version at the original path"
                    } else {
                        "User selected the remote version"
                    }
                } else if (pendingResolution != null) {
                    FileSyncAction.Nothing to "Waiting for the version selected by the user"
                } else if (!remote.deleted && remote.relativePath in exceptions) {
                    FileSyncAction.Nothing to "This device has an active overwrite-only exception"
                } else {
                    decideFileSync(local, remote)
                }
                if (action == FileSyncAction.Conflict) recordConflict(folderId, local, remote)
                val manifest = if (!remote.deleted) blockManifests.load(
                    remote.folderId,
                    remote.fileId,
                    remote.relativePath,
                    remote.sizeBytes,
                    remote.contentSha256,
                ) else null
                add(FileSyncPlan(action, remote.relativePath, local, remote, reason, manifest))
            }
        }
    }

    suspend fun markRemoteApplied(
        remoteDeviceId: String,
        remote: RemoteFileVersionEntity,
        acknowledgeRemoteSequence: Boolean = true,
    ) {
        database.withTransaction {
            val previousLocal = syncDao.fileVersion(remote.folderId, remote.relativePath)
            val pendingResolution = syncDao.pendingRemoteResolution(remote.folderId, remote.relativePath)
                ?.takeIf { it.matches(remote) }
            val localState = syncDao.folderIndexState(remote.folderId, currentDeviceId) ?: FolderIndexStateEntity(
                remote.folderId,
                currentDeviceId,
                randomEpoch(),
                0,
                0,
                0,
                System.currentTimeMillis(),
            )
            val nextSequence = localState.maxSequence + 1
            val appliedVector = if (pendingResolution != null && previousLocal != null) {
                VersionVector.fromJson(previousLocal.versionVectorJson)
                    .merge(VersionVector.fromJson(remote.versionVectorJson))
                    .increment(currentDeviceId)
                    .toJson()
            } else {
                remote.versionVectorJson
            }
            syncDao.upsertFileVersion(
                FileVersionEntity(
                    remote.folderId,
                    remote.relativePath,
                    remote.fileId,
                    remote.sizeBytes,
                    remote.modifiedAtMillis,
                    remote.contentSha256,
                    remote.previousContentSha256,
                    remote.deleted,
                    appliedVector,
                    remote.originDeviceId.ifBlank { remoteDeviceId },
                    nextSequence,
                    remote.purgeRecovery,
                ),
            )
            syncDao.upsertFolderIndexState(localState.copy(
                maxSequence = nextSequence,
                metadataReceivedSequence = nextSequence,
                contentAppliedSequence = nextSequence,
                updatedAtMillis = System.currentTimeMillis(),
            ))
            if (acknowledgeRemoteSequence) acknowledgeRemoteApplied(remoteDeviceId, remote)
            syncDao.completeRemoteResolution(remote.folderId, remote.relativePath, System.currentTimeMillis())
        }
    }

    suspend fun acknowledgeRemoteApplied(remoteDeviceId: String, remote: RemoteFileVersionEntity) {
        val remoteState = requireNotNull(syncDao.folderIndexState(remote.folderId, remoteDeviceId))
        indexStates.acknowledgeApplied(remote.folderId, remoteDeviceId, remoteState.indexEpoch, remote.remoteSequence)
    }

    private suspend fun recordConflict(folderId: String, local: FileVersionEntity?, remote: RemoteFileVersionEntity) {
        val conflictKey = "$folderId\u0000${remote.relativePath}\u0000${local?.contentSha256.orEmpty()}\u0000${remote.deviceId}\u0000${remote.contentSha256}"
        syncDao.upsertConflict(
            ConflictEntity(
                conflictId = UUID.nameUUIDFromBytes(conflictKey.toByteArray(StandardCharsets.UTF_8)).toString(),
                folderId = folderId,
                relativePath = remote.relativePath,
                leftSnapshotId = local?.let { "local:${it.fileId}:${it.contentSha256}" } ?: "local:missing",
                rightSnapshotId = "remote:${remote.deviceId}:${remote.fileId}:${remote.contentSha256}",
                state = ConflictState.Unresolved.name,
                createdAtMillis = System.currentTimeMillis(),
                resolvedAtMillis = null,
                renamedRelativePath = null,
            ),
        )
    }

}

private fun IndexedFileRecord.toBlockManifest(folderId: String): BlockManifest? =
    takeIf { !deleted && blocks.isNotEmpty() }?.let {
        BlockManifest(folderId, fileId, relativePath, sizeBytes, contentSha256, blockSizeBytes, blocks)
    }

private fun IndexedFileRecord.toEntity(folderId: String, deviceId: String) = RemoteFileVersionEntity(
    folderId,
    deviceId,
    normalizedRelativePath(relativePath),
    fileId,
    sizeBytes,
    modifiedAtMillis,
    contentSha256.lowercase(),
    previousContentSha256?.lowercase(),
    originDeviceId,
    deleted,
    version.toJson(),
    sequence,
    purgeRecovery,
)

private fun randomEpoch(): Long = (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
