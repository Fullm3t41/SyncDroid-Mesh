package com.syncdeck.app.mesh

import com.syncdroid.shared.protocol.FileTransferMessage
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID

object FileTransferWireCodec {
    fun encode(message: FileTransferMessage): ByteArray =
        com.syncdroid.shared.protocol.FileTransferWireCodec.encode(message)

    fun decode(bytes: ByteArray): FileTransferMessage =
        com.syncdroid.shared.protocol.FileTransferWireCodec.decode(bytes)
}

class AtomicFileApplier(
    rootDirectory: Path,
    private val expectedContent: com.syncdroid.shared.sync.ExpectedFileContent? = null,
) {
    private val root = rootDirectory.toAbsolutePath().normalize().also {
        require(Files.isDirectory(it)) { "Configured sync folder is unavailable" }
    }
    private val rootReal = root.toRealPath()

    fun apply(relativePath: String, input: InputStream, expectedSha256: String, modifiedAtMillis: Long) {
        val target = safeTarget(relativePath, createParents = true)
        require(!Files.isSymbolicLink(target)) { "Refusing to replace a symbolic link" }
        val temporary = target.parent.resolve(".syncdeck-${UUID.randomUUID()}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temporary.toFile()).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
                output.fd.sync()
            }
            require(digest.digest().toHex().equals(expectedSha256, true)) {
                "Received file hash does not match its manifest"
            }
            expectedContent?.verify(
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target))
                    Files.newInputStream(target).buffered().use(::sha256Hex)
                } else null,
            )
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            if (modifiedAtMillis > 0) Files.setLastModifiedTime(target, FileTime.fromMillis(modifiedAtMillis))
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun delete(relativePath: String) {
        val target = safeTarget(relativePath, createParents = false)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                "Refusing to delete a non-file sync path"
            }
            expectedContent?.verify(Files.newInputStream(target).buffered().use(::sha256Hex))
            Files.delete(target)
        }
    }

    private fun safeTarget(relativePath: String, createParents: Boolean): Path {
        val target = root.resolve(normalizedRelativePath(relativePath)).normalize()
        require(target != root && target.startsWith(root)) { "Sync path escapes its folder" }
        var current = root
        root.relativize(requireNotNull(target.parent)).forEach { part ->
            current = current.resolve(part)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) {
                    "Sync path traverses a symbolic link or non-directory"
                }
                require(current.toRealPath().startsWith(rootReal)) { "Sync path escapes its folder" }
            } else if (createParents) {
                Files.createDirectory(current)
            }
        }
        return target
    }
}

