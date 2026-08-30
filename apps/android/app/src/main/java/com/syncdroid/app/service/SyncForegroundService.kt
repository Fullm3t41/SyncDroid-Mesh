package com.syncdroid.app.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.text.format.DateFormat
import androidx.core.app.ServiceCompat
import com.syncdroid.app.data.SyncDroidDatabase
import com.syncdroid.app.sync.FileHistoryRepository
import com.syncdroid.app.mesh.AndroidDeviceIdentity
import com.syncdroid.app.mesh.LocalDeviceNameStore
import com.syncdroid.app.mesh.LocalMeshProfile
import com.syncdroid.app.mesh.LocalMeshProfileStore
import com.syncdroid.app.mesh.MembershipEvent
import com.syncdroid.app.mesh.MeshRuntime
import com.syncdroid.app.mesh.MeshRuntimeEvent
import com.syncdroid.app.mesh.MeshMembershipRepository
import com.syncdroid.app.notifications.SyncNotificationCenter
import com.syncdroid.app.scheduling.DiscoveryPolicy
import com.syncdroid.app.scheduling.DiscoveryPolicyStore
import com.syncdroid.app.sync.SyncStatusStore
import com.syncdroid.app.sync.VersionVector
import com.syncdroid.app.sync.formatTransferRate
import com.syncdroid.app.storage.LowStorageApprovalStore
import com.syncdroid.app.storage.StorageCapacityGuard
import com.syncdroid.app.storage.StorageSyncWarning
import com.syncdroid.app.storage.formatStorageBytes
import com.syncdroid.app.wifi.WifiConnectionMonitor
import com.syncdroid.app.wifi.WifiConnectionState
import com.syncdroid.app.wifi.WifiSyncPolicyStore
import com.syncdroid.app.wifi.hasWifiRuntimePermission
import com.syncdroid.app.update.AndroidUpdateProvider
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SyncForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var database: SyncDroidDatabase
    private lateinit var identity: AndroidDeviceIdentity
    private lateinit var notification: SyncServiceNotification
    private lateinit var eventNotifications: SyncNotificationCenter
    private lateinit var wifiMonitor: WifiConnectionMonitor
    private lateinit var storageCapacity: StorageCapacityGuard
    private lateinit var lowStorageApprovals: LowStorageApprovalStore
    private var wifiConnection = WifiConnectionState()
    private var runtime: MeshRuntime? = null
    private var runtimeKey: RuntimeKey? = null
    private var reconcileJob: Job? = null
    private var reconcilePending = false
    private var pendingReconcileForce = false
    private val activePeers = linkedMapOf<String, String>()
    private val peerTransferRates = linkedMapOf<String, Long>()
    private val peerTransferProgress = linkedMapOf<String, MeshRuntimeEvent.TransferProgress>()
    private var statusTitle = "Starting background sync"
    private var statusDetail = "Checking Wi-Fi and mesh settings"

    override fun onCreate() {
        super.onCreate()
        database = SyncDroidDatabase.get(this)
        identity = AndroidDeviceIdentity()
        notification = SyncServiceNotification(this)
        eventNotifications = SyncNotificationCenter(this)
        storageCapacity = StorageCapacityGuard(this)
        lowStorageApprovals = LowStorageApprovalStore(this)
        serviceScope.launch {
            FileHistoryRepository(this@SyncForegroundService, database, identity.deviceId).cleanupExpired()
            refreshActionItems()
        }

        ServiceCompat.startForeground(
            this,
            SyncServiceNotification.NOTIFICATION_ID,
            notification.build(statusTitle, statusDetail, DiscoveryPolicyStore(this).load()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        SyncServiceController.report(running = true, status = statusTitle)

        serviceScope.launch {
            SyncServiceController.appInForeground.collect {
                publishNotification()
                reconcile(force = false)
            }
        }
        wifiMonitor = WifiConnectionMonitor(applicationContext)
        wifiMonitor.start { connection ->
            wifiConnection = connection
            reconcile(force = false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CYCLE_INTERVAL -> {
                val store = DiscoveryPolicyStore(this)
                val current = store.load()
                store.save(current.copy(
                    intervalMinutes = nextValue(current.intervalMinutes, DiscoveryPolicy.SUPPORTED_INTERVALS.sorted()),
                    windowSecondsOverride = null,
                ))
                SyncServiceController.report(policyChanged = true)
                reconcile(force = true)
            }
            ACTION_CYCLE_WINDOW -> {
                val store = DiscoveryPolicyStore(this)
                val current = store.load()
                store.save(current.copy(
                    windowSecondsOverride = nextValue(current.windowSeconds, DiscoveryPolicy.SUPPORTED_WINDOWS_SECONDS.sorted()),
                ))
                SyncServiceController.report(policyChanged = true)
                reconcile(force = true)
            }
            ACTION_PROPAGATE_MEMBERSHIP -> {
                val addedDeviceId = intent.getStringExtra(EXTRA_ADDED_DEVICE_ID)
                if (addedDeviceId.isNullOrBlank()) reconcile(force = true)
                else runtime?.propagateMembershipChange(addedDeviceId) ?: reconcile(force = true)
            }
            ACTION_PROPAGATE_CHAT -> {
                if (runtime?.propagateLocalChatChange() != true) reconcile(force = true)
            }
            ACTION_REFRESH -> reconcile(force = true)
            else -> reconcile(force = false)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        reconcileJob?.cancel()
        runtime?.close()
        runtime = null
        wifiMonitor.stop()
        serviceScope.cancel()
        SyncServiceController.report(
            running = false,
            status = "Background sync stopped",
            activePeerIds = emptySet(),
            onlinePeerIds = emptySet(),
            peerSyncProgress = emptyMap(),
        )
        super.onDestroy()
    }

    private fun reconcile(force: Boolean) {
        if (activePeers.isNotEmpty()) {
            reconcilePending = true
            pendingReconcileForce = pendingReconcileForce || force
            return
        }
        reconcileJob?.cancel()
        reconcileJob = serviceScope.launch {
            val discoveryPolicy = DiscoveryPolicyStore(this@SyncForegroundService).load()
            val wifiPolicy = WifiSyncPolicyStore(this@SyncForegroundService).load()
            val profile = LocalMeshProfileStore(this@SyncForegroundService).getOrCreate()
            val permissionGranted = hasWifiRuntimePermission(this@SyncForegroundService)
            val syncAllowed = permissionGranted && wifiPolicy.allowsSyncWithForegroundOverride(
                isWifiConnected = wifiConnection.isWifiConnected,
                currentSsid = wifiConnection.ssid,
                appInForeground = SyncServiceController.appInForeground.value,
            )
            val key = RuntimeKey(
                groupId = profile.groupId,
                intervalMinutes = discoveryPolicy.intervalMinutes,
                windowSeconds = discoveryPolicy.windowSeconds,
                scheduledDiscoveryEnabled = discoveryPolicy.scheduledDiscoveryEnabled,
                syncAllowed = syncAllowed,
                wifiConnected = wifiConnection.isWifiConnected,
                wifiSsid = wifiConnection.ssid,
                permissionGranted = permissionGranted,
            )
            if (!force && key == runtimeKey) {
                publishNotification()
                return@launch
            }

            runtime?.close()
            runtime = null
            activePeers.clear()
            peerTransferRates.clear()
            peerTransferProgress.clear()

            when {
                !permissionGranted -> {
                    clearStorageWarning()
                    setStatus("Sync paused", "Open SyncDroid-Mesh and allow nearby Wi-Fi access")
                }
                !wifiConnection.isWifiConnected -> {
                    clearStorageWarning()
                    setStatus("Sync paused", "Connect this device to Wi-Fi")
                }
                !syncAllowed -> {
                    clearStorageWarning()
                    setStatus(
                        "Sync paused on this network",
                        wifiConnection.ssid?.let { "$it is not registered for syncing" }
                            ?: "The current Wi-Fi network is not registered",
                    )
                }
                !ensureLocalMembership(profile) -> {
                    clearStorageWarning()
                    setStatus("Mesh needs attention", "Open SyncDroid-Mesh to repair or join the mesh")
                }
                else -> {
                    val storageWarning = storageWarningBeforeSync(profile)
                    if (storageWarning == null) clearStorageWarning()
                    startRuntime(profile, discoveryPolicy, force)
                    if (storageWarning != null) {
                        SyncServiceController.report(storageWarning = storageWarning)
                        showStorageWarningStatus(storageWarning)
                    }
                }
            }
            runtimeKey = key
        }
    }

    private suspend fun ensureLocalMembership(profile: LocalMeshProfile): Boolean = try {
        val repository = MeshMembershipRepository(database.meshDao())
        val existing = database.meshDao().getDevice(profile.groupId, identity.deviceId)
            ?: if (repository.restoreCreatorProjection(
                    groupId = profile.groupId,
                    groupName = profile.groupName,
                    expectedCreatorDeviceId = identity.deviceId,
                )) database.meshDao().getDevice(profile.groupId, identity.deviceId) else null
        if (existing == null) {
            repository.apply(
                profile.groupName,
                MembershipEvent.createAddDevice(
                    groupId = profile.groupId,
                    subjectDisplayName = LocalDeviceNameStore(this).load(),
                    subjectPublicKey = identity.publicKey,
                    signer = identity,
                    parentEventIds = emptyList(),
                    version = VersionVector().increment(identity.deviceId),
                ),
            ).getOrThrow()
        }
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        false
    }

    private suspend fun startRuntime(
        profile: LocalMeshProfile,
        policy: DiscoveryPolicy,
        discoverImmediately: Boolean,
    ) {
        val newRuntime = MeshRuntime(
            context = this,
            database = database,
            identity = identity,
            groupId = profile.groupId,
            groupName = profile.groupName,
            rendezvousIntervalMinutes = policy.intervalMinutes,
            scheduledDiscoveryEnabled = policy.scheduledDiscoveryEnabled,
            rendezvousWindowSeconds = policy.windowSeconds,
            discoverImmediately = discoverImmediately,
            appInForeground = SyncServiceController.appInForeground,
            updateCache = AndroidUpdateProvider.get(this),
            onEvent = { event -> serviceScope.launch { handleRuntimeEvent(event) } },
        )
        runtime = newRuntime
        try {
            newRuntime.start()
            setStatus("Background sync ready", "Waiting for mesh discovery status")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            runtime = null
            newRuntime.close()
            setStatus("Background sync unavailable", error.message ?: "Could not start the local mesh")
        }
    }

    private suspend fun handleRuntimeEvent(event: MeshRuntimeEvent) {
        when (event) {
            is MeshRuntimeEvent.DiscoveryWaiting -> setStatus(
                "Waiting for nearby devices",
                "Next discovery at ${formatTime(event.nextWindowAtMillis)}",
            )
            is MeshRuntimeEvent.DiscoveryActive -> setStatus(
                "Looking for mesh devices",
                event.windowEndsAtMillis?.let { "Discovery active until ${formatTime(it)}" }
                    ?: "Discovery stays active while SyncDroid-Mesh is open",
            )
            is MeshRuntimeEvent.PresenceChanged -> SyncServiceController.report(
                onlinePeerIds = event.peerIds,
            )
            is MeshRuntimeEvent.SyncStarted -> {
                activePeers[event.peerId] = event.peerName
                peerTransferRates.remove(event.peerId)
                peerTransferProgress.remove(event.peerId)
                showActiveSyncStatus()
            }
            is MeshRuntimeEvent.TransferProgress -> {
                activePeers[event.peerId] = event.peerName
                peerTransferRates[event.peerId] = event.bytesPerSecond
                peerTransferProgress[event.peerId] = event
                showActiveSyncStatus()
            }
            is MeshRuntimeEvent.SyncCompleted -> {
                activePeers.remove(event.peerId)
                peerTransferRates.remove(event.peerId)
                peerTransferProgress.remove(event.peerId)
                SyncStatusStore(this).recordSuccessfulSync(event.folderIds)
                val currentStorageWarning = event.storageWarning
                    ?: SyncServiceController.snapshot.value.storageWarning
                if (currentStorageWarning != null) {
                    SyncServiceController.report(storageWarning = currentStorageWarning)
                    showStorageWarningStatus(currentStorageWarning)
                } else if (activePeers.isEmpty()) {
                    eventNotifications.clearStorageWarning()
                    setStatus("Files are up to date", "Last synced with ${event.peerName} at ${formatTime(System.currentTimeMillis())}")
                } else {
                    showActiveSyncStatus()
                }
                eventNotifications.showSyncComplete(event.peerId, event.peerName)
                refreshActionItems()
                SyncServiceController.report(syncCompleted = true)
                runPendingReconcileIfIdle()
            }
            is MeshRuntimeEvent.SyncFailed -> {
                activePeers.remove(event.peerId)
                peerTransferRates.remove(event.peerId)
                peerTransferProgress.remove(event.peerId)
                if (activePeers.isEmpty()) {
                    setStatus("Sync needs attention", "${event.peerName}: ${event.reason}")
                } else {
                    showActiveSyncStatus()
                }
                eventNotifications.showSyncFailed(event.peerId, event.peerName)
                refreshActionItems()
                runPendingReconcileIfIdle()
            }
            is MeshRuntimeEvent.ChatMessagesReceived -> {
                eventNotifications.showChatMessages(event.count, event.authorName, event.preview)
            }
        }
    }

    private fun showActiveSyncStatus() {
        val title = if (activePeers.size == 1) {
            "Syncing with ${activePeers.values.first()}"
        } else {
            "Syncing with ${activePeers.size} devices"
        }
        val totalRate = peerTransferRates.values.sum()
        val detail = if (totalRate > 0L) {
            "${formatTransferRate(totalRate)} · Transferring changed files"
        } else {
            "Comparing files and preparing transfers"
        }
        setStatus(title, detail)
    }

    private suspend fun refreshActionItems() {
        val profile = LocalMeshProfileStore(this).getOrCreate()
        eventNotifications.updateActionItems(
            conflicts = database.syncDao().unresolvedConflictCount(),
            foldersToConfigure = database.syncDao().pendingConfigurationCount(identity.deviceId, profile.groupId),
        )
    }

    private suspend fun storageWarningBeforeSync(profile: LocalMeshProfile): StorageSyncWarning? {
        val bindings = database.syncDao().configuredBindings(identity.deviceId, profile.groupId)
        val names = bindings.associate { binding ->
            binding.folderId to (database.syncDao().getFolder(binding.folderId)?.displayName ?: "Folder")
        }
        return storageCapacity.warningBeforeSync(bindings, lowStorageApprovals, names)
    }

    private fun showStorageWarningStatus(warning: StorageSyncWarning) {
        eventNotifications.showStorageWarning(warning)
        val lowestAvailable = warning.destinations.minOfOrNull { it.availableBytes } ?: 0L
        when (warning) {
            is StorageSyncWarning.Low -> setStatus(
                "Low storage · approval required",
                "${formatStorageBytes(lowestAvailable)} free · open SyncDroid-Mesh to continue",
            )
            is StorageSyncWarning.Full -> setStatus(
                "Incoming sync paused · storage full",
                "Free storage space before receiving more files",
            )
        }
    }

    private fun clearStorageWarning() {
        SyncServiceController.report(storageWarning = null)
        eventNotifications.clearStorageWarning()
    }

    private fun runPendingReconcileIfIdle() {
        if (activePeers.isNotEmpty() || !reconcilePending) return
        val force = pendingReconcileForce
        reconcilePending = false
        pendingReconcileForce = false
        reconcile(force)
    }

    private fun setStatus(title: String, detail: String) {
        statusTitle = title
        statusDetail = detail
        publishNotification()
    }

    private fun publishNotification() {
        val policy = DiscoveryPolicyStore(this).load()
        val foregroundDetail = if (
            SyncServiceController.appInForeground.value && runtime != null && activePeers.isEmpty() &&
            SyncServiceController.snapshot.value.storageWarning == null
        ) "Discovery stays active while SyncDroid-Mesh is open" else statusDetail
        val foregroundTitle = if (
            SyncServiceController.appInForeground.value && runtime != null && activePeers.isEmpty() &&
            SyncServiceController.snapshot.value.storageWarning == null
        ) "Looking for mesh devices" else statusTitle
        getSystemService(android.app.NotificationManager::class.java).notify(
            SyncServiceNotification.NOTIFICATION_ID,
            notification.build(
                foregroundTitle,
                foregroundDetail,
                policy,
                syncing = activePeers.isNotEmpty(),
                progress = aggregateTransferProgress(),
            ),
        )
        SyncServiceController.report(
            running = true,
            status = foregroundTitle,
            activePeerIds = activePeers.keys.toSet(),
            peerSyncProgress = activePeers.keys.associateWith { peerId ->
                peerTransferProgress[peerId]?.progressFraction()
            },
        )
    }

    private fun aggregateTransferProgress(): Float? {
        if (activePeers.isEmpty()) return null
        val progress = activePeers.keys.map { peerTransferProgress[it] ?: return null }
        if (progress.any { it.totalBytes <= 0L }) return null
        val totalBytes = progress.sumOf(MeshRuntimeEvent.TransferProgress::totalBytes)
        if (totalBytes <= 0L) return null
        return (progress.sumOf(MeshRuntimeEvent.TransferProgress::transferredBytes).toDouble() / totalBytes)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private fun formatTime(timeMillis: Long): String =
        DateFormat.getTimeFormat(this).format(Date(timeMillis))

    private data class RuntimeKey(
        val groupId: String,
        val intervalMinutes: Int,
        val windowSeconds: Long,
        val scheduledDiscoveryEnabled: Boolean,
        val syncAllowed: Boolean,
        val wifiConnected: Boolean,
        val wifiSsid: String?,
        val permissionGranted: Boolean,
    )

    companion object {
        const val ACTION_REFRESH = "com.syncdroid.app.action.REFRESH_BACKGROUND_SYNC"
        const val ACTION_PROPAGATE_MEMBERSHIP = "com.syncdroid.app.action.PROPAGATE_MEMBERSHIP"
        const val ACTION_PROPAGATE_CHAT = "com.syncdroid.app.action.PROPAGATE_CHAT"
        const val EXTRA_ADDED_DEVICE_ID = "com.syncdroid.app.extra.ADDED_DEVICE_ID"
        const val ACTION_CYCLE_INTERVAL = "com.syncdroid.app.action.CYCLE_DISCOVERY_INTERVAL"
        const val ACTION_CYCLE_WINDOW = "com.syncdroid.app.action.CYCLE_DISCOVERY_WINDOW"
    }
}

private fun <T> nextValue(current: T, values: List<T>): T {
    val index = values.indexOf(current)
    return values[(if (index < 0) 0 else index + 1) % values.size]
}

private fun MeshRuntimeEvent.TransferProgress.progressFraction(): Float? =
    totalBytes.takeIf { it > 0L }?.let { total ->
        (transferredBytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }
