package com.syncdroid.shared.sync

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncIgnoredPathTest {
    @Test
    fun appTemporaryFilesAndFinderSettingsAreIgnored() {
        val id = UUID.randomUUID()
        listOf(
            ".DS_Store",
            "photos/.ds_store",
            "saves/.syncdroid-$id.tmp",
            ".syncdroid-backup-$id",
            "docs/.synctosh-$id.part",
            "docs\\.syncdows-$id.part",
        ).forEach { assertTrue(isSyncIgnoredPath(it), it) }
    }

    @Test
    fun ordinaryFilesWithSimilarNamesStillSync() {
        listOf(
            "DS_Store",
            "notes/.DS_Store.txt",
            ".syncdroid-notes.tmp",
            "report.part",
            "desktop.ini",
        ).forEach { assertFalse(isSyncIgnoredPath(it), it) }
    }
}
