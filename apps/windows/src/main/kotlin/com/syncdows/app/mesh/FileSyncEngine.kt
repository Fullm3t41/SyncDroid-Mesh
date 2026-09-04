package com.syncdows.app.mesh

import com.syncdroid.shared.sync.FileSyncState
import com.syncdroid.shared.sync.decideFileSync as decideSharedFileSync
import com.syncdroid.shared.sync.normalizeRelativePath
import com.syncdroid.shared.sync.planIndexExport
import com.syncdroid.shared.sync.validateFolderIndexUpdate
import com.syncdows.app.platform.WindowsPathRules
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.io.path.invariantSeparatorsPathString

data class FileSyncPlan(
    val action: FileSyncAction,
    val relativePath: String,
    val local: FileVersion?,
    val remote: RemoteFileVersion,
    val reason: String,
    val remoteManifest: BlockManifest?,
    val conflictResolution: PendingConflictResolution? = null,
)

internal fun FileSyncPlan.expectedContent() = com.syncdroid.shared.sync.ExpectedFileContent(
    local?.takeIf { !it.deleted && it.relativePath == relativePath }?.contentSha256,
)

fun decideFileSync(local: FileVersion?, remote: RemoteFileVersion): Pair<FileSyncAction, String> {
    val decision = decideSharedFileSync(
        local = local?.let { FileSyncState(it.deleted, it.contentSha256, it.previousContentSha256, it.version) },
        remote = FileSyncState(remote.deleted, remote.contentSha256, remote.previousContentSha256, remote.version),
    )
    return decision.action to decision.reason
}

