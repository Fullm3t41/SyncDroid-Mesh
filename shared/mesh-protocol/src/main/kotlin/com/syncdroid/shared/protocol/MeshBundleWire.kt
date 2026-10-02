package com.syncdroid.shared.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

data class WireMembershipEvent(
    val eventId: String,
    val groupId: String,
    val eventType: String,
    val subjectDeviceId: String,
    val subjectDisplayName: String,
    val subjectPublicKeyBase64: String,
    val signerDeviceId: String,
    val parentEventIds: List<String>,
    val version: VersionVector,
    val createdAtMillis: Long,
    val signatureBase64: String,
)

data class WireFolderAnnouncement(
    val eventId: String,
    val groupId: String,
    val folderId: String,
    val displayName: String,
    val includePatterns: List<String>,
    val excludePatterns: List<String>,
    val signerDeviceId: String,
    val version: VersionVector,
    val createdAtMillis: Long,
    val signatureBase64: String,
)

data class SyncExceptionEvent(
    val eventId: String,
    val groupId: String,
    val folderId: String,
    val relativePath: String,
    val active: Boolean,
    val signerDeviceId: String,
    val version: VersionVector,
    val createdAtMillis: Long,
    val signatureBase64: String,
) {
    fun canonicalPayload(): ByteArray = canonicalSyncExceptionPayload(
        groupId,
        folderId,
        normalizeMeshRelativePath(relativePath),
        active,
        signerDeviceId,
        version,
        createdAtMillis,
    )

    fun hasValidEventId(): Boolean = eventId == eventIdFor(canonicalPayload())

    companion object
}

data class WireChatMessage(
    val messageId: String,
    val groupId: String,
    val authorDeviceId: String,
    val body: String,
    val createdAtMillis: Long,
    val signatureBase64: String,
    val attachment: WireChatAttachment? = null,
)

data class WireChatAttachment(
    val fileName: String,
    val mediaType: String,
    val sizeBytes: Long,
    val contentSha256: String,
    val expiresAtMillis: Long,
)

data class MeshStateBundleWire(
    val groupName: String,
    val membershipEvents: List<WireMembershipEvent>,
    val folderAnnouncements: List<WireFolderAnnouncement> = emptyList(),
    val syncExceptionEvents: List<SyncExceptionEvent> = emptyList(),
    val chatMessages: List<WireChatMessage> = emptyList(),
)

fun canonicalSyncExceptionPayload(
    groupId: String,
    folderId: String,
    relativePath: String,
    active: Boolean,
    signerDeviceId: String,
    version: VersionVector,
    createdAtMillis: Long,
): ByteArray = canonicalBytes {
    string("syncdroid-exception-v1")
    string(groupId)
    string(folderId)
    string(normalizeMeshRelativePath(relativePath))
    bool(active)
    string(signerDeviceId)
    int64(createdAtMillis)
    string(version.toJson())
}

fun createSignedSyncExceptionEvent(
    groupId: String,
    folderId: String,
    relativePath: String,
    active: Boolean,
    signerDeviceId: String,
    version: VersionVector,
    createdAtMillis: Long,
    sign: (ByteArray) -> ByteArray,
): SyncExceptionEvent {
    val normalizedPath = normalizeMeshRelativePath(relativePath)
    val payload = canonicalSyncExceptionPayload(
        groupId, folderId, normalizedPath, active, signerDeviceId, version, createdAtMillis,
    )
    return SyncExceptionEvent(
        eventIdFor(payload), groupId, folderId, normalizedPath, active, signerDeviceId,
        version, createdAtMillis, java.util.Base64.getEncoder().encodeToString(sign(payload)),
    )
}

fun normalizeMeshRelativePath(path: String): String {
    val normalized = path.replace('\\', '/').trim('/')
    require(normalized.isNotEmpty() && normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "Invalid mesh relative path"
    }
    return normalized
}

