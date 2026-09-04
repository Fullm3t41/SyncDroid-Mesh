package com.syncdroid.app.mesh

import android.content.Context
import android.net.Uri
import com.syncdroid.app.data.FileVersionEntity
import com.syncdroid.app.data.LocalFolderBindingEntity
import com.syncdroid.app.data.RemoteFileVersionEntity
import com.syncdroid.app.data.SyncDroidDatabase
import com.syncdroid.app.storage.SyncFilterRules
import com.syncdroid.app.storage.LowStorageApprovalStore
import com.syncdroid.app.storage.StorageCapacity
import com.syncdroid.app.storage.StorageCapacityGuard
import com.syncdroid.app.storage.StorageSyncWarning
import com.syncdroid.app.cloud.AndroidFolderKeyStore
import com.syncdroid.app.sync.AtomicFileApplier
import com.syncdroid.app.sync.BlockManifest
import com.syncdroid.app.sync.BlockManifestBuilder
import com.syncdroid.app.sync.BlockManifestRepository
import com.syncdroid.app.sync.FileSyncAction
import com.syncdroid.app.sync.FileSyncPlan
import com.syncdroid.app.sync.FileHistoryRepository
import com.syncdroid.app.sync.FileTransferWireCodec
import com.syncdroid.app.sync.DocumentTreeFileApplier
import com.syncdroid.app.sync.FolderIndexUpdate
import com.syncdroid.app.sync.IndexAcceptance
import com.syncdroid.app.sync.IndexedFileRecord
import com.syncdroid.app.sync.PeerFileServer
import com.syncdroid.app.sync.RemoteIndexRepository
import com.syncdroid.app.sync.ResumableBlockPeerClient
import com.syncdroid.app.sync.ResumableBlockReceiver
import com.syncdroid.app.sync.SnapshotRepository
import com.syncdroid.app.sync.SyncFileApplier
import com.syncdroid.app.sync.VersionVector
import com.syncdroid.app.sync.WholeFilePeerClient
import com.syncdroid.shared.protocol.FileTransferMessage
import com.syncdroid.shared.protocol.MeshSessionMessage
import com.syncdroid.shared.protocol.SessionFolderKey
import com.syncdroid.shared.sync.ActiveTransferClaims
import com.syncdroid.shared.sync.activeTransferKey
import com.syncdroid.shared.update.MeshUpdateCache
import com.syncdroid.shared.update.MeshUpdateExchange
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.json.JSONArray

