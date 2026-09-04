package com.syncdows.app.mesh

import com.syncdroid.shared.cloud.CloudEncryptedObjects
import com.syncdroid.shared.cloud.CloudFolderManifest
import com.syncdroid.shared.cloud.CloudOAuthConfiguration
import com.syncdroid.shared.cloud.CloudProvider
import com.syncdroid.shared.cloud.CloudRemoteStore
import com.syncdroid.shared.cloud.CloudSyncTrigger
import com.syncdroid.shared.cloud.CloudTransferOrchestrator
import com.syncdroid.shared.cloud.CloudTransferResult
import com.syncdroid.shared.cloud.CloudTransferRunner
import com.syncdroid.shared.cloud.DesktopCloudOAuth
import com.syncdroid.shared.cloud.EncryptedCloudTokenStore
import com.syncdroid.shared.cloud.GoogleDriveRemoteStore
import com.syncdroid.shared.cloud.LocalSecretCipher
import com.syncdroid.shared.cloud.OneDriveRemoteStore
import com.syncdows.app.platform.AppPreferences
import java.nio.file.Files
import java.nio.file.Path

data class CloudAccountStatus(
    val provider: CloudProvider,
    val configured: Boolean,
    val connected: Boolean,
)

class DesktopCloudSync(
    private val preferences: AppPreferences,
    private val store: MeshStore,
    private val identity: WindowsDeviceIdentity,
    private val status: (String) -> Unit,
) {
    private val tokenStore = EncryptedCloudTokenStore(
        LocalSecretCipher(identity.privateKey().encoded, "syncdows-oauth"),
        preferences::cloudToken,
        preferences::setCloudToken,
    )
    private val oauth = DesktopCloudOAuth(tokenStore)
    private val folderKeys = DesktopFolderKeyStore(store, identity)
    private val orchestrator = CloudTransferOrchestrator(
        policy = { preferences.cloudSyncPolicy },
        folderIds = {
            val profile = store.profile()
            if (profile == null) emptyList() else store.configuredFolders(profile.groupId, identity.deviceId).map(MeshFolder::folderId)
        },
        connectedProviders = { CloudProvider.entries.filter(oauth::connected) },
        runner = CloudTransferRunner(::transferFolder),
        onProgress = status,
    )

    suspend fun stopAndDrain() = orchestrator.stopAndDrain()

    fun accounts(): List<CloudAccountStatus> = CloudProvider.entries.map { provider ->
        CloudAccountStatus(provider, CloudOAuthConfiguration.configured(provider) != null, oauth.connected(provider))
    }

    suspend fun connect(provider: CloudProvider) {
        status("Opening ${provider.displayName} sign in…")
        oauth.connect(provider)
        status("${provider.displayName} connected")
    }

    fun disconnect(provider: CloudProvider) {
        oauth.disconnect(provider)
        status("${provider.displayName} disconnected")
    }

    suspend fun sync(trigger: CloudSyncTrigger): CloudTransferResult = orchestrator.run(trigger)

    fun pairingKeys(profile: MeshProfile): List<com.syncdroid.shared.cloud.FolderKeyMaterial> =
        store.folders(profile.groupId, identity.deviceId).map { folderKeys.getOrCreate(it.folderId) }

    fun availableKeys(profile: MeshProfile): List<com.syncdroid.shared.cloud.FolderKeyMaterial> =
        store.folders(profile.groupId, identity.deviceId).mapNotNull { folderKeys.existing(it.folderId) }

    fun importPairingKey(value: com.syncdroid.shared.cloud.FolderKeyMaterial) = folderKeys.import(value)

    private suspend fun transferFolder(provider: CloudProvider, folderId: String): CloudTransferResult {
        val profile = requireNotNull(store.profile()) { "Join a mesh before using cloud sync" }
        val folder = store.configuredFolders(profile.groupId, identity.deviceId).firstOrNull { it.folderId == folderId }
            ?: return CloudTransferResult()
        val root = Path.of(requireNotNull(folder.localPath))
        val engine = FileSyncEngine(store, identity, profile)
        engine.scanConfiguredFolders()
        val key = folderKeys.getOrCreate(folderId)
        val remote = remoteStore(provider)
        val syncRootId = remote.ensureFolder(remote.rootId, "SyncDroid")
        val folderRootId = remote.ensureFolder(syncRootId, folder.displayName)
        val remoteItems = remote.list(folderRootId).associateBy { it.name }
        var result = CloudTransferResult()

        val publishers = store.devices(profile.groupId)
            .filter { it.trusted && it.deviceId != identity.deviceId }
            .map(TrustedDevice::deviceId)
        publishers.forEach { publisherId ->
            val manifestItem = remoteItems[CloudEncryptedObjects.manifestName(key, publisherId)] ?: return@forEach
            val encryptedManifest = Files.createTempFile(store.storageDirectory, "cloud-manifest-", ".sdenc")
            try {
                remote.download(manifestItem.id, encryptedManifest)
                val manifest = CloudEncryptedObjects.decryptManifest(key, publisherId, Files.readAllBytes(encryptedManifest))
                val plans = engine.receiveIndexes(publisherId, listOf(manifest.index))
                plans.forEach { plan ->
                    when (plan.action) {
                        FileSyncAction.Nothing -> store.acknowledgeRemoteApplied(
                            plan.remote.folderId, publisherId, plan.remote.remoteSequence,
                        )
                        FileSyncAction.Conflict, FileSyncAction.SendLocal -> if (plan.action == FileSyncAction.Conflict) {
                            result = result.copy(conflicts = result.conflicts + 1)
                        }
                        FileSyncAction.DownloadRemote -> {
                            val localBefore = store.fileVersion(folderId, plan.relativePath)
                            if (plan.remote.deleted) {
                                if (localBefore != null && !localBefore.deleted) {
                                    FileHistoryRepository(store, identity.deviceId).deleteWithRecovery(
                                        root, localBefore, plan.remote.originDeviceId.ifBlank { publisherId },
                                    )
                                } else AtomicFileApplier(root).delete(plan.relativePath)
                            } else {
                                val objectName = CloudEncryptedObjects.fileName(key, plan.remote.fileId, plan.remote.contentSha256)
                                val objectItem = remoteItems[objectName]
                                    ?: error("${provider.displayName} is missing ${plan.relativePath}; it will retry next sync")
                                val encryptedFile = Files.createTempFile(store.storageDirectory, "cloud-file-", ".sdenc")
                                val plaintext = Files.createTempFile(store.storageDirectory, "cloud-file-", ".part")
                                try {
                                    remote.download(objectItem.id, encryptedFile)
                                    CloudEncryptedObjects.decryptFile(
                                        key, plan.remote.fileId, plan.remote.contentSha256, encryptedFile, plaintext,
                                    )
                                    Files.newInputStream(plaintext).use { input ->
                                        AtomicFileApplier(root, plan.expectedContent()).apply(
                                            plan.relativePath, input, plan.remote.contentSha256, plan.remote.modifiedAtMillis,
                                        )
                                    }
                                } finally {
                                    Files.deleteIfExists(encryptedFile)
                                    Files.deleteIfExists(plaintext)
                                }
                                FileHistoryRepository(store, identity.deviceId).recordSynced(plan.remote)
                                result = result.copy(
                                    downloadedFiles = result.downloadedFiles + 1,
                                    transferredBytes = result.transferredBytes + plan.remote.sizeBytes,
                                )
                            }
                            engine.markRemoteApplied(publisherId, plan.remote, acknowledge = true)
                        }
                    }
                }
            } finally {
                Files.deleteIfExists(encryptedManifest)
            }
        }

        val current = requireNotNull(engine.buildFullUpdate(folderId))
        current.files.filterNot { it.deleted }.forEach { file ->
            val objectName = CloudEncryptedObjects.fileName(key, file.fileId, file.contentSha256)
            if (remoteItems[objectName] == null) {
                val source = root.resolve(file.relativePath).normalize()
                require(source.startsWith(root.toAbsolutePath().normalize()) && Files.isRegularFile(source))
                val encrypted = Files.createTempFile(store.storageDirectory, "cloud-upload-", ".sdenc")
                try {
                    CloudEncryptedObjects.encryptFile(key, file.fileId, file.contentSha256, source, encrypted)
                    remote.upload(folderRootId, objectName, encrypted)
                    result = result.copy(
                        uploadedFiles = result.uploadedFiles + 1,
                        transferredBytes = result.transferredBytes + file.sizeBytes,
                    )
                } finally {
                    Files.deleteIfExists(encrypted)
                }
            }
        }
        val manifest = CloudFolderManifest(folderId, folder.displayName, identity.deviceId, System.currentTimeMillis(), current)
        val manifestBytes = CloudEncryptedObjects.encryptManifest(key, manifest)
        val manifestFile = Files.createTempFile(store.storageDirectory, "cloud-publish-", ".sdenc")
        try {
            Files.write(manifestFile, manifestBytes)
            remote.upload(folderRootId, CloudEncryptedObjects.manifestName(key, identity.deviceId), manifestFile)
        } finally {
            Files.deleteIfExists(manifestFile)
        }
        return result
    }

    private fun remoteStore(provider: CloudProvider): CloudRemoteStore = when (provider) {
        CloudProvider.GOOGLE_DRIVE -> GoogleDriveRemoteStore(accessToken = { oauth.accessToken(provider) })
        CloudProvider.ONE_DRIVE -> OneDriveRemoteStore(accessToken = { oauth.accessToken(provider) })
    }
}