/** Bounded SDMB v2 codec with read compatibility for SyncDroid's legacy v1 bundles. */
object MeshBundleWireCodec {
    fun encode(bundle: MeshStateBundleWire): ByteArray = ByteArrayOutputStream().use { bytes ->
        require(bundle.membershipEvents.size <= MAX_ITEMS) { "Too many membership events" }
        require(bundle.folderAnnouncements.size <= MAX_ITEMS) { "Too many folder announcements" }
        require(bundle.syncExceptionEvents.size <= MAX_ITEMS) { "Too many exception events" }
        require(bundle.chatMessages.size <= MAX_ITEMS) { "Too many chat messages" }
        DataOutputStream(bytes).use { output ->
            output.write(MAGIC_V2)
            output.writeShort(PROTOCOL_MAJOR)
            output.writeShort(PROTOCOL_MINOR)
            output.writeUtf8(bundle.groupName)
            output.writeInt(bundle.membershipEvents.size)
            bundle.membershipEvents.forEach { output.writeMembership(it) }
            output.writeInt(bundle.folderAnnouncements.size)
            bundle.folderAnnouncements.forEach { output.writeFolder(it) }
            output.writeInt(bundle.syncExceptionEvents.size)
            bundle.syncExceptionEvents.forEach { output.writeException(it) }
            output.writeInt(bundle.chatMessages.size)
            bundle.chatMessages.forEach { output.writeChat(it) }
        }
        require(bytes.size() <= MAX_BUNDLE_BYTES) { "Mesh metadata bundle is too large" }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): MeshStateBundleWire = runCatching {
        require(bytes.size <= MAX_BUNDLE_BYTES) { "Mesh metadata bundle is too large" }
        if (bytes.startsWith(MAGIC_V2)) decodeV2(bytes) else decodeLegacyV1(bytes)
    }.getOrElse { error ->
        if (error is IllegalArgumentException) throw error
        throw IllegalArgumentException("Invalid mesh metadata bundle", error)
    }

    private fun decodeV2(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(ByteArray(MAGIC_V2.size).also(input::readFully).contentEquals(MAGIC_V2)) {
            "Unsupported mesh protocol"
        }
        val major = input.readUnsignedShort()
        val minor = input.readUnsignedShort()
        require(major == PROTOCOL_MAJOR) { "Unsupported mesh protocol major version: $major" }
        require(minor <= PROTOCOL_MINOR) { "Unsupported mesh protocol minor version: $minor" }
        val groupName = input.readUtf8()
        val memberships = List(input.readSafeCount()) { input.readMembership() }
        val folders = List(input.readSafeCount()) { input.readFolder() }
        val exceptions = if (minor >= 1) List(input.readSafeCount()) { input.readException() } else emptyList()
        val chat = if (minor >= 2) List(input.readSafeCount()) { input.readChat(minor) } else emptyList()
        require(input.available() == 0) { "Unexpected data after mesh bundle" }
        MeshStateBundleWire(groupName, memberships, folders, exceptions, chat)
    }

