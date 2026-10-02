package com.syncdroid.app.mesh

import com.syncdroid.app.data.ChatMessageEntity
import com.syncdroid.app.data.SyncDroidDatabase
import com.syncdroid.shared.protocol.canonicalChatPayload
import com.syncdroid.shared.protocol.eventIdFor
import com.syncdroid.shared.protocol.verifyEcdsaSha256
import com.syncdroid.shared.protocol.WireChatAttachment
import java.nio.charset.StandardCharsets
import java.security.PublicKey
import java.util.Base64

data class MeshChatMessage(
    val messageId: String,
    val groupId: String,
    val authorDeviceId: String,
    val body: String,
    val createdAtMillis: Long,
    val signatureBase64: String,
    val attachment: WireChatAttachment? = null,
) {
    fun canonicalPayload(): ByteArray = canonicalChatPayload(
        groupId = groupId,
        authorDeviceId = authorDeviceId,
        body = body,
        createdAtMillis = createdAtMillis,
        attachment = attachment,
    )

    fun hasValidMessageId(): Boolean = messageId == eventIdFor(canonicalPayload())

    fun verifySignature(publicKey: PublicKey): Boolean =
        verifyEcdsaSha256(publicKey, canonicalPayload(), signatureBase64)

    companion object {
        fun create(
            groupId: String,
            body: String,
            signer: DeviceSigner,
            createdAtMillis: Long = System.currentTimeMillis(),
            attachment: WireChatAttachment? = null,
        ): MeshChatMessage {
            val cleanBody = body.trim()
            require(cleanBody.isNotEmpty() || attachment != null) { "A chat message cannot be empty" }
            require(cleanBody.toByteArray(StandardCharsets.UTF_8).size <= MAX_CHAT_BODY_BYTES) {
                "A chat message is too long"
            }
            attachment?.validateForChat(createdAtMillis)
            val payload = canonicalChatPayload(groupId, signer.deviceId, cleanBody, createdAtMillis, attachment)
            return MeshChatMessage(
                messageId = eventIdFor(payload),
                groupId = groupId,
                authorDeviceId = signer.deviceId,
                body = cleanBody,
                createdAtMillis = createdAtMillis,
                signatureBase64 = Base64.getEncoder().encodeToString(signer.sign(payload)),
                attachment = attachment,
            )
        }
    }
}

class MeshChatRepository(
    database: SyncDroidDatabase,
    private val signer: DeviceSigner,
) {
    private val meshDao = database.meshDao()
    private val chatDao = database.chatDao()

    suspend fun send(groupId: String, body: String): MeshChatMessage {
        return createSigned(groupId, body).also { applyVerified(it) }
    }

    suspend fun createSigned(
        groupId: String,
        body: String,
        attachment: WireChatAttachment? = null,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): MeshChatMessage {
        val author = requireNotNull(meshDao.getDevice(groupId, signer.deviceId)) {
            "The current device is not a member of this mesh"
        }
        require(author.trustState == TRUSTED) { "The current device is not trusted" }
        return MeshChatMessage.create(groupId, body, signer, createdAtMillis, attachment)
    }

    suspend fun receive(message: MeshChatMessage): Boolean {
        if (chatDao.getMessage(message.groupId, message.messageId) != null) return false
        return applyVerified(message)
    }

    private suspend fun applyVerified(message: MeshChatMessage): Boolean {
        require(message.body.toByteArray(StandardCharsets.UTF_8).size <= MAX_CHAT_BODY_BYTES) {
            "A chat message is too long"
        }
        require(message.body == message.body.trim() && (message.body.isNotEmpty() || message.attachment != null)) {
            "A chat message has invalid whitespace"
        }
        message.attachment?.validateForChat(message.createdAtMillis)
        require(message.hasValidMessageId()) { "Chat message ID does not match its payload" }
        requireNotNull(meshDao.getDevice(message.groupId, message.authorDeviceId)) {
            "Chat message author is not a member of this mesh"
        }
        val author = requireNotNull(meshDao.signerAt(message.groupId, message.authorDeviceId, message.createdAtMillis)) {
            "Chat message author is not trusted"
        }
        require(message.verifySignature(decodePublicKey(author.publicKeyBase64))) {
            "Chat message signature is invalid"
        }
        return chatDao.insertMessage(message.toEntity()) != -1L
    }
}

internal fun ChatMessageEntity.toDomain() = MeshChatMessage(
    messageId = messageId,
    groupId = groupId,
    authorDeviceId = authorDeviceId,
    body = body,
    createdAtMillis = createdAtMillis,
    signatureBase64 = signatureBase64,
    attachment = attachmentFileName?.let {
        WireChatAttachment(
            it,
            attachmentMediaType.orEmpty(),
            attachmentSizeBytes ?: 0L,
            attachmentSha256.orEmpty(),
            attachmentExpiresAtMillis ?: 0L,
        )
    },
)

private fun MeshChatMessage.toEntity() = ChatMessageEntity(
    messageId = messageId,
    groupId = groupId,
    authorDeviceId = authorDeviceId,
    body = body,
    createdAtMillis = createdAtMillis,
    signatureBase64 = signatureBase64,
    attachmentFileName = attachment?.fileName,
    attachmentMediaType = attachment?.mediaType,
    attachmentSizeBytes = attachment?.sizeBytes,
    attachmentSha256 = attachment?.contentSha256,
    attachmentExpiresAtMillis = attachment?.expiresAtMillis,
)

internal fun WireChatAttachment.validateForChat(createdAtMillis: Long) {
    require(fileName.isNotBlank() && fileName.length <= 255 && fileName.none { it == '/' || it == '\\' || it == '\u0000' }) {
        "A chat attachment has an invalid file name"
    }
    require(mediaType.length <= 255) { "A chat attachment media type is too long" }
    require(sizeBytes in 0..MAX_CHAT_ATTACHMENT_BYTES) { "A chat attachment is too large" }
    require(contentSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "A chat attachment hash is invalid" }
    require(expiresAtMillis == createdAtMillis + CHAT_ATTACHMENT_RETENTION_MILLIS) {
        "A chat attachment must expire after 30 days"
    }
}

private const val MAX_CHAT_BODY_BYTES = 4_000
internal const val CHAT_ATTACHMENT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1_000
private const val MAX_CHAT_ATTACHMENT_BYTES = 100L * 1024 * 1024 * 1024
private const val TRUSTED = "TRUSTED"
