package com.syncdroid.app.sync

import com.syncdroid.shared.sync.ExpectedFileContent
import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncApplySafetyTest {
    @Test fun editAfterScanIsPreservedWhenRemoteDeletesFile() {
        val root = Files.createTempDirectory("sync-delete-safety").toFile()
        try {
            val target = root.resolve("save.dat").apply { writeText("base") }
            val expected = ExpectedFileContent(target.inputStream().use(FileHasher::sha256))
            target.writeText("new local edit")
            assertThrows(IllegalStateException::class.java) { AtomicFileApplier(root, expected).delete("save.dat") }
            assertEquals("new local edit", target.readText())
            val current = ExpectedFileContent(target.inputStream().use(FileHasher::sha256))
            AtomicFileApplier(root, current).delete("save.dat")
            org.junit.Assert.assertFalse(target.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun editDuringApplyIsPreserved() {
        val root = Files.createTempDirectory("sync-apply-safety").toFile()
        try {
            val target = root.resolve("save.dat").apply { writeText("base") }
            val expected = ExpectedFileContent(target.inputStream().use(FileHasher::sha256))
            val bytes = "remote".toByteArray()
            val stream = object : ByteArrayInputStream(bytes) {
                var edited = false
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!edited) { target.writeText("new local edit"); edited = true }
                    return super.read(b, off, len)
                }
            }
            assertThrows(IllegalStateException::class.java) {
                AtomicFileApplier(root, expected).apply("save.dat", stream, FileHasher.sha256(ByteArrayInputStream(bytes)))
            }
            assertEquals("new local edit", target.readText())
            assertThrows(IllegalStateException::class.java) {
                AtomicFileApplier(root, ExpectedFileContent(null)).apply(
                    "save.dat", ByteArrayInputStream(bytes), FileHasher.sha256(ByteArrayInputStream(bytes)),
                )
            }
            assertEquals("new local edit", target.readText())
        } finally { root.deleteRecursively() }
    }
}
