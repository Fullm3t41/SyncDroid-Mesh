package com.syncdeck.app.mesh

import com.syncdroid.shared.protocol.PairingCompletionMessage
import com.syncdroid.shared.sync.MeshRouteCandidate
import com.syncdroid.shared.sync.initialMeshFanoutTargets
import com.syncdroid.shared.sync.propagationFanoutTargets
import com.syncdroid.shared.update.MeshUpdateCache
import com.syncdroid.shared.discovery.MeshLanDiscovery
import com.syncdroid.shared.cloud.CloudProvider
import com.syncdroid.shared.cloud.CloudSyncTrigger
import com.syncdroid.shared.cloud.PairingFolderKeyCrypto
import com.syncdeck.app.model.MeshPeer
import com.syncdeck.app.platform.AppPreferences
import com.syncdeck.app.platform.LinuxWifi
import java.io.Closeable
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.file.Path
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

data class VisiblePairingOffer(val code: String, val expiresAtMillis: Long)

data class MeshRuntimeState(
    val localDeviceId: String = "",
    val profile: MeshProfile? = null,
    val peers: List<MeshPeer> = emptyList(),
    val folders: List<MeshFolder> = emptyList(),
    val chatMessages: List<MeshChatMessage> = emptyList(),
    val fileHistory: List<FileHistoryEvent> = emptyList(),
    val currentWifiName: String? = null,
    val registeredWifiNames: Set<String> = emptySet(),
    val cloudAccounts: List<CloudAccountStatus> = emptyList(),
    val status: String = "Ready to connect",
    val pairingOffer: VisiblePairingOffer? = null,
    val busy: Boolean = false,
    val attemptsRemaining: Int = 5,
    val error: String? = null,
)

