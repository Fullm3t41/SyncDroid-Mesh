package com.synctosh.app.mesh

import com.syncdroid.shared.protocol.FileTransferMessage
import com.syncdroid.shared.protocol.MeshSessionMessage
import com.syncdroid.shared.protocol.SessionFolderKey
import com.syncdroid.shared.cloud.FolderKeyMaterial
import com.syncdroid.shared.protocol.verifyEcdsaSha256
import com.syncdroid.shared.sync.ActiveTransferClaims
import com.syncdroid.shared.sync.activeTransferKey
import com.syncdroid.shared.update.MeshUpdateCache
import com.syncdroid.shared.update.MeshUpdateExchange
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException

fun StablePeerProof.isValid(): Boolean = runCatching {
    val key = decodePublicKey(publicKeyBase64)
    deviceIdFor(key) == deviceId && verifyEcdsaSha256(key, payload(), signatureBase64)
}.getOrDefault(false)

class StablePeerAuthenticator(
    private val store: MeshStore,
    private val identity: MacDeviceIdentity,
    private val groupId: String,
) {
    suspend fun authenticate(connection: AuthenticatedPeerConnection): String {
        connection.send(StablePeerProofCodec.encode(createProof()))
        val remote = StablePeerProofCodec.decode(connection.receive())
        require(remote.groupId == groupId && remote.isValid()) { "Peer identity proof is invalid" }
        val tlsKey = Base64.getEncoder().encodeToString(connection.peerTlsIdentity.publicKeySpki)
        require(remote.tlsPublicKeyBase64 == tlsKey) { "Peer proof is not bound to this TLS connection" }
        val member = store.devices(groupId).firstOrNull { it.deviceId == remote.deviceId }
            ?: error("Peer is not a member of this mesh")
        require(member.trusted && member.identityPublicKeyBase64 == remote.publicKeyBase64) { "Peer is not trusted" }
        if (member.tlsPublicKeyBase64 == null) {
            store.recordTlsKey(groupId, remote.deviceId, connection.peerTlsIdentity.publicKeySpki)
        } else {
            require(member.tlsPublicKeyBase64 == remote.tlsPublicKeyBase64) { "Pinned peer TLS key changed" }
        }
        return remote.deviceId
    }

    private fun createProof(): StablePeerProof {
        val publicKey = Base64.getEncoder().encodeToString(identity.publicKey.encoded)
        val unsigned = StablePeerProof(
            groupId,
            identity.deviceId,
            publicKey,
            publicKey,
            ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getEncoder().encodeToString(it) },
            "",
        )
        return unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(identity.sign(unsigned.payload())))
    }
}

object StablePeerProofCodec {
    fun encode(value: StablePeerProof): ByteArray =
        com.syncdroid.shared.protocol.StablePeerProofWireCodec.encode(value)

    fun decode(bytes: ByteArray): StablePeerProof =
        com.syncdroid.shared.protocol.StablePeerProofWireCodec.decode(bytes)
}

object MeshSessionCodec {
    fun encode(message: MeshSessionMessage): ByteArray =
        com.syncdroid.shared.protocol.MeshSessionWireCodec.encode(message)

    fun decode(bytes: ByteArray): MeshSessionMessage =
        com.syncdroid.shared.protocol.MeshSessionWireCodec.decode(bytes)
}

