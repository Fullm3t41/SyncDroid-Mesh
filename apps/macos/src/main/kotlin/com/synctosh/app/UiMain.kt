package com.synctosh.app

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
import com.syncdroid.shared.update.LastUpdateCheckStore
import com.syncdroid.shared.update.ReleaseUpdateService
import com.syncdroid.shared.update.UpdatePlatform
import com.synctosh.app.mesh.MeshRuntime
import com.synctosh.app.platform.AppPreferences
import com.synctosh.app.platform.MacAppPaths
import com.synctosh.app.platform.MacDeviceName
import com.synctosh.app.platform.MacUpdateInstaller
import com.synctosh.app.platform.UpdateConfig
import com.synctosh.app.ui.SyncToshApp
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun runSyncToshUi(args: Array<String>) {
    val workerEndpoint = WorkerEndpoint.fromEnvironmentOrArguments(args)
    application {
        val preferences = remember { AppPreferences() }
        val updateService = remember {
            ReleaseUpdateService(
                currentVersion = UpdateConfig.CURRENT_VERSION,
                platform = UpdatePlatform.MacOsArm64,
                cacheDirectory = MacAppPaths.updates,
                lastCheck = { preferences.lastUpdateCheckMillis },
                lastCheckStore = LastUpdateCheckStore { preferences.lastUpdateCheckMillis = it },
            )
        }
        val meshRuntime = remember {
            MeshRuntime(
                preferences,
                deviceName = { preferences.deviceName ?: MacDeviceName.current() },
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
        val windowState = rememberWindowState(
            position = WindowPosition.Aligned(androidx.compose.ui.Alignment.Center),
            width = preferences.windowWidth.dp,
            height = preferences.windowHeight.dp,
        )
        val appIcon = painterResource("icons/synctosh.png")

        fun stopUi() {
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
            title = "SyncTosh",
            icon = appIcon,
            state = windowState,
            visible = windowVisible,
            onCloseRequest = ::stopUi,
        ) {
            SyncToshApp(
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
                onInstallUpdate = MacUpdateInstaller::open,
            )
        }

        androidx.compose.runtime.DisposableEffect(Unit) {
            workerEndpoint?.send(WorkerCommand.UI_STARTED)
            onDispose {
                if (stopped.compareAndSet(false, true)) {
                    Thread({
                        runCatching { meshRuntime.close() }
                        workerEndpoint?.send(WorkerCommand.UI_CLOSED)
                    }, "synctosh-ui-shutdown").apply {
                        isDaemon = false
                        start()
                    }
                }
            }
        }
    }
}
