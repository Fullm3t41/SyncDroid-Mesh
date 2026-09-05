package com.syncdroid.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MeshDao {
    @Upsert
    suspend fun upsertGroup(group: MeshGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: DeviceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembershipEvent(event: MembershipEventEntity): Long

    @Query("SELECT * FROM devices WHERE groupId = :groupId ORDER BY displayName")
    fun observeDevices(groupId: String): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE groupId = :groupId AND deviceId = :deviceId LIMIT 1")
    suspend fun getDevice(groupId: String, deviceId: String): DeviceEntity?

    @Query("SELECT * FROM devices WHERE groupId = :groupId AND trustState = 'TRUSTED' ORDER BY deviceId")
    suspend fun trustedDevices(groupId: String): List<DeviceEntity>

    @Query("UPDATE devices SET lastSeenAtMillis = :seenAtMillis WHERE groupId = :groupId AND deviceId = :deviceId")
    suspend fun updateLastSeen(groupId: String, deviceId: String, seenAtMillis: Long)

    @Query("SELECT COUNT(*) FROM devices WHERE groupId = :groupId AND trustState = 'TRUSTED'")
    suspend fun trustedDeviceCount(groupId: String): Int

    @Query("SELECT * FROM membership_events WHERE groupId = :groupId ORDER BY createdAtMillis")
    suspend fun membershipEvents(groupId: String): List<MembershipEventEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM membership_events WHERE eventId = :eventId)")
    suspend fun hasMembershipEvent(eventId: String): Boolean
}

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE groupId = :groupId ORDER BY createdAtMillis, messageId")
    fun observeMessages(groupId: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE groupId = :groupId ORDER BY createdAtMillis DESC, messageId DESC LIMIT :limit")
    suspend fun recentMessages(groupId: String, limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE groupId = :groupId AND messageId = :messageId LIMIT 1")
    suspend fun getMessage(groupId: String, messageId: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages WHERE groupId = :groupId AND attachmentFileName IS NOT NULL ORDER BY createdAtMillis")
    suspend fun attachmentMessages(groupId: String): List<ChatMessageEntity>
}

@Dao
interface SyncDao {
    @Upsert
    suspend fun upsertFolder(folder: SyncFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocalBinding(binding: LocalFolderBindingEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFolderAnnouncement(event: FolderAnnouncementEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEndpoint(endpoint: SyncEndpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncException(exception: SyncExceptionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncExceptionEvent(event: SyncExceptionEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFileVersion(file: FileVersionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFileVersions(files: List<FileVersionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRemoteFileVersions(files: List<RemoteFileVersionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolderIndexState(state: FolderIndexStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFileBlocks(blocks: List<FileBlockEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPartialTransfer(transfer: PartialTransferEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolderKey(key: FolderKeyEntity)

    @Query("SELECT * FROM sync_folders ORDER BY displayName")
    fun observeFolders(): Flow<List<SyncFolderEntity>>

    @Query("SELECT * FROM sync_folders WHERE groupId = :groupId AND enabled = 1 ORDER BY folderId")
    suspend fun enabledFolders(groupId: String): List<SyncFolderEntity>

    @Query("SELECT * FROM local_folder_bindings WHERE deviceId = :deviceId ORDER BY updatedAtMillis DESC")
    fun observeBindings(deviceId: String): Flow<List<LocalFolderBindingEntity>>

    @Query(
        """
        SELECT f.folderId, f.displayName, f.includePatternsJson, f.excludePatternsJson,
               b.localLocation, b.state AS bindingState, f.deletionPolicy, f.createdByDeviceId
        FROM sync_folders AS f
        INNER JOIN local_folder_bindings AS b ON b.folderId = f.folderId
        WHERE b.deviceId = :deviceId AND f.groupId = :groupId AND f.enabled = 1
        ORDER BY b.updatedAtMillis DESC
        """,
    )
    fun observeLocalFolderViews(deviceId: String, groupId: String): Flow<List<LocalFolderView>>

    @Query("SELECT * FROM local_folder_bindings WHERE folderId = :folderId AND deviceId = :deviceId LIMIT 1")
    suspend fun getBinding(folderId: String, deviceId: String): LocalFolderBindingEntity?

    @Query(
        """
        SELECT b.* FROM local_folder_bindings AS b
        INNER JOIN sync_folders AS f ON f.folderId = b.folderId
        WHERE b.deviceId = :deviceId AND f.groupId = :groupId AND b.state = 'CONFIGURED'
        ORDER BY b.folderId
        """,
    )
    suspend fun configuredBindings(deviceId: String, groupId: String): List<LocalFolderBindingEntity>

    @Query(
        """
        SELECT COUNT(*) FROM local_folder_bindings AS b
        INNER JOIN sync_folders AS f ON f.folderId = b.folderId
        WHERE b.deviceId = :deviceId AND f.groupId = :groupId
          AND f.enabled = 1 AND b.state = 'PENDING_CONFIGURATION'
        """,
    )
    suspend fun pendingConfigurationCount(deviceId: String, groupId: String): Int

    @Query("SELECT * FROM folder_announcements WHERE groupId = :groupId ORDER BY createdAtMillis")
    suspend fun folderAnnouncements(groupId: String): List<FolderAnnouncementEntity>

    @Query("SELECT * FROM sync_folders WHERE folderId = :folderId LIMIT 1")
    suspend fun getFolder(folderId: String): SyncFolderEntity?

    @Query("SELECT * FROM sync_folders WHERE groupId = :groupId AND displayName = :displayName LIMIT 1")
    suspend fun getFolderByName(groupId: String, displayName: String): SyncFolderEntity?

    @Query("UPDATE sync_folders SET deletionPolicy = :policy WHERE folderId = :folderId")
    suspend fun setDeletionPolicy(folderId: String, policy: String)

    @Query("SELECT * FROM sync_exceptions WHERE folderId = :folderId AND active = 1 ORDER BY relativePath")
    fun observeActiveExceptions(folderId: String): Flow<List<SyncExceptionEntity>>

    @Query("SELECT * FROM sync_exceptions WHERE active = 1 ORDER BY folderId, relativePath")
    fun observeAllActiveExceptions(): Flow<List<SyncExceptionEntity>>

    @Query("SELECT * FROM sync_exceptions WHERE folderId = :folderId AND active = 1 ORDER BY relativePath")
    suspend fun activeExceptions(folderId: String): List<SyncExceptionEntity>

    @Query("SELECT * FROM sync_exceptions WHERE folderId = :folderId AND relativePath = :relativePath LIMIT 1")
    suspend fun getSyncException(folderId: String, relativePath: String): SyncExceptionEntity?

    @Query("SELECT * FROM sync_exception_events WHERE groupId = :groupId ORDER BY createdAtMillis, eventId")
    suspend fun syncExceptionEvents(groupId: String): List<SyncExceptionEventEntity>

    @Query(
        """
        SELECT * FROM sync_exception_events
        WHERE folderId = :folderId AND relativePath = :relativePath
        ORDER BY createdAtMillis, eventId
        """,
    )
    suspend fun syncExceptionEventsForPath(folderId: String, relativePath: String): List<SyncExceptionEventEntity>

    @Query(
        """
        SELECT * FROM sync_exception_events
        WHERE folderId = :folderId AND signerDeviceId = :deviceId
        ORDER BY createdAtMillis, eventId
        """,
    )
    suspend fun syncExceptionEventsForDevice(folderId: String, deviceId: String): List<SyncExceptionEventEntity>

    @Query("UPDATE sync_exceptions SET active = 0, updatedAtMillis = :updatedAtMillis WHERE folderId = :folderId AND relativePath = :relativePath")
    suspend fun undoSyncException(folderId: String, relativePath: String, updatedAtMillis: Long)

    @Query("SELECT * FROM file_versions WHERE folderId = :folderId ORDER BY relativePath")
    suspend fun fileVersions(folderId: String): List<FileVersionEntity>

    @Query("SELECT * FROM file_versions WHERE folderId = :folderId AND relativePath = :relativePath LIMIT 1")
    suspend fun fileVersion(folderId: String, relativePath: String): FileVersionEntity?

    @Query("SELECT * FROM folder_index_states WHERE folderId = :folderId AND deviceId = :deviceId LIMIT 1")
    suspend fun folderIndexState(folderId: String, deviceId: String): FolderIndexStateEntity?

    @Query("SELECT * FROM folder_index_states WHERE folderId = :folderId ORDER BY deviceId")
    suspend fun folderIndexStates(folderId: String): List<FolderIndexStateEntity>

    @Query("SELECT * FROM file_versions WHERE folderId = :folderId AND localSequence > :afterSequence ORDER BY localSequence LIMIT :limit")
    suspend fun fileVersionsAfter(folderId: String, afterSequence: Long, limit: Int): List<FileVersionEntity>

    @Query("SELECT * FROM remote_file_versions WHERE folderId = :folderId AND deviceId = :deviceId ORDER BY relativePath")
    suspend fun remoteFileVersions(folderId: String, deviceId: String): List<RemoteFileVersionEntity>

    @Query(
        """
        SELECT * FROM remote_file_versions
        WHERE folderId = :folderId AND relativePath = :relativePath
        ORDER BY deviceId
        """,
    )
    suspend fun remoteFileVersionsForPath(folderId: String, relativePath: String): List<RemoteFileVersionEntity>

    @Query("DELETE FROM remote_file_versions WHERE folderId = :folderId AND deviceId = :deviceId")
    suspend fun deleteRemoteFileVersions(folderId: String, deviceId: String)

    @Query("SELECT * FROM file_blocks WHERE folderId = :folderId AND fileId = :fileId AND contentSha256 = :contentSha256 ORDER BY blockIndex")
    suspend fun fileBlocks(folderId: String, fileId: String, contentSha256: String): List<FileBlockEntity>

    @Query("DELETE FROM file_blocks WHERE folderId = :folderId AND fileId = :fileId AND contentSha256 != :keepContentSha256")
    suspend fun deleteOldFileBlocks(folderId: String, fileId: String, keepContentSha256: String)

    @Query("SELECT * FROM partial_transfers WHERE folderId = :folderId AND fileId = :fileId AND contentSha256 = :contentSha256 LIMIT 1")
    suspend fun partialTransfer(folderId: String, fileId: String, contentSha256: String): PartialTransferEntity?

    @Query("DELETE FROM partial_transfers WHERE folderId = :folderId AND fileId = :fileId AND contentSha256 = :contentSha256")
    suspend fun deletePartialTransfer(folderId: String, fileId: String, contentSha256: String)

    @Query("SELECT * FROM folder_keys WHERE folderId = :folderId LIMIT 1")
    suspend fun folderKey(folderId: String): FolderKeyEntity?

    @Query("SELECT * FROM folder_keys ORDER BY folderId")
    suspend fun folderKeys(): List<FolderKeyEntity>

    @Query("SELECT * FROM sync_endpoints WHERE folderId = :folderId AND enabled = 1")
    suspend fun endpointsForFolder(folderId: String): List<SyncEndpointEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: SnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshotFiles(files: List<SnapshotFileEntity>)

    @Transaction
    suspend fun insertSnapshotWithFiles(snapshot: SnapshotEntity, files: List<SnapshotFileEntity>) {
        insertSnapshot(snapshot)
        insertSnapshotFiles(files)
    }

    @Transaction
    suspend fun insertVersionedSnapshot(
        snapshot: SnapshotEntity,
        files: List<SnapshotFileEntity>,
        currentFiles: List<FileVersionEntity>,
        indexState: FolderIndexStateEntity,
    ) {
        insertSnapshot(snapshot)
        insertSnapshotFiles(files)
        upsertFileVersions(currentFiles)
        upsertFolderIndexState(indexState)
    }

    @Query("SELECT * FROM snapshots WHERE folderId = :folderId ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun latestSnapshot(folderId: String): SnapshotEntity?

    @Query("SELECT * FROM snapshot_files WHERE snapshotId = :snapshotId ORDER BY relativePath")
    suspend fun filesForSnapshot(snapshotId: String): List<SnapshotFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflict(conflict: ConflictEntity)

    @Query("SELECT * FROM conflicts WHERE state = 'Unresolved' ORDER BY createdAtMillis DESC")
    fun observeUnresolvedConflicts(): Flow<List<ConflictEntity>>

    @Query("SELECT COUNT(*) FROM conflicts WHERE folderId = :folderId AND state = 'Unresolved'")
    suspend fun unresolvedConflictCount(folderId: String): Int

    @Query("SELECT COUNT(*) FROM conflicts WHERE state = 'Unresolved'")
    suspend fun unresolvedConflictCount(): Int

    @Query("SELECT * FROM conflicts WHERE conflictId = :conflictId LIMIT 1")
    suspend fun conflict(conflictId: String): ConflictEntity?

    @Query("SELECT * FROM conflicts WHERE folderId = :folderId AND relativePath = :relativePath AND state IN ('KeepRight', 'KeepBoth') ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun pendingRemoteResolution(folderId: String, relativePath: String): ConflictEntity?

    @Query("SELECT relativePath FROM conflicts WHERE folderId = :folderId AND state IN ('KeepRight', 'KeepBoth')")
    suspend fun pathsAwaitingRemoteResolution(folderId: String): List<String>

    @Query("UPDATE conflicts SET state = :state, resolvedAtMillis = :resolvedAtMillis, renamedRelativePath = :renamedRelativePath WHERE conflictId = :conflictId")
    suspend fun updateConflictResolution(
        conflictId: String,
        state: String,
        resolvedAtMillis: Long?,
        renamedRelativePath: String?,
    )

    @Query("UPDATE conflicts SET state = 'Resolved', resolvedAtMillis = :resolvedAtMillis WHERE folderId = :folderId AND relativePath = :relativePath AND state = 'Unresolved'")
    suspend fun resolveDuplicateConflicts(folderId: String, relativePath: String, resolvedAtMillis: Long)

    @Query("UPDATE conflicts SET state = 'Resolved', resolvedAtMillis = :resolvedAtMillis WHERE folderId = :folderId AND relativePath = :relativePath AND state IN ('KeepRight', 'KeepBoth')")
    suspend fun completeRemoteResolution(folderId: String, relativePath: String, resolvedAtMillis: Long)

    @Query("SELECT * FROM remote_file_versions WHERE folderId = :folderId AND deviceId = :deviceId AND relativePath = :relativePath LIMIT 1")
    suspend fun remoteFileVersion(folderId: String, deviceId: String, relativePath: String): RemoteFileVersionEntity?

    @Query("SELECT * FROM remote_file_versions WHERE folderId = :folderId ORDER BY relativePath")
    suspend fun allRemoteFileVersions(folderId: String): List<RemoteFileVersionEntity>
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activity_events WHERE folderId = :folderId AND relativePath = :relativePath AND recoveryPath IS NOT NULL")
    suspend fun recoveriesForFile(folderId: String, relativePath: String): List<ActivityEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: ActivityEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ActivityEventEntity>)

    @Query("SELECT * FROM activity_events ORDER BY createdAtMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<ActivityEventEntity>>

    @Query("SELECT * FROM activity_events WHERE eventId = :eventId LIMIT 1")
    suspend fun event(eventId: String): ActivityEventEntity?

    @Query(
        """
        SELECT * FROM activity_events
        WHERE action = 'DELETED' AND folderId = :folderId AND relativePath = :relativePath
          AND contentSha256 = :contentSha256 AND recoveredAtMillis IS NULL
        ORDER BY createdAtMillis DESC LIMIT 1
        """,
    )
    suspend fun activeDeletion(folderId: String, relativePath: String, contentSha256: String): ActivityEventEntity?

    @Query(
        """
        SELECT * FROM activity_events
        WHERE recoveryPath IS NOT NULL AND recoveredAtMillis IS NULL
          AND recoverableUntilMillis IS NOT NULL AND recoverableUntilMillis <= :nowMillis
        """,
    )
    suspend fun expiredRecoveries(nowMillis: Long): List<ActivityEventEntity>

    @Query("UPDATE activity_events SET recoveryPath = NULL WHERE eventId = :eventId")
    suspend fun clearRecoveryPath(eventId: String)

    @Query(
        """
        UPDATE activity_events
        SET recoveredAtMillis = :recoveredAtMillis, recoveryPath = NULL
        WHERE eventId = :eventId
        """,
    )
    suspend fun markRecovered(eventId: String, recoveredAtMillis: Long)
}
