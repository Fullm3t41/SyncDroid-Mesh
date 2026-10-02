package com.syncdroid.shared.cloud

import com.syncdroid.shared.protocol.FolderIndexUpdate
import com.syncdroid.shared.protocol.IndexedFileRecord
import com.syncdroid.shared.protocol.VersionVector
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CloudEncryptionTest {
    private val key = FolderKeyMaterial("folder", "key", ByteArray(32) { it.toByte() })

    @Test
    fun manifestRoundTrips() {
        val update = FolderIndexUpdate(
            "folder", 7, 0, 1, true,
            listOf(IndexedFileRecord("save.sav", "file", 3, 4, "abc", null, "phone", false, VersionVector(mapOf("phone" to 1)), 1)),
        )
        val expected = CloudFolderManifest("folder", "Saves", "phone", 99, update)
        val encrypted = CloudEncryptedObjects.encryptManifest(key, expected)
        assertEquals(expected, CloudEncryptedObjects.decryptManifest(key, "phone", encrypted))
    }

    @Test
    fun fileRoundTrips() {
        val directory = Files.createTempDirectory("cloud-encryption-test")
        try {
            val source = directory.resolve("source").also { Files.write(it, byteArrayOf(1, 2, 3, 4)) }
            val encrypted = directory.resolve("encrypted")
            val restored = directory.resolve("restored")
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))
                .joinToString("") { "%02x".format(it) }
            assertFailsWith<IllegalArgumentException> {
                CloudEncryptedObjects.encryptFile(key, "file", "0".repeat(64), source, encrypted)
            }
            CloudEncryptedObjects.encryptFile(key, "file", hash, source, encrypted)
            CloudEncryptedObjects.decryptFile(key, "file", hash, encrypted, restored)
            assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(restored))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun chunkedFilesRoundTripAndRejectTampering() {
        val directory = Files.createTempDirectory("cloud-chunked-test")
        try {
            val content = ByteArray(3_500) { (it * 7).toByte() }
            val source = directory.resolve("source").also { Files.write(it, content) }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
            val encrypted = directory.resolve("encrypted")
            val restored = directory.resolve("restored")
            CloudEncryptedObjects.encryptFileChunked(key, "file", hash, source, encrypted, chunkBytes = 1_000)

            CloudEncryptedObjects.decryptFile(key, "file", hash, encrypted, restored)
            assertContentEquals(content, Files.readAllBytes(restored))

            // Dropping the final chunk must not decrypt as a shorter file.
            val bytes = Files.readAllBytes(encrypted)
            val lastChunk = 1 + 4 + (500 + 16)
            Files.write(encrypted, bytes.copyOf(bytes.size - lastChunk))
            assertFailsWith<Exception> { CloudEncryptedObjects.decryptFile(key, "file", hash, encrypted, restored) }

            // Marking an earlier chunk as final fails authentication too.
            val flagged = bytes.copyOf().also { it[4 + 8 + 4] = 1 }
            Files.write(encrypted, flagged)
            assertFailsWith<Exception> { CloudEncryptedObjects.decryptFile(key, "file", hash, encrypted, restored) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun chunkedFileWithAnExactMultipleOfTheChunkSizeEndsWithAnEmptyFinalChunk() {
        val directory = Files.createTempDirectory("cloud-chunked-exact-test")
        try {
            val content = ByteArray(2_000) { it.toByte() }
            val source = directory.resolve("source").also { Files.write(it, content) }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
            val encrypted = directory.resolve("encrypted")
            val restored = directory.resolve("restored")
            CloudEncryptedObjects.encryptFileChunked(key, "file", hash, source, encrypted, chunkBytes = 1_000)
            CloudEncryptedObjects.decryptFile(key, "file", hash, encrypted, restored)
            assertContentEquals(content, Files.readAllBytes(restored))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