class FileSyncEngine(
    private val store: MeshStore,
    private val identity: WindowsDeviceIdentity,
    private val profile: MeshProfile,
) {
    private val history = FileHistoryRepository(store, identity.deviceId)

    fun scanConfiguredFolders(recordHistory: Boolean = true) {
        store.configuredFolders(profile.groupId, identity.deviceId).forEach { scanFolder(it, recordHistory) }
    }

    fun buildCatalog(remoteDeviceId: String): List<FolderClock> =
        store.folders(profile.groupId, identity.deviceId).mapNotNull { folder ->
            val local = store.folderIndexState(folder.folderId, identity.deviceId) ?: return@mapNotNull null
            val knownPeer = store.folderIndexState(folder.folderId, remoteDeviceId)
            FolderClock(
                folder.folderId,
                local.indexEpoch,
                local.maxSequence,
                knownPeer?.indexEpoch ?: 0,
                knownPeer?.metadataReceivedSequence ?: 0,
                knownPeer?.contentAppliedSequence ?: 0,
            )
        }

    fun buildUpdatesForPeer(remoteCatalog: List<FolderClock>): List<FolderIndexUpdate> {
        val peerByFolder = remoteCatalog.associateBy(FolderClock::folderId)
        return store.folders(profile.groupId, identity.deviceId).mapNotNull { folder ->
            val local = store.folderIndexState(folder.folderId, identity.deviceId) ?: return@mapNotNull null
            val root = configuredRoot(folder.folderId) ?: return@mapNotNull null
            val peer = peerByFolder[folder.folderId]
            val range = planIndexExport(
                local.indexEpoch,
                local.maxSequence,
                peer?.knownPeerIndexEpoch,
                peer?.knownPeerReceivedSequence,
            ) ?: return@mapNotNull null
            val versions = (if (range.fullIndex) store.fileVersions(folder.folderId) else {
                store.fileVersionsAfter(folder.folderId, range.previousSequence)
            }).sortedBy(FileVersion::localSequence)
            require(versions.size <= MAX_INDEX_FILES) { "Folder index is too large for one session" }
            FolderIndexUpdate(
                folder.folderId,
                local.indexEpoch,
                range.previousSequence,
                range.lastSequence,
                range.fullIndex,
                versions.map { it.toIndexedRecord(root) },
            )
        }
    }

    fun buildFullUpdate(folderId: String): FolderIndexUpdate? {
        val folder = store.folders(profile.groupId, identity.deviceId).firstOrNull { it.folderId == folderId } ?: return null
        val root = configuredRoot(folderId) ?: return null
        val local = store.folderIndexState(folderId, identity.deviceId) ?: return null
        val versions = store.fileVersions(folderId).sortedBy(FileVersion::localSequence)
        require(versions.size <= MAX_INDEX_FILES) { "Folder index is too large for one cloud manifest" }
        return FolderIndexUpdate(
            folder.folderId,
            local.indexEpoch,
            0,
            local.maxSequence,
            true,
            versions.map { it.toIndexedRecord(root) },
        )
    }

    fun receiveIndexes(remoteDeviceId: String, updates: List<FolderIndexUpdate>): List<FileSyncPlan> {
        val plans = mutableListOf<FileSyncPlan>()
        updates.forEach { update ->
            validateFolderIndexUpdate(update)
            val windowsPaths = mutableSetOf<String>()
            update.files.forEach { file ->
                val path = WindowsPathRules.validateRelativePath(file.relativePath)
                require(windowsPaths.add(path.lowercase())) {
                    "Index contains file names that collide on Windows"
                }
            }
            require(store.folders(profile.groupId, identity.deviceId).any { it.folderId == update.folderId }) {
                "Peer sent an index for another mesh"
            }
            val localPaths = store.fileVersions(update.folderId).filterNot { it.deleted }.map { it.relativePath }
            val spellings = (localPaths + update.files.map { it.relativePath }).groupBy { it.lowercase(java.util.Locale.ROOT) }
            require(spellings.values.none { it.distinct().size > 1 }) {
                "File names differ only by capitalization. Rename the conflicting files to use the same spelling on every device before syncing."
            }
            require(store.acceptRemoteIndex(remoteDeviceId, update)) { "A full index is required" }
            val localByPath = store.fileVersions(update.folderId).associateBy(FileVersion::relativePath)
            update.files.map { it.toRemote(update.folderId, remoteDeviceId) }.forEach { remote ->
                val local = localByPath[remote.relativePath]
                plans += planFor(local, remote)
            }
        }
        val receivedKeys = plans.mapTo(mutableSetOf()) { it.key() }
        store.folders(profile.groupId, identity.deviceId).forEach { folder ->
            store.pendingRemoteVersions(folder.folderId, remoteDeviceId).forEach { remote ->
                val key = "${remote.folderId}\u0000${remote.relativePath}\u0000${remote.remoteSequence}"
                if (key in receivedKeys) return@forEach
                val local = store.fileVersion(folder.folderId, remote.relativePath)
                plans += planFor(local, remote)
            }
        }
        return plans.sortedWith(compareBy({ it.remote.folderId }, { it.remote.remoteSequence }))
    }

    private fun planFor(local: FileVersion?, remote: RemoteFileVersion): FileSyncPlan {
        val resolution = store.pendingConflictResolution(local, remote)
        val (action, reason) = when {
            resolution != null -> FileSyncAction.DownloadRemote to "Applying the selected conflict resolution"
            !remote.deleted && store.localActiveSyncException(
                remote.folderId,
                remote.relativePath,
                identity.deviceId,
            ) ->
                FileSyncAction.Nothing to "This device has an active overwrite-only exception"
            else -> decideFileSync(local, remote)
        }
        if (action == FileSyncAction.Conflict) store.recordConflict(local, remote)
        return FileSyncPlan(
            action,
            resolution?.targetRelativePath ?: remote.relativePath,
            local,
            remote,
            reason,
            store.remoteBlockManifest(remote),
            resolution,
        )
    }

    fun configuredRoot(folderId: String): Path? = store.configuredFolders(profile.groupId, identity.deviceId)
        .firstOrNull { it.folderId == folderId }
        ?.localPath
        ?.let(Path::of)
        ?.takeIf(Files::isDirectory)

    fun markRemoteApplied(remoteDeviceId: String, remote: RemoteFileVersion, acknowledge: Boolean) {
        store.markRemoteApplied(remote, remoteDeviceId, identity.deviceId, acknowledge)
    }

    private fun scanFolder(folder: MeshFolder, recordHistory: Boolean) {
        val root = Path.of(requireNotNull(folder.localPath)).toAbsolutePath().normalize()
        require(Files.isDirectory(root)) { "Configured folder is unavailable: ${folder.displayName}" }
        val scanned = scanFiles(root, folder.includePatterns, folder.excludePatterns)
        val previous = store.fileVersions(folder.folderId).associateBy(FileVersion::relativePath)
        val priorState = store.folderIndexState(folder.folderId, identity.deviceId)
        val state = priorState ?: FolderIndexState(
            folder.folderId,
            identity.deviceId,
            randomEpoch(),
            0,
            0,
            0,
            System.currentTimeMillis(),
        )
        var nextSequence = state.maxSequence
        var changed = false
        val updated = linkedMapOf<String, FileVersion>()
        val historyChanges = mutableListOf<Pair<FileHistoryAction, FileVersion>>()
        scanned.forEach { file ->
            val old = previous[file.relativePath]
            val unchanged = old != null && !old.deleted && old.sizeBytes == file.sizeBytes &&
                old.contentSha256.equals(file.sha256, true)
            updated[file.relativePath] = if (unchanged) old else {
                changed = true
                nextSequence++
                FileVersion(
                    folder.folderId,
                    file.relativePath,
                    old?.fileId ?: UUID.randomUUID().toString(),
                    file.sizeBytes,
                    file.modifiedAtMillis,
                    file.sha256,
                    old?.contentSha256?.takeIf(String::isNotBlank),
                    false,
                    (old?.version ?: VersionVector()).increment(identity.deviceId),
                    identity.deviceId,
                    nextSequence,
                ).also { current ->
                    historyChanges += (if (old == null || old.deleted) FileHistoryAction.ADDED else FileHistoryAction.UPDATED) to current
                }
            }
        }
        val scannedPaths = scanned.mapTo(mutableSetOf(), ScannedFile::relativePath)
        previous.forEach { (path, old) ->
            if (path in scannedPaths) return@forEach
            updated[path] = if (old.deleted) old else {
                changed = true
                nextSequence++
                old.copy(
                    sizeBytes = 0,
                    modifiedAtMillis = System.currentTimeMillis(),
                    contentSha256 = "",
                    previousContentSha256 = old.contentSha256.takeIf(String::isNotBlank),
                    deleted = true,
                    version = old.version.increment(identity.deviceId),
                    originDeviceId = identity.deviceId,
                    localSequence = nextSequence,
                ).also { historyChanges += FileHistoryAction.DELETED to old }
            }
        }
        if (changed || priorState == null) {
            val now = System.currentTimeMillis()
            store.saveLocalIndex(
                updated.values.toList(),
                state.copy(
                    maxSequence = nextSequence,
                    metadataReceivedSequence = nextSequence,
                    contentAppliedSequence = nextSequence,
                    updatedAtMillis = now,
                ),
            )
            if (recordHistory) historyChanges.forEach { (action, version) ->
                if (action == FileHistoryAction.DELETED) history.recordDetectedDeletion(version, now)
                else history.recordChange(action, version, identity.deviceId, now)
            }
        }
    }

    private fun FileVersion.toIndexedRecord(root: Path): IndexedFileRecord {
        val manifest = if (!deleted && sizeBytes >= BlockManifestBuilder.RESUMABLE_THRESHOLD_BYTES) {
            store.localBlockManifest(this) ?: BlockManifestBuilder.build(this, root.resolve(relativePath)).also {
                store.storeLocalBlockManifest(it)
            }
        } else null
        return IndexedFileRecord(
            relativePath,
            fileId,
            sizeBytes,
            modifiedAtMillis,
            contentSha256,
            previousContentSha256,
            originDeviceId,
            deleted,
            version,
            localSequence,
            blockSizeBytes = manifest?.blockSizeBytes ?: 0,
            blocks = manifest?.blocks ?: emptyList(),
        )
    }

    private companion object {
        const val MAX_INDEX_FILES = 50_000
    }
}

