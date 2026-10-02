package com.syncdroid.app.mesh

import androidx.room.withTransaction
import com.syncdroid.app.data.FolderAnnouncementEntity
import com.syncdroid.app.data.LocalFolderBindingEntity
import com.syncdroid.app.data.SyncDroidDatabase
import com.syncdroid.app.data.SyncFolderEntity
import com.syncdroid.app.storage.SyncFilterRules
import com.syncdroid.app.sync.VersionVector
import org.json.JSONArray

enum class LocalFolderBindingState {
    PENDING_CONFIGURATION,
    CONFIGURED,
    DECLINED,
}

class MeshFolderRepository(
    private val database: SyncDroidDatabase,
    private val currentDeviceId: String,
) {
    private val meshDao = database.meshDao()
    private val syncDao = database.syncDao()

    suspend fun announceLocalFolder(
        groupId: String,
        localLocation: String,
        displayName: String,
        rules: SyncFilterRules,
        signer: DeviceSigner,
        currentVersion: VersionVector = VersionVector(),
    ): FolderAnnouncement {
        require(signer.deviceId == currentDeviceId) { "The local identity must sign its own folder" }
        require(localLocation.isNotBlank()) { "A local folder location is required" }
        val announcement = FolderAnnouncement.create(
            groupId = groupId,
            displayName = displayName,
            includePatterns = rules.includes,
            excludePatterns = rules.excludes,
            signer = signer,
            version = currentVersion.increment(currentDeviceId),
        )
        applyVerified(announcement, localLocation)
        return announcement
    }

    suspend fun receive(announcement: FolderAnnouncement) {
        if (syncDao.hasFolderAnnouncement(announcement.eventId)) return
        applyVerified(announcement, localLocation = null)
    }

    suspend fun configureLocalFolder(folderId: String, localLocation: String) {
        require(localLocation.isNotBlank()) { "A local folder location is required" }
        requireNotNull(syncDao.getFolder(folderId)) { "Unknown mesh folder" }
        syncDao.upsertLocalBinding(
            LocalFolderBindingEntity(
                folderId = folderId,
                deviceId = currentDeviceId,
                localLocation = localLocation,
                state = LocalFolderBindingState.CONFIGURED.name,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun declineLocalFolder(folderId: String) {
        requireNotNull(syncDao.getFolder(folderId)) { "Unknown mesh folder" }
        syncDao.upsertLocalBinding(
            LocalFolderBindingEntity(
                folderId = folderId,
                deviceId = currentDeviceId,
                localLocation = null,
                state = LocalFolderBindingState.DECLINED.name,
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun applyVerified(announcement: FolderAnnouncement, localLocation: String?) {
        require(announcement.hasValidEventId()) { "Folder announcement ID does not match its payload" }
        requireNotNull(
            meshDao.getDevice(announcement.groupId, announcement.signerDeviceId),
        ) { "Folder announcement signer is not a member of this mesh" }
        val signer = requireNotNull(
            meshDao.signerAt(announcement.groupId, announcement.signerDeviceId, announcement.createdAtMillis),
        ) { "Folder announcement signer is not trusted" }
        require(announcement.verifySignature(decodePublicKey(signer.publicKeyBase64))) {
            "Folder announcement signature is invalid"
        }

        database.withTransaction {
            val existing = syncDao.getFolder(announcement.folderId)
            // Read this before updating the parent row. A destructive parent REPLACE would
            // otherwise cascade-delete the device-specific binding before it can be preserved.
            val existingBinding = syncDao.getBinding(announcement.folderId, currentDeviceId)
            val existingWithName = syncDao.getFolderByName(announcement.groupId, announcement.displayName)
            require(existingWithName == null || existingWithName.folderId == announcement.folderId) {
                "A mesh folder named '${announcement.displayName}' already exists"
            }
            require(existing == null || existing.matchesImmutableFields(announcement)) {
                "A different folder already uses this mesh folder ID"
            }
            syncDao.upsertFolder(
                announcement.toFolderEntity(
                    deletionPolicy = existing?.deletionPolicy ?: "PROPAGATE",
                ),
            )
            syncDao.insertFolderAnnouncement(announcement.toEntity())

            bindingUpdateForAnnouncement(
                folderId = announcement.folderId,
                deviceId = currentDeviceId,
                localLocation = localLocation,
                existingBinding = existingBinding,
                updatedAtMillis = System.currentTimeMillis(),
            )?.let { syncDao.upsertLocalBinding(it) }
        }
    }
}

internal fun bindingUpdateForAnnouncement(
    folderId: String,
    deviceId: String,
    localLocation: String?,
    existingBinding: LocalFolderBindingEntity?,
    updatedAtMillis: Long,
): LocalFolderBindingEntity? {
    if (localLocation == null && existingBinding != null) return null
    return LocalFolderBindingEntity(
        folderId = folderId,
        deviceId = deviceId,
        localLocation = localLocation,
        state = if (localLocation == null) {
            LocalFolderBindingState.PENDING_CONFIGURATION.name
        } else {
            LocalFolderBindingState.CONFIGURED.name
        },
        updatedAtMillis = updatedAtMillis,
    )
}

private fun FolderAnnouncement.toFolderEntity(deletionPolicy: String) = SyncFolderEntity(
    folderId = folderId,
    groupId = groupId,
    displayName = displayName,
    includePatternsJson = JSONArray(includePatterns).toString(),
    excludePatternsJson = JSONArray(excludePatterns).toString(),
    enabled = true,
    deletionPolicy = deletionPolicy,
    createdByDeviceId = signerDeviceId,
    versionVectorJson = version.toJson(),
    createdAtMillis = createdAtMillis,
)

private fun FolderAnnouncement.toEntity() = FolderAnnouncementEntity(
    eventId = eventId,
    groupId = groupId,
    folderId = folderId,
    displayName = displayName,
    includePatternsJson = JSONArray(includePatterns).toString(),
    excludePatternsJson = JSONArray(excludePatterns).toString(),
    signerDeviceId = signerDeviceId,
    signatureBase64 = signatureBase64,
    versionVectorJson = version.toJson(),
    createdAtMillis = createdAtMillis,
)

private fun SyncFolderEntity.matchesImmutableFields(value: FolderAnnouncement): Boolean =
    groupId == value.groupId &&
        displayName == value.displayName &&
        includePatternsJson == JSONArray(value.includePatterns).toString() &&
        excludePatternsJson == JSONArray(value.excludePatterns).toString() &&
        createdByDeviceId == value.signerDeviceId &&
        createdAtMillis == value.createdAtMillis
