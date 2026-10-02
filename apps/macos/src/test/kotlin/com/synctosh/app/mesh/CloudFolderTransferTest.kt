package com.synctosh.app.mesh

import com.syncdroid.shared.cloud.*
import com.syncdroid.shared.sync.validateFolderIndexUpdate
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CloudFolderTransferTest {
    @Test fun localDeletionKeepsPeersCopiesAndCanBeUndone() = runBlocking {
        Fixture().use { f ->
            val id = f.folders.first().folderId
            val a = f.rootsA.first().resolve("save.dat")
            val b = f.rootsB.first().resolve("save.dat")
            Files.writeString(a, "keep elsewhere")
            f.runA(id); f.runB(id)
            val engine = FileSyncEngine(f.sa, f.a, f.sa.profile()!!)
            assertNotNull(engine.managedFiles(id).single().lastSyncedAtMillis)
            engine.deleteFromThisDevice(id, "save.dat")
            assertFalse(Files.exists(a))
            assertFalse(f.sa.fileVersion(id, "save.dat")!!.deleted)
            f.runA(id); f.runB(id); f.runA(id)
            assertFalse(Files.exists(a))
            assertEquals("keep elsewhere", Files.readString(b))
            assertFalse(engine.managedFiles(id).single().onThisDevice)
            assertTrue(engine.buildFullUpdate(id)!!.files.isEmpty())
            engine.allowFileSyncAgain(id, "save.dat")
            f.runA(id)
            assertEquals("keep elsewhere", Files.readString(a))
            Files.writeString(a, "unsynced edit")
            assertNull(engine.managedFiles(id).single().lastSyncedAtMillis)
        }
    }

    @Test fun removingTheNewestFileFromThisDeviceKeepsTheIndexValid() = runBlocking {
        Fixture().use { f ->
            val id = f.folders.first().folderId
            Files.writeString(f.rootsA.first().resolve("older.dat"), "older")
            f.runA(id); f.runB(id)
            Files.writeString(f.rootsA.first().resolve("newer.dat"), "newer")
            f.runA(id); f.runB(id)
            val engine = FileSyncEngine(f.sa, f.a, f.sa.profile()!!)
            engine.deleteFromThisDevice(id, "newer.dat")

            validateFolderIndexUpdate(engine.buildFullUpdate(id)!!)
            engine.buildUpdatesForPeer(emptyList()).forEach(::validateFolderIndexUpdate)
            f.runA(id); f.runB(id)
            assertEquals("newer", Files.readString(f.rootsB.first().resolve("newer.dat")))
        }
    }

    @Test fun recoveringDoesNotReplaceAFileCreatedSinceTheLastScan() = runBlocking {
        Fixture().use { f ->
            val id = f.folders.first().folderId
            val root = f.rootsA.first()
            val file = root.resolve("notes.txt")
            Files.writeString(file, "old")
            FileSyncEngine(f.sa, f.a, f.sa.profile()!!).scanConfiguredFolders()
            val history = FileHistoryRepository(f.sa, f.a.deviceId)
            history.deleteWithRecovery(root, f.sa.fileVersion(id, "notes.txt")!!, f.a.deviceId)
            Files.writeString(file, "new and not yet scanned")

            val deleted = f.sa.fileHistory().first { it.action == FileHistoryAction.DELETED }
            assertFailsWith<IllegalStateException> { history.recover(deleted.eventId, f.sa.profile()!!) }
            assertEquals("new and not yet scanned", Files.readString(file))
        }
    }

    @Test fun temporaryAndFinderFilesAreNotSynced() = runBlocking {
        Fixture().use { f ->
            val id = f.folders.first().folderId
            val rootA = f.rootsA.first()
            val rootB = f.rootsB.first()
            Files.writeString(rootA.resolve(".DS_Store"), "finder view settings")
            Files.writeString(rootA.resolve(".synctosh-${java.util.UUID.randomUUID()}.part"), "left by a crash")
            Files.writeString(rootA.resolve("real.txt"), "real")
            f.runA(id); f.runB(id)

            assertEquals("real", Files.readString(rootB.resolve("real.txt")))
            assertFalse(Files.exists(rootB.resolve(".DS_Store")))
            assertNull(f.sa.fileVersion(id, ".DS_Store"))
        }
    }

    @Test fun staleTransfersAreCleanedUp() {
        Fixture().use { f ->
            val directory = Files.createDirectories(f.rootsA.first().parent.resolve("transfers"))
            val stale = Files.writeString(directory.resolve("stale.part"), "partial")
            val fresh = Files.writeString(directory.resolve("fresh.part"), "partial")
            Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(1_000))
            f.sa.upsertPartialTransfer(PartialTransfer("folder", "file", "a".repeat(64), stale.toString(), 7, 7, "", 1_000))

            cleanupStaleTransfers(f.sa, directory)

            assertFalse(Files.exists(stale))
            assertTrue(Files.exists(fresh))
            assertNull(f.sa.partialTransfer("folder", "file", "a".repeat(64)))
        }
    }

    @Test fun capitalizationOnlyRenameReachesTheOtherDevice() = runBlocking {
        Fixture().use { f ->
            val id = f.folders.first().folderId
            Files.writeString(f.rootsA.first().resolve("notes.txt"), "notes")
            f.runA(id); f.runB(id)
            val onB = f.rootsB.first().resolve("notes.txt")
            Files.move(onB, onB.resolveSibling("rename-step"))
            Files.move(onB.resolveSibling("rename-step"), onB.resolveSibling("Notes.txt"))

            repeat(3) { f.runB(id); f.runA(id) }

            val names = Files.list(f.rootsA.first()).use { paths -> paths.map { it.fileName.toString() }.toList() }
            assertEquals(listOf("Notes.txt"), names.filter { it.equals("notes.txt", ignoreCase = true) })
            assertEquals("notes", Files.readString(f.rootsA.first().resolve("Notes.txt")))
            assertTrue(f.sa.unresolvedConflicts().isEmpty())
        }
    }

    @Test fun folderSpelledDifferentlyOnACaseInsensitiveDiskBecomesAConflict() = runBlocking {
        Fixture().use { f ->
            val id = f.folders.first().folderId
            Files.createDirectories(f.rootsA.first().resolve("photos"))
            Files.writeString(f.rootsA.first().resolve("photos/a.jpg"), "a")
            Files.writeString(f.rootsA.first().resolve("other.txt"), "other")
            Files.createDirectories(f.rootsB.first().resolve("Photos"))
            Files.writeString(f.rootsB.first().resolve("Photos/b.jpg"), "b")
            val caseInsensitive = Files.isDirectory(f.rootsB.first().resolve("PHOTOS"))

            f.runB(id); f.runA(id); f.runB(id)

            assertEquals("other", Files.readString(f.rootsB.first().resolve("other.txt")))
            assertEquals(caseInsensitive, f.sb.unresolvedConflicts().any { it.relativePath == "photos/a.jpg" })
            assertEquals(caseInsensitive, f.sb.fileVersion(id, "photos/a.jpg") == null)
        }
    }

    @Test fun permanentDeletionRemovesRecoveryCopiesOnBothDevices() = runBlocking {
        Fixture().use { f ->
            val id = f.folders.first().folderId
            val a = f.rootsA.first().resolve("save.dat")
            val b = f.rootsB.first().resolve("save.dat")
            Files.writeString(a, "base")
            f.runA(id); f.runB(id)
            FileHistoryRepository(f.sa, f.a.deviceId).deleteWithRecovery(f.rootsA.first(), f.sa.fileVersion(id, "save.dat")!!, f.a.deviceId)
            FileHistoryRepository(f.sb, f.b.deviceId).deleteWithRecovery(f.rootsB.first(), f.sb.fileVersion(id, "save.dat")!!, f.b.deviceId)
            Files.writeString(a, "base"); Files.writeString(b, "base")
            val backups = (f.sa.recoveriesForFile(id, "save.dat") + f.sb.recoveriesForFile(id, "save.dat")).map { Path.of(it.recoveryPath!!) }
            assertEquals(2, backups.size)
            FileSyncEngine(f.sa, f.a, f.sa.profile()!!).deleteFromAllDevices(id, listOf("save.dat"), permanent = true)
            assertTrue(f.sa.fileVersion(id, "save.dat")!!.purgeRecovery)
            f.runA(id); f.runB(id)
            assertFalse(Files.exists(b))
            assertTrue(f.sb.fileVersion(id, "save.dat")!!.purgeRecovery)
            assertTrue(backups.none(Files::exists))
            assertTrue(f.sa.recoveriesForFile(id, "save.dat").isEmpty())
            assertTrue(f.sb.recoveriesForFile(id, "save.dat").isEmpty())
            val sequence = f.sb.folderIndexState(id, f.b.deviceId)!!.maxSequence
            f.runB(id)
            assertEquals(sequence, f.sb.folderIndexState(id, f.b.deviceId)!!.maxSequence)
        }
    }

    @Test fun explicitDeletionReachesOfflinePeerAndCanBeRecovered() = runBlocking {
        Fixture().use { f ->
            val id = f.folders.first().folderId
            val a = f.rootsA.first().resolve("save.dat")
            val b = f.rootsB.first().resolve("save.dat")
            Files.writeString(a, "recover me")
            f.runA(id); f.runB(id)
            val engine = FileSyncEngine(f.sa, f.a, f.sa.profile()!!)
            engine.deleteFromAllDevices(id, listOf("save.dat"))
            assertFalse(Files.exists(a))
            engine.deleteFromAllDevices(id, listOf("save.dat")) // Retrying a partial selection is safe.
            assertTrue(f.sa.fileVersion(id, "save.dat")!!.deleted)
            assertTrue(Files.exists(b), "Offline device retains its copy until it syncs")
            engine.scanConfiguredFolders()
            assertTrue(f.sa.fileVersion(id, "save.dat")!!.deleted)
            f.runA(id); f.runB(id)
            assertFalse(Files.exists(b))
            val recovery = f.sa.fileHistory().first { it.action == FileHistoryAction.DELETED }
            FileHistoryRepository(f.sa, f.a.deviceId).recover(recovery.eventId, f.sa.profile()!!)
            f.runA(id); f.runB(id)
            assertEquals("recover me", Files.readString(b))
        }
    }

    @Test fun explicitDeletionPreservesAnIndependentlyEditedRemoteCopy() = runBlocking {
        Fixture().use { f ->
            val id = f.folders.first().folderId
            val a = f.rootsA.first().resolve("save.dat")
            val b = f.rootsB.first().resolve("save.dat")
            Files.writeString(a, "base")
            f.runA(id); f.runB(id)
            Files.writeString(b, "offline edit")
            FileSyncEngine(f.sa, f.a, f.sa.profile()!!).deleteFromAllDevices(id, listOf("save.dat"))
            f.runA(id); f.runB(id)
            assertEquals("offline edit", Files.readString(b))
            assertEquals(1, f.sb.unresolvedConflicts().size)
        }
    }

    @Test fun devicesExchangeFilesAndPreserveConflictingVersionsThroughCloudOnly() = runBlocking {
        Fixture().use { f ->
            val id = f.folders.first().folderId
            val a = f.rootsA.first().resolve("save.dat")
            val b = f.rootsB.first().resolve("save.dat")
            Files.writeString(a, "initial")
            f.runA(id); f.runB(id)
            assertEquals("initial", Files.readString(b))
            Files.writeString(b, "remote edit")
            f.runB(id); f.runA(id)
            assertEquals("remote edit", Files.readString(a))
            Files.writeString(a, "A conflict")
            Files.writeString(b, "B conflict")
            f.runA(id); f.runB(id)
            assertEquals(1, f.sb.unresolvedConflicts().size)
            assertEquals("B conflict", Files.readString(b))
            f.runA(id)
            assertEquals("A conflict", Files.readString(a))
            assertEquals(1, f.sa.unresolvedConflicts().size)
        }
    }

    @Test fun pendingDeletionInAnotherFolderCannotDeleteCurrentFolder() = runBlocking {
        Fixture().use { f ->
            f.rootsA.forEach { Files.writeString(it.resolve("save.dat"), "initial") }
            f.folders.forEach { f.runA(it.folderId); f.runB(it.folderId) }
            Files.delete(f.rootsB[1].resolve("save.dat"))
            f.runB(f.folders[1].folderId)
            val bEngine = FileSyncEngine(f.sb, f.b, f.sb.profile()!!)
            FileSyncEngine(f.sa, f.a, f.sa.profile()!!).receiveIndexes(f.b.deviceId,
                listOf(bEngine.buildFullUpdate(f.folders[1].folderId)!!))
            f.runA(f.folders[0].folderId)
            assertEquals("initial", Files.readString(f.rootsA[0].resolve("save.dat")))
            assertTrue(Files.exists(f.rootsA[1].resolve("save.dat")))
            f.runA(f.folders[1].folderId)
            assertFalse(Files.exists(f.rootsA[1].resolve("save.dat")))
        }
    }

    @Test fun convergedKeysPreserveLegacyCloudFilesAcrossRestart() = runBlocking {
        Fixture(shareKeys = false).use { f ->
            val folder = f.folders.first()
            val ka = DesktopFolderKeyStore(f.sa, f.a)
            val kb = DesktopFolderKeyStore(f.sb, f.b)
            val oldA = ka.getOrCreate(folder.folderId)
            val oldB = kb.getOrCreate(folder.folderId)
            Files.writeString(f.rootsB[0].resolve("save.dat"), "legacy")
            val engine = FileSyncEngine(f.sb, f.b, f.sb.profile()!!)
            engine.scanConfiguredFolders()
            val index = engine.buildFullUpdate(folder.folderId)!!
            val root = f.remote.ensureFolder(f.remote.ensureFolder("root", "SyncDroid"), folder.displayName)
            val encrypted = f.dir.resolve("legacy.sdenc")
            val file = index.files.single()
            CloudEncryptedObjects.encryptFile(oldB, file.fileId, file.contentSha256, f.rootsB[0].resolve("save.dat"), encrypted)
            f.remote.upload(root, CloudEncryptedObjects.fileName(oldB, file.fileId, file.contentSha256), encrypted)
            Files.write(encrypted, CloudEncryptedObjects.encryptManifest(oldB,
                CloudFolderManifest(folder.folderId, folder.displayName, f.b.deviceId, System.currentTimeMillis(), index)))
            f.remote.upload(root, CloudEncryptedObjects.manifestName(oldB, f.b.deviceId), encrypted)
            ka.import(oldB); kb.import(oldA)
            assertEquals(ka.existing(folder.folderId)!!.keyId, kb.existing(folder.folderId)!!.keyId)
            assertEquals(2, DesktopFolderKeyStore(f.sa, f.a).all(folder.folderId).size)
            f.runA(folder.folderId)
            assertEquals("legacy", Files.readString(f.rootsA[0].resolve("save.dat")))
        }
    }

    @Test fun cleanupStartsGracePeriodAndPreservesOtherPublisherObjects() = runBlocking {
        Fixture().use { f ->
            val folder = f.folders.first()
            val key = DesktopFolderKeyStore(f.sa, f.a).getOrCreate(folder.folderId)
            Files.writeString(f.rootsA[0].resolve("save.dat"), "keep")
            f.runA(folder.folderId)
            val parent = f.remote.ensureFolder(f.remote.ensureFolder("root", "SyncDroid"), folder.displayName)
            val old = System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000
            val own = CloudEncryptedObjects.publisherFileName(key, f.a.deviceId, "old", "a".repeat(64))
            val other = CloudEncryptedObjects.publisherFileName(key, f.b.deviceId, "old", "a".repeat(64))
            val legacy = CloudEncryptedObjects.fileName(key, "old", "a".repeat(64))
            for (name in listOf(own, other, legacy)) f.remote.put(parent, name, byteArrayOf(1), old)
            f.remote.entries.values.filter { it.item.name != own }.forEach { it.item = it.item.copy(modifiedAtMillis = old) }
            f.runA(folder.folderId)
            assertTrue(f.remote.trashed.isEmpty(), "Old upload dates must not bypass the retention grace period")
            assertTrue(f.remote.entries.containsKey("$parent/$other"))
            assertTrue(f.remote.entries.containsKey("$parent/$legacy"))
        }
    }

    private class Fixture(shareKeys: Boolean = true) : AutoCloseable {
        val dir = Files.createTempDirectory("cloud-sync-test-")
        val a = MacDeviceIdentity("A", dir.resolve("a.p12"), legacyKeyStoreFactory = null)
        val b = MacDeviceIdentity("B", dir.resolve("b.p12"), legacyKeyStoreFactory = null)
        val sa = MeshStore(dir.resolve("a.db"))
        val sb = MeshStore(dir.resolve("b.db"))
        val remote = MemoryCloud()
        val folders: List<FolderAnnouncement>
        val rootsA: List<Path>
        val rootsB: List<Path>
        init {
            val profile = sa.createMesh("Test", "A", a)
            val parents = sa.membershipEvents(profile.groupId)
            sa.applyMembership(profile.groupName, MembershipEvent.createAddDevice(profile.groupId, "B", b.publicKey, a,
                parents.map { it.eventId }, parents.fold(VersionVector()) { v, e -> v.merge(e.version) }.increment(a.deviceId)))
            folders = listOf("Saves", "Photos").map { name ->
                val unsigned = FolderAnnouncement("", profile.groupId, java.util.UUID.randomUUID().toString(), name,
                    emptyList(), emptyList(), a.deviceId, VersionVector().increment(a.deviceId), System.currentTimeMillis(), "")
                val payload = unsigned.canonicalPayload()
                unsigned.copy(eventId = eventIdFor(payload), signatureBase64 = java.util.Base64.getEncoder().encodeToString(a.sign(payload)))
            }
            sa.importBundle(MeshStateBundle(profile.groupName, sa.membershipEvents(profile.groupId), folders))
            sb.importBundle(sa.exportBundle(), requiredLocalDeviceId = b.deviceId)
            rootsA = folders.mapIndexed { i, folder -> Files.createDirectory(dir.resolve("a$i")).also { sa.configureFolder(folder.folderId, a.deviceId, it) } }
            rootsB = folders.mapIndexed { i, folder -> Files.createDirectory(dir.resolve("b$i")).also { sb.configureFolder(folder.folderId, b.deviceId, it) } }
            if (shareKeys) folders.forEach { DesktopFolderKeyStore(sb, b).import(DesktopFolderKeyStore(sa, a).getOrCreate(it.folderId)) }
        }
        suspend fun runA(folder: String) = DesktopCloudFolderTransfer(sa, a, remote).run(CloudProvider.GOOGLE_DRIVE, folder)
        suspend fun runB(folder: String) = DesktopCloudFolderTransfer(sb, b, remote).run(CloudProvider.GOOGLE_DRIVE, folder)
        override fun close() { sa.close(); sb.close(); dir.toFile().deleteRecursively() }
    }

    private class MemoryCloud : CloudRemoteStore {
        override val rootId = "root"
        data class Entry(val parent: String, var item: CloudRemoteItem, val bytes: ByteArray)
        val entries = linkedMapOf<String, Entry>()
        val trashed = mutableListOf<String>()
        override suspend fun ensureFolder(parentId: String, name: String): String = "$parentId/$name"
        override suspend fun list(parentId: String) = entries.values.filter { it.parent == parentId }.map { it.item }
        override suspend fun upload(parentId: String, name: String, source: Path) = put(parentId, name, Files.readAllBytes(source))
        fun put(parentId: String, name: String, bytes: ByteArray, modified: Long = System.currentTimeMillis()): CloudRemoteItem {
            val item = CloudRemoteItem("$parentId/$name", name, bytes.size.toLong(), false, modified)
            entries[item.id] = Entry(parentId, item, bytes)
            return item
        }
        override suspend fun download(itemId: String, destination: Path) { Files.write(destination, entries.getValue(itemId).bytes) }
        override suspend fun trash(itemId: String) { entries.remove(itemId); trashed += itemId }
    }
}