private data class ScannedFile(val relativePath: String, val sizeBytes: Long, val modifiedAtMillis: Long, val sha256: String)

private fun scanFiles(root: Path, includes: List<String>, excludes: List<String>): List<ScannedFile> {
    val rootReal = root.toRealPath()
    return Files.walk(rootReal).use { paths ->
        paths.filter { path ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
        }.map { path ->
            val real = path.toRealPath(LinkOption.NOFOLLOW_LINKS)
            require(real.startsWith(rootReal)) { "Folder contains a file outside its root" }
            rootReal.relativize(real).invariantSeparatorsPathString to real
        }.filter { (relativePath) -> shouldSync(relativePath, includes, excludes) }
            .map { (relativePath, path) -> stableFile(relativePath, path) }
            .sorted(compareBy(ScannedFile::relativePath))
            .toList()
    }
}

private fun stableFile(relativePath: String, path: Path): ScannedFile {
    repeat(2) {
        val size = Files.size(path)
        val modified = Files.getLastModifiedTime(path).toMillis()
        val hash = Files.newInputStream(path).buffered().use(::sha256Hex)
        if (size == Files.size(path) && modified == Files.getLastModifiedTime(path).toMillis()) {
            return ScannedFile(relativePath, size, modified, hash)
        }
    }
    error("File changed repeatedly while it was being scanned: $relativePath")
}

private fun shouldSync(relativePath: String, includes: List<String>, excludes: List<String>): Boolean {
    if (excludes.any { globMatches(it, relativePath) }) return false
    return includes.isEmpty() || includes.any { globMatches(it, relativePath) }
}

private fun globMatches(rawPattern: String, path: String): Boolean {
    val pattern = rawPattern.trim().replace('\\', '/')
    if (pattern.isEmpty()) return false
    val target = if ('/' in pattern) path else path.substringAfterLast('/')
    val regex = buildString {
        append('^')
        var index = 0
        while (index < pattern.length) {
            when (val char = pattern[index]) {
                '*' -> if (index + 1 < pattern.length && pattern[index + 1] == '*') {
                    append(".*"); index++
                } else append("[^/]*")
                '?' -> append("[^/]")
                '.', '(', ')', '[', ']', '$', '^', '{', '}', '+', '|', '\\' -> append("\\$char")
                else -> append(char)
            }
            index++
        }
        append('$')
    }
    return Regex(regex, RegexOption.IGNORE_CASE).matches(target)
}

fun normalizedRelativePath(path: String): String {
    return WindowsPathRules.validateRelativePath(normalizeRelativePath(path))
}

fun sha256Hex(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count > 0) digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun IndexedFileRecord.toRemote(folderId: String, deviceId: String) = RemoteFileVersion(
    folderId,
    deviceId,
    normalizedRelativePath(relativePath),
    fileId,
    sizeBytes,
    modifiedAtMillis,
    contentSha256.lowercase(),
    previousContentSha256?.lowercase(),
    originDeviceId,
    deleted,
    version,
    sequence,
)

private fun FileSyncPlan.key() = "${remote.folderId}\u0000${remote.relativePath}\u0000${remote.remoteSequence}"

private fun randomEpoch(): Long = (SecureRandom().nextLong() and Long.MAX_VALUE).coerceAtLeast(1)
