package com.syncdows.app.mesh

import com.syncdows.app.platform.WindowsAppPaths

import com.syncdroid.shared.sync.IndexReceiveDecision
import com.syncdroid.shared.sync.IndexStateSnapshot
import com.syncdroid.shared.sync.acknowledgeIndexContent
import com.syncdroid.shared.sync.normalizeRelativePath
import com.syncdroid.shared.sync.reconcileReceivedIndex
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import com.syncdroid.shared.protocol.WireChatAttachment
import java.sql.Statement
import java.util.Base64
import java.util.UUID

data class MeshProfile(val groupId: String, val groupName: String, val createdAtMillis: Long)

data class TrustedDevice(
    val groupId: String,
    val deviceId: String,
    val displayName: String,
    val identityPublicKeyBase64: String,
    val tlsPublicKeyBase64: String?,
    val fingerprint: String,
    val trusted: Boolean,
    val lastSeenAtMillis: Long?,
)

enum class LocalFolderBindingState { PENDING_CONFIGURATION, CONFIGURED, DECLINED }

data class MeshFolder(
    val folderId: String,
    val groupId: String,
    val displayName: String,
    val includePatterns: List<String>,
    val excludePatterns: List<String>,
    val createdByDeviceId: String,
    val bindingState: LocalFolderBindingState,
    val localPath: String?,
)

data class FileVersion(
    val folderId: String,
    val relativePath: String,
    val fileId: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val contentSha256: String,
    val previousContentSha256: String?,
    val deleted: Boolean,
    val version: VersionVector,
    val originDeviceId: String,
    val localSequence: Long,
    val purgeRecovery: Boolean = false,
)

data class RemoteFileVersion(
    val folderId: String,
    val deviceId: String,
    val relativePath: String,
    val fileId: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val contentSha256: String,
    val previousContentSha256: String?,
    val originDeviceId: String,
    val deleted: Boolean,
    val version: VersionVector,
    val remoteSequence: Long,
    val purgeRecovery: Boolean = false,
)

data class FolderIndexState(
    val folderId: String,
    val deviceId: String,
    val indexEpoch: Long,
    val maxSequence: Long,
    val metadataReceivedSequence: Long,
    val contentAppliedSequence: Long,
    val updatedAtMillis: Long,
)

private fun FolderIndexState.toSnapshot() = IndexStateSnapshot(
    indexEpoch, maxSequence, metadataReceivedSequence, contentAppliedSequence,
)

data class SyncExceptionState(
    val folderId: String,
    val relativePath: String,
    val active: Boolean,
    val createdByDeviceId: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val version: VersionVector,
    val lastEventId: String,
)

data class FileConflict(
    val conflictId: String,
    val folderId: String,
    val relativePath: String,
    val localHash: String?,
    val remoteDeviceId: String,
    val remoteHash: String,
    val createdAtMillis: Long,
)

enum class ConflictResolutionAction { KEEP_REMOTE, KEEP_BOTH }

data class FileConflictReview(
    val conflict: FileConflict,
    val local: FileVersion?,
    val remote: RemoteFileVersion,
)

data class PendingConflictResolution(
    val conflictId: String,
    val action: ConflictResolutionAction,
    val targetRelativePath: String,
)

enum class FileHistoryAction { ADDED, UPDATED, SYNCED, DELETED, RECOVERED }

data class FileHistoryEvent(
    val eventId: String,
    val action: FileHistoryAction,
    val folderId: String,
    val relativePath: String,
    val sourceDeviceId: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val contentSha256: String?,
    val createdAtMillis: Long,
    val recoveryPath: String?,
    val recoverableUntilMillis: Long?,
    val recoveredAtMillis: Long?,
)

data class StoredFolderKey(
    val folderId: String,
    val keyId: String,
    val encryptedKey: String,
)

private data class StoredFolder(
    val folderId: String,
    val groupId: String,
    val displayName: String,
    val includePatterns: List<String>,
    val excludePatterns: List<String>,
    val signerDeviceId: String,
    val version: VersionVector,
    val createdAtMillis: Long,
) {
    fun matches(value: FolderAnnouncement): Boolean =
        groupId == value.groupId &&
            displayName == value.displayName &&
            includePatterns == value.includePatterns &&
            excludePatterns == value.excludePatterns &&
            signerDeviceId == value.signerDeviceId &&
            version == value.version &&
            createdAtMillis == value.createdAtMillis
}

class MeshStore(databasePath: Path = defaultDatabasePath()) : AutoCloseable {
    private val connection: Connection
    val storageDirectory: Path = requireNotNull(databasePath.toAbsolutePath().parent)

    init {
        Files.createDirectories(storageDirectory)
        connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        connection.createStatement().use {
            it.execute("PRAGMA foreign_keys = ON")
            it.execute("PRAGMA journal_mode = WAL")
            it.execute("PRAGMA busy_timeout = 5000")
        }
        migrate()
    }

    @Synchronized
    fun profile(): MeshProfile? = connection.prepareStatement(
        "SELECT group_id, group_name, created_at_millis FROM mesh_profile LIMIT 1",
    ).use { statement -> statement.executeQuery().use { if (it.next()) it.profile() else null } }

    @Synchronized
    fun clearMesh() = transaction {
        listOf(
            "conflict_resolutions",
            "file_conflicts",
            "partial_transfers",
            "remote_file_blocks",
            "file_blocks",
            "remote_file_versions",
            "file_versions",
            "folder_index_states",
            "file_history",
            "sync_exceptions",
            "sync_exception_events",
            "chat_messages",
            "folder_key_history",
            "folder_keys",
            "local_folder_bindings",
            "folder_announcements",
            "mesh_folders",
            "membership_events",
            "devices",
            "mesh_profile",
        ).forEach { table -> connection.createStatement().use { it.executeUpdate("DELETE FROM $table") } }
    }

    @Synchronized
    fun createMesh(groupName: String, displayName: String, signer: DeviceSigner): MeshProfile {
        profile()?.let { return it }
        val cleanGroupName = groupName.trim().also { require(it.isNotEmpty() && it.length <= 64) }
        val profile = MeshProfile(UUID.randomUUID().toString(), cleanGroupName, System.currentTimeMillis())
        val creator = MembershipEvent.createAddDevice(
            profile.groupId,
            displayName,
            signer.publicKey,
            signer,
            emptyList(),
            VersionVector().increment(signer.deviceId),
            profile.createdAtMillis,
        )
        transaction {
            connection.prepareStatement("INSERT INTO mesh_profile VALUES (?, ?, ?)").use {
                it.setString(1, profile.groupId); it.setString(2, profile.groupName); it.setLong(3, profile.createdAtMillis)
                it.executeUpdate()
            }
            applyMembershipLocked(profile.groupName, creator)
        }
        return profile
    }

    @Synchronized
    fun applyMembership(groupName: String, event: MembershipEvent): Boolean = transaction {
        applyMembershipLocked(groupName, event)
    }

    @Synchronized
    fun applyFolder(event: FolderAnnouncement): Boolean = transaction { applyFolderLocked(event) }

    @Synchronized
    fun importBundle(
        bundle: MeshStateBundle,
        expectedOfferingIdentity: PairingIdentity? = null,
        requiredLocalDeviceId: String? = null,
    ): MeshProfile = transaction {
        require(bundle.membershipEvents.isNotEmpty()) { "Pairing response contains no membership" }
        val groupId = bundle.membershipEvents.first().groupId
        require(bundle.membershipEvents.all { it.groupId == groupId }) { "Pairing response mixes mesh groups" }
        require(bundle.folderAnnouncements.all { it.groupId == groupId }) { "Pairing response mixes folder groups" }
        require(bundle.syncExceptionEvents.all { it.groupId == groupId }) { "Pairing response mixes exception groups" }
        require(bundle.chatMessages.all { it.groupId == groupId }) { "Pairing response mixes chat groups" }
        bundle.membershipEvents.forEach { applyMembershipLocked(bundle.groupName, it) }
        val existing = profile()
        if (existing == null) {
            val created = bundle.membershipEvents.minOf(MembershipEvent::createdAtMillis)
            connection.prepareStatement("INSERT INTO mesh_profile VALUES (?, ?, ?)").use {
                it.setString(1, groupId); it.setString(2, bundle.groupName); it.setLong(3, created); it.executeUpdate()
            }
        } else {
            require(existing.groupId == groupId) { "This PC already belongs to a different mesh" }
        }
        val imported = requireNotNull(profile())
        expectedOfferingIdentity?.let { expected ->
            val offeringDevice = device(groupId, expected.deviceId)
            require(
                offeringDevice?.trusted == true &&
                    offeringDevice.identityPublicKeyBase64 == expected.publicKeySpkiBase64,
            ) { "Pairing device is not a trusted member of this mesh" }
        }
        requiredLocalDeviceId?.let { localId ->
            require(device(groupId, localId)?.trusted == true) {
                "Pairing response did not authorize this PC"
            }
        }
        bundle.folderAnnouncements
            .sortedWith(compareBy(FolderAnnouncement::createdAtMillis, FolderAnnouncement::eventId))
            .forEach(::applyFolderLocked)
        bundle.syncExceptionEvents
            .sortedWith(compareBy(SyncExceptionEvent::createdAtMillis, SyncExceptionEvent::eventId))
            .forEach(::applySyncExceptionLocked)
        bundle.chatMessages
            .sortedWith(compareBy(MeshChatMessage::createdAtMillis, MeshChatMessage::messageId))
            .forEach(::applyChatLocked)
        imported
    }

    @Synchronized
    fun exportBundle(): MeshStateBundle {
        val profile = requireNotNull(profile()) { "No mesh exists" }
        return MeshStateBundle(
            profile.groupName,
            membershipEvents(profile.groupId),
            folderAnnouncements(profile.groupId),
            syncExceptionEvents(profile.groupId),
            chatMessages = chatMessages(profile.groupId),
        )
    }

