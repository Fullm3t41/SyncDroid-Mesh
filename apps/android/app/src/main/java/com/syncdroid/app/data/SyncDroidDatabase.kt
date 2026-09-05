package com.syncdroid.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MeshGroupEntity::class,
        DeviceEntity::class,
        MembershipEventEntity::class,
        ChatMessageEntity::class,
        SyncFolderEntity::class,
        LocalFolderBindingEntity::class,
        SyncExceptionEntity::class,
        FolderAnnouncementEntity::class,
        SyncEndpointEntity::class,
        SnapshotEntity::class,
        SnapshotFileEntity::class,
        ConflictEntity::class,
        ActivityEventEntity::class,
        FileVersionEntity::class,
        RemoteFileVersionEntity::class,
        FolderIndexStateEntity::class,
        FileBlockEntity::class,
        PartialTransferEntity::class,
        SyncExceptionEventEntity::class,
        FolderKeyEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class SyncDroidDatabase : RoomDatabase() {
    abstract fun meshDao(): MeshDao
    abstract fun chatDao(): ChatDao
    abstract fun syncDao(): SyncDao
    abstract fun activityDao(): ActivityDao

    companion object {
        @Volatile private var instance: SyncDroidDatabase? = null

        fun get(context: Context): SyncDroidDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                SyncDroidDatabase::class.java,
                "syncdroid.db",
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
            )
                .build()
                .also { instance = it }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE file_versions ADD COLUMN purgeRecovery INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE remote_file_versions ADD COLUMN purgeRecovery INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sync_folders ADD COLUMN deletionPolicy TEXT NOT NULL DEFAULT 'PROPAGATE'",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_exceptions (
                        folderId TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        active INTEGER NOT NULL,
                        createdByDeviceId TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL,
                        versionVectorJson TEXT NOT NULL,
                        PRIMARY KEY(folderId, relativePath),
                        FOREIGN KEY(folderId) REFERENCES sync_folders(folderId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_exceptions_active ON sync_exceptions(active)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_exceptions ADD COLUMN lastEventId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE snapshot_files ADD COLUMN fileId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE snapshot_files ADD COLUMN previousSha256 TEXT")
                db.execSQL("ALTER TABLE snapshot_files ADD COLUMN versionVectorJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE snapshot_files ADD COLUMN localSequence INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS file_versions (
                        folderId TEXT NOT NULL, relativePath TEXT NOT NULL, fileId TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL, modifiedAtMillis INTEGER NOT NULL,
                        contentSha256 TEXT NOT NULL, previousContentSha256 TEXT,
                        deleted INTEGER NOT NULL, versionVectorJson TEXT NOT NULL,
                        originDeviceId TEXT NOT NULL, localSequence INTEGER NOT NULL,
                        PRIMARY KEY(folderId, relativePath),
                        FOREIGN KEY(folderId) REFERENCES sync_folders(folderId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_versions_fileId ON file_versions(fileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_versions_contentSha256 ON file_versions(contentSha256)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_versions_localSequence ON file_versions(localSequence)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS remote_file_versions (
                        folderId TEXT NOT NULL, deviceId TEXT NOT NULL, relativePath TEXT NOT NULL,
                        fileId TEXT NOT NULL, sizeBytes INTEGER NOT NULL, modifiedAtMillis INTEGER NOT NULL,
                        contentSha256 TEXT NOT NULL, previousContentSha256 TEXT, deleted INTEGER NOT NULL,
                        versionVectorJson TEXT NOT NULL, remoteSequence INTEGER NOT NULL,
                        PRIMARY KEY(folderId, deviceId, relativePath),
                        FOREIGN KEY(folderId) REFERENCES sync_folders(folderId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_file_versions_deviceId ON remote_file_versions(deviceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_file_versions_fileId ON remote_file_versions(fileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_file_versions_contentSha256 ON remote_file_versions(contentSha256)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folder_index_states (
                        folderId TEXT NOT NULL, deviceId TEXT NOT NULL, indexEpoch INTEGER NOT NULL,
                        maxSequence INTEGER NOT NULL, metadataReceivedSequence INTEGER NOT NULL,
                        contentAppliedSequence INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(folderId, deviceId),
                        FOREIGN KEY(folderId) REFERENCES sync_folders(folderId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_folder_index_states_deviceId ON folder_index_states(deviceId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS file_blocks (
                        folderId TEXT NOT NULL, fileId TEXT NOT NULL, contentSha256 TEXT NOT NULL,
                        blockIndex INTEGER NOT NULL, offsetBytes INTEGER NOT NULL, sizeBytes INTEGER NOT NULL,
                        blockSha256 TEXT NOT NULL,
                        PRIMARY KEY(folderId, fileId, contentSha256, blockIndex),
                        FOREIGN KEY(folderId) REFERENCES sync_folders(folderId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_blocks_contentSha256 ON file_blocks(contentSha256)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS partial_transfers (
                        folderId TEXT NOT NULL, fileId TEXT NOT NULL, contentSha256 TEXT NOT NULL,
                        temporaryPath TEXT NOT NULL, totalSizeBytes INTEGER NOT NULL,
                        blockSizeBytes INTEGER NOT NULL, receivedBlocksBase64 TEXT NOT NULL,
                        updatedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(folderId, fileId, contentSha256),
                        FOREIGN KEY(folderId) REFERENCES sync_folders(folderId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sync_exception_events (
                        eventId TEXT NOT NULL, groupId TEXT NOT NULL, folderId TEXT NOT NULL,
                        relativePath TEXT NOT NULL, active INTEGER NOT NULL, signerDeviceId TEXT NOT NULL,
                        versionVectorJson TEXT NOT NULL, createdAtMillis INTEGER NOT NULL,
                        signatureBase64 TEXT NOT NULL, PRIMARY KEY(eventId)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_exception_events_groupId ON sync_exception_events(groupId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_exception_events_folderId ON sync_exception_events(folderId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folder_keys (
                        folderId TEXT NOT NULL, keyId TEXT NOT NULL, wrappedKeyBase64 TEXT NOT NULL,
                        nonceBase64 TEXT NOT NULL, createdAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(folderId),
                        FOREIGN KEY(folderId) REFERENCES sync_folders(folderId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        messageId TEXT NOT NULL,
                        groupId TEXT NOT NULL,
                        authorDeviceId TEXT NOT NULL,
                        body TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        signatureBase64 TEXT NOT NULL,
                        PRIMARY KEY(messageId),
                        FOREIGN KEY(groupId) REFERENCES mesh_groups(groupId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_groupId ON chat_messages(groupId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_authorDeviceId ON chat_messages(authorDeviceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_createdAtMillis ON chat_messages(createdAtMillis)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE remote_file_versions ADD COLUMN originDeviceId TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conflicts ADD COLUMN renamedRelativePath TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity_events ADD COLUMN action TEXT NOT NULL DEFAULT 'INFO'")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN folderId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN relativePath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN sourceDeviceId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN sizeBytes INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN modifiedAtMillis INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN contentSha256 TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN recoveryPath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN recoverableUntilMillis INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN recoveredAtMillis INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_events_folderId ON activity_events(folderId)")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_activity_events_recoverableUntilMillis ON activity_events(recoverableUntilMillis)",
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attachmentFileName TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attachmentMediaType TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attachmentSizeBytes INTEGER")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attachmentSha256 TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attachmentExpiresAtMillis INTEGER")
            }
        }
    }
}
