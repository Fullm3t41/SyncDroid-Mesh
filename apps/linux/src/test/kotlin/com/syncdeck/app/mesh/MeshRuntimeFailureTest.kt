package com.syncdeck.app.mesh

import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshRuntimeFailureTest {
    @Test
    fun peerAvailabilityFailuresAreTransient() {
        listOf(
            ConnectException("Connection refused"),
            NoRouteToHostException("No route to host"),
            SocketTimeoutException("Connect timed out"),
            SocketException("Connection reset"),
            EOFException("Peer closed the connection"),
        ).forEach { error ->
            assertTrue(error.isTransientPeerAvailabilityFailure(), error.toString())
        }
    }

    @Test
    fun wrappedAvailabilityFailureIsTransient() {
        val error = IllegalStateException("Peer session failed", ConnectException("Connection refused"))

        assertTrue(error.isTransientPeerAvailabilityFailure())
    }

    @Test
    fun authenticationAndProtocolFailuresRemainActionable() {
        assertFalse(IllegalStateException("Peer fingerprint mismatch").isTransientPeerAvailabilityFailure())
        assertFalse(IllegalArgumentException("Invalid signed membership event").isTransientPeerAvailabilityFailure())
    }
}