class MeshFileSyncSession(
    private val store: MeshStore,
    private val identity: MacDeviceIdentity,
    private val profile: MeshProfile,
    private val updateCache: MeshUpdateCache? = null,
    private val onBytesTransferred: (Long) -> Unit = {},
    private val onIncomingTransferPlanned: (Long) -> Unit = {},
    private val onIncomingBytesTransferred: (Long) -> Unit = {},
) {
    private val history = FileHistoryRepository(store, identity.deviceId)
    private val chatAttachments = ChatAttachmentStore(store)

    suspend fun run(connection: AuthenticatedPeerConnection, remoteDeviceId: String): MeshFileSyncResult {
        history.cleanupExpired()
        val metadataCountBefore = store.exportBundle().replicatedItemCount()
        connection.send(MeshSessionCodec.encode(MeshSessionMessage.Metadata(MeshWireCodec.encode(store.exportBundle()))))
        val remoteMetadata = connection.receiveSession<MeshSessionMessage.Metadata>()
        store.importBundle(MeshWireCodec.decode(remoteMetadata.bundle))
        exchangeFolderKeys(connection)
        val chatMessages = store.chatMessages(profile.groupId)
        chatAttachments.cleanupExpired(chatMessages)
        val missingAttachments = chatAttachments.missing(chatMessages)
        val metadataChanged = store.exportBundle().replicatedItemCount() > metadataCountBefore

        val engine = FileSyncEngine(store, identity, profile)
        engine.scanConfiguredFolders()
        connection.send(MeshSessionCodec.encode(MeshSessionMessage.Catalog(engine.buildCatalog(remoteDeviceId))))
        val remoteCatalog = connection.receiveSession<MeshSessionMessage.Catalog>().folders
        connection.send(MeshSessionCodec.encode(MeshSessionMessage.IndexBatch(engine.buildUpdatesForPeer(remoteCatalog))))
        val remoteUpdates = connection.receiveSession<MeshSessionMessage.IndexBatch>().updates
        val candidatePlans = engine.receiveIndexes(remoteDeviceId, remoteUpdates)
        val transferClaims = ActiveTransferClaims.claim(
            candidatePlans.filter { it.action == FileSyncAction.DownloadRemote && !it.remote.deleted }
                .map(FileSyncPlan::transferClaimKey),
        )
        try {
        val plans = candidatePlans.filter { plan ->
            plan.action != FileSyncAction.DownloadRemote || plan.remote.deleted ||
                transferClaims.owns(plan.transferClaimKey())
        }
        val prepared = plans.map { plan ->
            val root = engine.configuredRoot(plan.remote.folderId)
            val manifest = plan.remoteManifest.takeIf {
                plan.action == FileSyncAction.DownloadRemote && !plan.remote.deleted && root != null
            }
            val missingBlocks = manifest?.let {
                ResumableBlockReceiver(store, transferCache(), AtomicFileApplier(root!!, plan.expectedContent())).missingBlocks(it)
            }.orEmpty()
            val requestCount = when {
                plan.action != FileSyncAction.DownloadRemote || plan.remote.deleted || root == null -> 0
                manifest != null -> missingBlocks.size
                else -> 1
            }
            val expectedBytes = when {
                requestCount == 0 -> 0L
                manifest != null -> manifest.blocks
                    .filter { it.index in missingBlocks }
                    .sumOf { it.sizeBytes.toLong() }
                else -> plan.remote.sizeBytes
            }
            PreparedDownload(plan, manifest, requestCount, expectedBytes)
        }
        val localRequests = prepared.sumOf(PreparedDownload::requestCount) + missingAttachments.size
        onIncomingTransferPlanned(
            prepared.sumOf(PreparedDownload::expectedBytes) +
                missingAttachments.sumOf { it.attachment?.sizeBytes ?: 0L },
        )
        connection.send(MeshSessionCodec.encode(MeshSessionMessage.TransferPlan(localRequests)))
        val remoteRequests = connection.receiveSession<MeshSessionMessage.TransferPlan>().requestCount

        val appliedChangeCount = if (identity.deviceId < remoteDeviceId) {
            val count = downloadPhase(connection, remoteDeviceId, prepared, missingAttachments, engine)
            connection.send(MeshSessionCodec.encode(MeshSessionMessage.PhaseDone))
            serveRequests(connection, remoteRequests, engine)
            connection.receiveSession<MeshSessionMessage.PhaseDone>()
            count
        } else {
            serveRequests(connection, remoteRequests, engine)
            connection.receiveSession<MeshSessionMessage.PhaseDone>()
            val count = downloadPhase(connection, remoteDeviceId, prepared, missingAttachments, engine)
            connection.send(MeshSessionCodec.encode(MeshSessionMessage.PhaseDone))
            count
        }
        store.markSeen(profile.groupId, remoteDeviceId)
        updateCache?.let { cache ->
            runCatching {
                MeshUpdateExchange(cache).run(
                    localDeviceId = identity.deviceId,
                    remoteDeviceId = remoteDeviceId,
                    send = { connection.send(MeshSessionCodec.encode(it)) },
                    receive = { MeshSessionCodec.decode(connection.receive()) },
                )
            }.onFailure { if (it is CancellationException) throw it }
        }
        return MeshFileSyncResult(appliedChangeCount, metadataChanged)
        } finally {
            transferClaims.close()
        }
    }

    private suspend fun exchangeFolderKeys(connection: AuthenticatedPeerConnection) {
        val keys = DesktopFolderKeyStore(store, identity)
        val local = store.folders(profile.groupId, identity.deviceId).mapNotNull { folder ->
            keys.existing(folder.folderId)?.let { SessionFolderKey(it.folderId, it.keyId, it.bytes) }
        }
        connection.send(MeshSessionCodec.encode(MeshSessionMessage.FolderKeys(local)))
        val remote = connection.receiveSession<MeshSessionMessage.FolderKeys>()
        val knownFolders = store.folders(profile.groupId, identity.deviceId).mapTo(mutableSetOf(), MeshFolder::folderId)
        remote.keys.filter { it.folderId in knownFolders }.forEach { value ->
            keys.import(FolderKeyMaterial(value.folderId, value.keyId, value.keyBytes))
        }
    }

    private suspend fun downloadPhase(
        connection: AuthenticatedPeerConnection,
        remoteDeviceId: String,
        downloads: List<PreparedDownload>,
        attachmentDownloads: List<MeshChatMessage>,
        engine: FileSyncEngine,
    ): Int {
        val onIncomingBytes: (Long) -> Unit = { bytes ->
            onBytesTransferred(bytes)
            onIncomingBytesTransferred(bytes)
        }
        val acknowledgementBlocked = mutableSetOf<String>()
        var appliedChangeCount = 0
        downloads.forEach { prepared ->
            val plan = prepared.plan
            val folderId = plan.remote.folderId
            when (plan.action) {
                FileSyncAction.Conflict, FileSyncAction.SendLocal -> acknowledgementBlocked += folderId
                FileSyncAction.Nothing -> if (folderId !in acknowledgementBlocked) {
                    store.acknowledgeRemoteApplied(folderId, remoteDeviceId, plan.remote.remoteSequence)
                }
                FileSyncAction.DownloadRemote -> {
                    val root = engine.configuredRoot(folderId)
                    if (root == null) {
                        acknowledgementBlocked += folderId
                        return@forEach
                    }
                    val applier = AtomicFileApplier(root, plan.expectedContent())
                    val localBefore = store.fileVersion(folderId, plan.relativePath)
                    if (plan.remote.deleted) {
                        if (localBefore != null && !localBefore.deleted) {
                            history.deleteWithRecovery(
                                root,
                                localBefore,
                                plan.remote.originDeviceId.ifBlank { remoteDeviceId },
                            )
                        } else {
                            applier.delete(plan.relativePath)
                        }
                    } else if (prepared.manifest != null) {
                        if (prepared.requestCount > 0) {
                            val completed = ResumableBlockPeerClient(
                                ResumableBlockReceiver(store, transferCache(), applier),
                                onIncomingBytes,
                            ).fetchMissing(connection, prepared.manifest)
                            require(completed) { "Resumable transfer did not receive every block" }
                        }
                    } else {
                        WholeFilePeerClient(transferCache(), onIncomingBytes).fetch(
                            connection,
                            FileTransferMessage.WholeFileRequest(
                                plan.remote.folderId,
                                plan.remote.fileId,
                                plan.remote.relativePath,
                                plan.remote.contentSha256,
                            ),
                            applier,
                        )
                    }
                    engine.markRemoteApplied(remoteDeviceId, plan.remote, folderId !in acknowledgementBlocked)
                    appliedChangeCount++
                    if (!plan.remote.deleted) history.recordSynced(plan.remote)
                }
            }
        }
        attachmentDownloads.forEach { message ->
            runCatching { chatAttachments.receive(connection, message, onIncomingBytes) }
        }
        return appliedChangeCount
    }

    private suspend fun serveRequests(
        connection: AuthenticatedPeerConnection,
        requestCount: Int,
        engine: FileSyncEngine,
    ) {
        repeat(requestCount) {
            val request = FileTransferWireCodec.decode(connection.receive())
            if (request is FileTransferMessage.AttachmentRequest) {
                chatAttachments.serve(connection, request, onBytesTransferred)
                return@repeat
            }
            val folderId = when (request) {
                is FileTransferMessage.WholeFileRequest -> request.folderId
                is FileTransferMessage.BlockRequest -> request.folderId
                else -> null
            }
            val root = folderId?.let(engine::configuredRoot)
            if (root == null) {
                connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error("Folder is not available on this Mac")))
            } else {
                PeerFileServer(store, root, onBytesTransferred).serve(connection, request)
            }
        }
    }

    private fun transferCache(): java.nio.file.Path = java.nio.file.Path.of(
        System.getProperty("user.home"), "Library", "Application Support", "SyncTosh", "transfers",
    )

    private data class PreparedDownload(
        val plan: FileSyncPlan,
        val manifest: BlockManifest?,
        val requestCount: Int,
        val expectedBytes: Long,
    )
}

private fun FileSyncPlan.transferClaimKey(): String =
    activeTransferKey(remote.folderId, remote.fileId, remote.contentSha256)

data class MeshFileSyncResult(
    val appliedChangeCount: Int,
    val metadataChanged: Boolean,
)

private fun MeshStateBundle.replicatedItemCount(): Int =
    membershipEvents.size + folderAnnouncements.size + syncExceptionEvents.size + chatMessages.size

class MetadataOnlyMeshSession(
    private val store: MeshStore,
    private val identity: MacDeviceIdentity,
    private val profile: MeshProfile,
) {
    suspend fun run(connection: AuthenticatedPeerConnection, remoteDeviceId: String) =
        MeshFileSyncSession(store, identity, profile).run(connection, remoteDeviceId)
}

private suspend inline fun <reified T : MeshSessionMessage> AuthenticatedPeerConnection.receiveSession(): T {
    return when (val value = MeshSessionCodec.decode(receive())) {
        is MeshSessionMessage.Error -> error(value.reason)
        is T -> value
        else -> error("Unexpected mesh session message")
    }
}
