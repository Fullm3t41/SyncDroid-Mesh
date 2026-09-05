package com.syncdroid.shared.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

data class FolderClock(
    val folderId: String,
    val localIndexEpoch: Long,
    val localMaxSequence: Long,
    val knownPeerIndexEpoch: Long,
    val knownPeerReceivedSequence: Long,
    val knownPeerAppliedSequence: Long,
)

data class FileBlock(val index: Int, val offsetBytes: Long, val sizeBytes: Int, val sha256: String)

data class IndexedFileRecord(
    val relativePath: String,
    val fileId: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val contentSha256: String,
    val previousContentSha256: String?,
    val originDeviceId: String,
    val deleted: Boolean,
    val version: VersionVector,
    val sequence: Long,
    val blockSizeBytes: Int = 0,
    val blocks: List<FileBlock> = emptyList(),
    val purgeRecovery: Boolean = false,
)

data class FolderIndexUpdate(
    val folderId: String,
    val indexEpoch: Long,
    val previousSequence: Long,
    val lastSequence: Long,
    val fullIndex: Boolean,
    val files: List<IndexedFileRecord>,
)

data class UpdateAssetDescriptor(
    val releaseVersion: String,
    val platformId: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
)

data class SessionFolderKey(val folderId: String, val keyId: String, val keyBytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is SessionFolderKey &&
        folderId == other.folderId && keyId == other.keyId && keyBytes.contentEquals(other.keyBytes)
    override fun hashCode(): Int = 31 * (31 * folderId.hashCode() + keyId.hashCode()) + keyBytes.contentHashCode()
}

sealed interface MeshSessionMessage {
    data class Metadata(val bundle: ByteArray) : MeshSessionMessage
    data class Catalog(val folders: List<FolderClock>) : MeshSessionMessage
    data class IndexBatch(val updates: List<FolderIndexUpdate>) : MeshSessionMessage
    data class TransferPlan(val requestCount: Int) : MeshSessionMessage
    data class FolderKeys(val keys: List<SessionFolderKey>) : MeshSessionMessage
    data object PhaseDone : MeshSessionMessage
    data class UpdateInventory(val assets: List<UpdateAssetDescriptor>) : MeshSessionMessage
    data class UpdateRequest(val sha256: String, val offset: Long, val maxBytes: Int) : MeshSessionMessage
    data class UpdateChunk(val asset: UpdateAssetDescriptor, val offset: Long, val bytes: ByteArray) : MeshSessionMessage {
        override fun equals(other: Any?): Boolean = other is UpdateChunk &&
            asset == other.asset && offset == other.offset && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * (31 * asset.hashCode() + offset.hashCode()) + bytes.contentHashCode()
    }
    data object UpdatePhaseDone : MeshSessionMessage
    data class Error(val reason: String) : MeshSessionMessage
}

