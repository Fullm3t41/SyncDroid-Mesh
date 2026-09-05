package com.syncdows.app.mesh

import com.syncdroid.shared.cloud.FolderKeyMaterial
import com.syncdroid.shared.cloud.LocalSecretCipher
import java.security.SecureRandom
import java.util.UUID

class DesktopFolderKeyStore(
    private val store: MeshStore,
    identity: WindowsDeviceIdentity,
) {
    private val cipher = LocalSecretCipher(identity.privateKey().encoded, "syncdows-folder-keys")

    fun getOrCreate(folderId: String): FolderKeyMaterial = synchronized(store) {
        existing(folderId) ?: FolderKeyMaterial(folderId, UUID.randomUUID().toString(), ByteArray(32).also(SecureRandom()::nextBytes))
            .also(::save)
    }

    fun existing(folderId: String): FolderKeyMaterial? = store.storedFolderKey(folderId)?.let(::decode)

    fun all(folderId: String): List<FolderKeyMaterial> = synchronized(store) {
        (listOfNotNull(existing(folderId)) + store.archivedFolderKeys(folderId).map(::decode)).distinctBy { it.keyId }
    }

    // Converge deterministically, keeping every previous key for existing cloud ciphertext.
    fun import(value: FolderKeyMaterial): FolderKeyMaterial = synchronized(store) {
        require(value.keyId.isNotBlank())
        val known = all(value.folderId)
        known.firstOrNull { it.keyId == value.keyId }?.let {
            require(it.bytes.contentEquals(value.bytes)) { "Cloud key material does not match its ID" }
        }
        (known + value).forEach {
            store.archiveFolderKey(StoredFolderKey(it.folderId, it.keyId, cipher.encrypt(it.bytes)))
        }
        (known + value).minBy { it.keyId }.also(::save)
    }

    private fun save(value: FolderKeyMaterial) = store.saveFolderKey(
        StoredFolderKey(value.folderId, value.keyId, cipher.encrypt(value.bytes)),
    )

    private fun decode(value: StoredFolderKey) = FolderKeyMaterial(
        value.folderId, value.keyId, cipher.decrypt(value.encryptedKey),
    )
}