    private fun decodeLegacyV1(bytes: ByteArray) = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readUTF() == LEGACY_MAGIC_V1) { "Unsupported mesh protocol" }
        val groupName = input.readUTF()
        val memberships = List(input.readSafeCount()) { input.readLegacyMembership() }
        val folders = List(input.readSafeCount()) { input.readLegacyFolder() }
        require(input.available() == 0) { "Unexpected data after mesh bundle" }
        MeshStateBundleWire(groupName, memberships, folders)
    }

    private fun DataOutputStream.writeMembership(value: WireMembershipEvent) {
        writeUtf8(value.eventId); writeUtf8(value.groupId); writeUtf8(value.eventType)
        writeUtf8(value.subjectDeviceId); writeUtf8(value.subjectDisplayName); writeUtf8(value.subjectPublicKeyBase64)
        writeUtf8(value.signerDeviceId); writeList(value.parentEventIds); writeUtf8(value.version.toJson())
        writeLong(value.createdAtMillis); writeUtf8(value.signatureBase64)
    }

    private fun DataInputStream.readMembership() = WireMembershipEvent(
        readUtf8(), readUtf8(), readUtf8(), readUtf8(), readUtf8(), readUtf8(),
        readUtf8(), readList(), VersionVector.fromJson(readUtf8()), readLong(), readUtf8(),
    )

    private fun DataOutputStream.writeFolder(value: WireFolderAnnouncement) {
        writeUtf8(value.eventId); writeUtf8(value.groupId); writeUtf8(value.folderId); writeUtf8(value.displayName)
        writeList(value.includePatterns); writeList(value.excludePatterns); writeUtf8(value.signerDeviceId)
        writeUtf8(value.version.toJson()); writeLong(value.createdAtMillis); writeUtf8(value.signatureBase64)
    }

    private fun DataInputStream.readFolder() = WireFolderAnnouncement(
        readUtf8(), readUtf8(), readUtf8(), readUtf8(), readList(), readList(), readUtf8(),
        VersionVector.fromJson(readUtf8()), readLong(), readUtf8(),
    )

    private fun DataOutputStream.writeException(value: SyncExceptionEvent) {
        writeUtf8(value.eventId); writeUtf8(value.groupId); writeUtf8(value.folderId); writeUtf8(value.relativePath)
        writeBoolean(value.active); writeUtf8(value.signerDeviceId); writeUtf8(value.version.toJson())
        writeLong(value.createdAtMillis); writeUtf8(value.signatureBase64)
    }

    private fun DataInputStream.readException() = SyncExceptionEvent(
        readUtf8(), readUtf8(), readUtf8(), readUtf8(), readBoolean(), readUtf8(),
        VersionVector.fromJson(readUtf8()), readLong(), readUtf8(),
    )

    private fun DataOutputStream.writeChat(value: WireChatMessage) {
        writeUtf8(value.messageId); writeUtf8(value.groupId); writeUtf8(value.authorDeviceId); writeUtf8(value.body)
        writeLong(value.createdAtMillis); writeUtf8(value.signatureBase64)
        writeBoolean(value.attachment != null)
        value.attachment?.let { attachment ->
            writeUtf8(attachment.fileName)
            writeUtf8(attachment.mediaType)
            writeLong(attachment.sizeBytes)
            writeUtf8(attachment.contentSha256)
            writeLong(attachment.expiresAtMillis)
        }
    }

    private fun DataInputStream.readChat(minor: Int): WireChatMessage {
        val messageId = readUtf8()
        val groupId = readUtf8()
        val authorDeviceId = readUtf8()
        val body = readUtf8()
        val createdAtMillis = readLong()
        val signatureBase64 = readUtf8()
        val attachment = if (minor >= 4 && readBoolean()) WireChatAttachment(
            readUtf8(), readUtf8(), readLong(), readUtf8(), readLong(),
        ) else null
        return WireChatMessage(
            messageId, groupId, authorDeviceId, body, createdAtMillis, signatureBase64, attachment,
        )
    }

    private fun DataInputStream.readLegacyMembership() = WireMembershipEvent(
        readUTF(), readUTF(), readUTF(), readUTF(), readUTF(), readUTF(),
        readUTF(), readLegacyList(), VersionVector.fromJson(readUTF()), readLong(), readUTF(),
    )

    private fun DataInputStream.readLegacyFolder() = WireFolderAnnouncement(
        readUTF(), readUTF(), readUTF(), readUTF(), readLegacyList(), readLegacyList(),
        readUTF(), VersionVector.fromJson(readUTF()), readLong(), readUTF(),
    )

    private fun DataOutputStream.writeUtf8(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_STRING_BYTES) { "Mesh string is too large" }
        writeInt(encoded.size)
        write(encoded)
    }
    private fun DataInputStream.readUtf8(): String {
        val size = readInt().also { require(it in 0..MAX_STRING_BYTES) { "Invalid mesh string length" } }
        return String(ByteArray(size).also(::readFully), StandardCharsets.UTF_8)
    }
    private fun DataOutputStream.writeList(values: List<String>) {
        require(values.size <= MAX_ITEMS) { "Mesh list is too large" }
        writeInt(values.size)
        values.forEach { writeUtf8(it) }
    }
    private fun DataInputStream.readList() = List(readSafeCount()) { readUtf8() }
    private fun DataInputStream.readLegacyList() = List(readSafeCount()) { readUTF() }
    private fun DataInputStream.readSafeCount() = readInt().also {
        require(it in 0..MAX_ITEMS) { "Invalid mesh item count" }
    }
    private fun ByteArray.startsWith(prefix: ByteArray) =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val MAGIC_V2 = byteArrayOf('S'.code.toByte(), 'D'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())
    private const val LEGACY_MAGIC_V1 = "syncdroid-mesh-state-v1"
    private const val PROTOCOL_MAJOR = 2
    private const val PROTOCOL_MINOR = 4
    private const val MAX_ITEMS = 10_000
    private const val MAX_STRING_BYTES = 1024 * 1024
    private const val MAX_BUNDLE_BYTES = 16 * 1024 * 1024
}

/**
 * The newest chat messages that fit in a metadata bundle alongside its other records. Five
 * thousand long messages would exceed the 16 MiB bundle limit and fail every session.
 * [messages] must be oldest first.
 */
fun <T> newestChatWithinBudget(messages: List<T>, bodyLength: (T) -> Int): List<T> {
    var remaining = CHAT_BUNDLE_BUDGET_BYTES
    val kept = messages.asReversed().takeWhile { message ->
        remaining -= CHAT_MESSAGE_OVERHEAD_BYTES + 3L * bodyLength(message)
        remaining >= 0
    }
    return kept.asReversed()
}

private const val CHAT_BUNDLE_BUDGET_BYTES = 6L * 1024 * 1024
private const val CHAT_MESSAGE_OVERHEAD_BYTES = 1_024L