    @Synchronized
    fun syncExceptionEvents(groupId: String): List<SyncExceptionEvent> = connection.prepareStatement(
        """SELECT event_id, group_id, folder_id, relative_path, active, signer_device_id,
                  version_json, created_at_millis, signature
           FROM sync_exception_events WHERE group_id = ? ORDER BY created_at_millis, event_id""",
    ).use { statement ->
        statement.setString(1, groupId)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.syncExceptionEvent()) } }
    }

    @Synchronized
    fun activeSyncException(folderId: String, relativePath: String): Boolean = connection.prepareStatement(
        "SELECT active FROM sync_exceptions WHERE folder_id = ? AND relative_path = ? LIMIT 1",
    ).use { statement ->
        statement.setString(1, folderId); statement.setString(2, normalizeRelativePath(relativePath))
        statement.executeQuery().use { rows -> rows.next() && rows.getInt(1) != 0 }
    }

    @Synchronized
    fun localActiveSyncException(folderId: String, relativePath: String, deviceId: String): Boolean {
        if (!activeSyncException(folderId, relativePath)) return false
        return connection.prepareStatement(
            """SELECT active, version_json, event_id FROM sync_exception_events
               WHERE folder_id = ? AND relative_path = ? AND signer_device_id = ?""",
        ).use { statement ->
            statement.setString(1, folderId)
            statement.setString(2, normalizeRelativePath(relativePath))
            statement.setString(3, deviceId)
            statement.executeQuery().use { rows ->
                var latestCounter = -1L
                var latestEventId = ""
                var latestActive = false
                while (rows.next()) {
                    val counter = VersionVector.fromJson(rows.getString(2)).counters[deviceId] ?: 0L
                    val eventId = rows.getString(3)
                    if (counter > latestCounter || counter == latestCounter && eventId > latestEventId) {
                        latestCounter = counter
                        latestEventId = eventId
                        latestActive = rows.getInt(1) != 0
                    }
                }
                latestCounter >= 0 && latestActive
            }
        }
    }

    @Synchronized
    fun activeSyncExceptions(): List<SyncExceptionState> = connection.prepareStatement(
        """SELECT folder_id, relative_path, active, created_by_device_id, created_at_millis,
                  updated_at_millis, version_json, last_event_id
           FROM sync_exceptions WHERE active = 1 ORDER BY updated_at_millis DESC, relative_path""",
    ).use { statement ->
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.syncExceptionState()) } }
    }

    @Synchronized
    fun applySyncException(event: SyncExceptionEvent): Boolean = transaction { applySyncExceptionLocked(event) }

    @Synchronized
    fun recordSyncException(
        folderId: String,
        relativePath: String,
        active: Boolean,
        signer: DeviceSigner,
        nowMillis: Long = System.currentTimeMillis(),
    ): SyncExceptionEvent = transaction {
        val folder = requireNotNull(meshFolder(folderId)) { "Unknown mesh folder" }
        val normalized = normalizeRelativePath(relativePath)
        val existing = syncExceptionState(folderId, normalized)
        val version = (existing?.version ?: VersionVector()).increment(signer.deviceId)
        SyncExceptionEvent.create(
            folder.groupId, folderId, normalized, active, signer, version, nowMillis,
        ).also { applySyncExceptionLocked(it) }
    }

    @Synchronized
    fun applyChat(message: MeshChatMessage): Boolean = transaction { applyChatLocked(message) }

    @Synchronized
    fun chatMessages(groupId: String, limit: Int = MAX_REPLICATED_CHAT_MESSAGES): List<MeshChatMessage> =
        connection.prepareStatement(
            """SELECT message_id, group_id, author_device_id, body, created_at_millis, signature,
                      attachment_file_name, attachment_media_type, attachment_size_bytes,
                      attachment_sha256, attachment_expires_at_millis
               FROM (
                 SELECT message_id, group_id, author_device_id, body, created_at_millis, signature,
                        attachment_file_name, attachment_media_type, attachment_size_bytes,
                        attachment_sha256, attachment_expires_at_millis
                 FROM chat_messages WHERE group_id = ?
                 ORDER BY created_at_millis DESC, message_id DESC LIMIT ?
               ) ORDER BY created_at_millis, message_id""",
        ).use { statement ->
            statement.setString(1, groupId); statement.setInt(2, limit.coerceIn(1, MAX_REPLICATED_CHAT_MESSAGES))
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(MeshChatMessage(
                    rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getString(4), rows.getLong(5), rows.getString(6),
                    rows.getString(7)?.let { fileName ->
                        WireChatAttachment(
                            fileName, rows.getString(8).orEmpty(), rows.getLong(9),
                            rows.getString(10), rows.getLong(11),
                        )
                    },
                ))
            } }
        }

    @Synchronized
    fun chatMessage(groupId: String, messageId: String): MeshChatMessage? =
        chatMessages(groupId).firstOrNull { it.messageId == messageId }

    @Synchronized
    fun membershipEvents(groupId: String): List<MembershipEvent> = connection.prepareStatement(
        """SELECT event_id, group_id, event_type, subject_device_id, subject_name, subject_key,
                  signer_device_id, parent_ids, version_json, created_at_millis, signature
           FROM membership_events WHERE group_id = ? ORDER BY created_at_millis, event_id""",
    ).use { statement ->
        statement.setString(1, groupId)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.membership()) } }
    }

    @Synchronized
    fun devices(groupId: String): List<TrustedDevice> = connection.prepareStatement(
        """SELECT group_id, device_id, display_name, identity_key, tls_key, fingerprint, trust_state, last_seen_at_millis
           FROM devices WHERE group_id = ? ORDER BY display_name COLLATE NOCASE, device_id""",
    ).use { statement ->
        statement.setString(1, groupId)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.device()) } }
    }

    @Synchronized
    fun folderAnnouncements(groupId: String): List<FolderAnnouncement> = connection.prepareStatement(
        """SELECT event_id, group_id, folder_id, display_name, include_patterns, exclude_patterns,
                  signer_device_id, version_json, created_at_millis, signature
           FROM folder_announcements WHERE group_id = ? ORDER BY created_at_millis, event_id""",
    ).use { statement ->
        statement.setString(1, groupId)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.folderAnnouncement()) } }
    }

    @Synchronized
    fun folders(groupId: String, localDeviceId: String): List<MeshFolder> = connection.prepareStatement(
        """SELECT f.folder_id, f.group_id, f.display_name, f.include_patterns, f.exclude_patterns,
                  f.signer_device_id, b.state, b.local_path
           FROM mesh_folders AS f
           LEFT JOIN local_folder_bindings AS b
             ON b.folder_id = f.folder_id AND b.device_id = ?
           WHERE f.group_id = ? ORDER BY f.display_name COLLATE NOCASE, f.folder_id""",
    ).use { statement ->
        statement.setString(1, localDeviceId)
        statement.setString(2, groupId)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.folder()) } }
    }

    @Synchronized
    fun configureFolder(folderId: String, localDeviceId: String, localPath: Path) = transaction {
        require(Files.isDirectory(localPath)) { "Choose an existing local folder" }
        require(meshFolderExists(folderId)) { "Unknown mesh folder" }
        upsertBinding(
            folderId,
            localDeviceId,
            localPath.toAbsolutePath().normalize().toString(),
            LocalFolderBindingState.CONFIGURED,
        )
    }

    @Synchronized
    fun declineFolder(folderId: String, localDeviceId: String) = transaction {
        require(meshFolderExists(folderId)) { "Unknown mesh folder" }
        upsertBinding(folderId, localDeviceId, null, LocalFolderBindingState.DECLINED)
    }

    @Synchronized
    fun configuredFolders(groupId: String, localDeviceId: String): List<MeshFolder> =
        folders(groupId, localDeviceId).filter { it.bindingState == LocalFolderBindingState.CONFIGURED && it.localPath != null }

    @Synchronized
    fun storedFolderKey(folderId: String): StoredFolderKey? = connection.prepareStatement(
        "SELECT folder_id, key_id, encrypted_key FROM folder_keys WHERE folder_id = ? LIMIT 1",
    ).use { statement ->
        statement.setString(1, folderId)
        statement.executeQuery().use { rows ->
            if (rows.next()) StoredFolderKey(rows.getString(1), rows.getString(2), rows.getString(3)) else null
        }
    }

    @Synchronized
    fun archivedFolderKeys(folderId: String): List<StoredFolderKey> = connection.prepareStatement(
        "SELECT folder_id, key_id, encrypted_key FROM folder_key_history WHERE folder_id = ?",
    ).use { statement ->
        statement.setString(1, folderId)
        statement.executeQuery().use { rows -> buildList {
            while (rows.next()) add(StoredFolderKey(rows.getString(1), rows.getString(2), rows.getString(3)))
        } }
    }

    @Synchronized
    fun archiveFolderKey(value: StoredFolderKey) {
        require(meshFolderExists(value.folderId)) { "Unknown mesh folder" }
        connection.prepareStatement("INSERT OR IGNORE INTO folder_key_history(folder_id,key_id,encrypted_key) VALUES (?,?,?)").use {
            it.setString(1, value.folderId); it.setString(2, value.keyId); it.setString(3, value.encryptedKey)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun saveFolderKey(value: StoredFolderKey) {
        require(meshFolderExists(value.folderId)) { "Unknown mesh folder" }
        connection.prepareStatement(
            """INSERT INTO folder_keys(folder_id, key_id, encrypted_key, updated_at_millis)
               VALUES (?, ?, ?, ?)
               ON CONFLICT(folder_id) DO UPDATE SET
                   key_id = excluded.key_id, encrypted_key = excluded.encrypted_key,
                   updated_at_millis = excluded.updated_at_millis""",
        ).use {
            it.setString(1, value.folderId); it.setString(2, value.keyId); it.setString(3, value.encryptedKey)
            it.setLong(4, System.currentTimeMillis()); it.executeUpdate()
        }
    }

    @Synchronized
    fun fileVersions(folderId: String): List<FileVersion> = connection.prepareStatement(
        """SELECT folder_id, relative_path, file_id, size_bytes, modified_at_millis, content_sha256,
                  previous_content_sha256, deleted, version_json, origin_device_id, local_sequence, purge_recovery
           FROM file_versions WHERE folder_id = ? ORDER BY local_sequence, relative_path""",
    ).use { statement ->
        statement.setString(1, folderId)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.fileVersion()) } }
    }

    @Synchronized
    fun fileVersion(folderId: String, relativePath: String): FileVersion? = connection.prepareStatement(
        """SELECT folder_id, relative_path, file_id, size_bytes, modified_at_millis, content_sha256,
                  previous_content_sha256, deleted, version_json, origin_device_id, local_sequence, purge_recovery
           FROM file_versions WHERE folder_id = ? AND relative_path = ? LIMIT 1""",
    ).use { statement ->
        statement.setString(1, folderId); statement.setString(2, relativePath)
        statement.executeQuery().use { rows -> if (rows.next()) rows.fileVersion() else null }
    }

    @Synchronized
    fun fileVersionsAfter(folderId: String, afterSequence: Long): List<FileVersion> = connection.prepareStatement(
        """SELECT folder_id, relative_path, file_id, size_bytes, modified_at_millis, content_sha256,
                  previous_content_sha256, deleted, version_json, origin_device_id, local_sequence, purge_recovery
           FROM file_versions WHERE folder_id = ? AND local_sequence > ? ORDER BY local_sequence, relative_path""",
    ).use { statement ->
        statement.setString(1, folderId); statement.setLong(2, afterSequence)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.fileVersion()) } }
    }

    @Synchronized
    fun folderIndexState(folderId: String, deviceId: String): FolderIndexState? = connection.prepareStatement(
        """SELECT folder_id, device_id, index_epoch, max_sequence, metadata_received_sequence,
                  content_applied_sequence, updated_at_millis
           FROM folder_index_states WHERE folder_id = ? AND device_id = ? LIMIT 1""",
    ).use { statement ->
        statement.setString(1, folderId); statement.setString(2, deviceId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.folderIndexState() else null }
    }

    @Synchronized
    fun saveLocalIndex(files: List<FileVersion>, state: FolderIndexState) = transaction {
        files.forEach(::upsertFileVersionLocked)
        upsertFolderIndexStateLocked(state)
    }

    @Synchronized
    fun storeLocalBlockManifest(manifest: BlockManifest) = transaction {
        connection.prepareStatement(
            "DELETE FROM file_blocks WHERE folder_id = ? AND file_id = ? AND content_sha256 <> ?",
        ).use {
            it.setString(1, manifest.folderId); it.setString(2, manifest.fileId); it.setString(3, manifest.contentSha256)
            it.executeUpdate()
        }
        connection.prepareStatement(
            "DELETE FROM file_blocks WHERE folder_id = ? AND file_id = ? AND content_sha256 = ?",
        ).use {
            it.setString(1, manifest.folderId); it.setString(2, manifest.fileId); it.setString(3, manifest.contentSha256)
            it.executeUpdate()
        }
        manifest.blocks.forEach { block -> upsertLocalBlockLocked(manifest, block) }
    }

    @Synchronized
    fun localBlockManifest(version: FileVersion): BlockManifest? {
        val blocks = connection.prepareStatement(
            """SELECT block_index, offset_bytes, size_bytes, block_sha256
               FROM file_blocks WHERE folder_id = ? AND file_id = ? AND content_sha256 = ? ORDER BY block_index""",
        ).use { statement ->
            statement.setString(1, version.folderId); statement.setString(2, version.fileId)
            statement.setString(3, version.contentSha256)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(FileBlock(rows.getInt(1), rows.getLong(2), rows.getInt(3), rows.getString(4)))
            } }
        }
        if (blocks.isEmpty()) return null
        return BlockManifest(
            version.folderId, version.fileId, version.relativePath, version.sizeBytes, version.modifiedAtMillis,
            version.contentSha256, blocks.first().sizeBytes, blocks,
        )
    }

    @Synchronized
    fun remoteBlockManifest(version: RemoteFileVersion): BlockManifest? {
        val blocks = connection.prepareStatement(
            """SELECT block_index, offset_bytes, size_bytes, block_sha256
               FROM remote_file_blocks
               WHERE folder_id = ? AND device_id = ? AND file_id = ? AND content_sha256 = ?
               ORDER BY block_index""",
        ).use { statement ->
            statement.setString(1, version.folderId); statement.setString(2, version.deviceId)
            statement.setString(3, version.fileId); statement.setString(4, version.contentSha256)
            statement.executeQuery().use { rows -> buildList {
                while (rows.next()) add(FileBlock(rows.getInt(1), rows.getLong(2), rows.getInt(3), rows.getString(4)))
            } }
        }
        if (blocks.isEmpty()) return null
        return BlockManifest(
            version.folderId, version.fileId, version.relativePath, version.sizeBytes, version.modifiedAtMillis,
            version.contentSha256, blocks.first().sizeBytes, blocks,
        )
    }

    @Synchronized
    fun partialTransfer(folderId: String, fileId: String, contentSha256: String): PartialTransfer? =
        connection.prepareStatement(
            """SELECT folder_id, file_id, content_sha256, temporary_path, total_size_bytes,
                      block_size_bytes, received_blocks_base64, updated_at_millis
               FROM partial_transfers WHERE folder_id = ? AND file_id = ? AND content_sha256 = ?""",
        ).use { statement ->
            statement.setString(1, folderId); statement.setString(2, fileId); statement.setString(3, contentSha256)
            statement.executeQuery().use { rows -> if (rows.next()) PartialTransfer(
                rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5),
                rows.getInt(6), rows.getString(7), rows.getLong(8),
            ) else null }
        }

    @Synchronized
    fun upsertPartialTransfer(value: PartialTransfer) {
        connection.prepareStatement(
            """INSERT INTO partial_transfers(
                   folder_id, file_id, content_sha256, temporary_path, total_size_bytes,
                   block_size_bytes, received_blocks_base64, updated_at_millis)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(folder_id, file_id, content_sha256) DO UPDATE SET
                   temporary_path = excluded.temporary_path, total_size_bytes = excluded.total_size_bytes,
                   block_size_bytes = excluded.block_size_bytes,
                   received_blocks_base64 = excluded.received_blocks_base64,
                   updated_at_millis = excluded.updated_at_millis""",
        ).use {
            it.setString(1, value.folderId); it.setString(2, value.fileId); it.setString(3, value.contentSha256)
            it.setString(4, value.temporaryPath); it.setLong(5, value.totalSizeBytes); it.setInt(6, value.blockSizeBytes)
            it.setString(7, value.receivedBlocksBase64); it.setLong(8, value.updatedAtMillis); it.executeUpdate()
        }
    }

    @Synchronized
    fun deletePartialTransfer(folderId: String, fileId: String, contentSha256: String) {
        connection.prepareStatement(
            "DELETE FROM partial_transfers WHERE folder_id = ? AND file_id = ? AND content_sha256 = ?",
        ).use {
            it.setString(1, folderId); it.setString(2, fileId); it.setString(3, contentSha256); it.executeUpdate()
        }
    }

    @Synchronized
    fun acceptRemoteIndex(
        remoteDeviceId: String,
        update: FolderIndexUpdate,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = transaction {
        val existing = folderIndexState(update.folderId, remoteDeviceId)
        val decision = reconcileReceivedIndex(
            existing?.toSnapshot(), update.indexEpoch, update.previousSequence, update.lastSequence, update.fullIndex,
        )
        if (decision is IndexReceiveDecision.RequiresFullIndex) return@transaction false
        val next = (decision as IndexReceiveDecision.Accepted).next
        if (update.fullIndex) {
            connection.prepareStatement(
                "DELETE FROM remote_file_versions WHERE folder_id = ? AND device_id = ?",
            ).use { it.setString(1, update.folderId); it.setString(2, remoteDeviceId); it.executeUpdate() }
            connection.prepareStatement(
                "DELETE FROM remote_file_blocks WHERE folder_id = ? AND device_id = ?",
            ).use { it.setString(1, update.folderId); it.setString(2, remoteDeviceId); it.executeUpdate() }
        }
        update.files.forEach { record ->
            upsertRemoteFileVersionLocked(record.toRemote(update.folderId, remoteDeviceId))
            replaceRemoteBlocksLocked(update.folderId, remoteDeviceId, record)
        }
        upsertFolderIndexStateLocked(
            FolderIndexState(
                update.folderId,
                remoteDeviceId,
                next.indexEpoch,
                next.maxSequence,
                next.metadataReceivedSequence,
                next.contentAppliedSequence,
                nowMillis,
            ),
        )
        true
    }

    @Synchronized
    fun pendingRemoteVersions(folderId: String, remoteDeviceId: String): List<RemoteFileVersion> {
        val applied = folderIndexState(folderId, remoteDeviceId)?.contentAppliedSequence ?: 0
        return connection.prepareStatement(
            """SELECT folder_id, device_id, relative_path, file_id, size_bytes, modified_at_millis,
                      content_sha256, previous_content_sha256, origin_device_id, deleted, version_json, remote_sequence, purge_recovery
               FROM remote_file_versions
               WHERE folder_id = ? AND device_id = ? AND remote_sequence > ?
               ORDER BY remote_sequence, relative_path""",
        ).use { statement ->
            statement.setString(1, folderId); statement.setString(2, remoteDeviceId); statement.setLong(3, applied)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.remoteFileVersion()) } }
        }
    }

    @Synchronized
    fun acknowledgeRemoteApplied(
        folderId: String,
        remoteDeviceId: String,
        remoteSequence: Long,
        nowMillis: Long = System.currentTimeMillis(),
    ) = transaction {
        val state = requireNotNull(folderIndexState(folderId, remoteDeviceId)) { "Unknown remote folder index" }
        val next = acknowledgeIndexContent(state.toSnapshot(), state.indexEpoch, remoteSequence)
        upsertFolderIndexStateLocked(state.copy(contentAppliedSequence = next.contentAppliedSequence, updatedAtMillis = nowMillis))
    }

    @Synchronized
    fun markRemoteApplied(
        remote: RemoteFileVersion,
        remoteDeviceId: String,
        currentDeviceId: String,
        acknowledge: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
    ) = transaction {
        val localState = folderIndexState(remote.folderId, currentDeviceId) ?: FolderIndexState(
            remote.folderId, currentDeviceId, randomIndexEpoch(), 0, 0, 0, nowMillis,
        )
        val nextSequence = localState.maxSequence + 1
        upsertFileVersionLocked(
            FileVersion(
                remote.folderId,
                remote.relativePath,
                remote.fileId,
                remote.sizeBytes,
                remote.modifiedAtMillis,
                remote.contentSha256,
                remote.previousContentSha256,
                remote.deleted,
                remote.version,
                remote.originDeviceId.ifBlank { remoteDeviceId },
                nextSequence,
                remote.purgeRecovery,
            ),
        )
        upsertFolderIndexStateLocked(
            localState.copy(
                maxSequence = nextSequence,
                metadataReceivedSequence = nextSequence,
                contentAppliedSequence = nextSequence,
                updatedAtMillis = nowMillis,
            ),
        )
        if (acknowledge) {
            val remoteState = requireNotNull(folderIndexState(remote.folderId, remoteDeviceId))
            upsertFolderIndexStateLocked(
                remoteState.copy(contentAppliedSequence = remote.remoteSequence, updatedAtMillis = nowMillis),
            )
        }
    }

    @Synchronized
    fun recordConflict(local: FileVersion?, remote: RemoteFileVersion, nowMillis: Long = System.currentTimeMillis()) {
        val key = "${remote.folderId}\u0000${remote.relativePath}\u0000${local?.contentSha256.orEmpty()}\u0000${remote.deviceId}\u0000${remote.contentSha256}"
        val id = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
        connection.prepareStatement(
            """INSERT OR IGNORE INTO file_conflicts(
                   conflict_id, folder_id, relative_path, local_hash, remote_device_id, remote_hash, created_at_millis)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
        ).use {
            it.setString(1, id); it.setString(2, remote.folderId); it.setString(3, remote.relativePath)
            it.setString(4, local?.contentSha256); it.setString(5, remote.deviceId); it.setString(6, remote.contentSha256)
            it.setLong(7, nowMillis); it.executeUpdate()
        }
    }

    @Synchronized
    fun unresolvedConflicts(): List<FileConflict> = connection.prepareStatement(
        """SELECT conflict_id, folder_id, relative_path, local_hash, remote_device_id, remote_hash, created_at_millis
           FROM file_conflicts AS c
           WHERE NOT EXISTS (SELECT 1 FROM conflict_resolutions AS r WHERE r.conflict_id = c.conflict_id)
           ORDER BY created_at_millis DESC""",
    ).use { statement ->
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.fileConflict()) } }
    }

    @Synchronized
    fun unresolvedConflictReviews(): List<FileConflictReview> = unresolvedConflicts().mapNotNull { conflict ->
        val remote = remoteFileVersion(
            conflict.folderId,
            conflict.remoteDeviceId,
            conflict.relativePath,
            conflict.remoteHash,
        ) ?: return@mapNotNull null
        FileConflictReview(conflict, fileVersion(conflict.folderId, conflict.relativePath), remote)
    }

    @Synchronized
    fun resolveConflictKeepLocal(
        conflictId: String,
        localDeviceId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) = transaction {
        val review = requireNotNull(conflictReview(conflictId)) { "This conflict is no longer available" }
        val local = requireNotNull(review.local) { "The local file version is no longer available" }
        val state = folderIndexState(local.folderId, localDeviceId) ?: FolderIndexState(
            local.folderId, localDeviceId, randomIndexEpoch(), 0, 0, 0, nowMillis,
        )
        val nextSequence = state.maxSequence + 1
        upsertFileVersionLocked(
            local.copy(
                version = local.version.merge(review.remote.version).increment(localDeviceId),
                originDeviceId = localDeviceId,
                localSequence = nextSequence,
            ),
        )
        upsertFolderIndexStateLocked(
            state.copy(
                maxSequence = nextSequence,
                metadataReceivedSequence = nextSequence,
                contentAppliedSequence = nextSequence,
                updatedAtMillis = nowMillis,
            ),
        )
        acknowledgeRemoteSequenceLocked(review.remote, nowMillis)
        clearConflictsLocked(local.folderId, local.relativePath)
    }

    @Synchronized
    fun queueConflictResolution(
        conflictId: String,
        action: ConflictResolutionAction,
        localDeviceId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): String = transaction {
        val review = requireNotNull(conflictReview(conflictId)) { "This conflict is no longer available" }
        val target = when (action) {
            ConflictResolutionAction.KEEP_REMOTE -> review.conflict.relativePath
            ConflictResolutionAction.KEEP_BOTH -> nextAvailableConflictPath(
                review.conflict.folderId,
                review.conflict.relativePath,
                localDeviceId,
            )
        }
        connection.prepareStatement(
            """INSERT INTO conflict_resolutions(conflict_id, action, target_relative_path, created_at_millis)
               VALUES (?, ?, ?, ?)
               ON CONFLICT(conflict_id) DO UPDATE SET
                 action = excluded.action,
                 target_relative_path = excluded.target_relative_path,
                 created_at_millis = excluded.created_at_millis""",
        ).use {
            it.setString(1, conflictId)
            it.setString(2, action.name)
            it.setString(3, target)
            it.setLong(4, nowMillis)
            it.executeUpdate()
        }
        target
    }

    @Synchronized
    fun pendingConflictResolution(
        local: FileVersion?,
        remote: RemoteFileVersion,
    ): PendingConflictResolution? = connection.prepareStatement(
        """SELECT c.conflict_id, r.action, r.target_relative_path
           FROM file_conflicts AS c
           JOIN conflict_resolutions AS r ON r.conflict_id = c.conflict_id
           WHERE c.folder_id = ? AND c.relative_path = ? AND c.remote_device_id = ?
             AND c.remote_hash = ? AND COALESCE(c.local_hash, '') = ?
           LIMIT 1""",
    ).use { statement ->
        statement.setString(1, remote.folderId)
        statement.setString(2, remote.relativePath)
        statement.setString(3, remote.deviceId)
        statement.setString(4, remote.contentSha256)
        statement.setString(5, local?.contentSha256.orEmpty())
        statement.executeQuery().use { rows ->
            if (!rows.next()) null else PendingConflictResolution(
                rows.getString(1),
                ConflictResolutionAction.valueOf(rows.getString(2)),
                rows.getString(3),
            )
        }
    }

    @Synchronized
    fun finalizeConflictResolution(
        resolution: PendingConflictResolution,
        remote: RemoteFileVersion,
        localDeviceId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) = transaction {
        val review = requireNotNull(conflictReview(resolution.conflictId)) { "Conflict resolution is no longer available" }
        val local = requireNotNull(review.local) { "The local conflict version is no longer available" }
        require(review.remote.contentSha256.equals(remote.contentSha256, true)) { "The remote conflict version changed" }
        val state = folderIndexState(remote.folderId, localDeviceId) ?: FolderIndexState(
            remote.folderId, localDeviceId, randomIndexEpoch(), 0, 0, 0, nowMillis,
        )
        var sequence = state.maxSequence
        val resolvedVector = local.version.merge(remote.version).increment(localDeviceId)
        when (resolution.action) {
            ConflictResolutionAction.KEEP_REMOTE -> {
                sequence++
                upsertFileVersionLocked(
                    FileVersion(
                        remote.folderId,
                        remote.relativePath,
                        remote.fileId,
                        remote.sizeBytes,
                        remote.modifiedAtMillis,
                        remote.contentSha256,
                        local.contentSha256.takeIf(String::isNotBlank),
                        remote.deleted,
                        resolvedVector,
                        remote.originDeviceId.ifBlank { remote.deviceId },
                        sequence,
                        remote.purgeRecovery,
                    ),
                )
            }
            ConflictResolutionAction.KEEP_BOTH -> {
                require(!remote.deleted) { "A deleted file cannot be kept as a renamed copy" }
                sequence++
                upsertFileVersionLocked(
                    local.copy(
                        version = resolvedVector,
                        originDeviceId = localDeviceId,
                        localSequence = sequence,
                    ),
                )
                sequence++
                upsertFileVersionLocked(
                    FileVersion(
                        remote.folderId,
                        resolution.targetRelativePath,
                        UUID.randomUUID().toString(),
                        remote.sizeBytes,
                        remote.modifiedAtMillis,
                        remote.contentSha256,
                        null,
                        false,
                        remote.version.increment(localDeviceId),
                        remote.originDeviceId.ifBlank { remote.deviceId },
                        sequence,
                        remote.purgeRecovery,
                    ),
                )
            }
        }
        upsertFolderIndexStateLocked(
            state.copy(
                maxSequence = sequence,
                metadataReceivedSequence = sequence,
                contentAppliedSequence = sequence,
                updatedAtMillis = nowMillis,
            ),
        )
        acknowledgeRemoteSequenceLocked(remote, nowMillis)
        clearConflictsLocked(remote.folderId, remote.relativePath)
    }

    @Synchronized
    fun fileHistory(limit: Int = 500): List<FileHistoryEvent> = connection.prepareStatement(
        """SELECT event_id, action, folder_id, relative_path, source_device_id, size_bytes,
                  modified_at_millis, content_sha256, created_at_millis, recovery_path,
                  recoverable_until_millis, recovered_at_millis
           FROM file_history ORDER BY created_at_millis DESC, event_id DESC LIMIT ?""",
    ).use { statement ->
        statement.setInt(1, limit.coerceIn(1, 10_000))
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.fileHistoryEvent()) } }
    }

    @Synchronized
    fun recoveriesForFile(folderId: String, relativePath: String): List<FileHistoryEvent> = connection.prepareStatement(
        """SELECT event_id, action, folder_id, relative_path, source_device_id, size_bytes,
                  modified_at_millis, content_sha256, created_at_millis, recovery_path,
                  recoverable_until_millis, recovered_at_millis
           FROM file_history WHERE folder_id = ? AND relative_path = ? AND recovery_path IS NOT NULL""",
    ).use { statement ->
        statement.setString(1, folderId); statement.setString(2, relativePath)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.fileHistoryEvent()) } }
    }

    @Synchronized
    fun historyEvent(eventId: String): FileHistoryEvent? = connection.prepareStatement(
        """SELECT event_id, action, folder_id, relative_path, source_device_id, size_bytes,
                  modified_at_millis, content_sha256, created_at_millis, recovery_path,
                  recoverable_until_millis, recovered_at_millis
           FROM file_history WHERE event_id = ? LIMIT 1""",
    ).use { statement ->
        statement.setString(1, eventId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.fileHistoryEvent() else null }
    }

    @Synchronized
    fun activeDeletion(folderId: String, relativePath: String, contentSha256: String): FileHistoryEvent? =
        connection.prepareStatement(
            """SELECT event_id, action, folder_id, relative_path, source_device_id, size_bytes,
                      modified_at_millis, content_sha256, created_at_millis, recovery_path,
                      recoverable_until_millis, recovered_at_millis
               FROM file_history
               WHERE action = 'DELETED' AND folder_id = ? AND relative_path = ? AND content_sha256 = ?
                 AND recovered_at_millis IS NULL
               ORDER BY created_at_millis DESC LIMIT 1""",
        ).use { statement ->
            statement.setString(1, folderId); statement.setString(2, relativePath); statement.setString(3, contentSha256)
            statement.executeQuery().use { rows -> if (rows.next()) rows.fileHistoryEvent() else null }
        }

    @Synchronized
    fun expiredRecoveries(nowMillis: Long): List<FileHistoryEvent> = connection.prepareStatement(
        """SELECT event_id, action, folder_id, relative_path, source_device_id, size_bytes,
                  modified_at_millis, content_sha256, created_at_millis, recovery_path,
                  recoverable_until_millis, recovered_at_millis
           FROM file_history
           WHERE recovery_path IS NOT NULL AND recovered_at_millis IS NULL
             AND recoverable_until_millis IS NOT NULL AND recoverable_until_millis <= ?""",
    ).use { statement ->
        statement.setLong(1, nowMillis)
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.fileHistoryEvent()) } }
    }

    @Synchronized
    fun insertHistory(event: FileHistoryEvent) {
        connection.prepareStatement(
            """INSERT OR REPLACE INTO file_history(
                   event_id, action, folder_id, relative_path, source_device_id, size_bytes,
                   modified_at_millis, content_sha256, created_at_millis, recovery_path,
                   recoverable_until_millis, recovered_at_millis)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use {
            it.setString(1, event.eventId); it.setString(2, event.action.name); it.setString(3, event.folderId)
            it.setString(4, event.relativePath); it.setString(5, event.sourceDeviceId)
            if (event.sizeBytes == null) it.setNull(6, java.sql.Types.BIGINT) else it.setLong(6, event.sizeBytes)
            if (event.modifiedAtMillis == null) it.setNull(7, java.sql.Types.BIGINT) else it.setLong(7, event.modifiedAtMillis)
            it.setString(8, event.contentSha256); it.setLong(9, event.createdAtMillis); it.setString(10, event.recoveryPath)
            if (event.recoverableUntilMillis == null) it.setNull(11, java.sql.Types.BIGINT) else it.setLong(11, event.recoverableUntilMillis)
            if (event.recoveredAtMillis == null) it.setNull(12, java.sql.Types.BIGINT) else it.setLong(12, event.recoveredAtMillis)
            it.executeUpdate()
        }
    }

    @Synchronized
    fun clearRecoveryPath(eventId: String) {
        connection.prepareStatement("UPDATE file_history SET recovery_path = NULL WHERE event_id = ?").use {
            it.setString(1, eventId); it.executeUpdate()
        }
    }

    @Synchronized
    fun markRecovered(eventId: String, recoveredAtMillis: Long) {
        connection.prepareStatement(
            "UPDATE file_history SET recovered_at_millis = ?, recovery_path = NULL WHERE event_id = ?",
        ).use { it.setLong(1, recoveredAtMillis); it.setString(2, eventId); it.executeUpdate() }
    }

    @Synchronized
    fun recordTlsKey(groupId: String, deviceId: String, tlsKey: ByteArray) {
        connection.prepareStatement("UPDATE devices SET tls_key = ? WHERE group_id = ? AND device_id = ?").use {
            it.setString(1, Base64.getEncoder().encodeToString(tlsKey)); it.setString(2, groupId); it.setString(3, deviceId)
            require(it.executeUpdate() == 1) { "Cannot pin TLS key for an unknown device" }
        }
    }

    @Synchronized
    fun markSeen(groupId: String, deviceId: String, atMillis: Long = System.currentTimeMillis()) {
        connection.prepareStatement("UPDATE devices SET last_seen_at_millis = ? WHERE group_id = ? AND device_id = ?").use {
            it.setLong(1, atMillis); it.setString(2, groupId); it.setString(3, deviceId); it.executeUpdate()
        }
    }

    private fun applyMembershipLocked(groupName: String, event: MembershipEvent): Boolean {
        require(event.isStructurallyValid()) { "Membership event is malformed" }
        if (hasEvent(event.eventId)) return false
        val trusted = trustedDevices(event.groupId)
        val signerKey = if (trusted.isEmpty()) {
            require(event.eventType == MembershipEventType.AddDevice && event.signerDeviceId == event.subjectDeviceId) {
                "The first mesh member must add itself"
            }
            decodePublicKey(event.subjectPublicKeyBase64)
        } else {
            val signer = trusted.firstOrNull { it.deviceId == event.signerDeviceId }
                ?: error("Membership signer is not trusted")
            decodePublicKey(signer.identityPublicKeyBase64)
        }
        require(event.verifySignature(signerKey)) { "Membership signature is invalid" }

        val existing = device(event.groupId, event.subjectDeviceId)
        when (event.eventType) {
            MembershipEventType.UpdateDeviceName -> require(
                event.signerDeviceId == event.subjectDeviceId && existing?.trusted == true,
            ) { "A device can only rename its own trusted identity" }
            MembershipEventType.RemoveDevice -> require(existing?.trusted == true) { "Only a trusted device can be removed" }
            MembershipEventType.AddDevice -> Unit
        }

        connection.prepareStatement(
            """INSERT INTO membership_events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use {
            it.setString(1, event.eventId); it.setString(2, event.groupId); it.setString(3, event.eventType.name)
            it.setString(4, event.subjectDeviceId); it.setString(5, event.subjectDisplayName)
            it.setString(6, event.subjectPublicKeyBase64); it.setString(7, event.signerDeviceId)
            it.setString(8, event.parentEventIds.joinToString("\n")); it.setString(9, event.version.toJson())
            it.setLong(10, event.createdAtMillis); it.setString(11, event.signatureBase64); it.executeUpdate()
        }
        connection.prepareStatement(
            """INSERT INTO devices(group_id, device_id, display_name, identity_key, tls_key, fingerprint, trust_state, last_seen_at_millis)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(group_id, device_id) DO UPDATE SET
                 display_name = excluded.display_name,
                 identity_key = excluded.identity_key,
                 fingerprint = excluded.fingerprint,
                 trust_state = excluded.trust_state""",
        ).use {
            val key = decodePublicKey(event.subjectPublicKeyBase64)
            it.setString(1, event.groupId); it.setString(2, event.subjectDeviceId); it.setString(3, event.subjectDisplayName)
            it.setString(4, event.subjectPublicKeyBase64); it.setString(5, existing?.tlsPublicKeyBase64)
            it.setString(6, fingerprintFor(key)); it.setString(7, if (event.eventType == MembershipEventType.RemoveDevice) "REMOVED" else "TRUSTED")
            if (existing?.lastSeenAtMillis == null) it.setNull(8, java.sql.Types.BIGINT) else it.setLong(8, existing.lastSeenAtMillis)
            it.executeUpdate()
        }
        if (profile() == null) {
            // importBundle writes the profile after validating the entire membership chain.
        }
        return true
    }

    private fun applyFolderLocked(event: FolderAnnouncement): Boolean {
        require(event.hasValidEventId()) { "Folder announcement is malformed" }
        if (hasFolderEvent(event.eventId)) return false
        val signer = device(event.groupId, event.signerDeviceId)
            ?: error("Folder announcement signer is not a mesh member")
        require(signer.trusted) { "Folder announcement signer is not trusted" }
        require(event.verifySignature(decodePublicKey(signer.identityPublicKeyBase64))) {
            "Folder announcement signature is invalid"
        }

        val existing = meshFolder(event.folderId)
        val sameName = meshFolderByName(event.groupId, event.displayName)
        require(sameName == null || sameName.folderId == event.folderId) {
            "A different mesh folder already uses the name '${event.displayName}'"
        }
        require(existing == null || existing.matches(event)) {
            "A different mesh folder already uses this folder ID"
        }
        if (existing == null) {
            connection.prepareStatement(
                """INSERT INTO mesh_folders(
                       folder_id, group_id, display_name, include_patterns, exclude_patterns,
                       signer_device_id, version_json, created_at_millis)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            ).use {
                it.setString(1, event.folderId); it.setString(2, event.groupId); it.setString(3, event.displayName)
                it.setString(4, encodeList(event.includePatterns)); it.setString(5, encodeList(event.excludePatterns))
                it.setString(6, event.signerDeviceId); it.setString(7, event.version.toJson())
                it.setLong(8, event.createdAtMillis); it.executeUpdate()
            }
        }
        connection.prepareStatement(
            """INSERT INTO folder_announcements(
                   event_id, group_id, folder_id, display_name, include_patterns, exclude_patterns,
                   signer_device_id, version_json, created_at_millis, signature)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use {
            it.setString(1, event.eventId); it.setString(2, event.groupId); it.setString(3, event.folderId)
            it.setString(4, event.displayName); it.setString(5, encodeList(event.includePatterns))
            it.setString(6, encodeList(event.excludePatterns)); it.setString(7, event.signerDeviceId)
            it.setString(8, event.version.toJson()); it.setLong(9, event.createdAtMillis)
            it.setString(10, event.signatureBase64); it.executeUpdate()
        }
        return true
    }

    private fun applyChatLocked(message: MeshChatMessage): Boolean {
        require(message.body.toByteArray(Charsets.UTF_8).size <= MAX_CHAT_BODY_BYTES) {
            "A chat message is too long"
        }
        require(message.body == message.body.trim() && (message.body.isNotEmpty() || message.attachment != null)) {
            "A chat message has invalid whitespace"
        }
        message.attachment?.validateForChat(message.createdAtMillis)
        require(message.hasValidMessageId()) { "Chat message ID does not match its payload" }
        val author = device(message.groupId, message.authorDeviceId)
            ?: error("Chat message author is not a member of this mesh")
        require(author.trusted) { "Chat message author is not trusted" }
        require(message.verifySignature(decodePublicKey(author.identityPublicKeyBase64))) {
            "Chat message signature is invalid"
        }
        return connection.prepareStatement(
            """INSERT OR IGNORE INTO chat_messages(
                   message_id, group_id, author_device_id, body, created_at_millis, signature,
                   attachment_file_name, attachment_media_type, attachment_size_bytes,
                   attachment_sha256, attachment_expires_at_millis)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use {
            it.setString(1, message.messageId); it.setString(2, message.groupId)
            it.setString(3, message.authorDeviceId); it.setString(4, message.body)
            it.setLong(5, message.createdAtMillis); it.setString(6, message.signatureBase64)
            it.setString(7, message.attachment?.fileName); it.setString(8, message.attachment?.mediaType)
            message.attachment?.let { attachment ->
                it.setLong(9, attachment.sizeBytes); it.setString(10, attachment.contentSha256)
                it.setLong(11, attachment.expiresAtMillis)
            } ?: run {
                it.setNull(9, java.sql.Types.BIGINT); it.setNull(10, java.sql.Types.VARCHAR)
                it.setNull(11, java.sql.Types.BIGINT)
            }
            it.executeUpdate() > 0
        }
    }

    private fun applySyncExceptionLocked(event: SyncExceptionEvent): Boolean {
        require(event.hasValidEventId()) { "Exception event ID does not match its payload" }
        val folder = requireNotNull(meshFolder(event.folderId)) { "Unknown mesh folder" }
        require(folder.groupId == event.groupId) { "Exception event belongs to a different mesh" }
        val signer = device(event.groupId, event.signerDeviceId)
            ?: error("Exception signer is not a mesh member")
        require(signer.trusted) { "Exception signer is not trusted" }
        require(event.verifySignature(decodePublicKey(signer.identityPublicKeyBase64))) {
            "Exception signature is invalid"
        }
        val inserted = connection.prepareStatement(
            """INSERT OR IGNORE INTO sync_exception_events(
                   event_id, group_id, folder_id, relative_path, active, signer_device_id,
                   version_json, created_at_millis, signature)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use {
            it.setString(1, event.eventId); it.setString(2, event.groupId); it.setString(3, event.folderId)
            it.setString(4, event.relativePath); it.setInt(5, if (event.active) 1 else 0)
            it.setString(6, event.signerDeviceId); it.setString(7, event.version.toJson())
            it.setLong(8, event.createdAtMillis); it.setString(9, event.signatureBase64)
            it.executeUpdate() > 0
        }
        if (!inserted) return false
        val existing = syncExceptionState(event.folderId, event.relativePath)
        val replaces = existing == null || when (existing.version.relationTo(event.version)) {
            CausalRelation.Before -> true
            CausalRelation.After -> false
            CausalRelation.Equal, CausalRelation.Concurrent -> event.eventId > existing.lastEventId
        }
        val merged = (existing?.version ?: VersionVector()).merge(event.version)
        val resolvedActive = if (replaces) event.active else requireNotNull(existing).active
        connection.prepareStatement(
            """INSERT INTO sync_exceptions(
                   folder_id, relative_path, active, created_by_device_id, created_at_millis,
                   updated_at_millis, version_json, last_event_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(folder_id, relative_path) DO UPDATE SET
                   active = excluded.active, updated_at_millis = excluded.updated_at_millis,
                   version_json = excluded.version_json, last_event_id = excluded.last_event_id""",
        ).use {
            it.setString(1, event.folderId); it.setString(2, event.relativePath)
            it.setInt(3, if (resolvedActive) 1 else 0)
            it.setString(4, existing?.createdByDeviceId ?: event.signerDeviceId)
            it.setLong(5, existing?.createdAtMillis ?: event.createdAtMillis)
            it.setLong(6, maxOf(existing?.updatedAtMillis ?: 0, event.createdAtMillis))
            it.setString(7, merged.toJson())
            it.setString(8, if (replaces) event.eventId else requireNotNull(existing).lastEventId)
            it.executeUpdate()
        }
        return true
    }

    private fun syncExceptionState(folderId: String, relativePath: String): SyncExceptionState? =
        connection.prepareStatement(
            """SELECT folder_id, relative_path, active, created_by_device_id, created_at_millis,
                      updated_at_millis, version_json, last_event_id
               FROM sync_exceptions WHERE folder_id = ? AND relative_path = ? LIMIT 1""",
        ).use { statement ->
            statement.setString(1, folderId); statement.setString(2, relativePath)
            statement.executeQuery().use { rows -> if (rows.next()) rows.syncExceptionState() else null }
        }

    private fun conflictReview(conflictId: String): FileConflictReview? = connection.prepareStatement(
        """SELECT conflict_id, folder_id, relative_path, local_hash, remote_device_id, remote_hash, created_at_millis
           FROM file_conflicts WHERE conflict_id = ? LIMIT 1""",
    ).use { statement ->
        statement.setString(1, conflictId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            val conflict = rows.fileConflict()
            val remote = remoteFileVersion(
                conflict.folderId,
                conflict.remoteDeviceId,
                conflict.relativePath,
                conflict.remoteHash,
            ) ?: return@use null
            FileConflictReview(conflict, fileVersion(conflict.folderId, conflict.relativePath), remote)
        }
    }

    private fun remoteFileVersion(
        folderId: String,
        deviceId: String,
        relativePath: String,
        contentSha256: String,
    ): RemoteFileVersion? = connection.prepareStatement(
        """SELECT folder_id, device_id, relative_path, file_id, size_bytes, modified_at_millis,
                  content_sha256, previous_content_sha256, origin_device_id, deleted, version_json, remote_sequence, purge_recovery
           FROM remote_file_versions
           WHERE folder_id = ? AND device_id = ? AND relative_path = ? AND content_sha256 = ? LIMIT 1""",
    ).use { statement ->
        statement.setString(1, folderId)
        statement.setString(2, deviceId)
        statement.setString(3, relativePath)
        statement.setString(4, contentSha256)
        statement.executeQuery().use { rows -> if (rows.next()) rows.remoteFileVersion() else null }
    }

    private fun nextAvailableConflictPath(folderId: String, relativePath: String, localDeviceId: String): String {
        val normalized = normalizedRelativePath(relativePath)
        val parent = normalized.substringBeforeLast('/', "")
        val fileName = normalized.substringAfterLast('/')
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 } ?: fileName.length
        val stem = fileName.substring(0, dot)
        val extension = fileName.substring(dot)
        val known = fileVersions(folderId).mapTo(mutableSetOf()) { it.relativePath.lowercase() }
        val root = configuredFolders(requireNotNull(profile()).groupId, localDeviceId)
            .firstOrNull { it.folderId == folderId }
            ?.localPath
            ?.let(Path::of)
        for (suffix in 1..9_999) {
            val child = "${stem}_$suffix$extension"
            val candidate = normalizedRelativePath(if (parent.isEmpty()) child else "$parent/$child")
            if (candidate.lowercase() in known) continue
            if (root != null && Files.exists(root.resolve(candidate))) continue
            return candidate
        }
        error("Could not find an available name for the second conflict copy")
    }

    private fun acknowledgeRemoteSequenceLocked(remote: RemoteFileVersion, nowMillis: Long) {
        val remoteState = requireNotNull(folderIndexState(remote.folderId, remote.deviceId)) {
            "Unknown remote folder index"
        }
        upsertFolderIndexStateLocked(
            remoteState.copy(
                contentAppliedSequence = maxOf(remoteState.contentAppliedSequence, remote.remoteSequence),
                updatedAtMillis = nowMillis,
            ),
        )
    }

    private fun clearConflictsLocked(folderId: String, relativePath: String) {
        connection.prepareStatement(
            "DELETE FROM conflict_resolutions WHERE conflict_id IN (SELECT conflict_id FROM file_conflicts WHERE folder_id = ? AND relative_path = ?)",
        ).use {
            it.setString(1, folderId)
            it.setString(2, relativePath)
            it.executeUpdate()
        }
        connection.prepareStatement("DELETE FROM file_conflicts WHERE folder_id = ? AND relative_path = ?").use {
            it.setString(1, folderId)
            it.setString(2, relativePath)
            it.executeUpdate()
        }
    }

    private fun upsertBinding(
        folderId: String,
        deviceId: String,
        localPath: String?,
        state: LocalFolderBindingState,
    ) {
        connection.prepareStatement(
            """INSERT INTO local_folder_bindings(folder_id, device_id, local_path, state, updated_at_millis)
               VALUES (?, ?, ?, ?, ?)
               ON CONFLICT(folder_id, device_id) DO UPDATE SET
                 local_path = excluded.local_path,
                 state = excluded.state,
                 updated_at_millis = excluded.updated_at_millis""",
        ).use {
            it.setString(1, folderId); it.setString(2, deviceId); it.setString(3, localPath)
            it.setString(4, state.name); it.setLong(5, System.currentTimeMillis()); it.executeUpdate()
        }
    }

    private fun trustedDevices(groupId: String) = devices(groupId).filter(TrustedDevice::trusted)
    private fun device(groupId: String, deviceId: String) = devices(groupId).firstOrNull { it.deviceId == deviceId }
    private fun hasEvent(eventId: String) = connection.prepareStatement("SELECT 1 FROM membership_events WHERE event_id = ?").use {
        it.setString(1, eventId); it.executeQuery().use(ResultSet::next)
    }
    private fun hasFolderEvent(eventId: String) = connection.prepareStatement(
        "SELECT 1 FROM folder_announcements WHERE event_id = ?",
    ).use { it.setString(1, eventId); it.executeQuery().use(ResultSet::next) }
    private fun meshFolderExists(folderId: String) = connection.prepareStatement(
        "SELECT 1 FROM mesh_folders WHERE folder_id = ?",
    ).use { it.setString(1, folderId); it.executeQuery().use(ResultSet::next) }
    private fun meshFolder(folderId: String): StoredFolder? = connection.prepareStatement(
        """SELECT folder_id, group_id, display_name, include_patterns, exclude_patterns,
                  signer_device_id, version_json, created_at_millis FROM mesh_folders WHERE folder_id = ?""",
    ).use { statement ->
        statement.setString(1, folderId)
        statement.executeQuery().use { rows -> if (rows.next()) rows.storedFolder() else null }
    }
    private fun meshFolderByName(groupId: String, displayName: String): StoredFolder? = connection.prepareStatement(
        """SELECT folder_id, group_id, display_name, include_patterns, exclude_patterns,
                  signer_device_id, version_json, created_at_millis
           FROM mesh_folders WHERE group_id = ? AND display_name = ?""",
    ).use { statement ->
        statement.setString(1, groupId); statement.setString(2, displayName)
        statement.executeQuery().use { rows -> if (rows.next()) rows.storedFolder() else null }
    }

    private fun upsertFileVersionLocked(value: FileVersion) {
        connection.prepareStatement(
            """INSERT INTO file_versions(
                   folder_id, relative_path, file_id, size_bytes, modified_at_millis, content_sha256,
                   previous_content_sha256, deleted, version_json, origin_device_id, local_sequence, purge_recovery)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(folder_id, relative_path) DO UPDATE SET
                   file_id = excluded.file_id, size_bytes = excluded.size_bytes,
                   modified_at_millis = excluded.modified_at_millis, content_sha256 = excluded.content_sha256,
                   previous_content_sha256 = excluded.previous_content_sha256, deleted = excluded.deleted,
                   version_json = excluded.version_json, origin_device_id = excluded.origin_device_id,
                   local_sequence = excluded.local_sequence, purge_recovery = excluded.purge_recovery""",
        ).use {
            it.setString(1, value.folderId); it.setString(2, value.relativePath); it.setString(3, value.fileId)
            it.setLong(4, value.sizeBytes); it.setLong(5, value.modifiedAtMillis); it.setString(6, value.contentSha256)
            it.setString(7, value.previousContentSha256); it.setInt(8, if (value.deleted) 1 else 0)
            it.setString(9, value.version.toJson()); it.setString(10, value.originDeviceId)
            it.setLong(11, value.localSequence); it.setInt(12, if (value.purgeRecovery) 1 else 0); it.executeUpdate()
        }
    }

    private fun upsertRemoteFileVersionLocked(value: RemoteFileVersion) {
        connection.prepareStatement(
            """INSERT INTO remote_file_versions(
                   folder_id, device_id, relative_path, file_id, size_bytes, modified_at_millis,
                   content_sha256, previous_content_sha256, origin_device_id, deleted, version_json, remote_sequence, purge_recovery)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(folder_id, device_id, relative_path) DO UPDATE SET
                   file_id = excluded.file_id, size_bytes = excluded.size_bytes,
                   modified_at_millis = excluded.modified_at_millis, content_sha256 = excluded.content_sha256,
                   previous_content_sha256 = excluded.previous_content_sha256,
                   origin_device_id = excluded.origin_device_id, deleted = excluded.deleted,
                   version_json = excluded.version_json, remote_sequence = excluded.remote_sequence, purge_recovery = excluded.purge_recovery""",
        ).use {
            it.setString(1, value.folderId); it.setString(2, value.deviceId); it.setString(3, value.relativePath)
            it.setString(4, value.fileId); it.setLong(5, value.sizeBytes); it.setLong(6, value.modifiedAtMillis)
            it.setString(7, value.contentSha256); it.setString(8, value.previousContentSha256)
            it.setString(9, value.originDeviceId); it.setInt(10, if (value.deleted) 1 else 0)
            it.setString(11, value.version.toJson()); it.setLong(12, value.remoteSequence); it.setInt(13, if (value.purgeRecovery) 1 else 0); it.executeUpdate()
        }
    }

    private fun upsertLocalBlockLocked(manifest: BlockManifest, block: FileBlock) {
        connection.prepareStatement(
            """INSERT INTO file_blocks(
                   folder_id, file_id, content_sha256, block_index, offset_bytes, size_bytes, block_sha256)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
        ).use {
            it.setString(1, manifest.folderId); it.setString(2, manifest.fileId); it.setString(3, manifest.contentSha256)
            it.setInt(4, block.index); it.setLong(5, block.offsetBytes); it.setInt(6, block.sizeBytes)
            it.setString(7, block.sha256); it.executeUpdate()
        }
    }

    private fun replaceRemoteBlocksLocked(folderId: String, deviceId: String, record: IndexedFileRecord) {
        connection.prepareStatement(
            "DELETE FROM remote_file_blocks WHERE folder_id = ? AND device_id = ? AND file_id = ?",
        ).use {
            it.setString(1, folderId); it.setString(2, deviceId); it.setString(3, record.fileId); it.executeUpdate()
        }
        record.blocks.forEach { block ->
            connection.prepareStatement(
                """INSERT INTO remote_file_blocks(
                       folder_id, device_id, file_id, content_sha256, block_index,
                       offset_bytes, size_bytes, block_sha256)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            ).use {
                it.setString(1, folderId); it.setString(2, deviceId); it.setString(3, record.fileId)
                it.setString(4, record.contentSha256); it.setInt(5, block.index); it.setLong(6, block.offsetBytes)
                it.setInt(7, block.sizeBytes); it.setString(8, block.sha256); it.executeUpdate()
            }
        }
    }

    private fun upsertFolderIndexStateLocked(value: FolderIndexState) {
        connection.prepareStatement(
            """INSERT INTO folder_index_states(
                   folder_id, device_id, index_epoch, max_sequence, metadata_received_sequence,
                   content_applied_sequence, updated_at_millis)
               VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(folder_id, device_id) DO UPDATE SET
                   index_epoch = excluded.index_epoch, max_sequence = excluded.max_sequence,
                   metadata_received_sequence = excluded.metadata_received_sequence,
                   content_applied_sequence = excluded.content_applied_sequence,
                   updated_at_millis = excluded.updated_at_millis""",
        ).use {
            it.setString(1, value.folderId); it.setString(2, value.deviceId); it.setLong(3, value.indexEpoch)
            it.setLong(4, value.maxSequence); it.setLong(5, value.metadataReceivedSequence)
            it.setLong(6, value.contentAppliedSequence); it.setLong(7, value.updatedAtMillis); it.executeUpdate()
        }
    }

    private fun migrate() {
        connection.createStatement().use { statement ->
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_info(version INTEGER NOT NULL)")
            if (!statement.executeQuery("SELECT 1 FROM schema_info LIMIT 1").use(ResultSet::next)) {
                statement.executeUpdate("INSERT INTO schema_info VALUES (1)")
            }
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS mesh_profile(
                    group_id TEXT PRIMARY KEY, group_name TEXT NOT NULL, created_at_millis INTEGER NOT NULL)""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS membership_events(
                    event_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, event_type TEXT NOT NULL,
                    subject_device_id TEXT NOT NULL, subject_name TEXT NOT NULL, subject_key TEXT NOT NULL,
                    signer_device_id TEXT NOT NULL, parent_ids TEXT NOT NULL, version_json TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL, signature TEXT NOT NULL)""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS devices(
                    group_id TEXT NOT NULL, device_id TEXT NOT NULL, display_name TEXT NOT NULL,
                    identity_key TEXT NOT NULL, tls_key TEXT, fingerprint TEXT NOT NULL,
                    trust_state TEXT NOT NULL, last_seen_at_millis INTEGER,
                    PRIMARY KEY(group_id, device_id))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS mesh_folders(
                    folder_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, display_name TEXT NOT NULL,
                    include_patterns TEXT NOT NULL, exclude_patterns TEXT NOT NULL,
                    signer_device_id TEXT NOT NULL, version_json TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL,
                    UNIQUE(group_id, display_name))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS folder_announcements(
                    event_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, folder_id TEXT NOT NULL,
                    display_name TEXT NOT NULL, include_patterns TEXT NOT NULL, exclude_patterns TEXT NOT NULL,
                    signer_device_id TEXT NOT NULL, version_json TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL, signature TEXT NOT NULL)""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS local_folder_bindings(
                    folder_id TEXT NOT NULL, device_id TEXT NOT NULL, local_path TEXT,
                    state TEXT NOT NULL, updated_at_millis INTEGER NOT NULL,
                    PRIMARY KEY(folder_id, device_id))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS file_versions(
                    folder_id TEXT NOT NULL, relative_path TEXT NOT NULL, file_id TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL, modified_at_millis INTEGER NOT NULL,
                    content_sha256 TEXT NOT NULL, previous_content_sha256 TEXT,
                    deleted INTEGER NOT NULL, version_json TEXT NOT NULL,
                    origin_device_id TEXT NOT NULL, local_sequence INTEGER NOT NULL,
                    PRIMARY KEY(folder_id, relative_path))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS remote_file_versions(
                    folder_id TEXT NOT NULL, device_id TEXT NOT NULL, relative_path TEXT NOT NULL,
                    file_id TEXT NOT NULL, size_bytes INTEGER NOT NULL, modified_at_millis INTEGER NOT NULL,
                    content_sha256 TEXT NOT NULL, previous_content_sha256 TEXT, origin_device_id TEXT NOT NULL,
                    deleted INTEGER NOT NULL, version_json TEXT NOT NULL, remote_sequence INTEGER NOT NULL,
                    PRIMARY KEY(folder_id, device_id, relative_path))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS folder_index_states(
                    folder_id TEXT NOT NULL, device_id TEXT NOT NULL, index_epoch INTEGER NOT NULL,
                    max_sequence INTEGER NOT NULL, metadata_received_sequence INTEGER NOT NULL,
                    content_applied_sequence INTEGER NOT NULL, updated_at_millis INTEGER NOT NULL,
                    PRIMARY KEY(folder_id, device_id))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS file_conflicts(
                    conflict_id TEXT PRIMARY KEY, folder_id TEXT NOT NULL, relative_path TEXT NOT NULL,
                    local_hash TEXT, remote_device_id TEXT NOT NULL, remote_hash TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL)""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS conflict_resolutions(
                    conflict_id TEXT PRIMARY KEY, action TEXT NOT NULL,
                    target_relative_path TEXT NOT NULL, created_at_millis INTEGER NOT NULL)""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS file_history(
                    event_id TEXT PRIMARY KEY, action TEXT NOT NULL, folder_id TEXT NOT NULL,
                    relative_path TEXT NOT NULL, source_device_id TEXT NOT NULL, size_bytes INTEGER,
                    modified_at_millis INTEGER, content_sha256 TEXT, created_at_millis INTEGER NOT NULL,
                    recovery_path TEXT, recoverable_until_millis INTEGER, recovered_at_millis INTEGER)""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS file_blocks(
                    folder_id TEXT NOT NULL, file_id TEXT NOT NULL, content_sha256 TEXT NOT NULL,
                    block_index INTEGER NOT NULL, offset_bytes INTEGER NOT NULL,
                    size_bytes INTEGER NOT NULL, block_sha256 TEXT NOT NULL,
                    PRIMARY KEY(folder_id, file_id, content_sha256, block_index))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS remote_file_blocks(
                    folder_id TEXT NOT NULL, device_id TEXT NOT NULL, file_id TEXT NOT NULL,
                    content_sha256 TEXT NOT NULL, block_index INTEGER NOT NULL,
                    offset_bytes INTEGER NOT NULL, size_bytes INTEGER NOT NULL, block_sha256 TEXT NOT NULL,
                    PRIMARY KEY(folder_id, device_id, file_id, content_sha256, block_index))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS partial_transfers(
                    folder_id TEXT NOT NULL, file_id TEXT NOT NULL, content_sha256 TEXT NOT NULL,
                    temporary_path TEXT NOT NULL, total_size_bytes INTEGER NOT NULL,
                    block_size_bytes INTEGER NOT NULL, received_blocks_base64 TEXT NOT NULL,
                    updated_at_millis INTEGER NOT NULL,
                    PRIMARY KEY(folder_id, file_id, content_sha256))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS chat_messages(
                    message_id TEXT PRIMARY KEY, group_id TEXT NOT NULL,
                    author_device_id TEXT NOT NULL, body TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL, signature TEXT NOT NULL,
                    attachment_file_name TEXT, attachment_media_type TEXT,
                    attachment_size_bytes INTEGER, attachment_sha256 TEXT,
                    attachment_expires_at_millis INTEGER)""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS sync_exception_events(
                    event_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, folder_id TEXT NOT NULL,
                    relative_path TEXT NOT NULL, active INTEGER NOT NULL,
                    signer_device_id TEXT NOT NULL, version_json TEXT NOT NULL,
                    created_at_millis INTEGER NOT NULL, signature TEXT NOT NULL)""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS sync_exceptions(
                    folder_id TEXT NOT NULL, relative_path TEXT NOT NULL, active INTEGER NOT NULL,
                    created_by_device_id TEXT NOT NULL, created_at_millis INTEGER NOT NULL,
                    updated_at_millis INTEGER NOT NULL, version_json TEXT NOT NULL,
                    last_event_id TEXT NOT NULL, PRIMARY KEY(folder_id, relative_path))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS folder_key_history(
                    folder_id TEXT NOT NULL, key_id TEXT NOT NULL, encrypted_key TEXT NOT NULL,
                    PRIMARY KEY(folder_id, key_id))""",
            )
            statement.executeUpdate(
                """CREATE TABLE IF NOT EXISTS folder_keys(
                    folder_id TEXT PRIMARY KEY, key_id TEXT NOT NULL,
                    encrypted_key TEXT NOT NULL, updated_at_millis INTEGER NOT NULL)""",
            )
            listOf("file_versions", "remote_file_versions").forEach { table ->
                val columns = statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                    buildSet { while (rows.next()) add(rows.getString("name")) }
                }
                if ("purge_recovery" !in columns) statement.executeUpdate("ALTER TABLE $table ADD COLUMN purge_recovery INTEGER NOT NULL DEFAULT 0")
            }
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS index_file_versions_sequence ON file_versions(folder_id, local_sequence)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS index_remote_file_versions_sequence ON remote_file_versions(folder_id, device_id, remote_sequence)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS index_file_history_created ON file_history(created_at_millis)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS index_file_history_recovery ON file_history(recoverable_until_millis)")
            val chatColumns = statement.executeQuery("PRAGMA table_info(chat_messages)").use { rows ->
                buildSet { while (rows.next()) add(rows.getString("name")) }
            }
            if ("attachment_file_name" !in chatColumns) statement.executeUpdate("ALTER TABLE chat_messages ADD COLUMN attachment_file_name TEXT")
            if ("attachment_media_type" !in chatColumns) statement.executeUpdate("ALTER TABLE chat_messages ADD COLUMN attachment_media_type TEXT")
            if ("attachment_size_bytes" !in chatColumns) statement.executeUpdate("ALTER TABLE chat_messages ADD COLUMN attachment_size_bytes INTEGER")
            if ("attachment_sha256" !in chatColumns) statement.executeUpdate("ALTER TABLE chat_messages ADD COLUMN attachment_sha256 TEXT")
            if ("attachment_expires_at_millis" !in chatColumns) statement.executeUpdate("ALTER TABLE chat_messages ADD COLUMN attachment_expires_at_millis INTEGER")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS index_chat_messages_group ON chat_messages(group_id, created_at_millis, message_id)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS index_sync_exception_events_group ON sync_exception_events(group_id, created_at_millis, event_id)")
            statement.executeUpdate("UPDATE schema_info SET version = 9 WHERE version < 9")
        }
    }

    private fun ResultSet.profile() = MeshProfile(getString(1), getString(2), getLong(3))
    private fun ResultSet.membership() = MembershipEvent(
        getString(1), getString(2), MembershipEventType.valueOf(getString(3)), getString(4), getString(5),
        getString(6), getString(7), getString(8).lines().filter(String::isNotBlank), VersionVector.fromJson(getString(9)),
        getLong(10), getString(11),
    )
    private fun ResultSet.device() = TrustedDevice(
        getString(1), getString(2), getString(3), getString(4), getString(5), getString(6),
        getString(7) == "TRUSTED", getLong(8).takeUnless { wasNull() },
    )
    private fun ResultSet.folderAnnouncement() = FolderAnnouncement(
        getString(1), getString(2), getString(3), getString(4), decodeList(getString(5)), decodeList(getString(6)),
        getString(7), VersionVector.fromJson(getString(8)), getLong(9), getString(10),
    )
    private fun ResultSet.folder() = MeshFolder(
        getString(1), getString(2), getString(3), decodeList(getString(4)), decodeList(getString(5)), getString(6),
        getString(7)?.let(LocalFolderBindingState::valueOf) ?: LocalFolderBindingState.PENDING_CONFIGURATION,
        getString(8),
    )
    private fun ResultSet.storedFolder() = StoredFolder(
        getString(1), getString(2), getString(3), decodeList(getString(4)), decodeList(getString(5)), getString(6),
        VersionVector.fromJson(getString(7)), getLong(8),
    )
    private fun ResultSet.fileVersion() = FileVersion(
        getString(1), getString(2), getString(3), getLong(4), getLong(5), getString(6), getString(7),
        getInt(8) != 0, VersionVector.fromJson(getString(9)), getString(10), getLong(11), getInt(12) != 0,
    )
    private fun ResultSet.remoteFileVersion() = RemoteFileVersion(
        getString(1), getString(2), getString(3), getString(4), getLong(5), getLong(6), getString(7), getString(8),
        getString(9), getInt(10) != 0, VersionVector.fromJson(getString(11)), getLong(12), getInt(13) != 0,
    )
    private fun ResultSet.folderIndexState() = FolderIndexState(
        getString(1), getString(2), getLong(3), getLong(4), getLong(5), getLong(6), getLong(7),
    )
    private fun ResultSet.syncExceptionEvent() = SyncExceptionEvent(
        getString(1), getString(2), getString(3), getString(4), getInt(5) != 0,
        getString(6), VersionVector.fromJson(getString(7)), getLong(8), getString(9),
    )
    private fun ResultSet.syncExceptionState() = SyncExceptionState(
        getString(1), getString(2), getInt(3) != 0, getString(4), getLong(5), getLong(6),
        VersionVector.fromJson(getString(7)), getString(8),
    )
    private fun ResultSet.fileConflict() = FileConflict(
        getString(1), getString(2), getString(3), getString(4), getString(5), getString(6), getLong(7),
    )
    private fun ResultSet.fileHistoryEvent() = FileHistoryEvent(
        getString(1),
        FileHistoryAction.valueOf(getString(2)),
        getString(3),
        getString(4),
        getString(5),
        getLong(6).takeUnless { wasNull() },
        getLong(7).takeUnless { wasNull() },
        getString(8),
        getLong(9),
        getString(10),
        getLong(11).takeUnless { wasNull() },
        getLong(12).takeUnless { wasNull() },
    )

    private fun <T> transaction(block: () -> T): T {
        val previous = connection.autoCommit
        connection.autoCommit = false
        return try { block().also { connection.commit() } } catch (error: Throwable) {
            connection.rollback(); throw error
        } finally { connection.autoCommit = previous }
    }

    override fun close() = connection.close()

    companion object {
        fun defaultDatabasePath(): Path = WindowsAppPaths.database
    }
}

private fun encodeList(values: List<String>): String = values.joinToString("\n") {
    Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
}

private fun randomIndexEpoch(): Long = (java.security.SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1)

private fun decodeList(value: String): List<String> = value.lines()
    .filter(String::isNotEmpty)
    .map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }

private const val MAX_REPLICATED_CHAT_MESSAGES = 5_000
