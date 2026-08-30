package com.syncdroid.app.mesh

import android.content.Context
import android.util.Log
import com.syncdroid.app.data.SyncDroidDatabase
import com.syncdroid.app.data.LocalFolderBindingEntity
import com.syncdroid.app.scheduling.nextRendezvousStart
import com.syncdroid.app.sync.TransferRateSampler
import com.syncdroid.app.storage.StorageSyncWarning
import com.syncdroid.shared.sync.MeshRouteCandidate
import com.syncdroid.shared.sync.initialMeshFanoutTargets
import com.syncdroid.shared.sync.propagationFanoutTargets
import com.syncdroid.shared.protocol.MeshSessionMessage
import com.syncdroid.shared.discovery.MeshLanDiscovery
import com.syncdroid.shared.update.MeshUpdateCache
import java.io.Closeable
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

sealed interface MeshRuntimeEvent {
    data class DiscoveryWaiting(val nextWindowAtMillis: Long) : MeshRuntimeEvent
    data class DiscoveryActive(val windowEndsAtMillis: Long?) : MeshRuntimeEvent
    data class PresenceChanged(val peerIds: Set<String>) : MeshRuntimeEvent
    data class SyncStarted(val peerId: String, val peerName: String) : MeshRuntimeEvent
    data class TransferProgress(
        val peerId: String,
        val peerName: String,
        val bytesPerSecond: Long,
        val transferredBytes: Long,
        val totalBytes: Long,
    ) : MeshRuntimeEvent
    data class SyncCompleted(
        val peerId: String,
        val peerName: String,
        val folderIds: Set<String>,
        val storageWarning: StorageSyncWarning? = null,
    ) : MeshRuntimeEvent
    data class SyncFailed(
        val peerId: String,
        val peerName: String,
        val reason: String,
        val storageWarning: StorageSyncWarning? = null,
    ) : MeshRuntimeEvent
    data class ChatMessagesReceived(
        val count: Int,
        val authorName: String,
        val preview: String,
    ) : MeshRuntimeEvent
}

