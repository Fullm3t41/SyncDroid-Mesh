package com.syncdeck.app.mesh

import com.syncdroid.shared.protocol.FileTransferMessage
import com.syncdroid.shared.sync.ContentBlockManifestBuilder
import com.syncdroid.shared.sync.ResumableTransferProgress
import com.syncdroid.shared.sync.isCompatiblePartialTransfer
import com.syncdroid.shared.sync.resumableTransferId
import com.syncdroid.shared.sync.validateReceivedBlock
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

data class BlockManifest(
    val folderId: String,
    val fileId: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val contentSha256: String,
    val blockSizeBytes: Int,
    val blocks: List<FileBlock>,
)

data class PartialTransfer(
    val folderId: String,
    val fileId: String,
    val contentSha256: String,
    val temporaryPath: String,
    val totalSizeBytes: Long,
    val blockSizeBytes: Int,
    val receivedBlocksBase64: String,
    val updatedAtMillis: Long,
)

object BlockManifestBuilder {
    fun build(version: FileVersion, source: Path): BlockManifest {
        require(!version.deleted && Files.isRegularFile(source)) { "Cannot build blocks for an unavailable file" }
        val content = Files.newInputStream(source).buffered().use { input ->
            ContentBlockManifestBuilder.build(version.sizeBytes, input)
        }
        require(content.contentSha256.equals(version.contentSha256, true)) {
            "File changed while its block manifest was created"
        }
        return BlockManifest(
            version.folderId,
            version.fileId,
            version.relativePath,
            version.sizeBytes,
            version.modifiedAtMillis,
            version.contentSha256.lowercase(),
            content.blockSizeBytes,
            content.blocks.map { FileBlock(it.index, it.offsetBytes, it.sizeBytes, it.sha256) },
        )
    }

    fun adaptiveBlockSize(fileSize: Long): Int = ContentBlockManifestBuilder.adaptiveBlockSize(fileSize)

    const val RESUMABLE_THRESHOLD_BYTES = ContentBlockManifestBuilder.RESUMABLE_THRESHOLD_BYTES
}

class ResumableBlockReceiver(
    private val store: MeshStore,
    private val temporaryDirectory: Path,
    private val applier: AtomicFileApplier,
) {
    init { Files.createDirectories(temporaryDirectory) }

    fun missingBlocks(manifest: BlockManifest): List<Int> {
        val state = loadOrCreate(manifest)
        val progress = ResumableTransferProgress(state.receivedBlocksBase64)
        val missing = progress.missingBlocks(manifest.blocks)
        if (missing.isEmpty()) complete(manifest, Path.of(state.temporaryPath))
        return missing
    }

    fun acceptBlock(manifest: BlockManifest, blockIndex: Int, data: ByteArray): Boolean {
        val block = manifest.blocks.firstOrNull { it.index == blockIndex } ?: error("Unknown block index")
        validateReceivedBlock(block, blockIndex, data)
        val state = loadOrCreate(manifest)
        RandomAccessFile(state.temporaryPath, "rw").use { file ->
            file.setLength(manifest.sizeBytes)
            file.seek(block.offsetBytes)
            file.write(data)
            file.fd.sync()
        }
        val progress = ResumableTransferProgress(state.receivedBlocksBase64).record(blockIndex)
        store.upsertPartialTransfer(
            state.copy(receivedBlocksBase64 = progress.receivedBlocksBase64, updatedAtMillis = System.currentTimeMillis()),
        )
        if (!progress.isComplete(manifest.blocks)) return false
        complete(manifest, Path.of(state.temporaryPath))
        return true
    }

    private fun loadOrCreate(manifest: BlockManifest): PartialTransfer {
        store.partialTransfer(manifest.folderId, manifest.fileId, manifest.contentSha256)?.let { existing ->
            val path = runCatching { Path.of(existing.temporaryPath).toAbsolutePath().normalize() }.getOrNull()
            val safe = path != null && path.startsWith(temporaryDirectory.toAbsolutePath().normalize())
            if (safe && isCompatiblePartialTransfer(
                    manifest.sizeBytes, manifest.blockSizeBytes, existing.totalSizeBytes, existing.blockSizeBytes,
                ) && Files.isRegularFile(path)
            ) return existing
            store.deletePartialTransfer(manifest.folderId, manifest.fileId, manifest.contentSha256)
            if (safe) Files.deleteIfExists(path)
        }
        val transferId = resumableTransferId(manifest.folderId, manifest.fileId, manifest.contentSha256)
        val temporary = temporaryDirectory.resolve("$transferId.part").toAbsolutePath().normalize()
        require(temporary.startsWith(temporaryDirectory.toAbsolutePath().normalize()))
        RandomAccessFile(temporary.toFile(), "rw").use { it.setLength(manifest.sizeBytes) }
        return PartialTransfer(
            manifest.folderId,
            manifest.fileId,
            manifest.contentSha256,
            temporary.toString(),
            manifest.sizeBytes,
            manifest.blockSizeBytes,
            ResumableTransferProgress().receivedBlocksBase64,
            System.currentTimeMillis(),
        ).also(store::upsertPartialTransfer)
    }

    private fun complete(manifest: BlockManifest, temporary: Path) {
        val actual = FileInputStream(temporary.toFile()).buffered().use(::sha256Hex)
        if (!actual.equals(manifest.contentSha256, true)) {
            store.deletePartialTransfer(manifest.folderId, manifest.fileId, manifest.contentSha256)
            Files.deleteIfExists(temporary)
            error("Completed file hash does not match its manifest")
        }
        FileInputStream(temporary.toFile()).buffered().use { input ->
            applier.apply(manifest.relativePath, input, manifest.contentSha256, manifest.modifiedAtMillis)
        }
        store.deletePartialTransfer(manifest.folderId, manifest.fileId, manifest.contentSha256)
        Files.deleteIfExists(temporary)
    }
}

class ResumableBlockPeerClient(
    private val receiver: ResumableBlockReceiver,
    private val onBytesTransferred: (Long) -> Unit = {},
) {
    suspend fun fetchMissing(connection: AuthenticatedPeerConnection, manifest: BlockManifest): Boolean {
        val missing = receiver.missingBlocks(manifest)
        if (missing.isEmpty()) return true
        for (index in missing) {
            connection.send(
                FileTransferWireCodec.encode(
                    FileTransferMessage.BlockRequest(
                        manifest.folderId,
                        manifest.fileId,
                        manifest.relativePath,
                        manifest.contentSha256,
                        index,
                    ),
                ),
            )
            when (val response = FileTransferWireCodec.decode(connection.receive())) {
                is FileTransferMessage.BlockResponse -> {
                    require(response.blockIndex == index) { "Peer returned the wrong block" }
                    if (receiver.acceptBlock(manifest, index, response.data)) {
                        onBytesTransferred(response.data.size.toLong())
                        return true
                    }
                    onBytesTransferred(response.data.size.toLong())
                }
                is FileTransferMessage.Error -> error(response.reason)
                else -> error("Unexpected block-transfer response")
            }
        }
        return false
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
