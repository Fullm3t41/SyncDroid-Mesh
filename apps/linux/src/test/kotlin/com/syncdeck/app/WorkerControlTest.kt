package com.syncdeck.app

import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkerControlTest {
    @Test
    fun authenticatedLoopbackCommandIsAcknowledged() {
        val received = CountDownLatch(1)
        var command: WorkerCommand? = null
        WorkerControlServer {
            command = it
            received.countDown()
        }.use { server ->
            assertTrue(server.endpoint.send(WorkerCommand.SHOW))
            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertEquals(WorkerCommand.SHOW, command)
        }
    }

    @Test
    fun stalledClientDoesNotStopTheAcceptLoop() {
        WorkerControlServer(readTimeoutMillis = 100) {}.use { server ->
            Socket(InetAddress.getLoopbackAddress(), server.endpoint.port).use {
                Thread.sleep(200)
            }
            assertTrue(server.endpoint.send(WorkerCommand.PING))
        }
    }

    @Test
    fun workerFileLockAllowsOnlyOneOwner() {
        val path = Files.createTempDirectory("syncdeck-worker-lock").resolve("worker.lock")
        WorkerInstanceLock.tryAcquire(path).use { first ->
            assertNotNull(first)
            assertNull(WorkerInstanceLock.tryAcquire(path))
        }
        WorkerInstanceLock.tryAcquire(path).use { assertNotNull(it) }
    }
}
