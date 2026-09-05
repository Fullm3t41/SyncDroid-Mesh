package com.synctosh.app.mesh

import com.syncdroid.shared.cloud.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CloudFolderTransferTest {
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
