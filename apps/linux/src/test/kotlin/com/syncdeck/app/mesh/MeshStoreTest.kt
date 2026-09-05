package com.syncdeck.app.mesh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MeshStoreTest {
    @Test
    fun createdMeshRoundTripsThroughAndroidBundleFormat() {
        val directory = Files.createTempDirectory("syncdeck-store-test")
        val signer = signer()
        MeshStore(directory.resolve("source.db")).use { source ->
            val profile = source.createMesh("Home mesh", "Mac", signer)
            val message = MeshChatMessage.create(profile.groupId, "Hello from Android 👋", signer, 300)
            assertTrue(source.applyChat(message))
            val encoded = MeshWireCodec.encode(source.exportBundle())
            MeshStore(directory.resolve("destination.db")).use { destination ->
                val imported = destination.importBundle(MeshWireCodec.decode(encoded))
                assertEquals(profile.groupId, imported.groupId)
                assertEquals("Home mesh", imported.groupName)
                assertEquals(listOf(signer.deviceId), destination.devices(profile.groupId).map { it.deviceId })
                assertEquals(listOf(message), destination.chatMessages(profile.groupId))
                assertTrue(!destination.applyChat(message))
            }
        }
    }

    @Test
    fun tamperedChatMessageIsRejected() {
        val directory = Files.createTempDirectory("syncdeck-chat-test")
        val signer = signer()
        MeshStore(directory.resolve("mesh.db")).use { store ->
            val profile = store.createMesh("Home mesh", "Mac", signer)
            val message = MeshChatMessage.create(profile.groupId, "Original", signer, 123)
            assertFailsWith<IllegalArgumentException> { store.applyChat(message.copy(body = "Changed")) }
            assertTrue(store.chatMessages(profile.groupId).isEmpty())
        }
    }

    @Test
    fun unsignedMembershipChangeIsRejected() {
        val directory = Files.createTempDirectory("syncdeck-store-test")
        val signer = signer()
        MeshStore(directory.resolve("mesh.db")).use { store ->
            val profile = store.createMesh("Home mesh", "Mac", signer)
            val valid = MembershipEvent.createAddDevice(
                profile.groupId, "Peer", signer().publicKey, signer,
                store.membershipEvents(profile.groupId).map { it.eventId },
                VersionVector().increment(signer.deviceId),
            )
            val tampered = valid.copy(subjectDisplayName = "Imposter")
            assertFailsWith<IllegalArgumentException> { store.applyMembership(profile.groupName, tampered) }
            assertTrue(store.devices(profile.groupId).none { it.displayName == "Imposter" })
        }
    }

    @Test
    fun importsLegacyAndroidCreatorThroughAJoinedOfferingDevice() {
        val directory = Files.createTempDirectory("syncdeck-android-compat-test")
        val creator = signer()
        val offeringAndroid = signer()
        val joiningMac = signer()
        val groupId = "legacy-android-mesh"
        val createdAt = 1_700_000_000_000L
        val creatorEvent = legacyCreatorEvent(groupId, "Android creator", creator, createdAt)
        val offeringEvent = MembershipEvent.createAddDevice(
            groupId,
            "Android offering code",
            offeringAndroid.publicKey,
            creator,
            listOf(creatorEvent.eventId),
            VersionVector(mapOf(creator.deviceId to 2)),
            createdAt + 1,
        )
        val macEvent = MembershipEvent.createAddDevice(
            groupId,
            "Mac",
            joiningMac.publicKey,
            offeringAndroid,
            listOf(creatorEvent.eventId, offeringEvent.eventId),
            VersionVector(mapOf(creator.deviceId to 2, offeringAndroid.deviceId to 1)),
            createdAt + 2,
        )
        val bundle = MeshStateBundle("Home mesh", listOf(creatorEvent, offeringEvent, macEvent))

        MeshStore(directory.resolve("mesh.db")).use { store ->
            val profile = store.importBundle(
                bundle,
                expectedOfferingIdentity = PairingIdentity.from(offeringAndroid, "Android offering code"),
                requiredLocalDeviceId = joiningMac.deviceId,
            )
            assertEquals(groupId, profile.groupId)
            assertEquals(
                setOf(creator.deviceId, offeringAndroid.deviceId, joiningMac.deviceId),
                store.devices(groupId).filter { it.trusted }.map { it.deviceId }.toSet(),
            )
        }
    }

    @Test
    fun rejectsPairingFromADeviceRemovedFromTheMesh() {
        val directory = Files.createTempDirectory("syncdeck-removed-peer-test")
        val creator = signer()
        val removedPeer = signer()
        val groupId = "removed-peer-mesh"
        val creatorEvent = MembershipEvent.createAddDevice(
            groupId, "Creator", creator.publicKey, creator, emptyList(),
            VersionVector().increment(creator.deviceId), 1_700_000_000_000L,
        )
        val addPeer = MembershipEvent.createAddDevice(
            groupId, "Old Android", removedPeer.publicKey, creator, listOf(creatorEvent.eventId),
            VersionVector(mapOf(creator.deviceId to 2)), 1_700_000_000_001L,
        )
        val removePeer = MembershipEvent(
            eventId = "", groupId = groupId, eventType = MembershipEventType.RemoveDevice,
            subjectDeviceId = removedPeer.deviceId, subjectDisplayName = "Old Android",
            subjectPublicKeyBase64 = Base64.getEncoder().encodeToString(removedPeer.publicKey.encoded),
            signerDeviceId = creator.deviceId, parentEventIds = listOf(addPeer.eventId),
            version = VersionVector(mapOf(creator.deviceId to 3)), createdAtMillis = 1_700_000_000_002L,
            signatureBase64 = "",
        ).signedBy(creator)

        MeshStore(directory.resolve("mesh.db")).use { store ->
            assertFailsWith<IllegalArgumentException> {
                store.importBundle(
                    MeshStateBundle("Home mesh", listOf(creatorEvent, addPeer, removePeer)),
                    expectedOfferingIdentity = PairingIdentity.from(removedPeer, "Old Android"),
                )
            }
            assertEquals(null, store.profile())
        }
    }

    @Test
    fun androidFolderAnnouncementArrivesPendingAndCanBeConfigured() {
        val directory = Files.createTempDirectory("syncdeck-folder-import-test")
        val signer = signer()
        val sourcePath = directory.resolve("source.db")
        val destinationPath = directory.resolve("destination.db")
        val localFolder = Files.createDirectory(directory.resolve("local-save-folder"))

        MeshStore(sourcePath).use { source ->
            val profile = source.createMesh("Home mesh", "Android", signer)
            val announcement = signedFolderAnnouncement(
                profile.groupId,
                "Game saves",
                listOf("*.sav"),
                listOf("cache/**"),
                signer,
            )
            source.importBundle(
                MeshStateBundle(
                    profile.groupName,
                    source.membershipEvents(profile.groupId),
                    listOf(announcement),
                ),
            )
            val encoded = MeshWireCodec.encode(source.exportBundle())

            MeshStore(destinationPath).use { destination ->
                destination.importBundle(MeshWireCodec.decode(encoded), requiredLocalDeviceId = signer.deviceId)
                val pending = destination.folders(profile.groupId, signer.deviceId).single()
                assertEquals("Game saves", pending.displayName)
                assertEquals(listOf("*.sav"), pending.includePatterns)
                assertEquals(listOf("cache/**"), pending.excludePatterns)
                assertEquals(LocalFolderBindingState.PENDING_CONFIGURATION, pending.bindingState)

                destination.configureFolder(pending.folderId, signer.deviceId, localFolder)
                val configured = destination.folders(profile.groupId, signer.deviceId).single()
                assertEquals(LocalFolderBindingState.CONFIGURED, configured.bindingState)
                assertEquals(localFolder.toAbsolutePath().toString(), configured.localPath)
            }
        }
    }

    @Test
    fun signedOverwriteOnlyExceptionsReplicateAndCanBeUndone() {
        val directory = Files.createTempDirectory("syncdeck-exception-test")
        val signer = signer()
        MeshStore(directory.resolve("source.db")).use { source ->
            val profile = source.createMesh("Home mesh", "Mac", signer)
            val folder = signedFolderAnnouncement(profile.groupId, "Game saves", listOf("*.sav"), emptyList(), signer)
            source.importBundle(MeshStateBundle(profile.groupName, source.membershipEvents(profile.groupId), listOf(folder)))
            val excluded = source.recordSyncException(folder.folderId, "save\\main.sav", true, signer, 500)
            assertTrue(excluded.hasValidEventId())
            assertTrue(source.activeSyncException(folder.folderId, "save/main.sav"))
            assertTrue(source.localActiveSyncException(folder.folderId, "save/main.sav", signer.deviceId))
            assertEquals(false, source.localActiveSyncException(folder.folderId, "save/main.sav", "other-device"))

            MeshStore(directory.resolve("destination.db")).use { destination ->
                destination.importBundle(
                    MeshWireCodec.decode(MeshWireCodec.encode(source.exportBundle())),
                    requiredLocalDeviceId = signer.deviceId,
                )
                assertTrue(destination.activeSyncException(folder.folderId, "save/main.sav"))
            }

            source.recordSyncException(folder.folderId, "save/main.sav", false, signer, 600)
            assertEquals(false, source.activeSyncException(folder.folderId, "save/main.sav"))
            assertEquals(false, source.localActiveSyncException(folder.folderId, "save/main.sav", signer.deviceId))
        }
    }

    private fun legacyCreatorEvent(
        groupId: String,
        displayName: String,
        signer: DeviceSigner,
        createdAtMillis: Long,
    ): MembershipEvent {
        val publicKey = Base64.getEncoder().encodeToString(signer.publicKey.encoded)
        val version = VersionVector().increment(signer.deviceId)
        val payload = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF("syncdroid-membership-v1")
                output.writeUTF(groupId)
                output.writeUTF(MembershipEventType.AddDevice.name)
                output.writeUTF(signer.deviceId)
                output.writeUTF(displayName)
                output.writeUTF(publicKey)
                output.writeUTF(signer.deviceId)
                output.writeLong(createdAtMillis)
                output.writeUTF(version.toJson())
                output.writeInt(0)
            }
            bytes.toByteArray()
        }
        return MembershipEvent(
            eventId = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(payload),
            ),
            groupId = groupId,
            eventType = MembershipEventType.AddDevice,
            subjectDeviceId = signer.deviceId,
            subjectDisplayName = displayName,
            subjectPublicKeyBase64 = publicKey,
            signerDeviceId = signer.deviceId,
            parentEventIds = emptyList(),
            version = version,
            createdAtMillis = createdAtMillis,
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign(payload)),
        )
    }

    private fun signedFolderAnnouncement(
        groupId: String,
        displayName: String,
        includePatterns: List<String>,
        excludePatterns: List<String>,
        signer: DeviceSigner,
    ): FolderAnnouncement {
        val unsigned = FolderAnnouncement(
            eventId = "",
            groupId = groupId,
            folderId = "folder-${System.nanoTime()}",
            displayName = displayName,
            includePatterns = includePatterns,
            excludePatterns = excludePatterns,
            signerDeviceId = signer.deviceId,
            version = VersionVector().increment(signer.deviceId),
            createdAtMillis = 1_700_000_000_100L,
            signatureBase64 = "",
        )
        val payload = unsigned.canonicalPayload()
        return unsigned.copy(
            eventId = eventIdFor(payload),
            signatureBase64 = Base64.getEncoder().encodeToString(signer.sign(payload)),
        )
    }

    private fun MembershipEvent.signedBy(signer: DeviceSigner): MembershipEvent {
        val payload = canonicalPayload()
        val eventId = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(payload),
        )
        return copy(eventId = eventId, signatureBase64 = Base64.getEncoder().encodeToString(signer.sign(payload)))
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
