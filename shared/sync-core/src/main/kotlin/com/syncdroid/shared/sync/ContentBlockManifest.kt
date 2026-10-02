package com.syncdroid.shared.sync

import java.io.InputStream
import java.security.MessageDigest

data class ContentBlock(
    val index: Int,
    val offsetBytes: Long,
    val sizeBytes: Int,
    val sha256: String,
)

data class ContentBlockManifest(
    val sizeBytes: Long,
    val contentSha256: String,
    val blockSizeBytes: Int,
    val blocks: List<ContentBlock>,
)

object ContentBlockManifestBuilder {
    fun build(sizeBytes: Long, input: InputStream): ContentBlockManifest {
        require(sizeBytes >= 0) { "File size cannot be negative" }
        val blockSize = adaptiveBlockSize(sizeBytes)
        val wholeDigest = MessageDigest.getInstance("SHA-256")
        val blocks = mutableListOf<ContentBlock>()
        val buffer = ByteArray(blockSize)
        var offset = 0L
        var index = 0
        while (true) {
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read > 0) count += read
            }
            if (count == 0) break
            wholeDigest.update(buffer, 0, count)
            val digest = MessageDigest.getInstance("SHA-256").digest(buffer.copyOf(count)).toHex()
            blocks += ContentBlock(index++, offset, count, digest)
            offset += count
        }
        if (blocks.isEmpty()) {
            blocks += ContentBlock(0, 0, 0, MessageDigest.getInstance("SHA-256").digest().toHex())
        }
        require(offset == sizeBytes) { "File changed size while its block manifest was created" }
        return ContentBlockManifest(sizeBytes, wholeDigest.digest().toHex(), blockSize, blocks)
    }

    fun adaptiveBlockSize(fileSize: Long): Int {
        require(fileSize >= 0) { "File size cannot be negative" }
        var size = MIN_BLOCK_SIZE
        while (size < MAX_BLOCK_SIZE && fileSize / size > TARGET_BLOCK_COUNT) size *= 2
        return size
    }

    const val RESUMABLE_THRESHOLD_BYTES = 1L * 1024 * 1024
    private const val MIN_BLOCK_SIZE = 128 * 1024
    // A block travels in one message, which the transport caps at 16 MiB including its header.
    private const val MAX_BLOCK_SIZE = 8 * 1024 * 1024
    private const val TARGET_BLOCK_COUNT = 1_000
}

fun normalizeRelativePath(path: String): String {
    val normalized = path.replace('\\', '/').trim('/')
    require(normalized.isNotEmpty() && normalized.length <= 4_096 &&
        normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "Invalid relative path"
    }
    return normalized
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        this@toHex.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
