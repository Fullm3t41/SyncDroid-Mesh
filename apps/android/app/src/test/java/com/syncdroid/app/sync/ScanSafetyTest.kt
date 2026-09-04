package com.syncdroid.app.sync

import com.syncdroid.app.storage.SyncFilterRules
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Test

class ScanSafetyTest {
    @Test fun inaccessibleSubdirectoryAbortsScan() {
        val root = Files.createTempDirectory("review-scanner-")
        val child = Files.createDirectory(root.resolve("saves"))
        Files.write(child.resolve("slot.dat"), "important saved data".toByteArray())
        val scanner = DirectFolderScanner()
        assertEquals(setOf("saves/slot.dat"), scanner.listRelativeFilePaths(root.toFile()))
        org.junit.Assume.assumeTrue(Files.getFileStore(child).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(child, emptySet())
        try {
            org.junit.Assume.assumeTrue(child.toFile().listFiles() == null)
            assertThrows(java.io.IOException::class.java) { scanner.listRelativeFilePaths(root.toFile()) }
            assertThrows(java.io.IOException::class.java) { scanner.scan(root.toFile(), SyncFilterRules()) }
        } finally { Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("rwx------")) }
    }
}