class WholeFilePeerClient(
    private val receiveDirectory: Path,
    private val onBytesTransferred: (Long) -> Unit = {},
) {
    init { Files.createDirectories(receiveDirectory) }

    suspend fun fetch(
        connection: AuthenticatedPeerConnection,
        request: FileTransferMessage.WholeFileRequest,
        applier: AtomicFileApplier,
    ) {
        connection.send(FileTransferWireCodec.encode(request))
        val start = FileTransferWireCodec.decode(connection.receive())
        if (start is FileTransferMessage.Error) error(start.reason)
        require(start is FileTransferMessage.FileStart && start.sizeBytes >= 0) { "Peer did not start the requested file" }
        val temporary = Files.createTempFile(receiveDirectory, "syncdeck-whole-", ".part")
        try {
            var expectedSequence = 0
            var received = 0L
            FileOutputStream(temporary.toFile()).use { output ->
                while (true) {
                    when (val message = FileTransferWireCodec.decode(connection.receive())) {
                        is FileTransferMessage.FileChunk -> {
                            require(message.sequence == expectedSequence++) { "File chunks arrived out of order" }
                            received += message.data.size
                            require(received <= start.sizeBytes) { "Peer sent more file data than advertised" }
                            output.write(message.data); onBytesTransferred(message.data.size.toLong())
                        }
                        is FileTransferMessage.FileEnd -> {
                            require(message.contentSha256.equals(request.contentSha256, true)) {
                                "Peer sent a different file version"
                            }
                            break
                        }
                        is FileTransferMessage.Error -> error(message.reason)
                        else -> error("Unexpected file-transfer response")
                    }
                }
                output.fd.sync()
            }
            require(received == start.sizeBytes) { "Received file size does not match its manifest" }
            FileInputStream(temporary.toFile()).buffered().use { input ->
                applier.apply(request.relativePath, input, request.contentSha256, start.modifiedAtMillis)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

class PeerFileServer(
    private val store: MeshStore,
    private val rootDirectory: Path,
    private val onBytesTransferred: (Long) -> Unit = {},
) {
    private val root = rootDirectory.toAbsolutePath().normalize()
    private val rootReal = root.toRealPath()

    suspend fun serve(connection: AuthenticatedPeerConnection, request: FileTransferMessage) {
        when (request) {
            is FileTransferMessage.WholeFileRequest -> serveWhole(connection, request)
            is FileTransferMessage.BlockRequest -> serveBlock(connection, request)
            else -> connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error("Expected a file request")))
        }
    }

    private suspend fun serveWhole(connection: AuthenticatedPeerConnection, request: FileTransferMessage.WholeFileRequest) {
        val relativePath = runCatching { normalizedRelativePath(request.relativePath) }.getOrNull()
        val version = relativePath?.let { store.fileVersion(request.folderId, it) }
        val source = relativePath?.let(::safeExistingFile)
        val unavailableReason = when {
            version == null -> "Requested path is not in this device's file index"
            version.fileId != request.fileId -> "Requested file ID is no longer current"
            version.deleted -> "Requested file has been deleted"
            !version.contentSha256.equals(request.contentSha256, true) -> "Requested file hash is no longer current"
            source == null -> "Requested file is unavailable in the configured folder"
            else -> null
        }
        if (unavailableReason != null) {
            connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error(unavailableReason)))
            return
        }
        val currentVersion = requireNotNull(version)
        val currentSource = requireNotNull(source)
        connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileStart(Files.size(currentSource), currentVersion.modifiedAtMillis)))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(currentSource).buffered().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var sequence = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileChunk(sequence++, buffer.copyOf(count))))
                onBytesTransferred(count.toLong())
            }
        }
        connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileEnd(digest.digest().toHex())))
    }

    private suspend fun serveBlock(connection: AuthenticatedPeerConnection, request: FileTransferMessage.BlockRequest) {
        val relativePath = runCatching { normalizedRelativePath(request.relativePath) }.getOrNull()
        val version = relativePath?.let { store.fileVersion(request.folderId, it) }
        val source = relativePath?.let(::safeExistingFile)
        if (
            version == null || version.fileId != request.fileId || version.deleted ||
            !version.contentSha256.equals(request.contentSha256, true) || source == null
        ) {
            connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error("Requested version is unavailable")))
            return
        }
        val manifest = store.localBlockManifest(version)
            ?: runCatching { BlockManifestBuilder.build(version, source).also(store::storeLocalBlockManifest) }.getOrNull()
        val block = manifest?.blocks?.firstOrNull { it.index == request.blockIndex }
        if (block == null) {
            connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error("Requested block is unavailable")))
            return
        }
        val data = ByteArray(block.sizeBytes)
        RandomAccessFile(source.toFile(), "r").use { file ->
            file.seek(block.offsetBytes)
            file.readFully(data)
        }
        if (!MessageDigest.getInstance("SHA-256").digest(data).toHex().equals(block.sha256, true)) {
            connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error("Source block changed")))
            return
        }
        connection.send(FileTransferWireCodec.encode(FileTransferMessage.BlockResponse(block.index, data)))
        onBytesTransferred(data.size.toLong())
    }

    private fun safeExistingFile(relativePath: String): Path? = runCatching {
        val source = root.resolve(relativePath).normalize()
        require(source.startsWith(root) && source != root)
        require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source))
        require(source.toRealPath().startsWith(rootReal))
        source
    }.getOrNull()

    private companion object { const val CHUNK_SIZE = 64 * 1024 }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
