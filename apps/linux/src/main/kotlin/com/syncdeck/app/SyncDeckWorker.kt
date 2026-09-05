package com.syncdeck.app

import com.syncdroid.shared.update.LastUpdateCheckStore
import com.syncdroid.shared.update.ReleaseUpdateService
import com.syncdroid.shared.update.UpdatePlatform
import com.syncdeck.app.mesh.MeshRuntime
import com.syncdeck.app.mesh.MeshRuntimeState
import com.syncdeck.app.mesh.SUPPORTED_DISCOVERY_INTERVALS
import com.syncdeck.app.mesh.SUPPORTED_DISCOVERY_WINDOWS
import com.syncdeck.app.mesh.discoveryIntervalLabel
import com.syncdeck.app.mesh.discoveryWindowLabel
import com.syncdeck.app.platform.AppPreferences
import com.syncdeck.app.platform.LinuxAppPaths
import com.syncdeck.app.platform.LinuxDeviceName
import com.syncdeck.app.platform.UpdateConfig
import java.awt.CheckboxMenuItem
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Menu
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.desktop.AppReopenedListener
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class SyncDeckWorker(private val arguments: Array<String>) : Closeable {
    private val preferences = AppPreferences()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    private val uiTransition = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val deckyRequested = "--decky" in arguments
    private val permission = BackgroundPermission().apply { if (deckyRequested) renew() }
    @Volatile private var draining = false
    private fun backgroundAllowed() = permission.allowed(
        com.syncdeck.app.platform.LinuxSession.gamingMode(),
        com.syncdeck.app.platform.LinuxSession.desktopAvailable(), preferences.noBackgroundService)
    private fun controlStatus(): String {
        val mode = when { draining -> "stopping"; uiProcess?.isAlive == true -> "app"; runtime != null -> "background"; else -> "paused" }
        val text = java.util.Base64.getEncoder().encodeToString(latestState.status.toByteArray())
        return "$mode\t$text\t${latestState.peers.count { it.online }}\t${latestState.peers.size}"
    }
    private val backgroundRequested = "--background" in arguments
    private val updateService = ReleaseUpdateService(
        currentVersion = UpdateConfig.CURRENT_VERSION,
        platform = UpdatePlatform.LinuxX64,
        cacheDirectory = LinuxAppPaths.updates,
        lastCheck = { preferences.lastUpdateCheckMillis },
        lastCheckStore = LastUpdateCheckStore { preferences.lastUpdateCheckMillis = it },
    )
    private var controlServer: WorkerControlServer? = null
    private var runtime: MeshRuntime? = null
    private var runtimeStateJob: Job? = null
    @Volatile private var latestState = MeshRuntimeState(status = "Starting background service…")
    private val trayRequested = AtomicBoolean(false)
    private var trayIcon: TrayIcon? = null
    @Volatile private var uiProcess: Process? = null
    private var instanceLock: WorkerInstanceLock? = null

    fun run() {
        if (backgroundRequested && !backgroundAllowed()) return
        val existing = WorkerEndpoint.load()
        if (existing?.send(WorkerCommand.PING) == true) {
            if (deckyRequested) existing.send(WorkerCommand.DECKY_KEEPALIVE)
            if (!backgroundRequested) existing.send(WorkerCommand.SHOW)
            return
        }
        val acquiredLock = WorkerInstanceLock.tryAcquire(LinuxAppPaths.workerLock)
        if (acquiredLock == null) {
            if (!backgroundRequested) awaitExistingWorker()?.send(WorkerCommand.SHOW)
            return
        }
        instanceLock = acquiredLock
        val racedWorker = WorkerEndpoint.load()
        if (racedWorker?.send(WorkerCommand.PING) == true) {
            if (!backgroundRequested) racedWorker.send(WorkerCommand.SHOW)
            acquiredLock.close()
            instanceLock = null
            return
        }
        racedWorker?.deleteIfCurrent()

        val server = WorkerControlServer(onCommand = ::handleCommand, status = ::controlStatus)
        controlServer = server
        server.endpoint.save()
        if (com.syncdeck.app.platform.LinuxSession.desktopAvailable()) installTray()


        if (backgroundRequested) startBackgroundRuntime()
        else showUi()

        scope.launch {
            while (!stopped.get()) {
                kotlinx.coroutines.delay(5_000)
                if (com.syncdeck.app.platform.LinuxSession.desktopAvailable()) installTray()
                if (!backgroundAllowed() && uiProcess?.isAlive != true && !uiTransition.get()) stopBackgroundGracefully()
                else if (backgroundAllowed()) startBackgroundRuntime()
            }
        }
        Runtime.getRuntime().addShutdownHook(Thread { close() })
        finished.await()
        kotlin.system.exitProcess(0)
    }

    @Synchronized
    private fun handleCommand(command: WorkerCommand) {
        when (command) {
            WorkerCommand.PING, WorkerCommand.STATUS, WorkerCommand.UI_STARTED -> Unit
            WorkerCommand.SHOW -> showUi()
            WorkerCommand.UI_CLOSED -> Unit // Wait for process exit before handing the database back.
            WorkerCommand.QUIT -> close()
            WorkerCommand.SYNC_NOW -> if (!draining && uiProcess?.isAlive != true && backgroundAllowed()) {
                startBackgroundRuntime()
                runtime?.syncNow()
            }
            WorkerCommand.DECKY_KEEPALIVE -> {
                permission.renew()
                if (!draining) startBackgroundRuntime()
            }
            WorkerCommand.DECKY_DISABLE -> {
                permission.revoke()
                if (!backgroundAllowed() && uiProcess?.isAlive != true) scope.launch { stopBackgroundGracefully() }
            }
        }
    }

    private fun showUi() {
        if (stopped.get() || draining) return
        val activeUi = uiProcess?.takeIf(Process::isAlive)
        if (activeUi != null) {
            activate(activeUi.pid())
            return
        }
        if (!uiTransition.compareAndSet(false, true)) return
        scope.launch {
            try {
                latestState = latestState.copy(status = "Opening SyncDeck…")
                updateTray()
                val oldRuntime = synchronized(this@SyncDeckWorker) {
                    runtime.also {
                        runtime = null
                        runtimeStateJob?.cancel()
                        runtimeStateJob = null
                    }
                }
                runCatching { oldRuntime?.closeAfterActiveTransfers() }
                    .onFailure { oldRuntime?.close() }
                if (stopped.get()) return@launch
                val endpoint = checkNotNull(controlServer?.endpoint) { "Background worker is unavailable" }
                val process = launchUiProcess(endpoint)
                synchronized(this@SyncDeckWorker) { uiProcess = process }
                latestState = latestState.copy(status = "SyncDeck is open")
                updateTray()
                scope.launch {
                    process.waitFor()
                    synchronized(this@SyncDeckWorker) {
                        if (uiProcess === process) onUiClosed()
                    }
                }
            } catch (error: Throwable) {
                latestState = latestState.copy(status = error.message ?: "Could not open SyncDeck")
                updateTray()
                if (!stopped.get()) startBackgroundRuntime()
            } finally {
                uiTransition.set(false)
            }
        }
    }

    @Synchronized
    private fun onUiClosed() {
        preferences.reload()
        uiProcess = null
        uiTransition.set(false)
        if (stopped.get()) return
        if (!backgroundAllowed()) close()
        else startBackgroundRuntime()
    }

    @Synchronized
    private fun startBackgroundRuntime() {
        if (stopped.get() || draining || uiTransition.get() || !backgroundAllowed() || runtime != null || uiProcess?.isAlive == true) return
        val next = MeshRuntime(
            preferences,
            deviceName = { preferences.deviceName ?: LinuxDeviceName.current() },
            updateCache = updateService,
            initiallyForeground = false,
        )
        runtime = next
        runtimeStateJob = scope.launch {
            next.state.collectLatest { state ->
                latestState = state
                updateTray()
            }
        }
    }

    private fun installTray() {
        if (!trayRequested.compareAndSet(false, true)) return
        if (!SystemTray.isSupported()) return
        EventQueue.invokeLater {
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                runCatching { desktop.addAppEventListener(AppReopenedListener { showUi() }) }
                runCatching {
                    desktop.setQuitHandler { _, response ->
                        close()
                        response.performQuit()
                    }
                }
            }
            val image = javaClass.classLoader.getResourceAsStream("icons/syncdeck-tray.png")?.use(ImageIO::read)
                ?: return@invokeLater
            trayIcon = TrayIcon(image, "SyncDeck").apply {
                isImageAutoSize = true
                addActionListener { showUi() }
                popupMenu = trayMenu()
                SystemTray.getSystemTray().add(this)
            }
        }
    }

    private fun updateTray() {
        if (trayIcon == null) return
        EventQueue.invokeLater {
            trayIcon?.apply {
                toolTip = "SyncDeck · ${latestState.status}"
                popupMenu = trayMenu()
            }
        }
    }

    private fun trayMenu(): PopupMenu = PopupMenu().apply {
        add(MenuItem("Open SyncDeck").apply { addActionListener { showUi() } })
        addSeparator()
        add(MenuItem("Status: ${latestState.status}").apply { isEnabled = false })
        addSeparator()
        val peers = latestState.peers
        add(MenuItem("Devices · ${peers.count { it.online }}/${peers.size} online").apply { isEnabled = false })
        if (latestState.profile == null) {
            add(MenuItem("No mesh connected").apply { isEnabled = false })
        } else if (peers.isEmpty()) {
            add(MenuItem("No other mesh devices").apply { isEnabled = false })
        } else {
            peers.sortedWith(compareByDescending<com.syncdeck.app.model.MeshPeer> { it.online }.thenBy { it.name.lowercase() })
                .forEach { peer ->
                    add(MenuItem("${if (peer.online) "🟢" else "●"} ${peer.name}").apply {
                        isEnabled = peer.online
                        addActionListener { showUi() }
                    })
                }
        }
        addSeparator()
        add(CheckboxMenuItem("Always-on discovery", preferences.alwaysOnDiscovery).apply {
            addItemListener {
                preferences.alwaysOnDiscovery = state
                runtime?.discoveryScheduleChanged()
                updateTray()
            }
        })
        add(Menu("Sync interval · ${discoveryIntervalLabel(preferences.discoveryIntervalMinutes)}").apply {
            isEnabled = !preferences.alwaysOnDiscovery
            SUPPORTED_DISCOVERY_INTERVALS.forEach { minutes ->
                add(CheckboxMenuItem(discoveryIntervalLabel(minutes), preferences.discoveryIntervalMinutes == minutes).apply {
                    addItemListener {
                        preferences.discoveryIntervalMinutes = minutes
                        runtime?.discoveryScheduleChanged()
                        updateTray()
                    }
                })
            }
        })
        add(Menu("Discovery duration · ${discoveryWindowLabel(preferences.discoveryWindowSeconds)}").apply {
            isEnabled = !preferences.alwaysOnDiscovery
            SUPPORTED_DISCOVERY_WINDOWS.forEach { seconds ->
                add(CheckboxMenuItem(discoveryWindowLabel(seconds), preferences.discoveryWindowSeconds == seconds).apply {
                    addItemListener {
                        preferences.discoveryWindowSeconds = seconds
                        runtime?.discoveryScheduleChanged()
                        updateTray()
                    }
                })
            }
        })
        addSeparator()
        add(MenuItem("Quit").apply { addActionListener { close() } })
    }

    private fun launchUiProcess(endpoint: WorkerEndpoint): Process {
        Files.createDirectories(LinuxAppPaths.workerLog.parent)
        return ProcessBuilder(AppProcessLauncher.command(UI_ARGUMENT))
            .apply {
                environment()[WORKER_PORT_ENV] = endpoint.port.toString()
                environment()[WORKER_TOKEN_ENV] = endpoint.token
            }
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(LinuxAppPaths.workerLog.toFile()))
            .start()
    }

    private fun activate(@Suppress("UNUSED_PARAMETER") pid: Long) {
        // Window managers may reject focus stealing; never open a second sync runtime.
        runCatching { ProcessBuilder("wmctrl", "-x", "-a", "SyncDeck").start() }
    }

    private suspend fun stopBackgroundGracefully() {
        val old = synchronized(this) {
            if (draining) return
            draining = true
            runtime.also { runtime = null }
        }
        latestState = latestState.copy(status = "Finishing active transfers before stopping…")
        runCatching { old?.closeAfterActiveTransfers() }.onFailure { old?.close() }
        // A renewed lease can arrive while a transfer drains.
        draining = false
        if (backgroundAllowed()) startBackgroundRuntime() else close()
    }

    private fun awaitExistingWorker(): WorkerEndpoint? {
        val deadline = System.nanoTime() + WORKER_STARTUP_WAIT_MILLIS * 1_000_000
        while (System.nanoTime() < deadline) {
            val endpoint = WorkerEndpoint.load()
            if (endpoint?.send(WorkerCommand.PING) == true) return endpoint
            Thread.sleep(WORKER_STARTUP_POLL_MILLIS)
        }
        return null
    }

    @Synchronized
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        try {
            runCatching { uiProcess?.destroy() }
            uiProcess = null
            runtimeStateJob?.cancel()
            runtimeStateJob = null
            runCatching { runtime?.close() }
            runtime = null
            trayIcon?.let { icon -> EventQueue.invokeLater { runCatching { SystemTray.getSystemTray().remove(icon) } } }
            trayIcon = null
            runCatching { controlServer?.endpoint?.deleteIfCurrent() }
            runCatching { controlServer?.close() }
            controlServer = null
            scope.cancel()
            runCatching { instanceLock?.close() }
            instanceLock = null
        } finally {
            finished.countDown()
        }
    }
}

private const val WORKER_STARTUP_WAIT_MILLIS = 5_000L
private const val WORKER_STARTUP_POLL_MILLIS = 100L

internal object AppProcessLauncher {
    fun command(vararg arguments: String): List<String> {
        val packaged = System.getProperty("jpackage.app-path")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: ProcessHandle.current().info().command().orElse(null)?.let(Path::of)
        if (packaged != null && packaged.fileName.toString() == "SyncDeck") {
            return listOf(packaged.toString()) + arguments
        }
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        return listOf(java.toString(), "-cp", System.getProperty("java.class.path"), "com.syncdeck.app.MainKt") + arguments
    }
}
