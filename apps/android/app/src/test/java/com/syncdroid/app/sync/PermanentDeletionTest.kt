package com.syncdroid.app.sync

import com.syncdroid.app.data.FileVersionEntity
import com.syncdroid.app.data.RemoteFileVersionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PermanentDeletionTest {
    private val remote = RemoteFileVersionEntity("folder", "sender", "save.dat", "file", 0, 100,
        "", "a".repeat(64), "sender", true, "{\"sender\":2}", 2, purgeRecovery = true)

    @Test fun absentAndAlreadyDeletedFilesStillPurgeRecoveryOnce() {
        assertEquals(FileSyncAction.DownloadRemote, decideFileSync(null, remote).first)
        val deleted = FileVersionEntity("folder", "save.dat", "file", 0, 100, "", "a".repeat(64), true,
            "{\"sender\":2}", "sender", 1)
        assertEquals(FileSyncAction.DownloadRemote, decideFileSync(deleted, remote).first)
        assertEquals(FileSyncAction.Nothing, decideFileSync(deleted.copy(purgeRecovery = true), remote).first)
        assertEquals(FileSyncAction.Nothing, decideFileSync(deleted.copy(versionVectorJson = "{\"sender\":3}"), remote).first)
    }

    @Test fun independentEditStillRequiresConflictReview() {
        val edited = FileVersionEntity("folder", "save.dat", "file", 3, 100, "b".repeat(64), "a".repeat(64), false,
            "{\"sender\":1,\"local\":1}", "local", 1)
        assertEquals(FileSyncAction.Conflict, decideFileSync(edited, remote).first)
    }
}