class MeshRuntime(
    private val preferences: AppPreferences,
    private val deviceName: () -> String,
    private val store: MeshStore = MeshStore(),
    private val identity: LinuxDeviceIdentity = LinuxDeviceIdentity(),
    private val updateCache: MeshUpdateCache? = null,
    initiallyForeground: Boolean = true,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lanDiscovery = PairingLanDiscovery(identity.deviceId)
    private val bonjour = BonjourDiscovery(identity.deviceId)
    private val lanMeshPeers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    private val mutableState = MutableStateFlow(MeshRuntimeState())
    val state: StateFlow<MeshRuntimeState> = mutableState.asStateFlow()
    private var pairingServer: MeshPeerServer? = null
    private var meshServer: MeshPeerServer? = null
    private var meshLanDiscovery: MeshLanDiscovery? = null
    private var meshLanCollector: Job? = null
    private var meshPort: Int? = null
    private val activeSessions = ConcurrentHashMap.newKeySet<String>()
    private val sessionProgress = ConcurrentHashMap<String, Float>()
    private val automaticallyContactedPeers = ConcurrentHashMap.newKeySet<String>()
    private val lastSessionAtMillis = ConcurrentHashMap<String, Long>()
    private val retiredPeerServers = ConcurrentHashMap.newKeySet<MeshPeerServer>()
    private val connectionJobs = ConcurrentHashMap<String, Job>()
    private val stableConnections = ConcurrentHashMap.newKeySet<AuthenticatedPeerConnection>()
    private val outboundSockets = PeerSocketTracker()
    private val syncMutex = Mutex()
    private val fileHistory = FileHistoryRepository(store, identity.deviceId)
    private val chatAttachments = ChatAttachmentStore(store)
    private val cloud = DesktopCloudSync(preferences, store, identity, syncMutex,
        automaticAllowed = { backgroundWifiAllowed(LinuxWifi.currentSsid()) }) { cloudStatus ->
        mutableState.value = mutableState.value.copy(status = cloudStatus, busy = false)
    }
    private var expiryJob: Job? = null
    private var backgroundScheduleJob: Job? = null
    private var discoveryRetryJob: Job? = null
    private val closeStarted = AtomicBoolean(false)
    @Volatile private var windowForeground = initiallyForeground
    @Volatile private var discoveryActive = false
    @Volatile private var closing = false

    init {
        fileHistory.cleanupExpired()
        refresh("Ready to connect")
        store.profile()?.let(::startMeshNetworking)
        scope.launch { setDiscoveryActive(initiallyForeground) }
        scope.launch {
            combine(bonjour.peers, lanMeshPeers, bonjour.pairingOffers, lanDiscovery.offers) { peers, fallback, _, _ ->
                mergeDiscoveredPeers(fallback, peers)
            }
                .collectLatest { peers ->
                    refresh(mutableState.value.status)
                    if (!discoveryActive) return@collectLatest
                    delay(ROUTING_SETTLE_MILLIS)
                    val profile = store.profile() ?: return@collectLatest
                    connectToAvailablePeers(profile, peers.values, initiatorOrdering = true)
                }
        }
        scope.launch {
            while (isActive) {
                mutableState.value = mutableState.value.copy(
                    currentWifiName = LinuxWifi.currentSsid(),
                    registeredWifiNames = preferences.registeredWifiNames,
                )
                delay(if (windowForeground) FOREGROUND_WIFI_POLL_MILLIS else BACKGROUND_WIFI_POLL_MILLIS)
            }
        }
        scope.launch {
            while (isActive && !closing) {
                if ((windowForeground || preferences.alwaysOnDiscovery) && backgroundWifiAllowed(LinuxWifi.currentSsid())) {
                    runCatching { cloud.sync(CloudSyncTrigger.SCHEDULED_WINDOW) }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else report(it) }
                }
                delay(60_000)
            }
        }
        if (!windowForeground) restartBackgroundSchedule()
    }

    fun setWindowForeground(foreground: Boolean) {
        if (windowForeground == foreground) return
        windowForeground = foreground
        if (foreground) {
            backgroundScheduleJob?.cancel()
            backgroundScheduleJob = null
            scope.launch {
                setDiscoveryActive(true)
                val profile = store.profile()
                refresh(if (profile == null) "Ready to connect" else "Discovery active while SyncDeck is open")
                if (profile != null) connectToAvailablePeers(profile, discoveredPeers().values, initiatorOrdering = false)
            }
        } else {
            restartBackgroundSchedule()
        }
    }

    fun discoveryScheduleChanged() {
        if (!windowForeground) restartBackgroundSchedule()
    }

    /** Stops accepting new work, then preserves any authenticated transfer until it completes. */
    suspend fun closeAfterActiveTransfers() {
        windowForeground = false
        setDiscoveryActive(false)
        // Cloud runs use their own mutex. Finish them before cancelling their scheduling job.
        cloud.stopAndDrain()
        backgroundScheduleJob?.cancel()
        backgroundScheduleJob = null
        setDiscoveryActive(false)
        connectionJobs
            .filterKeys { it !in activeSessions }
            .values
            .forEach(Job::cancel)
        while (activeSessions.isNotEmpty() || syncMutex.isLocked) {
            closeStalledPeerConnections()
            delay(500)
        }
        close()
    }

    fun registerCurrentWifi() {
        val current = LinuxWifi.currentSsid() ?: mutableState.value.currentWifiName
        if (current.isNullOrBlank()) {
            report(IllegalStateException("Connect this device to Wi-Fi before adding the network"))
            return
        }
        preferences.registeredWifiNames = preferences.registeredWifiNames + current
        mutableState.value = mutableState.value.copy(
            currentWifiName = current,
            registeredWifiNames = preferences.registeredWifiNames,
            error = null,
        )
        if (!windowForeground) restartBackgroundSchedule()
    }

    fun removeRegisteredWifi(name: String) {
        preferences.registeredWifiNames = preferences.registeredWifiNames - name
        mutableState.value = mutableState.value.copy(registeredWifiNames = preferences.registeredWifiNames)
        if (!windowForeground) restartBackgroundSchedule()
    }

    fun createMesh(groupName: String) = scope.launch {
        updateBusy("Creating encrypted mesh identity…")
        runCatching { store.createMesh(groupName, deviceName(), identity) }
            .onSuccess {
                startMeshNetworking(it)
                refresh("Mesh ready · add another device when you’re ready")
            }
            .onFailure(::report)
    }

    fun createPairingOffer() = scope.launch {
        val profile = store.profile()
        if (profile == null) {
            report(IllegalStateException("Start a mesh before adding another device"))
            return@launch
        }
        updateBusy("Opening a secure pairing offer…")
        runCatching {
            stopPairingOffer()
            val offer = PairingCodeOffer.create()
            val attempts = AtomicInteger(0)
            val server = MeshPeerServer(DeviceTlsContext(identity, allowUnknownPeer = true)) { connection ->
                require(!offer.expired() && attempts.incrementAndGet() <= 5) { "Pairing offer expired" }
                val handshake = PairingHandshake(
                    PairingRole.Inviter,
                    offer.invitationId,
                    offer.code,
                    PairingIdentity.from(identity, deviceName()),
                )
                val result = PairingConnectionProtocol(connection, handshake).run()
                completeInvitation(profile, connection, result)
            }
            val port = server.start()
            pairingServer = server
            lanDiscovery.advertise(port, offer.invitationId)
            bonjour.advertisePairing(port, offer.invitationId)
            mutableState.value = mutableState.value.copy(
                status = "Pairing code ready",
                pairingOffer = VisiblePairingOffer(offer.code, offer.expiresAtMillis),
                busy = false,
                error = null,
            )
            expiryJob = scope.launch {
                delay((offer.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0))
                stopPairingOffer()
                refresh("Pairing code expired")
            }
        }.onFailure(::report)
    }

    fun joinMesh(code: String) = scope.launch {
        if (!code.matches(Regex("\\d{6}"))) {
            report(IllegalArgumentException("Enter all six digits")); return@launch
        }
        val limiter = preferences.pairingAttemptState().normalized()
        if (limiter.locked) {
            mutableState.value = mutableState.value.copy(
                attemptsRemaining = 0,
                error = "0 attempts remaining.",
            )
            return@launch
        }
        updateBusy("Searching this Wi-Fi for a matching pairing offer…")
        runCatching {
            val offers = withTimeout(12_000) {
                while (isActive) {
                    val found = (bonjour.pairingOffers.value + lanDiscovery.offers.value).values.distinctBy {
                        Triple(it.invitationId, it.address.hostAddress, it.port)
                    }
                    if (found.isNotEmpty()) return@withTimeout found
                    delay(150)
                }
                emptyList()
            }
            var lastError: Throwable? = null
            for (offer in offers) {
                val result = runCatching { withTimeout(35_000) { joinOffer(offer, code) } }
                if (result.isSuccess) {
                    preferences.clearPairingAttempts()
                    store.profile()?.let(::startMeshNetworking)
                    refresh("Joined ${store.profile()?.groupName.orEmpty()}")
                    return@launch
                }
                lastError = result.exceptionOrNull()
            }
            throw IllegalArgumentException("The code did not match a nearby pairing offer", lastError)
        }.onFailure {
            val next = preferences.recordPairingFailure()
            mutableState.value = mutableState.value.copy(
                busy = false,
                attemptsRemaining = next.attemptsRemaining,
                error = "${next.attemptsRemaining} attempts remaining.",
            )
        }
    }

    fun dismissError() { mutableState.value = mutableState.value.copy(error = null) }

    fun removeDevice(deviceId: String) = scope.launch {
        val profile = store.profile() ?: return@launch
        val target = store.devices(profile.groupId).firstOrNull { it.deviceId == deviceId && it.trusted }
        if (target == null || target.deviceId == identity.deviceId) {
            report(IllegalArgumentException("This mesh device is no longer available"))
            return@launch
        }
        updateBusy("Removing ${target.displayName} from the mesh…")
        runCatching {
            val events = store.membershipEvents(profile.groupId)
            val event = MembershipEvent.createRemoveDevice(
                profile.groupId,
                target.displayName,
                decodePublicKey(target.identityPublicKeyBase64),
                identity,
                events.map(MembershipEvent::eventId),
                events.fold(VersionVector()) { version, item -> version.merge(item.version) }.increment(identity.deviceId),
            )
            store.applyMembership(profile.groupName, event)
            connectionJobs.remove(deviceId)?.cancel()
            refresh("${target.displayName} removed from the mesh")
            connectToAvailablePeers(profile, discoveredPeers().values, initiatorOrdering = false)
        }.onFailure(::report)
    }

    fun configureFolder(folderId: String, localPath: Path) = scope.launch {
        runCatching {
            store.configureFolder(folderId, identity.deviceId, localPath)
            refresh("Folder configured")
            store.profile()?.let { connectToAvailablePeers(it, discoveredPeers().values, initiatorOrdering = false) }
        }.onFailure(::report)
    }

    fun syncNow() = scope.launch {
        val profile = store.profile() ?: return@launch
        setDiscoveryActive(true)
        delay(ROUTING_SETTLE_MILLIS)
        updateBusy("Looking for trusted devices…")
        runCatching { cloud.sync(CloudSyncTrigger.MANUAL) }.onFailure(::report)
        val discovered = discoveredPeers()
        connectToAvailablePeers(profile, discovered.values, initiatorOrdering = false)
        if (discovered.isEmpty()) refresh("No trusted devices are currently online")
        if (!windowForeground) restartBackgroundSchedule()
    }

    fun syncCloudNow() = scope.launch {
        updateBusy("Syncing cloud files…")
        runCatching { cloud.sync(CloudSyncTrigger.MANUAL) }
            .onSuccess { result -> refresh("Cloud sync finished · ${result.uploadedFiles} up, ${result.downloadedFiles} down, ${result.conflicts} conflicts") }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else report(it) }
    }

    fun connectCloud(provider: CloudProvider) = scope.launch {
        updateBusy("Connecting ${provider.displayName}…")
        runCatching { cloud.connect(provider) }
            .onSuccess { refresh("${provider.displayName} connected") }
            .onFailure(::report)
    }

    fun disconnectCloud(provider: CloudProvider) {
        cloud.disconnect(provider)
        refresh("${provider.displayName} disconnected")
    }

    fun sendChat(body: String) = scope.launch {
        val profile = store.profile()
        if (profile == null) {
            report(IllegalStateException("Join a mesh before sending a message"))
            return@launch
        }
        runCatching {
            val message = MeshChatMessage.create(profile.groupId, body, identity)
            check(store.applyChat(message)) { "This message is already in the mesh" }
            refresh("Message ready to sync")
            connectToAvailablePeers(
                profile,
                discoveredPeers().values,
                initiatorOrdering = false,
                maxFanout = Int.MAX_VALUE,
            )
        }.onFailure(::report)
    }

    fun sendChatAttachment(source: Path, body: String = "") =
        sendChatAttachments(listOf(source), body)

    fun sendChatAttachments(sources: List<Path>, body: String = "") = scope.launch {
        val profile = store.profile()
        if (profile == null) {
            report(IllegalStateException("Join a mesh before sending an attachment"))
            return@launch
        }
        val files = sources.map(Path::toAbsolutePath).map(Path::normalize).distinct()
        if (files.isEmpty()) return@launch
        updateBusy("Preparing attachment…")
        val errors = mutableListOf<Throwable>()
        var preparedCount = 0
        files.forEachIndexed { index, source ->
            runCatching {
                val createdAtMillis = System.currentTimeMillis() + index
                val attachment = chatAttachments.describe(source, createdAtMillis)
                val message = MeshChatMessage.create(profile.groupId, body, identity, createdAtMillis, attachment)
                chatAttachments.import(message, source)
                check(store.applyChat(message)) { "This attachment is already in the mesh" }
                preparedCount++
            }.onFailure(errors::add)
        }
        if (preparedCount > 0) {
            refresh(if (preparedCount == 1) "Attachment ready to sync" else "$preparedCount attachments ready to sync")
            connectToAvailablePeers(
                profile, discoveredPeers().values, initiatorOrdering = false, maxFanout = Int.MAX_VALUE,
            )
        }
        errors.firstOrNull()?.let(::report)
    }

    fun chatAttachmentPath(messageId: String): Path? {
        val profile = store.profile() ?: return null
        val message = store.chatMessage(profile.groupId, messageId) ?: return null
        return chatAttachments.localPath(message)
    }

    suspend fun filesForManagement(folderId: String): List<ManagedFile> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        syncMutex.withLock { FileSyncEngine(store, identity, requireNotNull(store.profile())).managedFiles(folderId) }
    }

    suspend fun restoreManagedFile(folderId: String, relativePath: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        syncMutex.withLock {
            FileSyncEngine(store, identity, requireNotNull(store.profile())).allowFileSyncAgain(folderId, relativePath)
            refresh("File can download again on the next sync")
        }
    }

    suspend fun deleteManagedFile(folderId: String, relativePath: String, allDevices: Boolean) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        syncMutex.withLock {
            val engine = FileSyncEngine(store, identity, requireNotNull(store.profile()))
            if (allDevices) engine.deleteFromAllDevices(folderId, listOf(relativePath))
            else engine.deleteFromThisDevice(folderId, relativePath)
            refresh(if (allDevices) "Deletion saved · applies on the next sync" else "File removed from this device · other copies are kept")
        }
    }

    suspend fun deleteFilesFromAllDevices(folderId: String, paths: List<String>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        syncMutex.withLock {
            val profile = requireNotNull(store.profile())
            FileSyncEngine(store, identity, profile).deleteFromAllDevices(folderId, paths)
            refresh("Deletion saved · other devices will apply it on their next sync")
        }
    }

    fun recoverFile(eventId: String) = scope.launch {
        val profile = store.profile() ?: return@launch
        updateBusy("Recovering file…")
        runCatching {
            syncMutex.withLock {
                val path = fileHistory.recover(eventId, profile)
                store.historyEvent(eventId)?.folderId?.let { folderId ->
                    if (store.localActiveSyncException(folderId, path, identity.deviceId)) {
                        store.recordSyncException(folderId, path, active = false, signer = identity)
                    }
                }
                FileSyncEngine(store, identity, profile).scanConfiguredFolders(recordHistory = false)
            }
            refresh("File recovered · ready to sync")
            connectToAvailablePeers(profile, discoveredPeers().values, initiatorOrdering = false)
        }.onFailure(::report)
    }

    fun declineFolder(folderId: String) = scope.launch {
        runCatching {
            store.declineFolder(folderId, identity.deviceId)
            refresh("Folder declined on this device")
        }.onFailure(::report)
    }

    private suspend fun completeInvitation(
        profile: MeshProfile,
        connection: AuthenticatedPeerConnection,
        pairing: PairingResult,
    ) {
        val parents = store.membershipEvents(profile.groupId)
        val version = parents.fold(VersionVector()) { merged, event -> merged.merge(event.version) }.increment(identity.deviceId)
        val add = MembershipEvent.createAddDevice(
            profile.groupId,
            pairing.remoteIdentity.displayName,
            pairing.remoteIdentity.decodePublicKey(),
            identity,
            parents.map { it.eventId },
            version,
        )
        store.applyMembership(profile.groupName, add)
        store.recordTlsKey(profile.groupId, pairing.remoteIdentity.deviceId, connection.peerTlsIdentity.publicKeySpki)
        connection.send(
            PairingCompletionCodec.encode(
                PairingCompletionMessage.Complete(
                    profile.groupId,
                    profile.groupName,
                    MeshWireCodec.encode(store.exportBundle()),
                    cloud.pairingKeys(profile).map { PairingFolderKeyCrypto.wrap(it, pairing.sessionKey) },
                ),
            ),
        )
        require(PairingCompletionCodec.decode(connection.receive()) == PairingCompletionMessage.Ack)
        stopPairingOffer()
        refresh("Paired with ${pairing.remoteIdentity.displayName}")
        connectToAvailablePeers(
            profile,
            discoveredPeers().values,
            initiatorOrdering = false,
            sourcePeerId = pairing.remoteIdentity.deviceId,
        )
    }

    private suspend fun joinOffer(offer: PairingOffer, code: String) {
        MeshPeerClient(DeviceTlsContext(identity, allowUnknownPeer = true), outboundSockets)
            .connect(offer.address, offer.port).use { connection ->
            val handshake = PairingHandshake(
                PairingRole.Joiner,
                offer.invitationId,
                code,
                PairingIdentity.from(identity, deviceName()),
            )
            val result = PairingConnectionProtocol(connection, handshake).run()
            require(result.remoteIdentity.deviceId == offer.deviceId) { "Pairing identity differs from discovery" }
            val completion = PairingCompletionCodec.decode(connection.receive()) as? PairingCompletionMessage.Complete
                ?: error("Existing device did not finish pairing")
            val bundle = MeshWireCodec.decode(completion.meshBundle)
            require(bundle.membershipEvents.any { it.subjectDeviceId == identity.deviceId }) {
                "Pairing response did not authorize this device"
            }
            val profile = store.importBundle(
                bundle,
                expectedOfferingIdentity = result.remoteIdentity,
                requiredLocalDeviceId = identity.deviceId,
            )
            completion.folderKeys.forEach { wrapped ->
                cloud.importPairingKey(PairingFolderKeyCrypto.unwrap(wrapped, result.sessionKey))
            }
            require(profile.groupId == completion.groupId && profile.groupName == completion.groupName)
            store.recordTlsKey(profile.groupId, result.remoteIdentity.deviceId, connection.peerTlsIdentity.publicKeySpki)
            connection.send(PairingCompletionCodec.encode(PairingCompletionMessage.Ack))
        }
    }

    private fun refresh(status: String) {
        val profile = store.profile()
        val discovered = discoveredPeers()
        val peers = profile?.let { mesh ->
            store.devices(mesh.groupId)
                .filter { it.trusted && it.deviceId != identity.deviceId }
                .map { device ->
                    val live = discovered[device.deviceId]
                    if (live != null) store.markSeen(mesh.groupId, device.deviceId, live.lastSeenAtMillis)
                    MeshPeer(
                        device.deviceId,
                        device.displayName,
                        live != null || device.deviceId in activeSessions,
                        live?.lastSeenAtMillis ?: device.lastSeenAtMillis,
                        syncing = device.deviceId in activeSessions,
                        syncProgress = sessionProgress[device.deviceId],
                    )
                }
        }.orEmpty()
        mutableState.value = mutableState.value.copy(
            localDeviceId = identity.deviceId,
            profile = profile,
            peers = peers,
            folders = profile?.let { store.folders(it.groupId, identity.deviceId) }.orEmpty(),
            chatMessages = profile?.let { store.chatMessages(it.groupId) }.orEmpty(),
            fileHistory = store.fileHistory(),
            currentWifiName = LinuxWifi.currentSsid() ?: mutableState.value.currentWifiName,
            registeredWifiNames = preferences.registeredWifiNames,
            cloudAccounts = cloud.accounts(),
            status = status,
            busy = false,
            attemptsRemaining = preferences.pairingAttemptState().normalized().attemptsRemaining,
            error = null,
        )
    }

    private fun startMeshNetworking(profile: MeshProfile) {
        if (meshServer != null) return
        val server = MeshPeerServer(DeviceTlsContext(identity, allowUnknownPeer = true)) { connection ->
            if (!discoveryActive) return@MeshPeerServer
            runStableSession(connection, profile)
        }
        val port = server.start()
        meshServer = server
        meshPort = port
        bonjour.advertiseMesh(port)
        runCatching { MeshLanDiscovery(identity.deviceId, profile.groupId) }.getOrNull()?.let { lan ->
            meshLanDiscovery = lan
            meshLanCollector = scope.launch {
                lan.peers.collectLatest { peers ->
                    lanMeshPeers.value = peers.mapValues { (_, peer) ->
                        DiscoveredPeer(peer.deviceId, peer.address, peer.port, peer.protocolMajor, peer.lastSeenAtMillis)
                    }
                }
            }
        }
        if (discoveryActive) setDiscoveryActive(true)
    }

    private suspend fun runStableSession(connection: AuthenticatedPeerConnection, profile: MeshProfile) {
        stableConnections += connection
        try {
            val remoteId = StablePeerAuthenticator(store, identity, profile.groupId).authenticate(connection)
            if (!activeSessions.add(remoteId)) return
            updatePeerSyncState(remoteId)
            try {
                syncMutex.withLock {
                    var transferred = 0L
                    var incomingTransferred = 0L
                    var incomingTotal = 0L
                    var displayedPercent = -1
                    val startedAt = System.nanoTime()
                    mutableState.value = mutableState.value.copy(status = "Scanning configured folders…")
                    val result = MeshFileSyncSession(
                        store = store,
                        identity = identity,
                        profile = profile,
                        updateCache = updateCache,
                        onBytesTransferred = { bytes ->
                            transferred += bytes
                            val seconds = ((System.nanoTime() - startedAt) / 1_000_000_000.0).coerceAtLeast(0.1)
                            mutableState.value = mutableState.value.copy(
                                status = "Syncing files · ${formatTransferRate((transferred / seconds).toLong())}",
                            )
                        },
                        onIncomingTransferPlanned = { total ->
                            incomingTotal = total
                            displayedPercent = -1
                            sessionProgress.remove(remoteId)
                            updatePeerSyncState(remoteId)
                        },
                        onIncomingBytesTransferred = { bytes ->
                            incomingTransferred += bytes
                            if (incomingTotal > 0L) {
                                val progress = (incomingTransferred.toDouble() / incomingTotal).toFloat().coerceIn(0f, 1f)
                                val percent = (progress * 100).toInt()
                                if (percent != displayedPercent) {
                                    displayedPercent = percent
                                    sessionProgress[remoteId] = progress
                                    updatePeerSyncState(remoteId)
                                }
                            }
                        },
                    ).runFiles(connection, remoteId)
                    lastSessionAtMillis[remoteId] = System.currentTimeMillis()
                    val conflicts = store.unresolvedConflicts().size
                    refresh(if (conflicts == 0) "Files synced" else "$conflicts file conflict${if (conflicts == 1) "" else "s"} need review")
                    if (result.appliedChangeCount > 0 || result.metadataChanged) {
                        connectToAvailablePeers(
                            profile,
                            discoveredPeers().values,
                            initiatorOrdering = false,
                            sourcePeerId = remoteId,
                        )
                    }
                }
                // Keep the peer authenticated and tracked, but let other peers sync files meanwhile.
                exchangeMeshUpdates(updateCache, identity.deviceId, remoteId, connection)
            } finally {
                sessionProgress.remove(remoteId)
                activeSessions.remove(remoteId)
                updatePeerSyncState(remoteId)
            }
        } finally {
            stableConnections.remove(connection)
        }
    }

    private fun updatePeerSyncState(peerId: String) {
        mutableState.value = mutableState.value.copy(
            peers = mutableState.value.peers.map { peer ->
                if (peer.deviceId == peerId) peer.copy(
                    syncing = peerId in activeSessions,
                    syncProgress = sessionProgress[peerId],
                ) else peer
            },
        )
    }

    private fun connectToAvailablePeers(
        profile: MeshProfile,
        peers: Collection<DiscoveredPeer>,
        initiatorOrdering: Boolean,
        sourcePeerId: String? = null,
        maxFanout: Int = ROUTING_FANOUT,
    ) {
        if (!discoveryActive) return
        val trustedIds = store.devices(profile.groupId)
            .filter(TrustedDevice::trusted)
            .mapTo(mutableSetOf(), TrustedDevice::deviceId)
        val available = peers.filter { it.protocolMajor == 2 && it.deviceId in trustedIds }
            .associateBy(DiscoveredPeer::deviceId)
        val targetIds = if (initiatorOrdering) {
            val remaining = (ROUTING_FANOUT - automaticallyContactedPeers.size).coerceAtLeast(0)
            initialMeshFanoutTargets(identity.deviceId, available.keys, ROUTING_FANOUT)
                .filterNot(automaticallyContactedPeers::contains)
                .take(remaining)
                .also { automaticallyContactedPeers.addAll(it) }
        } else {
            propagationFanoutTargets(
                localDeviceId = identity.deviceId,
                sourceDeviceId = sourcePeerId,
                peers = available.keys.map { deviceId ->
                    MeshRouteCandidate(
                        deviceId,
                        lastSessionAtMillis[deviceId] ?: 0L,
                        connectionJobs[deviceId]?.isActive == true || deviceId in activeSessions,
                    )
                },
                maxFanout = maxFanout,
            )
        }
        targetIds.mapNotNull(available::get).forEach { peer ->
            if (connectionJobs[peer.deviceId]?.isActive == true || peer.deviceId in activeSessions) return@forEach
            connectionJobs[peer.deviceId] = scope.launch {
                runCatching {
                    MeshPeerClient(DeviceTlsContext(identity, allowUnknownPeer = true), outboundSockets)
                        .connect(peer.address, peer.port)
                        .use { runStableSession(it, profile) }
                }.onFailure { reportSessionFailure(peer.deviceId, it) }
            }
        }
    }

    private fun reportSessionFailure(peerId: String, error: Throwable) {
        activeSessions.remove(peerId)
        automaticallyContactedPeers.remove(peerId)
        connectionJobs.remove(peerId)
        if (error is CancellationException) return
        val peerName = store.profile()
            ?.let { profile -> store.devices(profile.groupId).firstOrNull { it.deviceId == peerId } }
            ?.displayName
            ?: "Trusted device"
        val current = mutableState.value
        mutableState.value = if (error.isTransientPeerAvailabilityFailure()) {
            current.copy(status = "$peerName is unavailable · retrying automatically")
        } else {
            current.copy(
                status = "$peerName needs attention",
                error = error.message?.takeIf(String::isNotBlank) ?: "Secure mesh session failed",
            )
        }
    }

    private fun updateBusy(status: String) {
        mutableState.value = mutableState.value.copy(status = status, busy = true, error = null)
    }

    private fun report(error: Throwable) {
        mutableState.value = mutableState.value.copy(
            busy = false,
            error = error.message ?: "Mesh operation failed",
        )
    }

    private fun stopPairingOffer() {
        expiryJob?.cancel(); expiryJob = null
        pairingServer?.let { server ->
            server.close()
            retiredPeerServers += server
        }
        pairingServer = null
        lanDiscovery.stopAdvertising(); bonjour.stopPairingAdvertisement()
        mutableState.value = mutableState.value.copy(pairingOffer = null)
    }

    private fun restartBackgroundSchedule() {
        backgroundScheduleJob?.cancel()
        backgroundScheduleJob = scope.launch {
            while (isActive && !windowForeground) {
                val currentWifi = LinuxWifi.currentSsid()
                mutableState.value = mutableState.value.copy(
                    currentWifiName = currentWifi,
                    registeredWifiNames = preferences.registeredWifiNames,
                )
                if (!backgroundWifiAllowed(currentWifi)) {
                    if (windowForeground) break
                    suspendDiscoveryAndDrain()
                    refresh("Background discovery paused · connect to a registered Wi-Fi")
                    delay(BACKGROUND_WIFI_POLL_MILLIS)
                    continue
                }
                if (preferences.alwaysOnDiscovery) {
                    setDiscoveryActive(true)
                    refresh("Background discovery always on")
                    store.profile()?.let { profile ->
                        connectToAvailablePeers(profile, discoveredPeers().values, initiatorOrdering = false)
                    }
                    while (isActive && !windowForeground && preferences.alwaysOnDiscovery) delay(1_000)
                    continue
                }
                val now = LocalDateTime.now()
                val window = currentOrNextDiscoveryWindow(
                    now,
                    preferences.discoveryIntervalMinutes.coerceAtLeast(1),
                    preferences.discoveryWindowSeconds.coerceAtLeast(1),
                )
                val zone = ZoneId.systemDefault()
                val startMillis = window.start.atZone(zone).toInstant().toEpochMilli()
                val waitForStart = startMillis - System.currentTimeMillis()
                if (waitForStart > 0) {
                    if (windowForeground) break
                    suspendDiscoveryAndDrain()
                    refresh("Background sync scheduled · ${BACKGROUND_TIME_FORMAT.format(window.start)}")
                    val remainingToStart = startMillis - System.currentTimeMillis()
                    if (remainingToStart > 0) delay(remainingToStart)
                }
                if (windowForeground) break
                val endMillis = window.end.atZone(zone).toInstant().toEpochMilli()
                if (System.currentTimeMillis() >= endMillis) continue
                setDiscoveryActive(true)
                refresh("Background discovery window open")
                runCatching { cloud.sync(CloudSyncTrigger.SCHEDULED_WINDOW) }.onFailure(::report)
                store.profile()?.let { profile ->
                    connectToAvailablePeers(profile, discoveredPeers().values, initiatorOrdering = false)
                }
                val remaining = endMillis - System.currentTimeMillis()
                if (remaining > 0) delay(remaining)
                if (!windowForeground) suspendDiscoveryAndDrain()
            }
        }
    }

    private suspend fun suspendDiscoveryAndDrain() {
        setDiscoveryActive(false)
        connectionJobs
            .filterKeys { it !in activeSessions }
            .values
            .forEach(Job::cancel)
        waitForActiveWorkToFinish()
    }

    private suspend fun waitForActiveWorkToFinish() {
        while (
            !windowForeground &&
            (activeSessions.isNotEmpty() || syncMutex.isLocked)
        ) {
            closeStalledPeerConnections()
            delay(500)
        }
    }

    private fun closeStalledPeerConnections(nowMillis: Long = System.currentTimeMillis()) {
        val cutoff = nowMillis - PEER_INACTIVITY_TIMEOUT_MILLIS
        stableConnections.filter { it.inactiveSince(cutoff) }.forEach(AuthenticatedPeerConnection::close)
    }

    private fun discoveredPeers(): Map<String, DiscoveredPeer> =
        mergeDiscoveredPeers(lanMeshPeers.value, bonjour.peers.value)

    private fun backgroundWifiAllowed(currentWifi: String?): Boolean =
        currentWifi != null && currentWifi in preferences.registeredWifiNames

    @Synchronized
    private fun setDiscoveryActive(active: Boolean) {
        if (closing) return
        val changed = discoveryActive != active
        discoveryActive = active
        if (changed && active) automaticallyContactedPeers.clear()
        if (!active) {
            discoveryRetryJob?.cancel()
            discoveryRetryJob = null
            runCatching { lanDiscovery.setEnabled(false) }
            runCatching { bonjour.setEnabled(false) }
            meshLanDiscovery?.stop()
            return
        }
        if (applyDiscoveryStateLocked()) {
            discoveryRetryJob?.cancel()
            discoveryRetryJob = null
        } else if (discoveryRetryJob?.isActive != true) {
            discoveryRetryJob = scope.launch {
                var waitMillis = DISCOVERY_RETRY_INITIAL_MILLIS
                while (isActive) {
                    delay(waitMillis)
                    val ready = synchronized(this@MeshRuntime) {
                        closing || !discoveryActive || applyDiscoveryStateLocked()
                    }
                    if (ready) break
                    waitMillis = (waitMillis * 2).coerceAtMost(DISCOVERY_RETRY_MAX_MILLIS)
                }
            }
        }
    }

    private fun applyDiscoveryStateLocked(): Boolean {
        var ready = true
        val pairingNeeded = windowForeground || pairingServer != null
        val pairingResult = runCatching { lanDiscovery.setEnabled(pairingNeeded) }
        if (pairingResult.isFailure || (pairingNeeded && !lanDiscovery.isRunning)) ready = false
        val bonjourResult = runCatching { bonjour.setEnabled(true) }
        if (bonjourResult.isFailure || !bonjour.isRunning) ready = false
        meshPort?.let { port ->
            val lan = meshLanDiscovery
            if (lan == null || runCatching { lan.start(port) }.isFailure || !lan.isRunning) ready = false
        }
        return ready
    }

    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        closing = true
        discoveryActive = false
        val meshToAwait = meshServer
        backgroundScheduleJob?.cancel(); backgroundScheduleJob = null
        discoveryRetryJob?.cancel(); discoveryRetryJob = null
        stopPairingOffer(); meshServer?.close(); meshServer = null
        val serversToAwait = retiredPeerServers.toList() + listOfNotNull(meshToAwait)
        meshLanCollector?.cancel(); meshLanCollector = null
        meshLanDiscovery?.close(); meshLanDiscovery = null; meshPort = null
        connectionJobs.values.forEach(Job::cancel); connectionJobs.clear()
        outboundSockets.close()
        lanDiscovery.close(); bonjour.close()
        val runtimeJob = scope.coroutineContext[Job]
        scope.cancel()
        runBlocking {
            runtimeJob?.join()
            serversToAwait.forEach { it.awaitClosed() }
        }
        // Socket closure above makes blocking TLS operations terminate; only close SQLite after
        // every runtime and server job has stopped using it.
        retiredPeerServers.clear()
        store.close()
    }
}

