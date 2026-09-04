package com.syncdroid.shared.update

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopUpdateHandoffTest {
    @Test fun installerAndQuitWaitForActiveSyncCompletion() = runBlocking {
        val transferFinished = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val update = launch {
            handOffDesktopUpdate(
                drainSync = { events += "draining"; transferFinished.await(); events += "drained" },
                launchInstaller = { events += "installer" },
                quitApplication = { events += "quit" },
            )
        }
        yield()
        assertEquals(listOf("draining"), events)
        transferFinished.complete(Unit)
        update.join()
        assertEquals(listOf("draining", "drained", "installer", "quit"), events)
    }

    @Test fun failedDrainDoesNotLaunchInstallerOrQuit() = runBlocking {
        val events = mutableListOf<String>()
        assertFailsWith<IllegalStateException> {
            handOffDesktopUpdate(
                drainSync = { error("sync did not drain") },
                launchInstaller = { events += "installer" },
                quitApplication = { events += "quit" },
            )
        }
        assertEquals(emptyList(), events)
    }

    @Test fun failedInstallerLaunchDoesNotQuit() = runBlocking {
        var quit = false
        assertFailsWith<IllegalStateException> {
            handOffDesktopUpdate({}, { error("installer unavailable") }, { quit = true })
        }
        assertEquals(false, quit)
    }
}
