package com.syncdeck.app.mesh

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class FileContentSyncTest {
    @Test
    fun wholeFilesUpdatesAndDeletionsSyncBetweenConfiguredPeers() = runBlocking {
        val directory = Files.createTempDirectory("syncdeck-content-session")
        val firstIdentity = memoryIdentity("content-first")
        val secondIdentity = memoryIdentity("content-second")
        val firstFolder = Files.createDirectory(directory.resolve("first-folder"))
        val secondFolder = Files.createDirectory(directory.resolve("second-folder"))

        MeshStore(directory.resolve("first.db")).use { firstStore ->
            MeshStore(directory.resolve("second.db")).use { secondStore ->
                val profile = firstStore.createMesh("Content mesh", "First Linux device", firstIdentity)
                val parents = firstStore.membershipEvents(profile.groupId)
                firstStore.applyMembership(
                    profile.groupName,
                    MembershipEvent.createAddDevice(
                        profile.groupId,
                        "Second Linux device",
                        secondIdentity.publicKey,
                        firstIdentity,
                        parents.map { it.eventId },
                        parents.fold(VersionVector()) { vector, event -> vector.merge(event.version) }
                            .increment(firstIdentity.deviceId),
                    ),
                )
                val folder = signedFolder(profile.groupId, firstIdentity)
                firstStore.importBundle(
                    MeshStateBundle(profile.groupName, firstStore.membershipEvents(profile.groupId), listOf(folder)),
                )
                val secondProfile = secondStore.importBundle(
                    MeshWireCodec.decode(MeshWireCodec.encode(firstStore.exportBundle())),
                    requiredLocalDeviceId = secondIdentity.deviceId,
                )
                firstStore.configureFolder(folder.folderId, firstIdentity.deviceId, firstFolder)
                secondStore.configureFolder(folder.folderId, secondIdentity.deviceId, secondFolder)
                firstStore.recordTlsKey(profile.groupId, secondIdentity.deviceId, secondIdentity.publicKey.encoded)
                secondStore.recordTlsKey(profile.groupId, firstIdentity.deviceId, firstIdentity.publicKey.encoded)

                val firstFile = firstFolder.resolve("nested/slot.sav")
                Files.createDirectories(firstFile.parent)
                Files.write(firstFile, byteArrayOf(1, 2, 3, 4))
                val largeContent = ByteArray(2 * 1024 * 1024 + 73) { index -> (index * 31).toByte() }
                Files.write(firstFolder.resolve("large.sav"), largeContent)
                syncOnce(firstStore, firstIdentity, profile, secondStore, secondIdentity, secondProfile)
                assertContentEquals(byteArrayOf(1, 2, 3, 4), Files.readAllBytes(secondFolder.resolve("nested/slot.sav")))
                assertContentEquals(largeContent, Files.readAllBytes(secondFolder.resolve("large.sav")))
                for (path in listOf("nested/slot.sav", "large.sav")) {
                    assertTrue(firstStore.lastSyncedAt(firstStore.fileVersion(folder.folderId, path)!!) != null)
                    assertTrue(secondStore.lastSyncedAt(secondStore.fileVersion(folder.folderId, path)!!) != null)
                }
                assertTrue(FileSyncEngine(secondStore, secondIdentity, secondProfile).managedFiles(folder.folderId)
                    .all { it.sourceDeviceName == "First Linux device" })


                Files.write(secondFolder.resolve("nested/slot.sav"), byteArrayOf(9, 8, 7, 6, 5))
                syncOnce(firstStore, firstIdentity, profile, secondStore, secondIdentity, secondProfile)
                assertContentEquals(byteArrayOf(9, 8, 7, 6, 5), Files.readAllBytes(firstFile))

                Files.delete(firstFile)
                syncOnce(firstStore, firstIdentity, profile, secondStore, secondIdentity, secondProfile)
                assertFalse(Files.exists(secondFolder.resolve("nested/slot.sav")))
                assertTrue(firstStore.unresolvedConflicts().isEmpty())
                assertTrue(secondStore.unresolvedConflicts().isEmpty())

                val deleted = secondStore.fileHistory().first { it.action == FileHistoryAction.DELETED }
                assertTrue(deleted.recoveryPath != null)
                FileHistoryRepository(secondStore, secondIdentity.deviceId).recover(deleted.eventId, secondProfile)
                FileSyncEngine(secondStore, secondIdentity, secondProfile).scanConfiguredFolders(recordHistory = false)
                syncOnce(firstStore, firstIdentity, profile, secondStore, secondIdentity, secondProfile)
                assertContentEquals(byteArrayOf(9, 8, 7, 6, 5), Files.readAllBytes(firstFile))
                assertTrue(secondStore.fileHistory().any { it.action == FileHistoryAction.RECOVERED })

                // Exercise both whole-file and resumable downloads after the receiving peer scanned.
                for (relativePath in listOf("nested/slot.sav", "large.sav")) {
                    val source = firstFolder.resolve(relativePath)
                    val target = secondFolder.resolve(relativePath)
                    val remoteEdit = if (relativePath == "large.sav") ByteArray(2 * 1024 * 1024) { 73 } else byteArrayOf(73)
                    Files.write(source, remoteEdit)
                    val localEdit = "edited while the remote version was downloading".toByteArray()
                    assertFailsWith<IllegalStateException> {
                        syncOnce(firstStore, firstIdentity, profile, secondStore, secondIdentity, secondProfile,
                            beforeSecondDownload = { Files.write(target, localEdit) })
                    }
                    assertContentEquals(localEdit, Files.readAllBytes(target))
                    val receiver = FileSyncEngine(secondStore, secondIdentity, secondProfile)
                    receiver.scanConfiguredFolders()
                    val sender = FileSyncEngine(firstStore, firstIdentity, profile)
                    val plans = receiver.receiveIndexes(firstIdentity.deviceId, listOf(requireNotNull(sender.buildFullUpdate(folder.folderId))))
                    assertEquals(FileSyncAction.Conflict, plans.first { it.relativePath == relativePath }.action)
                }
            }
        }
    }

    @Test
    fun partialBlocksResumeAfterStoreIsReopened() {
        val directory = Files.createTempDirectory("syncdeck-resume")
        val database = directory.resolve("mesh.db")
        val source = directory.resolve("source.sav")
        val destination = Files.createDirectory(directory.resolve("destination"))
        val transfers = Files.createDirectory(directory.resolve("transfers"))
        val content = ByteArray(700 * 1024 + 19) { index -> (index * 17 + 3).toByte() }
        Files.write(source, content)
        val version = FileVersion(
            "folder", "restored.sav", "file", content.size.toLong(), 1_700_000_000_000,
            Files.newInputStream(source).use(::sha256Hex), null, false,
            VersionVector(mapOf("sender" to 1)), "sender", 1,
        )
        val manifest = BlockManifestBuilder.build(version, source)
        val midpoint = manifest.blocks.size / 2

        MeshStore(database).use { store ->
            val receiver = ResumableBlockReceiver(store, transfers, AtomicFileApplier(destination))
            manifest.blocks.take(midpoint).forEach { block ->
                receiver.acceptBlock(manifest, block.index, blockBytes(source, block))
            }
            assertEquals(manifest.blocks.drop(midpoint).map(FileBlock::index), receiver.missingBlocks(manifest))
        }

        MeshStore(database).use { reopened ->
            val receiver = ResumableBlockReceiver(reopened, transfers, AtomicFileApplier(destination))
            assertEquals(manifest.blocks.drop(midpoint).map(FileBlock::index), receiver.missingBlocks(manifest))
            manifest.blocks.drop(midpoint).forEach { block ->
                receiver.acceptBlock(manifest, block.index, blockBytes(source, block))
            }
        }
        assertContentEquals(content, Files.readAllBytes(destination.resolve("restored.sav")))
    }

    private suspend fun syncOnce(
        firstStore: MeshStore,
        firstIdentity: LinuxDeviceIdentity,
        firstProfile: MeshProfile,
        secondStore: MeshStore,
        secondIdentity: LinuxDeviceIdentity,
        secondProfile: MeshProfile,
        beforeSecondDownload: () -> Unit = {},
    ) {
        val serverDone = CompletableDeferred<Unit>()
        val server = MeshPeerServer(DeviceTlsContext(firstIdentity, allowUnknownPeer = true)) { connection ->
            runCatching {
                val remote = StablePeerAuthenticator(firstStore, firstIdentity, firstProfile.groupId).authenticate(connection)
                MeshFileSyncSession(firstStore, firstIdentity, firstProfile).run(connection, remote)
            }.onSuccess { serverDone.complete(Unit) }
                .onFailure(serverDone::completeExceptionally)
        }
        try {
            val port = server.start()
            MeshPeerClient(DeviceTlsContext(secondIdentity, allowUnknownPeer = true))
                .connect(InetAddress.getLoopbackAddress(), port)
                .use { connection ->
                    val remote = StablePeerAuthenticator(secondStore, secondIdentity, secondProfile.groupId).authenticate(connection)
                    MeshFileSyncSession(secondStore, secondIdentity, secondProfile,
                        onIncomingTransferPlanned = { if (it > 0) beforeSecondDownload() },
                    ).run(connection, remote)
                }
            withTimeout(15_000) { serverDone.await() }
        } finally {
            server.close()
        }
    }

    private fun signedFolder(groupId: String, signer: LinuxDeviceIdentity): FolderAnnouncement {
        val unsigned = FolderAnnouncement(
            eventId = "",
            groupId = groupId,
            folderId = "folder-content-test",
            displayName = "Game saves",
            includePatterns = listOf("*.sav"),
            excludePatterns = emptyList(),
            signerDeviceId = signer.deviceId,
            version = VersionVector().increment(signer.deviceId),
            createdAtMillis = System.currentTimeMillis(),
            signatureBase64 = "",
        )
        val payload = unsigned.canonicalPayload()
        return unsigned.copy(
            eventId = eventIdFor(payload),
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign(payload)),
        )
    }

    private fun memoryIdentity(alias: String): LinuxDeviceIdentity {
        val path: Path = Files.createTempDirectory("syncdeck-content-identity").resolve("identity.p12")
        return LinuxDeviceIdentity(alias, path)
    }

    private fun blockBytes(source: Path, block: FileBlock): ByteArray = java.io.RandomAccessFile(source.toFile(), "r").use {
        it.seek(block.offsetBytes)
        ByteArray(block.sizeBytes).also(it::readFully)
    }
}
