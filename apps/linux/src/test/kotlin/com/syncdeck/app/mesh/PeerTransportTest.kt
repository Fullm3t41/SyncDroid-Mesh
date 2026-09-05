package com.syncdeck.app.mesh

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class PeerTransportTest {
    @Test
    fun mutuallyAuthenticatedTlsCarriesJpakePairing() = runBlocking {
        val inviterIdentity = memoryIdentity("inviter")
        val joinerIdentity = memoryIdentity("joiner")
        val invitationId = "transport-test-invitation"
        val serverResult = CompletableDeferred<PairingResult>()
        val server = MeshPeerServer(DeviceTlsContext(inviterIdentity, allowUnknownPeer = true)) { connection ->
            val handshake = PairingHandshake(
                PairingRole.Inviter,
                invitationId,
                "482913",
                PairingIdentity.from(inviterIdentity, "Mac"),
            )
            serverResult.complete(PairingConnectionProtocol(connection, handshake).run())
        }

        try {
            val port = server.start()
            val joinerResult = MeshPeerClient(DeviceTlsContext(joinerIdentity, allowUnknownPeer = true))
                .connect(InetAddress.getLoopbackAddress(), port)
                .use { connection ->
                    assertContentEquals(inviterIdentity.publicKey.encoded, connection.peerTlsIdentity.publicKeySpki)
                    PairingConnectionProtocol(
                        connection,
                        PairingHandshake(
                            PairingRole.Joiner,
                            invitationId,
                            "482913",
                            PairingIdentity.from(joinerIdentity, "Android"),
                        ),
                    ).run()
                }
            val inviterResult = withTimeout(10_000) { serverResult.await() }
            assertEquals(inviterIdentity.deviceId, joinerResult.remoteIdentity.deviceId)
            assertEquals(joinerIdentity.deviceId, inviterResult.remoteIdentity.deviceId)
            assertContentEquals(inviterResult.sessionKey, joinerResult.sessionKey)
        } finally {
            server.close()
        }
    }

    @Test
    fun closingServerInterruptsAConnectionBlockedOnTlsRead() = runBlocking {
        val serverIdentity = memoryIdentity("blocked-server")
        val clientIdentity = memoryIdentity("blocked-client")
        val receiving = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val server = MeshPeerServer(DeviceTlsContext(serverIdentity, allowUnknownPeer = true)) { connection ->
            receiving.complete(Unit)
            runCatching { connection.receive() }
            released.complete(Unit)
        }
        val client = MeshPeerClient(DeviceTlsContext(clientIdentity, allowUnknownPeer = true))
            .connect(InetAddress.getLoopbackAddress(), server.start())
        try {
            withTimeout(2_000) { receiving.await() }
            server.close()
            withTimeout(2_000) {
                released.await()
                server.awaitClosed()
            }
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun closingOutboundSocketTrackerInterruptsAClientBlockedOnTlsRead() = runBlocking {
        val serverIdentity = memoryIdentity("tracked-server")
        val clientIdentity = memoryIdentity("tracked-client")
        val accepted = CompletableDeferred<Unit>()
        val server = MeshPeerServer(DeviceTlsContext(serverIdentity, allowUnknownPeer = true)) {
            accepted.complete(Unit)
            awaitCancellation()
        }
        val tracker = PeerSocketTracker()
        val client = MeshPeerClient(DeviceTlsContext(clientIdentity, allowUnknownPeer = true), tracker)
            .connect(InetAddress.getLoopbackAddress(), server.start())
        try {
            withTimeout(2_000) { accepted.await() }
            val blockedRead = async(Dispatchers.IO) { runCatching { client.receive() } }
            tracker.close()
            withTimeout(2_000) { blockedRead.await() }
        } finally {
            client.close()
            tracker.close()
            server.close()
            withTimeout(2_000) { server.awaitClosed() }
        }
        Unit
    }

    private fun memoryIdentity(alias: String): LinuxDeviceIdentity {
        val path = java.nio.file.Files.createTempDirectory("syncdeck-peer-identity").resolve("identity.p12")
        return LinuxDeviceIdentity(alias, path)
    }
}