private const val ROUTING_FANOUT = 2
private const val DISCOVERY_RETRY_INITIAL_MILLIS = 1_000L
private const val DISCOVERY_RETRY_MAX_MILLIS = 30_000L
private const val ROUTING_SETTLE_MILLIS = 750L
private const val FOREGROUND_WIFI_POLL_MILLIS = 10_000L
private const val BACKGROUND_WIFI_POLL_MILLIS = 60_000L

private fun mergeDiscoveredPeers(
    fallback: Map<String, DiscoveredPeer>,
    bonjour: Map<String, DiscoveredPeer>,
): Map<String, DiscoveredPeer> = (fallback.keys + bonjour.keys).associateWith { deviceId ->
    listOfNotNull(fallback[deviceId], bonjour[deviceId]).maxBy(DiscoveredPeer::lastSeenAtMillis)
}

private data class PairingCodeOffer(
    val code: String,
    val invitationId: String,
    val expiresAtMillis: Long,
) {
    fun expired() = System.currentTimeMillis() >= expiresAtMillis

    companion object {
        fun create(): PairingCodeOffer {
            val random = SecureRandom()
            return PairingCodeOffer(
                random.nextInt(1_000_000).toString().padStart(6, '0'),
                ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) },
                System.currentTimeMillis() + 5 * 60 * 1_000,
            )
        }
    }
}

private fun formatTransferRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L * 1024L -> "%.1f GB/s".format(bytesPerSecond / (1024.0 * 1024 * 1024))
    bytesPerSecond >= 1024L * 1024L -> "%.1f MB/s".format(bytesPerSecond / (1024.0 * 1024))
    bytesPerSecond >= 1024L -> "%.0f KB/s".format(bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond B/s"
}

internal fun Throwable.isTransientPeerAvailabilityFailure(): Boolean =
    generateSequence(this) { it.cause }.any { cause ->
        cause is ConnectException ||
            cause is NoRouteToHostException ||
            cause is SocketTimeoutException ||
            cause is SocketException ||
            cause is EOFException
    }

private val BACKGROUND_TIME_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
