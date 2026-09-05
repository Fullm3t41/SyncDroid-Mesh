package com.syncdroid.app.cloud

import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*

class CloudTransferGateTest {
    @Test fun cloudWaitsForMeshAndNewMeshSessionsRetry() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val mesh = launch { CloudTransferGate.mesh { started.complete(Unit); release.await() } }
            started.await()
            var cloudRan = false
            val cloud = launch { CloudTransferGate.cloud { cloudRan = true } }
            yield()
            assertFalse(cloudRan)
            var rejected = false
            try { CloudTransferGate.mesh { error("A new mesh session must not enter") } }
            catch (_: IllegalStateException) { rejected = true }
            assertTrue(rejected)
            release.complete(Unit)
            mesh.join(); cloud.join()
            assertTrue(cloudRan)
            CloudTransferGate.mesh { assertTrue(true) }
        }
    }
    @Test fun cancelledCloudWaitDoesNotLeaveMeshBlocked() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val mesh = launch { CloudTransferGate.mesh { started.complete(Unit); release.await() } }
            started.await()
            val cloud = launch { CloudTransferGate.cloud { error("Must not run") } }
            yield(); cloud.cancelAndJoin()
            CloudTransferGate.mesh { assertTrue(true) }
            release.complete(Unit); mesh.join()
        }
    }
}
