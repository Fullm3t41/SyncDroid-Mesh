package com.syncdroid.shared.cloud

import com.syncdroid.shared.protocol.FolderIndexUpdate
import com.syncdroid.shared.protocol.MeshSessionMessage
import com.syncdroid.shared.protocol.MeshSessionWireCodec
import com.syncdroid.shared.protocol.WrappedFolderKeyTransfer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class FolderKeyMaterial(val folderId: String, val keyId: String, val bytes: ByteArray) {
    init { require(bytes.size == 32) { "Folder keys must be 256 bits" } }
}

object PairingFolderKeyCrypto {
    fun wrap(key: FolderKeyMaterial, pairingSessionKey: ByteArray): WrappedFolderKeyTransfer {
        require(pairingSessionKey.size == 32)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(pairingSessionKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD("${key.folderId}\u0000${key.keyId}".toByteArray(StandardCharsets.UTF_8))
        }
        return WrappedFolderKeyTransfer(key.folderId, key.keyId, nonce, cipher.doFinal(key.bytes))
    }

    fun unwrap(value: WrappedFolderKeyTransfer, pairingSessionKey: ByteArray): FolderKeyMaterial {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(pairingSessionKey, "AES"), GCMParameterSpec(TAG_BITS, value.nonce))
            updateAAD("${value.folderId}\u0000${value.keyId}".toByteArray(StandardCharsets.UTF_8))
        }
        return FolderKeyMaterial(value.folderId, value.keyId, cipher.doFinal(value.ciphertext))
    }

    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}

data class CloudFolderManifest(
    val folderId: String,
    val folderName: String,
    val publisherDeviceId: String,
    val publishedAtMillis: Long,
    val index: FolderIndexUpdate,
)

object CloudEncryptedObjects {
    const val MANIFEST_PREFIX = "manifest-"
    const val ENCRYPTED_SUFFIX = ".sdenc"

    fun manifestName(key: FolderKeyMaterial, publisherDeviceId: String): String =
        MANIFEST_PREFIX + opaqueName(key, "manifest\u0000$publisherDeviceId") + ENCRYPTED_SUFFIX

    fun fileName(key: FolderKeyMaterial, fileId: String, contentSha256: String): String =
        opaqueName(key, "file\u0000$fileId\u0000$contentSha256") + ENCRYPTED_SUFFIX

