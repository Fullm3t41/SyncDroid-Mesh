package com.syncdeck.app.mesh

import java.net.DatagramSocket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFails
import kotlin.test.assertTrue

class DiscoveryLifecycleTest {
    @Test
    fun pairingUdpSocketExistsOnlyWhileEnabled() {
        val discovery = PairingLanDiscovery("device-identifier-1234", discoveryPort = 0)
        try {
            assertFalse(discovery.isRunning)
            discovery.setEnabled(true)
            assertTrue(discovery.isRunning)
            discovery.setEnabled(false)
            assertFalse(discovery.isRunning)
        } finally {
            discovery.close()
        }
    }

    @Test
    fun pairingDiscoveryCanRetryAfterItsPortBecomesAvailable() {
        val blocker = DatagramSocket(0)
        val discovery = PairingLanDiscovery("device-identifier-1234", discoveryPort = blocker.localPort)
        try {
            assertFails { discovery.setEnabled(true) }
            assertFalse(discovery.isRunning)
            blocker.close()
            discovery.setEnabled(true)
            assertTrue(discovery.isRunning)
        } finally {
            blocker.close()
            discovery.close()
        }
    }
}