/** Wire-compatible with version 2 of the SDMS session and index protocol. */
object MeshSessionWireCodec {
    fun encode(message: MeshSessionMessage): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeShort(VERSION)
            when (message) {
                is MeshSessionMessage.Metadata -> {
                    output.writeByte(METADATA)
                    output.writeData(message.bundle)
                }
                is MeshSessionMessage.Catalog -> {
                    output.writeByte(CATALOG)
                    output.writeCount(message.folders.size)
                    message.folders.forEach { clock ->
                        output.writeString(clock.folderId)
                        output.writeLong(clock.localIndexEpoch)
                        output.writeLong(clock.localMaxSequence)
                        output.writeLong(clock.knownPeerIndexEpoch)
                        output.writeLong(clock.knownPeerReceivedSequence)
                        output.writeLong(clock.knownPeerAppliedSequence)
                    }
                }
                is MeshSessionMessage.IndexBatch -> {
                    val extended = message.updates.any { update -> update.files.any { it.purgeRecovery } }
                    output.writeByte(if (extended) INDEX_BATCH_PERMANENT else INDEX_BATCH)
                    output.writeCount(message.updates.size)
                    message.updates.forEach { output.writeUpdate(it, extended) }
                }
                is MeshSessionMessage.TransferPlan -> {
                    require(message.requestCount in 0..MAX_REQUESTS)
                    output.writeByte(TRANSFER_PLAN)
                    output.writeInt(message.requestCount)
                }
                is MeshSessionMessage.FolderKeys -> {
                    output.writeByte(FOLDER_KEYS)
                    output.writeCount(message.keys.size)
                    message.keys.forEach { key ->
                        require(key.keyBytes.size == 32) { "Folder keys must be 256 bits" }
                        output.writeString(key.folderId)
                        output.writeString(key.keyId)
                        output.writeData(key.keyBytes)
                    }
                }
                MeshSessionMessage.PhaseDone -> output.writeByte(PHASE_DONE)
                is MeshSessionMessage.UpdateInventory -> {
                    output.writeByte(UPDATE_INVENTORY)
                    output.writeCount(message.assets.size)
                    message.assets.forEach { output.writeUpdateAsset(it) }
                }
                is MeshSessionMessage.UpdateRequest -> {
                    require(message.sha256.matches(SHA256_PATTERN))
                    require(message.offset >= 0L)
                    require(message.maxBytes in 1..MAX_UPDATE_CHUNK_BYTES)
                    output.writeByte(UPDATE_REQUEST)
                    output.writeString(message.sha256)
                    output.writeLong(message.offset)
                    output.writeInt(message.maxBytes)
                }
                is MeshSessionMessage.UpdateChunk -> {
                    require(message.offset >= 0L)
                    require(message.bytes.size in 1..MAX_UPDATE_CHUNK_BYTES)
                    output.writeByte(UPDATE_CHUNK)
                    output.writeUpdateAsset(message.asset)
                    output.writeLong(message.offset)
                    output.writeData(message.bytes)
                }
                MeshSessionMessage.UpdatePhaseDone -> output.writeByte(UPDATE_PHASE_DONE)
                is MeshSessionMessage.Error -> {
                    output.writeByte(ERROR)
                    output.writeString(message.reason)
                }
            }
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): MeshSessionMessage = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == MAGIC && input.readUnsignedShort() == VERSION) {
            "Unsupported mesh session protocol"
        }
        val message = when (val type = input.readUnsignedByte()) {
            METADATA -> MeshSessionMessage.Metadata(input.readData())
            CATALOG -> MeshSessionMessage.Catalog(List(input.readCount()) {
                FolderClock(
                    input.readString(), input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong(),
                )
            })
            INDEX_BATCH -> MeshSessionMessage.IndexBatch(List(input.readCount()) { input.readUpdate() })
            INDEX_BATCH_PERMANENT -> MeshSessionMessage.IndexBatch(List(input.readCount()) { input.readUpdate(true) })
            TRANSFER_PLAN -> MeshSessionMessage.TransferPlan(input.readInt().also { require(it in 0..MAX_REQUESTS) })
            FOLDER_KEYS -> MeshSessionMessage.FolderKeys(List(input.readCount()) {
                SessionFolderKey(input.readString(), input.readString(), input.readData().also { require(it.size == 32) })
            })
            PHASE_DONE -> MeshSessionMessage.PhaseDone
            UPDATE_INVENTORY -> MeshSessionMessage.UpdateInventory(List(input.readCount()) { input.readUpdateAsset() })
            UPDATE_REQUEST -> MeshSessionMessage.UpdateRequest(
                sha256 = input.readString().also { require(it.matches(SHA256_PATTERN)) },
                offset = input.readLong().also { require(it >= 0L) },
                maxBytes = input.readInt().also { require(it in 1..MAX_UPDATE_CHUNK_BYTES) },
            )
            UPDATE_CHUNK -> MeshSessionMessage.UpdateChunk(
                asset = input.readUpdateAsset(),
                offset = input.readLong().also { require(it >= 0L) },
                bytes = input.readData().also { require(it.size in 1..MAX_UPDATE_CHUNK_BYTES) },
            )
            UPDATE_PHASE_DONE -> MeshSessionMessage.UpdatePhaseDone
            ERROR -> MeshSessionMessage.Error(input.readString())
            else -> error("Unknown mesh session message $type")
        }
        require(input.available() == 0) { "Trailing mesh session data" }
        message
    }

    private fun DataOutputStream.writeUpdate(update: FolderIndexUpdate, extended: Boolean) {
        writeString(update.folderId)
        writeLong(update.indexEpoch)
        writeLong(update.previousSequence)
        writeLong(update.lastSequence)
        writeBoolean(update.fullIndex)
        writeCount(update.files.size)
        update.files.forEach { file ->
            require(!file.purgeRecovery || file.deleted) { "Recovery purge requires a deletion" }
            writeString(file.relativePath)
            writeString(file.fileId)
            writeLong(file.sizeBytes)
            writeLong(file.modifiedAtMillis)
            writeString(file.contentSha256)
            writeNullableString(file.previousContentSha256)
            writeString(file.originDeviceId)
            writeBoolean(file.deleted)
            writeString(file.version.toJson())
            writeLong(file.sequence)
            writeInt(file.blockSizeBytes)
            writeCount(file.blocks.size)
            file.blocks.forEach { block ->
                writeInt(block.index)
                writeLong(block.offsetBytes)
                writeInt(block.sizeBytes)
                writeString(block.sha256)
            }
            if (extended) writeBoolean(file.purgeRecovery)
        }
    }

    private fun DataInputStream.readUpdate(extended: Boolean = false): FolderIndexUpdate {
        val folderId = readString()
        val epoch = readLong()
        val previous = readLong()
        val last = readLong()
        val full = readBoolean()
        val files = List(readCount()) {
            IndexedFileRecord(
                relativePath = readString(),
                fileId = readString(),
                sizeBytes = readLong(),
                modifiedAtMillis = readLong(),
                contentSha256 = readString(),
                previousContentSha256 = readNullableString(),
                originDeviceId = readString(),
                deleted = readBoolean(),
                version = VersionVector.fromJson(readString()),
                sequence = readLong(),
                blockSizeBytes = readInt(),
                blocks = List(readCount()) { FileBlock(readInt(), readLong(), readInt(), readString()) },
                purgeRecovery = extended && readBoolean(),
            )
        }
        return FolderIndexUpdate(folderId, epoch, previous, last, full, files)
    }

    private fun DataOutputStream.writeUpdateAsset(asset: UpdateAssetDescriptor) {
        require(asset.sha256.matches(SHA256_PATTERN))
        require(asset.sizeBytes > 0L)
        writeString(asset.releaseVersion)
        writeString(asset.platformId)
        writeString(asset.fileName)
        writeString(asset.sha256)
        writeLong(asset.sizeBytes)
    }

    private fun DataInputStream.readUpdateAsset() = UpdateAssetDescriptor(
        releaseVersion = readString(),
        platformId = readString(),
        fileName = readString(),
        sha256 = readString().also { require(it.matches(SHA256_PATTERN)) },
        sizeBytes = readLong().also { require(it > 0L) },
    )

    private fun DataOutputStream.writeString(value: String) = writeData(value.toByteArray(StandardCharsets.UTF_8))
    private fun DataInputStream.readString() = String(readData(), StandardCharsets.UTF_8)
    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }
    private fun DataInputStream.readNullableString(): String? = if (readBoolean()) readString() else null
    private fun DataOutputStream.writeData(value: ByteArray) {
        require(value.size <= MAX_FIELD_BYTES) { "Mesh session field is too large" }
        writeInt(value.size)
        write(value)
    }
    private fun DataInputStream.readData() = ByteArray(readInt().also {
        require(it in 0..MAX_FIELD_BYTES) { "Invalid mesh session field length" }
    }).also(::readFully)
    private fun DataOutputStream.writeCount(value: Int) {
        require(value in 0..MAX_ITEMS)
        writeInt(value)
    }
    private fun DataInputStream.readCount() = readInt().also { require(it in 0..MAX_ITEMS) }

    private const val MAGIC = 0x53444D53
    private const val VERSION = 2
    private const val METADATA = 1
    private const val CATALOG = 2
    private const val INDEX_BATCH = 3
    private const val TRANSFER_PLAN = 4
    private const val PHASE_DONE = 5
    private const val UPDATE_INVENTORY = 6
    private const val UPDATE_REQUEST = 7
    private const val UPDATE_CHUNK = 8
    private const val UPDATE_PHASE_DONE = 9
    private const val FOLDER_KEYS = 10
    private const val INDEX_BATCH_PERMANENT = 11
    private const val ERROR = 127
    private const val MAX_ITEMS = 50_000
    private const val MAX_REQUESTS = 1_000_000
    private const val MAX_FIELD_BYTES = 16 * 1024 * 1024
    private const val MAX_UPDATE_CHUNK_BYTES = 1024 * 1024
    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
}
