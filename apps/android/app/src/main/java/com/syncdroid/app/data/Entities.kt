package com.syncdroid.app.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "mesh_groups", primaryKeys = ["groupId"])
data class MeshGroupEntity(
    val groupId: String,
    val displayName: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "devices",
    primaryKeys = ["groupId", "deviceId"],
    indices = [Index("deviceId")],
    foreignKeys = [
        ForeignKey(
            entity = MeshGroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DeviceEntity(
    val groupId: String,
    val deviceId: String,
    val displayName: String,
    val publicKeyBase64: String,
    val fingerprint: String,
    val trustState: String,
    val addedByDeviceId: String,
    val addedAtMillis: Long,
    val lastSeenAtMillis: Long?,
)

@Entity(
    tableName = "membership_events",
    primaryKeys = ["eventId"],
    indices = [Index("groupId"), Index("subjectDeviceId")],
)
data class MembershipEventEntity(
    val eventId: String,
    val groupId: String,
    val eventType: String,
    val subjectDeviceId: String,
    val subjectDisplayName: String,
    val subjectPublicKeyBase64: String,
    val signerDeviceId: String,
    val signatureBase64: String,
    val parentEventIdsJson: String,
    val versionVectorJson: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "chat_messages",
    primaryKeys = ["messageId"],
    indices = [Index("groupId"), Index("authorDeviceId"), Index("createdAtMillis")],
    foreignKeys = [
        ForeignKey(
            entity = MeshGroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChatMessageEntity(
    val messageId: String,
    val groupId: String,
    val authorDeviceId: String,
    val body: String,
    val createdAtMillis: Long,
    val signatureBase64: String,
    val attachmentFileName: String? = null,
    val attachmentMediaType: String? = null,
    val attachmentSizeBytes: Long? = null,
    val attachmentSha256: String? = null,
    val attachmentExpiresAtMillis: Long? = null,
)

@Entity(
    tableName = "sync_folders",
    primaryKeys = ["folderId"],
    indices = [Index("groupId")],
    foreignKeys = [
        ForeignKey(
            entity = MeshGroupEntity::class,
            parentColumns = ["groupId"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SyncFolderEntity(
    val folderId: String,
    val groupId: String,
    val displayName: String,
    val includePatternsJson: String,
    val excludePatternsJson: String,
    val enabled: Boolean,
    @ColumnInfo(defaultValue = "'PROPAGATE'")
    val deletionPolicy: String = "PROPAGATE",
    val createdByDeviceId: String,
    val versionVectorJson: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "local_folder_bindings",
    primaryKeys = ["folderId", "deviceId"],
    indices = [Index("deviceId"), Index("state")],
    foreignKeys = [
        ForeignKey(
            entity = SyncFolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class LocalFolderBindingEntity(
    val folderId: String,
    val deviceId: String,
    val localLocation: String?,
    val state: String,
    val updatedAtMillis: Long,
)

data class LocalFolderView(
    val folderId: String,
    val displayName: String,
    val includePatternsJson: String,
    val excludePatternsJson: String,
    val localLocation: String?,
    val bindingState: String,
    val deletionPolicy: String,
    val createdByDeviceId: String,
)

@Entity(
    tableName = "sync_exceptions",
    primaryKeys = ["folderId", "relativePath"],
    indices = [Index("active")],
    foreignKeys = [
        ForeignKey(
            entity = SyncFolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SyncExceptionEntity(
    val folderId: String,
    val relativePath: String,
    val active: Boolean,
    val createdByDeviceId: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val versionVectorJson: String,
    @ColumnInfo(defaultValue = "''")
    val lastEventId: String = "",
)

@Entity(
    tableName = "folder_announcements",
    primaryKeys = ["eventId"],
    indices = [Index("groupId"), Index("folderId")],
)
data class FolderAnnouncementEntity(
    val eventId: String,
    val groupId: String,
    val folderId: String,
    val displayName: String,
    val includePatternsJson: String,
    val excludePatternsJson: String,
    val signerDeviceId: String,
    val signatureBase64: String,
    val versionVectorJson: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "sync_endpoints",
    primaryKeys = ["endpointId"],
    indices = [Index("folderId")],
    foreignKeys = [
        ForeignKey(
            entity = SyncFolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SyncEndpointEntity(
    val endpointId: String,
    val folderId: String,
    val endpointType: String,
    val direction: String,
    val accountId: String?,
    val remoteRootId: String?,
    val enabled: Boolean,
    val changeCursor: String?,
)

@Entity(
    tableName = "snapshots",
    primaryKeys = ["snapshotId"],
    indices = [Index("folderId"), Index("createdAtMillis")],
    foreignKeys = [
        ForeignKey(
            entity = SyncFolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SnapshotEntity(
    val snapshotId: String,
    val folderId: String,
    val originDeviceId: String,
    val createdAtMillis: Long,
    val versionVectorJson: String,
    val parentSnapshotIdsJson: String,
    val state: String,
)

@Entity(
    tableName = "snapshot_files",
    primaryKeys = ["snapshotId", "relativePath"],
    indices = [Index("sha256")],
    foreignKeys = [
        ForeignKey(
            entity = SnapshotEntity::class,
            parentColumns = ["snapshotId"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SnapshotFileEntity(
    val snapshotId: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val sha256: String,
    val deleted: Boolean,
    @ColumnInfo(defaultValue = "''")
    val fileId: String = "",
    val previousSha256: String? = null,
    @ColumnInfo(defaultValue = "'{}'")
    val versionVectorJson: String = "{}",
    @ColumnInfo(defaultValue = "0")
    val localSequence: Long = 0,
)

@Entity(
    tableName = "file_versions",
    primaryKeys = ["folderId", "relativePath"],
    indices = [Index("fileId"), Index("contentSha256"), Index("localSequence")],
    foreignKeys = [
        ForeignKey(
            entity = SyncFolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FileVersionEntity(
    val folderId: String,
    val relativePath: String,
    val fileId: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val contentSha256: String,
    val previousContentSha256: String?,
    val deleted: Boolean,
    val versionVectorJson: String,
    val originDeviceId: String,
    val localSequence: Long,
    @ColumnInfo(defaultValue = "0") val purgeRecovery: Boolean = false,
)

@Entity(
    tableName = "remote_file_versions",
    primaryKeys = ["folderId", "deviceId", "relativePath"],
    indices = [Index("deviceId"), Index("fileId"), Index("contentSha256")],
    foreignKeys = [
        ForeignKey(
            entity = SyncFolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RemoteFileVersionEntity(
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
    val versionVectorJson: String,
    val remoteSequence: Long,
    @ColumnInfo(defaultValue = "0") val purgeRecovery: Boolean = false,
)

@Entity(
    tableName = "folder_index_states",
    primaryKeys = ["folderId", "deviceId"],
    indices = [Index("deviceId")],
    foreignKeys = [
        ForeignKey(
            entity = SyncFolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FolderIndexStateEntity(
    val folderId: String,
    val deviceId: String,
    val indexEpoch: Long,
    val maxSequence: Long,
    val metadataReceivedSequence: Long,
    val contentAppliedSequence: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "file_blocks",
    primaryKeys = ["folderId", "fileId", "contentSha256", "blockIndex"],
    indices = [Index("contentSha256")],
    foreignKeys = [
        ForeignKey(
            entity = SyncFolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FileBlockEntity(
    val folderId: String,
    val fileId: String,
    val contentSha256: String,
    val blockIndex: Int,
    val offsetBytes: Long,
    val sizeBytes: Int,
    val blockSha256: String,
)

@Entity(
    tableName = "partial_transfers",
    primaryKeys = ["folderId", "fileId", "contentSha256"],
    foreignKeys = [
        ForeignKey(
            entity = SyncFolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PartialTransferEntity(
    val folderId: String,
    val fileId: String,
    val contentSha256: String,
    val temporaryPath: String,
    val totalSizeBytes: Long,
    val blockSizeBytes: Int,
    val receivedBlocksBase64: String,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "sync_exception_events",
    primaryKeys = ["eventId"],
    indices = [Index("groupId"), Index("folderId")],
)
data class SyncExceptionEventEntity(
    val eventId: String,
    val groupId: String,
    val folderId: String,
    val relativePath: String,
    val active: Boolean,
    val signerDeviceId: String,
    val versionVectorJson: String,
    val createdAtMillis: Long,
    val signatureBase64: String,
)

@Entity(
    tableName = "folder_keys",
    primaryKeys = ["folderId"],
    foreignKeys = [
        ForeignKey(
            entity = SyncFolderEntity::class,
            parentColumns = ["folderId"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FolderKeyEntity(
    val folderId: String,
    val keyId: String,
    val wrappedKeyBase64: String,
    val nonceBase64: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "conflicts", primaryKeys = ["conflictId"], indices = [Index("folderId"), Index("state")])
data class ConflictEntity(
    val conflictId: String,
    val folderId: String,
    val relativePath: String?,
    val leftSnapshotId: String,
    val rightSnapshotId: String,
    val state: String,
    val createdAtMillis: Long,
    val resolvedAtMillis: Long?,
    @ColumnInfo(defaultValue = "NULL")
    val renamedRelativePath: String? = null,
)

@Entity(
    tableName = "activity_events",
    primaryKeys = ["eventId"],
    indices = [Index("createdAtMillis"), Index("folderId"), Index("recoverableUntilMillis")],
)
data class ActivityEventEntity(
    val eventId: String,
    val category: String,
    val title: String,
    val detail: String,
    val createdAtMillis: Long,
    @ColumnInfo(defaultValue = "'INFO'")
    val action: String = "INFO",
    @ColumnInfo(defaultValue = "NULL")
    val folderId: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val relativePath: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val sourceDeviceId: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val sizeBytes: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val modifiedAtMillis: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val contentSha256: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val recoveryPath: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val recoverableUntilMillis: Long? = null,
    @ColumnInfo(defaultValue = "NULL")
    val recoveredAtMillis: Long? = null,
)
