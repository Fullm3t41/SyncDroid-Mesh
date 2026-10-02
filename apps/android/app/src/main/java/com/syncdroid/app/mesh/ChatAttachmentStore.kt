package com.syncdroid.app.mesh

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.syncdroid.app.R
import com.syncdroid.app.data.SyncDroidDatabase
import com.syncdroid.app.sync.FileTransferWireCodec
import com.syncdroid.shared.protocol.FileTransferMessage
import com.syncdroid.shared.protocol.WireChatAttachment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

data class PreparedChatAttachment(
    val temporaryFile: File,
    val metadata: WireChatAttachment,
)

class ChatAttachmentStore(
    context: Context,
    private val database: SyncDroidDatabase,
) {
    private val root = File(context.filesDir, "chat-attachments").also(File::mkdirs)
    private val staging = File(root, ".staging").also(File::mkdirs)

    fun prepare(resolver: ContentResolver, uri: Uri, createdAtMillis: Long): PreparedChatAttachment {
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.substringAfterLast('/')?.substringAfterLast('\\')?.take(255)?.ifBlank { null } ?: "Attachment"
        val temporary = File.createTempFile("chat-", ".part", staging)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            requireNotNull(resolver.openInputStream(uri)) { "The selected attachment is unavailable" }.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) { output.write(buffer, 0, count); digest.update(buffer, 0, count) }
                    }
                    output.fd.sync()
                }
            }
            val metadata = WireChatAttachment(
                fileName = fileName,
                mediaType = resolver.getType(uri).orEmpty(),
                sizeBytes = temporary.length(),
                contentSha256 = digest.digest().toHex(),
                expiresAtMillis = createdAtMillis + CHAT_ATTACHMENT_RETENTION_MILLIS,
            ).also { it.validateForChat(createdAtMillis) }
            return PreparedChatAttachment(temporary, metadata)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun commit(message: MeshChatMessage, prepared: PreparedChatAttachment) {
        require(message.attachment == prepared.metadata)
        val destination = attachmentFile(message)
        require(destination.parentFile?.mkdirs() != false)
        require(prepared.temporaryFile.renameTo(destination) || runCatching {
            prepared.temporaryFile.copyTo(destination, overwrite = true)
            prepared.temporaryFile.delete()
        }.isSuccess) { "Could not retain the selected chat attachment" }
    }

    fun localFile(message: MeshChatMessage): File? = message.attachment
        ?.takeIf { it.expiresAtMillis > System.currentTimeMillis() }
        ?.let { attachmentFile(message) }
        ?.takeIf(File::isFile)

    suspend fun missing(messages: List<MeshChatMessage>, nowMillis: Long = System.currentTimeMillis()): List<MeshChatMessage> =
        messages.filter { it.attachment?.expiresAtMillis?.let { expiry -> expiry > nowMillis } == true && localFile(it) == null }

    suspend fun cleanupExpired(groupId: String, nowMillis: Long = System.currentTimeMillis()) {
        database.chatDao().attachmentMessages(groupId)
            .map { it.toDomain() }
            .filter { it.attachment?.expiresAtMillis?.let { expiry -> expiry <= nowMillis } == true }
            .forEach { message -> File(root, message.messageId).deleteRecursively() }
    }

    suspend fun receive(
        connection: AuthenticatedPeerConnection,
        message: MeshChatMessage,
        onBytes: (Long) -> Unit = {},
    ) {
        val attachment = requireNotNull(message.attachment)
        connection.send(FileTransferWireCodec.encode(FileTransferMessage.AttachmentRequest(message.messageId, attachment.contentSha256)))
        val start = FileTransferWireCodec.decode(connection.receive())
        if (start is FileTransferMessage.Error) error(start.reason)
        require(start is FileTransferMessage.FileStart && start.sizeBytes == attachment.sizeBytes) {
            "Peer advertised a different attachment size"
        }
        val destination = attachmentFile(message)
        require(destination.parentFile?.mkdirs() != false)
        val temporary = File.createTempFile("attachment-", ".part", destination.parentFile)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            var expectedSequence = 0
            var problem: String? = null
            FileOutputStream(temporary).use { output ->
                // Read to the end of an invalid transfer too, so the session's later messages stay in step.
                while (true) when (val response = FileTransferWireCodec.decode(connection.receive())) {
                    is FileTransferMessage.FileChunk -> {
                        if (response.sequence != expectedSequence++) problem = problem ?: "Attachment chunks arrived out of order"
                        received += response.data.size
                        if (received > attachment.sizeBytes) problem = problem ?: "Peer sent too much attachment data"
                        if (problem == null) {
                            output.write(response.data); digest.update(response.data); onBytes(response.data.size.toLong())
                        }
                    }
                    is FileTransferMessage.FileEnd -> {
                        if (!response.contentSha256.equals(attachment.contentSha256, true)) problem = problem ?: "Peer sent a different attachment"
                        break
                    }
                    is FileTransferMessage.Error -> error(response.reason)
                    else -> error("Unexpected attachment-transfer response")
                }
                output.fd.sync()
            }
            problem?.let(::error)
            require(received == attachment.sizeBytes && digest.digest().toHex().equals(attachment.contentSha256, true)) {
                "Received attachment does not match its signed metadata"
            }
            require(temporary.renameTo(destination) || runCatching { temporary.copyTo(destination, true); temporary.delete() }.isSuccess)
        } finally { temporary.delete() }
    }

    suspend fun serve(
        connection: AuthenticatedPeerConnection,
        groupId: String,
        request: FileTransferMessage.AttachmentRequest,
        onBytes: (Long) -> Unit = {},
    ) {
        val message = database.chatDao().getMessage(groupId, request.messageId)?.toDomain()
        val attachment = message?.attachment
        val source = message?.let(::localFile)
        if (attachment == null || source == null || !attachment.contentSha256.equals(request.contentSha256, true)) {
            connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error("Requested chat attachment is unavailable")))
            return
        }
        // The cached copy can change after it was shared; never send other content than the message describes.
        if (source.length() != attachment.sizeBytes) {
            connection.send(FileTransferWireCodec.encode(FileTransferMessage.Error("Requested chat attachment is unavailable")))
            return
        }
        connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileStart(attachment.sizeBytes, message.createdAtMillis)))
        FileInputStream(source).buffered().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var sequence = 0
            var sent = 0L
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), attachment.sizeBytes - sent).toInt())
                if (count <= 0) break
                sent += count
                connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileChunk(sequence++, buffer.copyOf(count))))
                onBytes(count.toLong())
            }
        }
        connection.send(FileTransferWireCodec.encode(FileTransferMessage.FileEnd(attachment.contentSha256)))
    }

    private fun attachmentFile(message: MeshChatMessage): File {
        val directory = File(root, message.messageId).canonicalFile
        require(directory.toPath().startsWith(root.canonicalFile.toPath()))
        return File(directory, requireNotNull(message.attachment).fileName).canonicalFile.also {
            require(it.toPath().startsWith(directory.toPath()))
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private companion object { const val CHUNK_SIZE = 64 * 1024 }
}

class ChatAttachmentFileProvider : FileProvider(R.xml.chat_attachment_paths)
