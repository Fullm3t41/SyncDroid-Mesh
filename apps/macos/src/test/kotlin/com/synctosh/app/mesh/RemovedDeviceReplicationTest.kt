package com.synctosh.app.mesh

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemovedDeviceReplicationTest {
    private val creator = signer()
    private val removed = signer()
    private val groupId = "removed-device-mesh"
    private val creatorEvent = MembershipEvent.createAddDevice(
        groupId, "Creator", creator.publicKey, creator, emptyList(),
        VersionVector().increment(creator.deviceId), 1_000L,
    )
    private val addRemoved = MembershipEvent.createAddDevice(
        groupId, "Old phone", removed.publicKey, creator, listOf(creatorEvent.eventId),
        VersionVector(mapOf(creator.deviceId to 2)), 2_000L,
    )
    private val removeRemoved = MembershipEvent.createRemoveDevice(
        groupId, "Old phone", removed.publicKey, creator, listOf(addRemoved.eventId),
        VersionVector(mapOf(creator.deviceId to 3)), 5_000L,
    )

    @Test
    fun historyFromARemovedDeviceStillImports() {
        val chat = MeshChatMessage.create(groupId, "Sent before removal", removed, 3_000L)
        val folder = signedFolderAnnouncement("Photos", removed, 3_000L)
        val exception = signedException(folder.folderId, "a.txt", removed, 4_000L)
        val bundle = MeshStateBundle(
            "Home mesh",
            listOf(creatorEvent, addRemoved, removeRemoved),
            listOf(folder),
            listOf(exception),
            listOf(chat),
        )

        withStore { store ->
            store.importBundle(bundle, requiredLocalDeviceId = creator.deviceId)
            // A later exchange with the same history must not fail either.
            store.importBundle(MeshWireCodec.decode(MeshWireCodec.encode(bundle)))

            assertEquals(listOf(chat), store.chatMessages(groupId))
            assertEquals(listOf("Photos"), store.folders(groupId, creator.deviceId).map { it.displayName })
            assertTrue(store.activeSyncException(folder.folderId, "a.txt"))
            assertFalse(store.devices(groupId).single { it.deviceId == removed.deviceId }.trusted)
        }
    }

    @Test
    fun itemsSignedAfterRemovalAreSkippedWithoutFailingTheImport() {
        val lateChat = MeshChatMessage.create(groupId, "Sent after removal", removed, 6_000L)
        val lateAdd = MembershipEvent.createAddDevice(
            groupId, "Intruder", signer().publicKey, removed, listOf(removeRemoved.eventId),
            removeRemoved.version.increment(removed.deviceId), 6_000L,
        )
        val validChat = MeshChatMessage.create(groupId, "Still delivered", creator, 7_000L)

        withStore { store ->
            store.importBundle(
                MeshStateBundle(
                    "Home mesh",
                    listOf(creatorEvent, addRemoved, removeRemoved, lateAdd),
                    chatMessages = listOf(lateChat, validChat),
                ),
                requiredLocalDeviceId = creator.deviceId,
            )

            assertEquals(listOf(validChat), store.chatMessages(groupId))
            assertTrue(store.devices(groupId).none { it.displayName == "Intruder" })
        }
    }

    @Test
    fun lateArrivingEventsCannotRestoreARemovedDevice() {
        val otherRemoval = MembershipEvent.createRemoveDevice(
            groupId, "Old phone", removed.publicKey, creator, listOf(addRemoved.eventId),
            VersionVector(mapOf(creator.deviceId to 4)), 5_500L,
        )
        withStore { store ->
            store.importBundle(
                MeshStateBundle("Home mesh", listOf(creatorEvent, addRemoved, removeRemoved)),
                requiredLocalDeviceId = creator.deviceId,
            )
            // The addition is replayed after the removal, and a second device also removes the peer.
            store.importBundle(MeshStateBundle("Home mesh", listOf(addRemoved, otherRemoval, creatorEvent)))

            assertFalse(store.devices(groupId).single { it.deviceId == removed.deviceId }.trusted)
            assertEquals(4, store.membershipEvents(groupId).size)
        }
    }

    private fun withStore(block: (MeshStore) -> Unit) {
        val directory = Files.createTempDirectory("synctosh-removed-device-test")
        MeshStore(directory.resolve("mesh.db")).use(block)
    }

    private fun signedFolderAnnouncement(displayName: String, signer: DeviceSigner, createdAtMillis: Long): FolderAnnouncement {
        val unsigned = FolderAnnouncement(
            eventId = "",
            groupId = groupId,
            folderId = "folder-${System.nanoTime()}",
            displayName = displayName,
            includePatterns = emptyList(),
            excludePatterns = emptyList(),
            signerDeviceId = signer.deviceId,
            version = VersionVector().increment(signer.deviceId),
            createdAtMillis = createdAtMillis,
            signatureBase64 = "",
        )
        val payload = unsigned.canonicalPayload()
        return unsigned.copy(
            eventId = eventIdFor(payload),
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign(payload)),
        )
    }

    private fun signedException(folderId: String, path: String, signer: DeviceSigner, createdAtMillis: Long): SyncExceptionEvent {
        val unsigned = SyncExceptionEvent(
            eventId = "",
            groupId = groupId,
            folderId = folderId,
            relativePath = path,
            active = true,
            signerDeviceId = signer.deviceId,
            version = VersionVector().increment(signer.deviceId),
            createdAtMillis = createdAtMillis,
            signatureBase64 = "",
        )
        val payload = unsigned.canonicalPayload()
        return unsigned.copy(
            eventId = eventIdFor(payload),
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign(payload)),
        )
    }

    private fun signer(): DeviceSigner {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        return object : DeviceSigner {
            override val deviceId = deviceIdFor(pair.public)
            override val publicKey = pair.public
            override fun sign(payload: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private); update(payload); sign()
            }
        }
    }
}
