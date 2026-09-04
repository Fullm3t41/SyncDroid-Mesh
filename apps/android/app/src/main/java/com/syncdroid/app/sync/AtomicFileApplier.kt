package com.syncdroid.app.sync

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class AtomicFileApplier(
    private val rootDirectory: File,
    private val expectedContent: com.syncdroid.shared.sync.ExpectedFileContent? = null,
) : SyncFileApplier {
    private val root: File = rootDirectory.canonicalFile.also {
        require(it.isDirectory || it.mkdirs()) { "Could not create sync root" }
    }

    override fun apply(
        relativePath: String,
        input: InputStream,
        expectedSha256: String,
        sourceModifiedAtMillis: Long?,
    ) {
        val target = safeTarget(relativePath)
        require(target.parentFile?.mkdirs() != false || target.parentFile?.isDirectory == true) {
            "Could not create destination directory"
        }
        val temporary = File(target.parentFile, ".syncdroid-${UUID.randomUUID()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(temporary).use { output ->
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
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(expectedSha256, ignoreCase = true)) { "Received file hash does not match its manifest" }
            expectedContent?.verify(
                if (target.exists()) {
                    require(target.isFile) { "Sync destination is no longer a file" }
                    target.inputStream().buffered().use(FileHasher::sha256)
                } else null,
            )
            moveIntoPlace(temporary, target)
            if (sourceModifiedAtMillis != null && sourceModifiedAtMillis > 0) {
                target.setLastModified(sourceModifiedAtMillis)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    override fun delete(relativePath: String) {
        val target = safeTarget(relativePath)
        if (target.exists()) require(target.isFile && target.delete()) { "Could not delete synced file" }
    }

    private fun safeTarget(relativePath: String): File {
        require(relativePath.isNotBlank()) { "Relative path cannot be blank" }
        require(!File(relativePath).isAbsolute) { "Absolute sync paths are not allowed" }
        val target = File(root, relativePath).canonicalFile
        require(target.toPath().startsWith(root.toPath()) && target != root) { "Sync path escapes its folder" }
        return target
    }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
