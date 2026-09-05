package com.synctosh.app.mesh

import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

class FileHistoryRepository(
    private val store: MeshStore,
    private val localDeviceId: String,
    private val recoveryRoot: Path = store.storageDirectory.resolve("Deleted Files"),
) {
    fun recordChange(
        action: FileHistoryAction,
        version: FileVersion,
        sourceDeviceId: String = version.originDeviceId,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        store.insertHistory(historyEvent(action, version, sourceDeviceId, nowMillis))
    }

    fun recordSynced(remote: RemoteFileVersion, nowMillis: Long = System.currentTimeMillis()) {
        if (remote.deleted) return
        store.insertHistory(
            FileHistoryEvent(
                UUID.randomUUID().toString(), FileHistoryAction.SYNCED, remote.folderId, remote.relativePath,
                remote.originDeviceId.ifBlank { remote.deviceId }, remote.sizeBytes, remote.modifiedAtMillis,
                remote.contentSha256, nowMillis, null, null, null,
            ),
        )
    }

    fun deleteWithRecovery(
        root: Path,
        version: FileVersion,
        sourceDeviceId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        cleanupExpired(nowMillis)
        val existing = store.activeDeletion(version.folderId, version.relativePath, version.contentSha256)
            ?.takeIf { event ->
                event.recoveryPath?.let(Path::of)?.let { Files.isRegularFile(it) && isInsideRecoveryRoot(it) } == true &&
                    (event.recoverableUntilMillis ?: 0) > nowMillis
            }
        if (existing != null) {
            AtomicFileApplier(root, com.syncdroid.shared.sync.ExpectedFileContent(version.contentSha256)).delete(version.relativePath)
            return
        }
        Files.createDirectories(recoveryRoot)
        val eventId = UUID.randomUUID().toString()
        val archive = recoveryRoot.resolve("$eventId.bin")
        val source = safeExistingFile(root, version.relativePath)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(source.toFile()).use { input ->
                FileOutputStream(archive.toFile()).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                        }
                    }
                    output.fd.sync()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(hash.equals(version.contentSha256, true)) {
                "The file changed while its recovery copy was being created"
            }
            AtomicFileApplier(root, com.syncdroid.shared.sync.ExpectedFileContent(version.contentSha256)).delete(version.relativePath)
            store.insertHistory(
                FileHistoryEvent(
                    eventId, FileHistoryAction.DELETED, version.folderId, version.relativePath, sourceDeviceId,
                    version.sizeBytes, version.modifiedAtMillis, hash, nowMillis, archive.toString(),
                    nowMillis + RECOVERY_RETENTION_MILLIS, null,
                ),
            )
        } catch (error: Throwable) {
            Files.deleteIfExists(archive)
            throw error
        }
    }

    fun deletePermanently(root: Path, version: FileVersion) {
        AtomicFileApplier(root, com.syncdroid.shared.sync.ExpectedFileContent(version.contentSha256.takeUnless { version.deleted }))
            .delete(version.relativePath)
        purgeRecoveries(version.folderId, version.relativePath)
        recordDetectedDeletion(version)
    }

    fun purgeRecoveries(folderId: String, relativePath: String) {
        store.recoveriesForFile(folderId, relativePath).forEach { event ->
            val path = requireNotNull(event.recoveryPath).let(Path::of)
            require(isInsideRecoveryRoot(path)) { "Invalid recovery location" }
            Files.deleteIfExists(path)
            store.clearRecoveryPath(event.eventId)
        }
    }

    fun recordDetectedDeletion(previous: FileVersion, nowMillis: Long = System.currentTimeMillis()) {
        if (store.activeDeletion(previous.folderId, previous.relativePath, previous.contentSha256) != null) return
        store.insertHistory(
            FileHistoryEvent(
                UUID.randomUUID().toString(), FileHistoryAction.DELETED, previous.folderId, previous.relativePath,
                localDeviceId, previous.sizeBytes, previous.modifiedAtMillis, previous.contentSha256,
                nowMillis, null, null, null,
            ),
        )
    }

    fun recover(eventId: String, profile: MeshProfile, nowMillis: Long = System.currentTimeMillis()): String {
        cleanupExpired(nowMillis)
        val event = requireNotNull(store.historyEvent(eventId)) { "This history item is no longer available" }
        require(event.action == FileHistoryAction.DELETED && event.recoveredAtMillis == null) {
            "This file cannot be recovered"
        }
        require((event.recoverableUntilMillis ?: 0) > nowMillis) { "The 30-day recovery window has expired" }
        val archive = event.recoveryPath?.let(Path::of)
        require(archive != null && Files.isRegularFile(archive) && isInsideRecoveryRoot(archive)) {
            "The recovery copy is unavailable on this Mac"
        }
        val folder = store.configuredFolders(profile.groupId, localDeviceId).firstOrNull { it.folderId == event.folderId }
            ?: error("Configure this folder on this Mac before recovering the file")
        val current = store.fileVersion(event.folderId, event.relativePath)
        require(current == null || current.deleted || current.contentSha256.equals(event.contentSha256, true)) {
            "A newer file already exists at this location"
        }
        FileInputStream(archive.toFile()).use { input ->
            AtomicFileApplier(Path.of(requireNotNull(folder.localPath))).apply(
                event.relativePath,
                input,
                requireNotNull(event.contentSha256),
                event.modifiedAtMillis ?: nowMillis,
            )
        }
        store.markRecovered(eventId, nowMillis)
        Files.deleteIfExists(archive)
        store.insertHistory(
            FileHistoryEvent(
                UUID.randomUUID().toString(), FileHistoryAction.RECOVERED, event.folderId, event.relativePath,
                localDeviceId, event.sizeBytes, event.modifiedAtMillis, event.contentSha256, nowMillis,
                null, null, null,
            ),
        )
        return event.relativePath
    }

    fun cleanupExpired(nowMillis: Long = System.currentTimeMillis()) {
        store.expiredRecoveries(nowMillis).forEach { event ->
            event.recoveryPath?.let(Path::of)?.takeIf(::isInsideRecoveryRoot)?.let(Files::deleteIfExists)
            store.clearRecoveryPath(event.eventId)
        }
        if (Files.isDirectory(recoveryRoot)) {
            Files.list(recoveryRoot).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .filter { Files.getLastModifiedTime(it).toMillis() < nowMillis - RECOVERY_RETENTION_MILLIS }
                    .forEach(Files::deleteIfExists)
            }
        }
    }

    private fun safeExistingFile(rootDirectory: Path, relativePath: String): Path {
        val root = rootDirectory.toAbsolutePath().normalize()
        val rootReal = root.toRealPath()
        val source = root.resolve(normalizedRelativePath(relativePath)).normalize()
        require(source.startsWith(root) && source != root)
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)) {
            "The file is no longer available"
        }
        require(source.toRealPath().startsWith(rootReal)) { "File path escapes its configured folder" }
        return source
    }

    private fun isInsideRecoveryRoot(path: Path): Boolean = runCatching {
        path.toAbsolutePath().normalize().startsWith(recoveryRoot.toAbsolutePath().normalize())
    }.getOrDefault(false)

    companion object {
        const val RECOVERY_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
    }
}

private fun historyEvent(
    action: FileHistoryAction,
    version: FileVersion,
    sourceDeviceId: String,
    nowMillis: Long,
) = FileHistoryEvent(
    UUID.randomUUID().toString(), action, version.folderId, version.relativePath, sourceDeviceId,
    version.sizeBytes, version.modifiedAtMillis, version.contentSha256, nowMillis, null, null, null,
)