class MeshRuntime(
    context: Context,
    private val database: SyncDroidDatabase,
    private val identity: AndroidDeviceIdentity,
    private val groupId: String,
    private val groupName: String,
    private val rendezvousIntervalMinutes: Int = 3 * 60,
    private val scheduledDiscoveryEnabled: Boolean = true,
    private val rendezvousWindowSeconds: Long = 5 * 60,
    private val discoverImmediately: Boolean = false,
    private val appInForeground: StateFlow<Boolean>,
    private val updateCache: MeshUpdateCache? = null,
    private val onEvent: (MeshRuntimeEvent) -> Unit = {},
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activePeers = ConcurrentHashMap.newKeySet<String>()
    private val peerJobs = ConcurrentHashMap<String, Job>()
    private val peerEndpoints = ConcurrentHashMap<String, DiscoveredMeshPeer>()
    private val lastSessionAtMillis = ConcurrentHashMap<String, Long>()
    private val propagationSignals = Channel<PropagationSignal>(Channel.BUFFERED)
    private val sessionStateLock = Any()
    private val closeRequested = AtomicBoolean(false)
    private var acceptingDiscoveredSessions = false
    private var server: MeshPeerServer? = null
    private var discovery: MeshNsdDiscovery? = null

    suspend fun start() {
        check(server == null) { "Mesh runtime is already active" }
        val trusted = database.meshDao().trustedDevices(groupId)
        val tls = DeviceTlsContext.create(identity, trusted, allowUnknownPeer = true)
        val peerServer = MeshPeerServer(tls) { connection -> runSessionOnce(connection) }
        val port = peerServer.start()
        server = peerServer
        val client = MeshPeerClient(tls)
        scope.launch {
            var initialDiscoveryPending = discoverImmediately
            appInForeground.collectLatest { foreground ->
                if (shouldRunContinuousDiscovery(foreground, scheduledDiscoveryEnabled)) {
                    initialDiscoveryPending = false
                    onEvent(MeshRuntimeEvent.DiscoveryActive(windowEndsAtMillis = null))
                    runDiscoveryWindow(client, port, null)
                } else {
                    // Background discovery remains aligned to the configured rendezvous grid.
                    // A window is allowed to drain if it already started a sync.
                    if (initialDiscoveryPending) {
                        initialDiscoveryPending = false
                        onEvent(MeshRuntimeEvent.DiscoveryActive(System.currentTimeMillis() + rendezvousWindowSeconds * 1_000))
                        runDiscoveryWindow(client, port, rendezvousWindowSeconds * 1_000)
                    }
                    while (isActive) {
                        val now = ZonedDateTime.now()
                        val nextWindowAtMillis = nextRendezvousStart(now.toLocalDateTime(), rendezvousIntervalMinutes)
                            .atZone(now.zone)
                            .toInstant()
                            .toEpochMilli()
                        val delayMillis = (nextWindowAtMillis - System.currentTimeMillis()).coerceAtLeast(1)
                        onEvent(MeshRuntimeEvent.DiscoveryWaiting(nextWindowAtMillis))
                        delay(delayMillis)
                        onEvent(MeshRuntimeEvent.DiscoveryActive(System.currentTimeMillis() + rendezvousWindowSeconds * 1_000))
                        runDiscoveryWindow(client, port, rendezvousWindowSeconds * 1_000)
                    }
                }
            }
        }
    }

    private suspend fun runDiscoveryWindow(client: MeshPeerClient, port: Int, durationMillis: Long?) {
        val nsd = MeshNsdDiscovery(appContext, identity.deviceId)
        val lan = runCatching { MeshLanDiscovery(identity.deviceId, groupId).also { it.start(port) } }.getOrNull()
        val initialContacted = mutableSetOf<String>()
        val loggedLanPeerIds = mutableSetOf<String>()
        var latestPeers = emptyMap<String, DiscoveredMeshPeer>()
        synchronized(sessionStateLock) { acceptingDiscoveredSessions = true }
        discovery = nsd
        nsd.start(port)
        val collector = scope.launch {
            combine(nsd.peers, lan?.peers ?: flowOf(emptyMap())) { nsdPeers, lanPeers ->
                val fallbackPeers = lanPeers.mapValues { (_, peer) ->
                    DiscoveredMeshPeer(
                        peer.deviceId,
                        "LAN fallback",
                        peer.address,
                        peer.port,
                        peer.protocolMajor,
                        peer.lastSeenAtMillis,
                    )
                }
                mergeDiscoveredPeers(fallbackPeers, nsdPeers)
            }.collect { peers ->
                latestPeers = peers
                peers.forEach { (deviceId, peer) -> peerEndpoints[deviceId] = peer }
                peers.values.filter { it.serviceName == "LAN fallback" && loggedLanPeerIds.add(it.deviceId) }
                    .forEach { peer ->
                        Log.d(TAG, "LAN fallback discovered ${peer.deviceId.take(8)} at ${peer.address.hostAddress}:${peer.port}")
                    }
                delay(ROUTING_SETTLE_MILLIS)
                val removed = peerJobs.keys - peers.keys
                removed.forEach { id ->
                    if (id !in activePeers) peerJobs.remove(id)?.cancel()
                }
                val trustedIds = database.meshDao().trustedDevices(groupId)
                    .mapTo(mutableSetOf()) { it.deviceId }
                val eligible = peers.filterValues { it.protocolMajor == 2 && it.deviceId in trustedIds }
                onEvent(MeshRuntimeEvent.PresenceChanged(eligible.keys))
                val remainingFanout = (ROUTING_FANOUT - initialContacted.size).coerceAtLeast(0)
                val targets = initialMeshFanoutTargets(identity.deviceId, eligible.keys, ROUTING_FANOUT)
                    .filterNot(initialContacted::contains)
                    .take(remainingFanout)
                targets.forEach { deviceId ->
                    initialContacted += deviceId
                    launchPeerConnection(client, requireNotNull(eligible[deviceId]), retry = true)
                }
            }
        }
        val propagationWorker = scope.launch {
            for (signal in propagationSignals) {
                delay(PROPAGATION_COALESCE_MILLIS)
                val trustedIds = database.meshDao().trustedDevices(groupId)
                    .mapTo(mutableSetOf()) { it.deviceId }
                val available = latestPeers.filterValues {
                    it.protocolMajor == 2 && it.deviceId in trustedIds
                }
                val targets = propagationFanoutTargets(
                    localDeviceId = identity.deviceId,
                    sourceDeviceId = signal.sourcePeerId,
                    peers = available.keys.map { deviceId ->
                        MeshRouteCandidate(
                            deviceId = deviceId,
                            lastSessionAtMillis = lastSessionAtMillis[deviceId] ?: 0L,
                            active = deviceId in activePeers || peerJobs[deviceId]?.isActive == true,
                        )
                    },
                    maxFanout = signal.maxFanout,
                )
                targets.forEach { deviceId ->
                    available[deviceId]?.let { launchPeerConnection(client, it, retry = false) }
                }
            }
        }
        try {
            if (durationMillis == null) collector.join() else delay(durationMillis)
        } finally {
            // A timed window ending, or the app moving to the background, must not
            // tear down an established transfer. Keep NSD advertised until every
            // active session has drained, then atomically stop admitting new ones.
            withContext(NonCancellable) {
                awaitActiveSyncsBeforeDiscoveryShutdown()
                collector.cancelAndJoin()
                propagationWorker.cancelAndJoin()
                peerJobs.values.forEach(Job::cancel)
                peerJobs.clear()
                peerEndpoints.clear()
                onEvent(MeshRuntimeEvent.PresenceChanged(emptySet()))
                lan?.close()
                nsd.close()
                if (discovery === nsd) discovery = null
            }
        }
    }

    fun propagateMembershipChange(sourcePeerId: String) {
        propagationSignals.trySend(PropagationSignal(sourcePeerId))
    }

    fun propagateLocalChatChange(): Boolean = synchronized(sessionStateLock) {
        acceptingDiscoveredSessions
    }.also { active ->
        if (active) propagationSignals.trySend(PropagationSignal(identity.deviceId, Int.MAX_VALUE))
    }

    private fun launchPeerConnection(
        client: MeshPeerClient,
        peer: DiscoveredMeshPeer,
        retry: Boolean,
    ) {
        if (peer.deviceId in activePeers || peerJobs[peer.deviceId]?.isActive == true) return
        peerJobs[peer.deviceId] = scope.launch {
            do {
                if (peer.deviceId in activePeers) break
                val endpoint = peerEndpoints[peer.deviceId] ?: peer
                val attempt = runCatching {
                    client.connect(endpoint.address, endpoint.port).use { connection ->
                        StablePeerAuthenticator(database, identity, groupId).authenticate(connection)
                        require(connection.peer.deviceId == peer.deviceId) {
                            "NSD identity does not match mesh identity"
                        }
                        runSessionOnce(connection, alreadyAuthenticated = true)
                    }
                }
                attempt.exceptionOrNull()?.let { error ->
                    if (error is PeerSessionBusyException) {
                        Log.d(TAG, "Peer ${peer.deviceId.take(8)} already has a session; retrying after collision backoff")
                    } else {
                        Log.w(TAG, "Could not connect to ${peer.deviceId.take(8)} at ${endpoint.address.hostAddress}:${endpoint.port}", error)
                    }
                }
                val connected = attempt.isSuccess
                if (connected || !retry) break
                val retryDelay = if (attempt.exceptionOrNull() is PeerSessionBusyException) {
                    if (identity.deviceId < peer.deviceId) LOWER_ID_COLLISION_RETRY_MILLIS
                    else HIGHER_ID_COLLISION_RETRY_MILLIS
                } else {
                    CONNECTION_RETRY_MILLIS
                }
                delay(retryDelay)
            } while (isActive)
        }
    }

    private suspend fun awaitActiveSyncsBeforeDiscoveryShutdown() {
        while (true) {
            val keepDiscoveryActive = synchronized(sessionStateLock) {
                shouldKeepDiscoveryActiveWhileSyncing(activePeers.size, closeRequested.get()).also { keepActive ->
                    if (!keepActive) acceptingDiscoveredSessions = false
                }
            }
            if (!keepDiscoveryActive) return
            delay(ACTIVE_SYNC_DRAIN_POLL_MILLIS)
        }
    }

    private suspend fun runSessionOnce(
        connection: AuthenticatedPeerConnection,
        alreadyAuthenticated: Boolean = false,
    ) {
        if (!alreadyAuthenticated) StablePeerAuthenticator(database, identity, groupId).authenticate(connection)
        val admitted = synchronized(sessionStateLock) {
            acceptingDiscoveredSessions && activePeers.add(connection.peer.deviceId)
        }
        if (!admitted) {
            runCatching {
                connection.send(MeshSessionCodec.encode(MeshSessionMessage.Error(SESSION_BUSY_REASON)))
            }
            return
        }
        val peerName = database.meshDao().getDevice(groupId, connection.peer.deviceId)?.displayName
            ?: connection.peer.deviceId.take(8)
        try {
            onEvent(MeshRuntimeEvent.SyncStarted(connection.peer.deviceId, peerName))
            val incomingTransferred = AtomicLong(0L)
            val incomingTotal = AtomicLong(0L)
            val currentRate = AtomicLong(0L)
            fun publishTransferProgress() {
                onEvent(MeshRuntimeEvent.TransferProgress(
                    connection.peer.deviceId,
                    peerName,
                    currentRate.get(),
                    incomingTransferred.get(),
                    incomingTotal.get(),
                ))
            }
            val rateSampler = TransferRateSampler { bytesPerSecond ->
                currentRate.set(bytesPerSecond)
                publishTransferProgress()
            }
            val result = MeshSyncSession(
                context = appContext,
                database = database,
                identity = identity,
                groupId = groupId,
                groupName = groupName,
                updateCache = updateCache,
                onBytesTransferred = rateSampler::record,
                onIncomingTransferPlanned = { totalBytes ->
                    incomingTotal.set(totalBytes)
                    publishTransferProgress()
                },
                onIncomingBytesTransferred = { bytes ->
                    incomingTransferred.addAndGet(bytes)
                    publishTransferProgress()
                },
            ).run(connection)
            lastSessionAtMillis[connection.peer.deviceId] = System.currentTimeMillis()
            if (result.appliedChangeCount > 0 || result.replicatedStateChanged) {
                propagationSignals.trySend(PropagationSignal(connection.peer.deviceId))
            }
            if (result.newChatMessages.isNotEmpty()) {
                val latest = result.newChatMessages.maxWith(
                    compareBy(MeshChatMessage::createdAtMillis, MeshChatMessage::messageId),
                )
                val authorName = database.meshDao().getDevice(groupId, latest.authorDeviceId)?.displayName
                    ?: latest.authorDeviceId.take(8)
                onEvent(MeshRuntimeEvent.ChatMessagesReceived(
                    count = result.newChatMessages.size,
                    authorName = authorName,
                    preview = latest.body,
                ))
            }
            val syncedFolders = database.syncDao().configuredBindings(identity.deviceId, groupId)
                .map(LocalFolderBindingEntity::folderId)
                .filterTo(mutableSetOf()) {
                    it !in result.storageBlockedFolderIds && database.syncDao().unresolvedConflictCount(it) == 0
                }
            onEvent(MeshRuntimeEvent.SyncCompleted(
                connection.peer.deviceId,
                peerName,
                syncedFolders,
                result.storageWarning,
            ))
        } catch (error: PeerSessionBusyException) {
            Log.d(TAG, "Concurrent session with $peerName was superseded")
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Mesh sync with $peerName failed", error)
            onEvent(MeshRuntimeEvent.SyncFailed(
                connection.peer.deviceId,
                peerName,
                error.message ?: "Unknown sync error",
            ))
            throw error
        } finally {
            synchronized(sessionStateLock) { activePeers.remove(connection.peer.deviceId) }
        }
    }

    override fun close() {
        closeRequested.set(true)
        synchronized(sessionStateLock) { acceptingDiscoveredSessions = false }
        discovery?.close()
        server?.close()
        peerJobs.values.forEach(Job::cancel)
        peerJobs.clear()
        peerEndpoints.clear()
        discovery = null
        server = null
        scope.cancel()
    }

    private companion object {
        const val ACTIVE_SYNC_DRAIN_POLL_MILLIS = 100L
        const val CONNECTION_RETRY_MILLIS = 5_000L
        const val LOWER_ID_COLLISION_RETRY_MILLIS = 150L
        const val HIGHER_ID_COLLISION_RETRY_MILLIS = 750L
        const val PROPAGATION_COALESCE_MILLIS = 300L
        const val ROUTING_SETTLE_MILLIS = 750L
        const val ROUTING_FANOUT = 2
        const val TAG = "SyncDroidMesh"
    }
}

private data class PropagationSignal(
    val sourcePeerId: String,
    val maxFanout: Int = 2,
)

private fun mergeDiscoveredPeers(
    fallback: Map<String, DiscoveredMeshPeer>,
    nsd: Map<String, DiscoveredMeshPeer>,
): Map<String, DiscoveredMeshPeer> = (fallback.keys + nsd.keys).associateWith { deviceId ->
    listOfNotNull(fallback[deviceId], nsd[deviceId]).maxBy(DiscoveredMeshPeer::lastSeenAtMillis)
}

internal fun shouldRunContinuousDiscovery(appInForeground: Boolean, scheduledDiscoveryEnabled: Boolean): Boolean =
    appInForeground || !scheduledDiscoveryEnabled

internal fun shouldKeepDiscoveryActiveWhileSyncing(activeSyncCount: Int, runtimeClosing: Boolean): Boolean =
    activeSyncCount > 0 && !runtimeClosing
