package com.syncdroid.shared.cloud

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudTransferOrchestratorTest {
    @Test fun shutdownWaitsForActiveRunAndRejectsQueuedWork() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var calls = 0
            val orchestrator = CloudTransferOrchestrator(
                policy = { CloudSyncPolicy(CloudSyncScope.ALL_FOLDERS) },
                folderIds = { listOf("games") },
                connectedProviders = { listOf(CloudProvider.GOOGLE_DRIVE) },
                runner = CloudTransferRunner { _, _ ->
                    calls++
                    started.complete(Unit)
                    finish.await()
                    CloudTransferResult(uploadedFiles = 1)
                },
            )
            val running = launch { orchestrator.run(CloudSyncTrigger.SCHEDULED_WINDOW) }
            started.await()
            val queued = launch { orchestrator.run(CloudSyncTrigger.MANUAL) }
            yield()
            val draining = launch { orchestrator.stopAndDrain() }
            yield()
            assertFalse(draining.isCompleted)
            assertTrue(running.isActive)
            finish.complete(Unit)
            running.join(); queued.join(); draining.join()
            orchestrator.run(CloudSyncTrigger.MANUAL)
            assertEquals(1, calls)
        }
    }

    @Test
    fun selectedFoldersRunForEveryConnectedProvider() = runBlocking {
        val calls = mutableListOf<Pair<CloudProvider, String>>()
        val orchestrator = CloudTransferOrchestrator(
            policy = { CloudSyncPolicy(CloudSyncScope.SELECTED_FOLDERS, setOf("games")) },
            folderIds = { listOf("games", "photos") },
            connectedProviders = { CloudProvider.entries },
            runner = CloudTransferRunner { provider, folder ->
                calls += provider to folder
                CloudTransferResult(uploadedFiles = 1)
            },
        )

        val result = orchestrator.run(CloudSyncTrigger.SCHEDULED_WINDOW)

        assertEquals(
            listOf(CloudProvider.GOOGLE_DRIVE to "games", CloudProvider.ONE_DRIVE to "games"),
            calls,
        )
        assertEquals(2, result.uploadedFiles)
    }

    @Test
    fun disabledPolicyDoesNoWork() = runBlocking {
        var calls = 0
        val orchestrator = CloudTransferOrchestrator(
            policy = { CloudSyncPolicy() },
            folderIds = { listOf("games") },
            connectedProviders = { CloudProvider.entries },
            runner = CloudTransferRunner { _, _ -> calls++; CloudTransferResult() },
        )
        orchestrator.run(CloudSyncTrigger.MANUAL)
        assertEquals(0, calls)
    }
}
