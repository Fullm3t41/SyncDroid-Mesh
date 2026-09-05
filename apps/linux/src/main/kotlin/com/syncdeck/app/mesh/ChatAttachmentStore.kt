package com.syncdeck.app.mesh

import com.syncdroid.shared.protocol.FileTransferMessage
import com.syncdroid.shared.protocol.WireChatAttachment
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class ChatAttachmentStore(
    private val store: MeshStore,
    private val root: Path = Path.of(
        com.syncdeck.app.platform.LinuxAppPaths.stateRoot.toString(), "chat-attachments",
    ),
) {
    init { Files.createDirectories(root) }

    fun describe(source: Path, createdAtMillis: Long): WireChatAttachment {
        val file = source.toAbsolutePath().normalize()
        require(Files.isRegularFile(file)) { "Choose an available file" }
        val name = requireNotNull(file.fileName).toString()
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return WireChatAttachment(
            fileName = name,
            mediaType = Files.probeContentType(file).orEmpty(),
            sizeBytes = Files.size(file),
            contentSha256 = digest.digest().toHex(),
            expiresAtMillis = createdAtMillis + CHAT_ATTACHMENT_RETENTION_MILLIS,
        ).also { it.validateForChat(createdAtMillis) }
    }

    fun import(message: MeshChatMessage, source: Path) {
        val attachment = requireNotNull(message.attachment)
        val destination = attachmentPath(message)
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".attachment-", ".part")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            require(Files.size(temporary) == attachment.sizeBytes && sha256(temporary).equals(attachment.contentSha256, true)) {
                "The selected attachment changed while it was being prepared"
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun localPath(message: MeshChatMessage): Path? = message.attachment
        ?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }
        ?.let { attachmentPath(message) }
        ?.takeIf(Files::isRegularFile)

    fun missing(messages: List<MeshChatMessage>, nowMillis: Long = System.currentTimeMillis()): List<MeshChatMessage> =
        messages.filter { it.attachment?.expiresAtMillis?.let { expiry -> expiry > nowMillis } == true && localPath(it) == null }

    fun cleanupExpired(messages: List<MeshChatMessage>, nowMillis: Long = System.currentTimeMillis()) {
        messages.filter { it.attachment?.expiresAtMillis?.let { expiry -> expiry <= nowMillis } == true }.forEach { message ->
            val directory = root.resolve(message.messageId)
            runCatching { Files.list(directory).use { files -> files.forEach(Files::deleteIfExists) } }
            runCatching { Files.deleteIfExists(directory) }
        }
    }

    suspend fun receive(connection: AuthenticatedPeerConnection, message: MeshChatMessage, onBytes: (Long) -> Unit = {}) {
        val attachment = requireNotNull(message.attachment)
        connection.send(FileTransferWireCodec.encode(FileTransferMessage.AttachmentRequest(message.messageId, attachment.contentSha256)))
        val start = FileTransferWireCodec.decode(connection.receive())
        if (start is FileTransferMessage.Error) error(start.reason)
        require(start is FileTransferMessage.FileStart && start.sizeBytes == attachment.sizeBytes) {
            "Peer advertised a different attachment size"
        }
        val destination = attachmentPath(message)
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".attachment-", ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var expectedSequence = 0
            FileOutputStream(temporary.toFile()).use { output ->
                while (true) when (val response = FileTransferWireCodec.decode(connection.receive())) {
                    is FileTransferMessage.FileChunk -> {
                        require(response.sequence == expectedSequence++) { "Attachment chunks arrived out of order" }
                        received += response.data.size
                        require(received <= attachment.sizeBytes) { "Peer sent too much attachment data" }
                        output.write(response.data); digest.update(response.data); onBytes(response.data.size.toLong())
                    }
                    is FileTransferMessage.FileEnd -> {
                        require(response.contentSha256.equals(attachment.contentSha256, true))
                        break
                    }
                    is FileTransferMessage.Error -> error(response.reason)
                    else -> error("Unexpected attachment-transfer response")
                }
                output.fd.sync()
            }
            require(received == attachment.sizeBytes && digest.digest().toHex().equals(attachment.contentSha256, true)) {
                "Received attachment does not match its signed metadata"
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    suspend fun serve(connection: AuthenticatedPeerConnection, request: FileTransferMessage.AttachmentRequest, onBytes: (Long) -> Unit = {}) {
        val profile = store.profile()
        val message = profile?.let { store.chatMessage(it.groupId, request.messageId) }
        val attachment = message?.attachment
        val source = message?.let(::localPath)
        if (
            attachment == null || source == null ||
            !attachment.contentSha256.equals(request.contentSha256, true)
        ) {
            connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error("Requested chat attachment is unavailable")))
            return
        }
        connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileStart(attachment.sizeBytes, message.createdAtMillis)))
        Files.newInputStream(source).buffered().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var sequence = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileChunk(sequence++, buffer.copyOf(count))))
                    onBytes(count.toLong())
                }
            }
        }
        connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileEnd(attachment.contentSha256)))
    }

    private fun attachmentPath(message: MeshChatMessage): Path =
        root.resolve(message.messageId).resolve(requireNotNull(message.attachment).fileName).normalize().also {
            require(it.startsWith(root)) { "Attachment path escapes its cache" }
        }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").let { digest ->
        Files.newInputStream(path).buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private companion object { const val CHUNK_SIZE = 64 * 1024 }
}