    fun encryptManifest(key: FolderKeyMaterial, manifest: CloudFolderManifest): ByteArray {
        require(manifest.folderId == key.folderId && manifest.index.folderId == key.folderId)
        val plaintext = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MANIFEST_MAGIC)
                output.writeInt(MANIFEST_VERSION)
                output.writeString(manifest.folderId)
                output.writeString(manifest.folderName)
                output.writeString(manifest.publisherDeviceId)
                output.writeLong(manifest.publishedAtMillis)
                output.writeData(MeshSessionWireCodec.encode(MeshSessionMessage.IndexBatch(listOf(manifest.index))))
            }
            bytes.toByteArray()
        }
        return encryptBytes(key, "manifest\u0000${manifest.publisherDeviceId}", plaintext)
    }

    fun decryptManifest(key: FolderKeyMaterial, publisherDeviceId: String, encrypted: ByteArray): CloudFolderManifest {
        val plaintext = decryptBytes(key, "manifest\u0000$publisherDeviceId", encrypted)
        return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            require(input.readInt() == MANIFEST_MAGIC && input.readInt() == MANIFEST_VERSION) { "Invalid cloud manifest" }
            val folderId = input.readString()
            val folderName = input.readString()
            val publisher = input.readString()
            val published = input.readLong()
            require(folderId == key.folderId && publisher == publisherDeviceId) { "Cloud manifest identity mismatch" }
            val message = MeshSessionWireCodec.decode(input.readData()) as? MeshSessionMessage.IndexBatch
                ?: error("Cloud manifest does not contain a folder index")
            require(message.updates.size == 1 && message.updates.single().folderId == folderId)
            require(input.available() == 0)
            CloudFolderManifest(folderId, folderName, publisher, published, message.updates.single())
        }
    }

    fun encryptFile(key: FolderKeyMaterial, fileId: String, contentSha256: String, source: Path, destination: Path) {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        Files.newOutputStream(destination).buffered().use { output ->
            output.write(FILE_MAGIC)
            output.write(nonce)
            val cipher = fileCipher(Cipher.ENCRYPT_MODE, key, fileId, contentSha256, nonce)
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            javax.crypto.CipherOutputStream(output, cipher).use { encrypted ->
                java.security.DigestInputStream(Files.newInputStream(source), digest).use { it.copyTo(encrypted) }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualHash.equals(contentSha256, true)) {
                "File changed after scanning; cloud upload was cancelled. Sync again to upload the current version."
            }
        }
    }

    fun decryptFile(
        key: FolderKeyMaterial,
        fileId: String,
        contentSha256: String,
        source: Path,
        destination: Path,
    ) {
        Files.newInputStream(source).buffered().use { input ->
            require(input.readNBytes(FILE_MAGIC.size).contentEquals(FILE_MAGIC)) { "Invalid encrypted cloud file" }
            val nonce = input.readNBytes(NONCE_BYTES).also { require(it.size == NONCE_BYTES) }
            val cipher = fileCipher(Cipher.DECRYPT_MODE, key, fileId, contentSha256, nonce)
            javax.crypto.CipherInputStream(input, cipher).use { decrypted ->
                Files.newOutputStream(destination).buffered().use(decrypted::copyTo)
            }
        }
    }

    private fun encryptBytes(key: FolderKeyMaterial, logicalId: String, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = objectCipher(Cipher.ENCRYPT_MODE, key, logicalId, nonce)
        return BYTE_MAGIC + nonce + cipher.doFinal(plaintext)
    }

    private fun decryptBytes(key: FolderKeyMaterial, logicalId: String, encrypted: ByteArray): ByteArray {
        require(encrypted.size > BYTE_MAGIC.size + NONCE_BYTES && encrypted.copyOfRange(0, BYTE_MAGIC.size).contentEquals(BYTE_MAGIC)) {
            "Invalid encrypted cloud object"
        }
        val nonceStart = BYTE_MAGIC.size
        val nonce = encrypted.copyOfRange(nonceStart, nonceStart + NONCE_BYTES)
        return objectCipher(Cipher.DECRYPT_MODE, key, logicalId, nonce)
            .doFinal(encrypted.copyOfRange(nonceStart + NONCE_BYTES, encrypted.size))
    }

    private fun objectCipher(mode: Int, key: FolderKeyMaterial, logicalId: String, nonce: ByteArray) =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key.bytes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD("syncdroid-cloud-v2\u0000${key.folderId}\u0000${key.keyId}\u0000$logicalId".toByteArray())
        }

    private fun fileCipher(mode: Int, key: FolderKeyMaterial, fileId: String, hash: String, nonce: ByteArray) =
        objectCipher(mode, key, "file\u0000$fileId\u0000$hash", nonce)

    private fun opaqueName(key: FolderKeyMaterial, logicalId: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.bytes, "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(logicalId.toByteArray()))
    }

    private fun DataOutputStream.writeString(value: String) = writeData(value.toByteArray(StandardCharsets.UTF_8))
    private fun DataInputStream.readString() = String(readData(), StandardCharsets.UTF_8)
    private fun DataOutputStream.writeData(value: ByteArray) { writeInt(value.size); write(value) }
    private fun DataInputStream.readData() = ByteArray(readInt().also { require(it in 0..MAX_MANIFEST_BYTES) }).also(::readFully)

    private val BYTE_MAGIC = byteArrayOf(0x53, 0x44, 0x43, 0x32)
    private val FILE_MAGIC = byteArrayOf(0x53, 0x44, 0x46, 0x32)
    private const val MANIFEST_MAGIC = 0x5344434D
    private const val MANIFEST_VERSION = 2
    private const val MAX_MANIFEST_BYTES = 64 * 1024 * 1024
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
}
