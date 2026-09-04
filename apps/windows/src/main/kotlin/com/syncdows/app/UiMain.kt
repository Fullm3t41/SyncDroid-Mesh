package com.syncdows.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.syncdows.app.mesh.MeshRuntime
import com.syncdows.app.platform.AppPreferences
import com.syncdows.app.platform.UpdateConfig
import com.syncdows.app.platform.WindowsAppPaths
import com.syncdows.app.platform.WindowsDeviceName
import com.syncdows.app.platform.WindowsUpdateInstaller
import com.syncdows.app.ui.SyncDowsApp
import com.syncdroid.shared.update.handOffDesktopUpdate
import com.syncdroid.shared.update.LastUpdateCheckStore
import com.syncdroid.shared.update.ReleaseUpdateService
import com.syncdroid.shared.update.UpdatePlatform
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun runSyncDowsUi(args: Array<String>) {
    val workerEndpoint = WorkerEndpoint.fromEnvironmentOrArguments(args)
    application {
        val preferences = remember { AppPreferences() }
        val updateService = remember {
            ReleaseUpdateService(
                currentVersion = UpdateConfig.CURRENT_VERSION,
                platform = UpdatePlatform.WindowsX64,
                cacheDirectory = WindowsAppPaths.updates,
                lastCheck = { preferences.lastUpdateCheckMillis },
                lastCheckStore = LastUpdateCheckStore { preferences.lastUpdateCheckMillis = it },
            )
        }
        val meshRuntime = remember {
            MeshRuntime(
                preferences,
                deviceName = { preferences.deviceName ?: WindowsDeviceName.current() },
                updateCache = updateService,
            )
        }
        val stopped = remember { AtomicBoolean(false) }
        val uiScope = rememberCoroutineScope()
        LaunchedEffect(updateService) { updateService.runDailyChecks() }
        var discoveryInterval by remember { mutableIntStateOf(preferences.discoveryIntervalMinutes) }
        var discoveryWindow by remember { mutableLongStateOf(preferences.discoveryWindowSeconds) }
        var alwaysOnDiscovery by remember { mutableStateOf(preferences.alwaysOnDiscovery) }
        var windowVisible by remember { mutableStateOf(true) }
        var preparingUpdate by remember { mutableStateOf(false) }
        val windowState = rememberWindowState(
            position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
            width = preferences.windowWidth.dp,
            height = preferences.windowHeight.dp,
        )
        val appIcon = painterResource("syncdows-icon-source.png")

        fun stopUi() {
            if (preparingUpdate) return
            if (!stopped.compareAndSet(false, true)) return
            val windowWidth = windowState.size.width.value
            val windowHeight = windowState.size.height.value
            windowVisible = false
            uiScope.launch {
                withContext(Dispatchers.IO) {
                    preferences.windowWidth = windowWidth
                    preferences.windowHeight = windowHeight
                    runCatching { meshRuntime.closeAfterActiveTransfers() }
                        .onFailure { runCatching { meshRuntime.close() } }
                    workerEndpoint?.send(WorkerCommand.UI_CLOSED)
                }
                exitApplication()
            }
        }

        fun updateDiscoveryInterval(minutes: Int) {
            discoveryInterval = minutes
            preferences.discoveryIntervalMinutes = minutes
            meshRuntime.discoveryScheduleChanged()
        }

        fun updateDiscoveryWindow(seconds: Long) {
            discoveryWindow = seconds
            preferences.discoveryWindowSeconds = seconds
            meshRuntime.discoveryScheduleChanged()
        }

        fun updateAlwaysOnDiscovery(enabled: Boolean) {
            alwaysOnDiscovery = enabled
            preferences.alwaysOnDiscovery = enabled
            meshRuntime.discoveryScheduleChanged()
        }

        Window(
            title = "SyncDows",
            icon = appIcon,
            state = windowState,
            visible = windowVisible,
            onCloseRequest = ::stopUi,
        ) {
            SyncDowsApp(
                preferences = preferences,
                runtime = meshRuntime,
                discoveryInterval = discoveryInterval,
                discoveryWindow = discoveryWindow,
                alwaysOnDiscovery = alwaysOnDiscovery,
                onDiscoveryIntervalChanged = ::updateDiscoveryInterval,
                onDiscoveryWindowChanged = ::updateDiscoveryWindow,
                onAlwaysOnDiscoveryChanged = ::updateAlwaysOnDiscovery,
                onCloseToNotificationBar = ::stopUi,
                updateService = updateService,
                onInstallUpdate = { installer ->
                    if (!preparingUpdate) {
                        preparingUpdate = true
                        uiScope.launch {
                            runCatching {
                                handOffDesktopUpdate(
                                    drainSync = { withContext(Dispatchers.IO) { meshRuntime.closeAfterActiveTransfers() } },
                                    launchInstaller = { WindowsUpdateInstaller.launch(installer) },
                                    quitApplication = {
                                        stopped.set(true)
                                        // The foreground runtime is now closed; do not send UI_CLOSED,
                                        // which would start a fresh background sync during the update.
                                        workerEndpoint?.send(WorkerCommand.QUIT)
                                        exitApplication()
                                    },
                                )
                            }.onFailure { error ->
                                preparingUpdate = false
                                javax.swing.JOptionPane.showMessageDialog(null,
                                    "The update was not started. Restart the app before syncing again.\n" + error.message,
                                    "Update could not start", javax.swing.JOptionPane.ERROR_MESSAGE)
                            }
                        }
                    }
                },
            )
            if (preparingUpdate) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {},
                    title = { androidx.compose.material3.Text("Preparing update") },
                    text = { androidx.compose.material3.Text("Waiting for active syncs to finish. The app will update and reopen automatically.") },
                    confirmButton = {},
                )
            }
        }

        DisposableEffect(Unit) {
            workerEndpoint?.send(WorkerCommand.UI_STARTED)
            onDispose {
                if (stopped.compareAndSet(false, true)) {
                    Thread({
                        runCatching { meshRuntime.close() }
                        workerEndpoint?.send(WorkerCommand.UI_CLOSED)
                    }, "syncdows-ui-shutdown").apply {
                        isDaemon = false
                        start()
                    }
                }
            }
        }
    }
}
