package com.syncdows.app.mesh

import com.syncdroid.shared.protocol.MESH_SESSION_BUSY_REASON
import com.syncdroid.shared.protocol.MeshSessionMessage
import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class StableSessionTest {
    @Test
    fun pairedPeersAuthenticateAndCompleteMetadataOnlySession() = runBlocking {
        val directory = Files.createTempDirectory("syncdows-session-test")
        val inviterIdentity = memoryIdentity("stable-inviter")
        val joinerIdentity = memoryIdentity("stable-joiner")
        MeshStore(directory.resolve("inviter.db")).use { inviterStore ->
            MeshStore(directory.resolve("joiner.db")).use { joinerStore ->
                val profile = inviterStore.createMesh("Home mesh", "Mac A", inviterIdentity)
                val parents = inviterStore.membershipEvents(profile.groupId)
                val version = parents.fold(VersionVector()) { merged, event -> merged.merge(event.version) }
                    .increment(inviterIdentity.deviceId)
                inviterStore.applyMembership(
                    profile.groupName,
                    MembershipEvent.createAddDevice(
                        profile.groupId,
                        "Mac B",
                        joinerIdentity.publicKey,
                        inviterIdentity,
                        parents.map { it.eventId },
                        version,
                    ),
                )
                val joinerProfile = joinerStore.importBundle(MeshWireCodec.decode(MeshWireCodec.encode(inviterStore.exportBundle())))
                val inviterMessage = MeshChatMessage.create(profile.groupId, "Message from the Mac", inviterIdentity, 100)
                val joinerMessage = MeshChatMessage.create(profile.groupId, "Message from Android", joinerIdentity, 200)
                assertTrue(inviterStore.applyChat(inviterMessage))
                assertTrue(joinerStore.applyChat(joinerMessage))
                inviterStore.recordTlsKey(profile.groupId, joinerIdentity.deviceId, joinerIdentity.publicKey.encoded)
                joinerStore.recordTlsKey(profile.groupId, inviterIdentity.deviceId, inviterIdentity.publicKey.encoded)

                val serverDone = CompletableDeferred<Unit>()
                val server = MeshPeerServer(DeviceTlsContext(inviterIdentity, allowUnknownPeer = true)) { connection ->
                    val remote = StablePeerAuthenticator(inviterStore, inviterIdentity, profile.groupId).authenticate(connection)
                    MetadataOnlyMeshSession(inviterStore, inviterIdentity, profile).run(connection, remote)
                    serverDone.complete(Unit)
                }
                try {
                    val port = server.start()
                    MeshPeerClient(DeviceTlsContext(joinerIdentity, allowUnknownPeer = true))
                        .connect(InetAddress.getLoopbackAddress(), port)
                        .use { connection ->
                            val remote = StablePeerAuthenticator(joinerStore, joinerIdentity, joinerProfile.groupId).authenticate(connection)
                            MetadataOnlyMeshSession(joinerStore, joinerIdentity, joinerProfile).run(connection, remote)
                        }
                    withTimeout(10_000) { serverDone.await() }
                    assertTrue(inviterStore.devices(profile.groupId).first { it.deviceId == joinerIdentity.deviceId }.lastSeenAtMillis != null)
                    assertTrue(joinerStore.devices(profile.groupId).first { it.deviceId == inviterIdentity.deviceId }.lastSeenAtMillis != null)
                    assertEquals(listOf(inviterMessage, joinerMessage), inviterStore.chatMessages(profile.groupId))
                    assertEquals(listOf(inviterMessage, joinerMessage), joinerStore.chatMessages(profile.groupId))
                } finally {
                    server.close()
                }
            }
        }
    }

    @Test
    fun busyReplyFromPeerIsReportedAsCollisionRatherThanFailure() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("syncdows-busy-session-test")
        val inviterIdentity = memoryIdentity("busy-inviter")
        val joinerIdentity = memoryIdentity("busy-joiner")
        MeshStore(directory.resolve("inviter.db")).use { inviterStore ->
            MeshStore(directory.resolve("joiner.db")).use { joinerStore ->
                val profile = inviterStore.createMesh("Home mesh", "Desktop A", inviterIdentity)
                val parents = inviterStore.membershipEvents(profile.groupId)
                inviterStore.applyMembership(
                    profile.groupName,
                    MembershipEvent.createAddDevice(
                        profile.groupId,
                        "Android",
                        joinerIdentity.publicKey,
                        inviterIdentity,
                        parents.map { it.eventId },
                        parents.fold(VersionVector()) { merged, event -> merged.merge(event.version) }
                            .increment(inviterIdentity.deviceId),
                    ),
                )
                val joinerProfile = joinerStore.importBundle(MeshWireCodec.decode(MeshWireCodec.encode(inviterStore.exportBundle())))

                // The inviter already has a session with the joiner, so it declines this duplicate one.
                val server = MeshPeerServer(DeviceTlsContext(inviterIdentity, allowUnknownPeer = true)) { connection ->
                    StablePeerAuthenticator(inviterStore, inviterIdentity, profile.groupId).authenticate(connection)
                    connection.send(MeshSessionCodec.encode(MeshSessionMessage.Error(MESH_SESSION_BUSY_REASON)))
                }
                try {
                    val port = server.start()
                    MeshPeerClient(DeviceTlsContext(joinerIdentity, allowUnknownPeer = true))
                        .connect(InetAddress.getLoopbackAddress(), port)
                        .use { connection ->
                            val remote = StablePeerAuthenticator(joinerStore, joinerIdentity, joinerProfile.groupId).authenticate(connection)
                            assertFailsWith<PeerSessionBusyException> {
                                MeshFileSyncSession(joinerStore, joinerIdentity, joinerProfile).runFiles(connection, remote)
                            }
                        }
                } finally {
                    server.close()
                }
            }
        }
    }

    private fun memoryIdentity(alias: String): WindowsDeviceIdentity {
        val path = java.nio.file.Files.createTempDirectory("syncdows-session-identity").resolve("identity.p12")
        return WindowsDeviceIdentity(alias, path, legacyKeyStoreFactory = null)
    }
}
