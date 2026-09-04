package com.syncdroid.shared.update

/** The installer timeout starts only after active synchronization has drained. */
suspend fun handOffDesktopUpdate(
    drainSync: suspend () -> Unit,
    launchInstaller: () -> Unit,
    quitApplication: () -> Unit,
) {
    drainSync()
    launchInstaller()
    quitApplication()
}
