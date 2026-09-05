package com.syncdeck.app.mesh

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedSyncAdapterTest {
    @Test
    fun macModelsUseSharedFileDecision() {
        val local = FileVersion(
            "folder", "save.sav", "file", 3, 1, "old", null, false,
            VersionVector(mapOf("mac" to 1)), "mac", 1,
        )
        val remote = RemoteFileVersion(
            "folder", "phone", "save.sav", "file", 4, 2, "new", "old", "phone", false,
            VersionVector(mapOf("mac" to 2)), 2,
        )
        assertEquals(FileSyncAction.DownloadRemote, decideFileSync(local, remote).first)
    }
}
