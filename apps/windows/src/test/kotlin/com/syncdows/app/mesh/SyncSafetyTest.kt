package com.syncdows.app.mesh

import com.syncdroid.shared.sync.ExpectedFileContent
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.*

class SyncSafetyTest {
    @Test fun caseVariantIsRejectedBeforeAcceptingIndex() {
        val dir = Files.createTempDirectory("review-case-")
        val identity = WindowsDeviceIdentity("review", dir.resolve("identity.p12"), legacyKeyStoreFactory = null)
        MeshStore(dir.resolve("mesh.db")).use { store ->
            val profile = store.createMesh("Review", "Local", identity)
            val folder = FolderAnnouncement.create(profile.groupId, "Saves", emptyList(), emptyList(), identity)
            store.applyFolder(folder)
            val root = Files.createDirectory(dir.resolve("files"))
            store.configureFolder(folder.folderId, identity.deviceId, root)
            Files.writeString(root.resolve("SAVE.dat"), "local edit")
            val engine = FileSyncEngine(store, identity, profile)
            engine.scanConfiguredFolders()
            val update = requireNotNull(engine.buildFullUpdate(folder.folderId))
            val remote = update.copy(files = update.files.map { it.copy(relativePath = "save.dat", contentSha256 = "a".repeat(64), version = VersionVector(mapOf("other" to 1)), originDeviceId = "other") })
            assertFailsWith<IllegalArgumentException> { engine.receiveIndexes("other", listOf(remote)) }
            assertNull(store.folderIndexState(folder.folderId, "other"))
            assertEquals("local edit", Files.readString(root.resolve("SAVE.dat")))
        }
    }
    @Test fun editDuringApplyIsPreserved() {
        val root = Files.createTempDirectory("review-overwrite-")
        val target = root.resolve("save.dat")
        Files.writeString(target, "base")
        val incoming = "remote update".toByteArray()
        val stream = object : ByteArrayInputStream(incoming) {
            var edited = false
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!edited) { Files.writeString(target, "new local edit"); edited = true }
                return super.read(b, off, len)
            }
        }
        val expected = ExpectedFileContent(sha256Hex(ByteArrayInputStream("base".toByteArray())))
        assertFailsWith<IllegalStateException> {
            AtomicFileApplier(root, expected).apply("save.dat", stream, sha256Hex(ByteArrayInputStream(incoming)), 0)
        }
        assertEquals("new local edit", Files.readString(target))
        assertFailsWith<IllegalStateException> {
            AtomicFileApplier(root, ExpectedFileContent(null)).apply(
                "save.dat", ByteArrayInputStream(incoming), sha256Hex(ByteArrayInputStream(incoming)), 0,
            )
        }
        assertEquals("new local edit", Files.readString(target))
    }
}