class MeshSyncSession(
    context: Context,
    private val database: SyncDroidDatabase,
    private val identity: AndroidDeviceIdentity,
    private val groupId: String,
    private val groupName: String,
    private val updateCache: MeshUpdateCache? = null,
    private val onBytesTransferred: (Long) -> Unit = {},
    private val onIncomingTransferPlanned: (Long) -> Unit = {},
    private val onIncomingBytesTransferred: (Long) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val syncDao = database.syncDao()
    private val replication = MeshReplicationRepository(database, identity)
    private val snapshots = SnapshotRepository(database, identity)
    private val remoteIndexes = RemoteIndexRepository(database, identity.deviceId)
    private val blockManifests = BlockManifestRepository(syncDao)
    private val fileHistory = FileHistoryRepository(appContext, database, identity.deviceId)
    private val storageCapacity = StorageCapacityGuard(appContext)
    private val lowStorageApprovals = LowStorageApprovalStore(appContext)
    private val chatAttachments = ChatAttachmentStore(appContext, database)
    private val folderKeys = AndroidFolderKeyStore(appContext, syncDao)

    suspend fun run(connection: AuthenticatedPeerConnection): MeshSyncResult {
        val remoteDeviceId = connection.peer.deviceId
        require(database.meshDao().getDevice(groupId, remoteDeviceId)?.trustState == "TRUSTED") {
            "The connected device is not a trusted member of this mesh"
        }

        val receiveResult = exchangeMetadata(connection)
        exchangeFolderKeys(connection)
        chatAttachments.cleanupExpired(groupId)
        val missingAttachments = chatAttachments.missing(
            database.chatDao().recentMessages(groupId, MAX_REPLICATED_CHAT_ATTACHMENTS)
                .asReversed().map { it.toDomain() },
        )
        scanConfiguredFolders()

        val localCatalog = buildCatalog(remoteDeviceId)
        connection.send(MeshSessionCodec.encode(MeshSessionMessage.Catalog(localCatalog)))
        val remoteCatalog = connection.receiveSession<MeshSessionMessage.Catalog>().folders

        val updates = buildUpdatesForPeer(remoteCatalog)
        connection.send(MeshSessionCodec.encode(MeshSessionMessage.IndexBatch(updates)))
        val receivedUpdates = connection.receiveSession<MeshSessionMessage.IndexBatch>().updates
        val receivedPlans = receiveIndexes(remoteDeviceId, receivedUpdates)
        val receivedKeys = receivedPlans.mapTo(mutableSetOf()) { it.planKey() }
        val pendingPlans = remoteIndexes.pendingPlans(
            remoteDeviceId,
            syncDao.enabledFolders(groupId).map { it.folderId },
        ).filterNot { it.planKey() in receivedKeys }
        val candidatePlans = (receivedPlans + pendingPlans)
            .sortedWith(compareBy({ it.remote.folderId }, { it.remote.remoteSequence }))
        val transferClaims = ActiveTransferClaims.claim(
            candidatePlans.filter { it.action == FileSyncAction.DownloadRemote && !it.remote.deleted }
                .map(FileSyncPlan::transferClaimKey),
        )
        try {
        val plans = candidatePlans.filter { plan ->
            plan.action != FileSyncAction.DownloadRemote || plan.remote.deleted ||
                transferClaims.owns(plan.transferClaimKey())
        }
        val prepared = prepareDownloads(plans)
        val localRequestCount = prepared.sumOf(PreparedDownload::requestCount) + missingAttachments.size
        onIncomingTransferPlanned(
            prepared.sumOf(PreparedDownload::expectedBytes) +
                missingAttachments.sumOf { it.attachment?.sizeBytes ?: 0L },
        )
        connection.send(MeshSessionCodec.encode(MeshSessionMessage.TransferPlan(localRequestCount)))
        val remoteRequestCount = connection.receiveSession<MeshSessionMessage.TransferPlan>().requestCount

        val downloadResult = if (identity.deviceId < remoteDeviceId) {
            val result = downloadPhase(connection, remoteDeviceId, prepared, missingAttachments)
            connection.send(MeshSessionCodec.encode(MeshSessionMessage.PhaseDone))
            serveRequests(connection, remoteRequestCount)
            connection.receiveSession<MeshSessionMessage.PhaseDone>()
            result
        } else {
            serveRequests(connection, remoteRequestCount)
            connection.receiveSession<MeshSessionMessage.PhaseDone>()
            val result = downloadPhase(connection, remoteDeviceId, prepared, missingAttachments)
            connection.send(MeshSessionCodec.encode(MeshSessionMessage.PhaseDone))
            result
        }
        database.meshDao().updateLastSeen(groupId, remoteDeviceId, System.currentTimeMillis())
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
        return MeshSyncResult(
            newChatMessages = receiveResult.newChatMessages,
            storageWarning = mergeStorageWarnings(downloadResult.warnings),
            storageBlockedFolderIds = downloadResult.blockedFolderIds,
            appliedChangeCount = downloadResult.appliedChangeCount,
            replicatedStateChanged = receiveResult.replicatedStateChanged,
        )
        } finally {
            transferClaims.close()
        }
    }

    private suspend fun exchangeMetadata(connection: AuthenticatedPeerConnection): MeshReceiveResult {
        val local = MeshWireCodec.encode(replication.export(groupId, groupName))
        connection.send(MeshSessionCodec.encode(MeshSessionMessage.Metadata(local)))
        val remote = connection.receiveSession<MeshSessionMessage.Metadata>()
        return replication.receive(MeshWireCodec.decode(remote.bundle))
    }

    private suspend fun exchangeFolderKeys(connection: AuthenticatedPeerConnection) {
        val local = syncDao.folderKeys().map { stored ->
            folderKeys.getOrCreate(stored.folderId).let { SessionFolderKey(it.folderId, it.keyId, it.bytes) }
        }
        connection.send(MeshSessionCodec.encode(MeshSessionMessage.FolderKeys(local)))
        val remote = connection.receiveSession<MeshSessionMessage.FolderKeys>()
        val knownFolders = syncDao.enabledFolders(groupId).mapTo(mutableSetOf()) { it.folderId }
        remote.keys.filter { it.folderId in knownFolders }.forEach { value ->
            folderKeys.import(value.folderId, value.keyId, value.keyBytes)
        }
    }

    private suspend fun scanConfiguredFolders() {
        val folders = syncDao.enabledFolders(groupId).associateBy { it.folderId }
        syncDao.configuredBindings(identity.deviceId, groupId).forEach { binding ->
            val folder = folders[binding.folderId] ?: return@forEach
            val rules = SyncFilterRules(
                includes = JSONArray(folder.includePatternsJson).strings(),
                excludes = JSONArray(folder.excludePatternsJson).strings(),
            )
            val location = binding.configuredLocationOrNull() ?: return@forEach
            if (location.startsWith("content://", true)) {
                snapshots.scanDocumentTree(appContext, folder.folderId, identity.deviceId, Uri.parse(location), rules)
            } else {
                val root = File(location)
                if (root.isDirectory) snapshots.scanDirectFolder(folder.folderId, identity.deviceId, root, rules)
            }
        }
    }

    private suspend fun buildCatalog(remoteDeviceId: String): List<FolderClock> =
        syncDao.enabledFolders(groupId).mapNotNull { folder ->
            val local = syncDao.folderIndexState(folder.folderId, identity.deviceId) ?: return@mapNotNull null
            val knownPeer = syncDao.folderIndexState(folder.folderId, remoteDeviceId)
            FolderClock(
                folder.folderId,
                local.indexEpoch,
                local.maxSequence,
                knownPeer?.indexEpoch ?: 0,
                knownPeer?.metadataReceivedSequence ?: 0,
                knownPeer?.contentAppliedSequence ?: 0,
            )
        }

    private suspend fun buildUpdatesForPeer(remoteCatalog: List<FolderClock>): List<FolderIndexUpdate> {
        val peerByFolder = remoteCatalog.associateBy(FolderClock::folderId)
        return syncDao.enabledFolders(groupId).mapNotNull { folder ->
            val local = syncDao.folderIndexState(folder.folderId, identity.deviceId) ?: return@mapNotNull null
            val peer = peerByFolder[folder.folderId]
            val full = peer == null || peer.knownPeerIndexEpoch != local.indexEpoch ||
                peer.knownPeerReceivedSequence > local.maxSequence
            val previous = if (full) 0 else peer.knownPeerReceivedSequence
            if (!full && previous == local.maxSequence) return@mapNotNull null
            val versions = (if (full) syncDao.fileVersions(folder.folderId) else {
                syncDao.fileVersionsAfter(folder.folderId, previous, MAX_INDEX_FILES)
            }).sortedBy(FileVersionEntity::localSequence)
            require(versions.size < MAX_INDEX_FILES || versions.last().localSequence == local.maxSequence) {
                "Folder index is too large for one session"
            }
            val binding = syncDao.getBinding(folder.folderId, identity.deviceId)
            FolderIndexUpdate(
                folder.folderId,
                local.indexEpoch,
                previous,
                local.maxSequence,
                full,
                versions.map { it.toIndexedRecord(binding) },
            )
        }
    }

    private suspend fun FileVersionEntity.toIndexedRecord(binding: LocalFolderBindingEntity?): IndexedFileRecord {
        var manifest: BlockManifest? = null
        if (!deleted && sizeBytes >= RESUMABLE_THRESHOLD_BYTES) {
            val root = binding?.directDirectoryOrNull()
            val source = root?.let { File(it, relativePath) }
            if (source?.isFile == true) {
                manifest = blockManifests.load(folderId, fileId, relativePath, sizeBytes, contentSha256)
                if (manifest == null || !manifest.contentSha256.equals(contentSha256, true)) {
                    val built = BlockManifestBuilder.build(folderId, fileId, relativePath, source)
                    if (built.contentSha256.equals(contentSha256, true)) {
                        blockManifests.store(built)
                        manifest = built
                    }
                }
            }
        }
        return IndexedFileRecord(
            relativePath,
            fileId,
            sizeBytes,
            modifiedAtMillis,
            contentSha256,
            previousContentSha256,
            originDeviceId,
            deleted,
            VersionVector.fromJson(versionVectorJson),
            localSequence,
            manifest?.blockSizeBytes ?: 0,
            manifest?.blocks ?: emptyList(),
        )
    }

    private suspend fun receiveIndexes(
        remoteDeviceId: String,
        updates: List<FolderIndexUpdate>,
    ): List<FileSyncPlan> = buildList {
        for (update in updates) {
            require(syncDao.getFolder(update.folderId)?.groupId == groupId) { "Peer sent an index for another mesh" }
            val (acceptance, plans) = remoteIndexes.receive(remoteDeviceId, update)
            require(acceptance !is IndexAcceptance.RequiresFullIndex) { "A full index is required" }
            addAll(plans)
        }
    }.sortedWith(compareBy({ it.remote.folderId }, { it.remote.remoteSequence }))

    private suspend fun prepareDownloads(plans: List<FileSyncPlan>): List<PreparedDownload> {
        return plans.map { plan ->
            if (plan.action != FileSyncAction.DownloadRemote || plan.remote.deleted) {
                PreparedDownload(plan, null, 0, 0L)
            } else {
                val binding = syncDao.getBinding(plan.remote.folderId, identity.deviceId)
                val applier = binding?.fileApplierOrNull(plan)
                if (applier == null) {
                    PreparedDownload(plan, null, 0, 0L)
                } else {
                    val storageWarning = storageCapacity.warningForIncomingFile(
                        binding,
                        plan.remote.sizeBytes,
                        lowStorageApprovals,
                    )
                    if (storageWarning != null) {
                        PreparedDownload(plan, null, 0, 0L, storageWarning)
                    } else if (plan.remoteManifest != null) {
                        val receiver = blockReceiver(applier)
                        val missingBlocks = receiver.missingBlocks(plan.remoteManifest)
                        PreparedDownload(
                            plan,
                            receiver,
                            missingBlocks.size,
                            plan.remoteManifest.blocks
                                .filter { it.index in missingBlocks }
                                .sumOf { it.sizeBytes.toLong() },
                        )
                    } else {
                        PreparedDownload(plan, null, 1, plan.remote.sizeBytes)
                    }
                }
            }
        }
    }

    private suspend fun downloadPhase(
        connection: AuthenticatedPeerConnection,
        remoteDeviceId: String,
        downloads: List<PreparedDownload>,
        attachmentDownloads: List<MeshChatMessage>,
    ): DownloadPhaseResult {
        val onIncomingBytes: (Long) -> Unit = { bytes ->
            onBytesTransferred(bytes)
            onIncomingBytesTransferred(bytes)
        }
        val acknowledgementBlocked = mutableSetOf<String>()
        val storageBlockedFolders = mutableSetOf<String>()
        val storageWarnings = mutableListOf<StorageSyncWarning>()
        var appliedChangeCount = 0
        for (prepared in downloads) {
            val plan = prepared.plan
            val folderId = plan.remote.folderId
            when (plan.action) {
                FileSyncAction.Conflict, FileSyncAction.SendLocal -> acknowledgementBlocked += folderId
                FileSyncAction.Nothing -> if (folderId !in acknowledgementBlocked) {
                    remoteIndexes.acknowledgeRemoteApplied(remoteDeviceId, plan.remote)
                }
                FileSyncAction.DownloadRemote -> {
                    val binding = syncDao.getBinding(folderId, identity.deviceId)
                    val applier = binding?.fileApplierOrNull(plan)
                    if (applier == null) {
                        acknowledgementBlocked += folderId
                        continue
                    }
                    val storageWarning = prepared.storageWarning ?: if (!plan.remote.deleted) {
                        storageCapacity.warningForIncomingFile(
                            binding,
                            plan.remote.sizeBytes,
                            lowStorageApprovals,
                        )
                    } else {
                        null
                    }
                    if (storageWarning != null) {
                        acknowledgementBlocked += folderId
                        storageBlockedFolders += folderId
                        storageWarnings += storageWarning
                        continue
                    }
                    val localBefore = syncDao.fileVersion(folderId, plan.relativePath)
                    if (plan.remote.deleted) {
                        if (localBefore != null && !localBefore.deleted) {
                            fileHistory.deleteWithRecovery(
                                binding = binding,
                                versions = listOf(localBefore),
                                sourceDeviceId = plan.remote.originDeviceId.ifBlank { remoteDeviceId },
                            )
                        } else {
                            applier.delete(plan.relativePath)
                        }
                    } else if (plan.remoteManifest != null && prepared.blockReceiver != null) {
                        if (prepared.requestCount > 0) {
                            check(ResumableBlockPeerClient(
                                prepared.blockReceiver,
                                onIncomingBytes,
                            ).fetchMissing(connection, plan.remoteManifest))
                        }
                        binding.directDirectoryOrNull()?.let {
                            File(it, plan.relativePath).setLastModified(plan.remote.modifiedAtMillis)
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
                    remoteIndexes.markRemoteApplied(remoteDeviceId, plan.remote, folderId !in acknowledgementBlocked)
                    appliedChangeCount++
                    if (!plan.remote.deleted) {
                        fileHistory.recordRemoteApplied(
                            remote = plan.remote,
                            wasNew = localBefore == null || localBefore.deleted,
                        )
                    }
                }
            }
        }
        attachmentDownloads.forEach { message ->
            runCatching { chatAttachments.receive(connection, message, onIncomingBytes) }
        }
        return DownloadPhaseResult(storageBlockedFolders, storageWarnings, appliedChangeCount)
    }

    private suspend fun serveRequests(connection: AuthenticatedPeerConnection, requestCount: Int) {
        repeat(requestCount) {
            val request = FileTransferWireCodec.decode(connection.receive())
            if (request is FileTransferMessage.AttachmentRequest) {
                chatAttachments.serve(connection, groupId, request, onBytesTransferred)
                return@repeat
            }
            val folderId = when (request) {
                is FileTransferMessage.WholeFileRequest -> request.folderId
                is FileTransferMessage.BlockRequest -> request.folderId
                else -> null
            }
            val binding = folderId?.let { syncDao.getBinding(it, identity.deviceId) }
            val root = binding?.directDirectoryOrNull() ?: binding?.let { materializeSafRequest(it, request) }
            if (root == null) {
                connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error("Folder is not available on this device")))
            } else {
                try {
                    PeerFileServer(syncDao, root, onBytesTransferred).serve(connection, request)
                } finally {
                    if (binding?.directDirectoryOrNull() == null) root.deleteRecursively()
                }
            }
        }
    }

    private fun blockReceiver(applier: SyncFileApplier): ResumableBlockReceiver = ResumableBlockReceiver(
        syncDao,
        transferCache(),
        applier,
    )

    private fun LocalFolderBindingEntity.fileApplierOrNull(plan: FileSyncPlan): SyncFileApplier? {
        val expected = com.syncdroid.shared.sync.ExpectedFileContent(
            plan.local?.takeIf { !it.deleted && it.relativePath == plan.relativePath }?.contentSha256,
        )
        val location = configuredLocationOrNull() ?: return null
        return if (location.startsWith("content://", true)) {
            runCatching { DocumentTreeFileApplier(appContext, Uri.parse(location), expected) }.getOrNull()
        } else {
            File(location).takeIf(File::isDirectory)?.let { AtomicFileApplier(it, expected) }
        }
    }

    private fun materializeSafRequest(binding: LocalFolderBindingEntity, request: FileTransferMessage): File? {
        val location = binding.configuredLocationOrNull()?.takeIf { it.startsWith("content://", true) } ?: return null
        val path = when (request) {
            is FileTransferMessage.WholeFileRequest -> request.relativePath
            is FileTransferMessage.BlockRequest -> request.relativePath
            else -> return null
        }
        val source = DocumentTreeFileApplier(appContext, Uri.parse(location)).open(path) ?: return null
        val root = File(transferCache(), "serve-${UUID.randomUUID()}")
        val target = File(root, path).canonicalFile
        require(target.toPath().startsWith(root.canonicalFile.toPath())) { "Requested path escapes the folder" }
        require(target.parentFile?.mkdirs() != false)
        source.use { input -> FileOutputStream(target).use(input::copyTo) }
        return root
    }

    private fun transferCache(): File = File(appContext.cacheDir, "mesh-transfers").apply { mkdirs() }

    private data class PreparedDownload(
        val plan: FileSyncPlan,
        val blockReceiver: ResumableBlockReceiver?,
        val requestCount: Int,
        val expectedBytes: Long,
        val storageWarning: StorageSyncWarning? = null,
    )

    private data class DownloadPhaseResult(
        val blockedFolderIds: Set<String>,
        val warnings: List<StorageSyncWarning>,
        val appliedChangeCount: Int,
    )

    private companion object {
        const val MAX_INDEX_FILES = 50_000
        const val MAX_REPLICATED_CHAT_ATTACHMENTS = 5_000
        const val RESUMABLE_THRESHOLD_BYTES = 1024 * 1024L
    }
}

private fun FileSyncPlan.transferClaimKey(): String =
    activeTransferKey(remote.folderId, remote.fileId, remote.contentSha256)

private fun FileSyncPlan.planKey(): String =
    "${remote.folderId}\u0000${remote.relativePath}\u0000${remote.remoteSequence}"

data class MeshSyncResult(
    val newChatMessages: List<MeshChatMessage> = emptyList(),
    val storageWarning: StorageSyncWarning? = null,
    val storageBlockedFolderIds: Set<String> = emptySet(),
    val appliedChangeCount: Int = 0,
    val replicatedStateChanged: Boolean = false,
)

private fun mergeStorageWarnings(warnings: List<StorageSyncWarning>): StorageSyncWarning? {
    val full = warnings.filterIsInstance<StorageSyncWarning.Full>()
    if (full.isNotEmpty()) {
        return StorageSyncWarning.Full(
            destinations = full.flatMap(StorageSyncWarning.Full::destinations)
                .distinctBy(StorageCapacity::destinationKey),
            incomingSizeBytes = full.mapNotNull(StorageSyncWarning.Full::incomingSizeBytes).maxOrNull(),
        )
    }
    val low = warnings.filterIsInstance<StorageSyncWarning.Low>()
    return if (low.isEmpty()) null else StorageSyncWarning.Low(
        low.flatMap(StorageSyncWarning.Low::destinations).distinctBy(StorageCapacity::destinationKey),
    )
}

private suspend inline fun <reified T : MeshSessionMessage> AuthenticatedPeerConnection.receiveSession(): T {
    return when (val message = MeshSessionCodec.decode(receive())) {
        is MeshSessionMessage.Error -> if (message.reason == SESSION_BUSY_REASON) {
            throw PeerSessionBusyException()
        } else {
            error(message.reason)
        }
        is T -> message
        else -> error("Unexpected mesh session message")
    }
}

internal class PeerSessionBusyException : IllegalStateException(SESSION_BUSY_REASON)

private fun LocalFolderBindingEntity.directDirectoryOrNull(): File? {
    val location = configuredLocationOrNull() ?: return null
    if (location.startsWith("content://", true)) return null
    return File(location).takeIf { it.isDirectory }
}

private fun LocalFolderBindingEntity.configuredLocationOrNull(): String? {
    if (state != LocalFolderBindingState.CONFIGURED.name) return null
    return localLocation?.takeIf(String::isNotBlank)
}

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

internal const val SESSION_BUSY_REASON = "Peer session already active"
