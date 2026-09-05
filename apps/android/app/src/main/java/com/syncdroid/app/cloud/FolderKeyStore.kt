package com.syncdroid.app.cloud

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.syncdroid.app.data.FolderKeyEntity
import com.syncdroid.app.data.SyncDao
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class FolderKeyMaterial(val folderId: String, val keyId: String, val bytes: ByteArray)

class AndroidFolderKeyStore(
    context: Context,
    private val syncDao: SyncDao,
) {
    private val appContext = context.applicationContext
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private val history = appContext.getSharedPreferences("cloud-key-history", Context.MODE_PRIVATE)

    suspend fun getOrCreate(folderId: String): FolderKeyMaterial = keyMutex.withLock {
        syncDao.folderKey(folderId)?.let { return@withLock unwrap(it) }
        FolderKeyMaterial(folderId, UUID.randomUUID().toString(), randomKey()).also { syncDao.upsertFolderKey(wrap(it)) }
    }

    suspend fun all(folderId: String): List<FolderKeyMaterial> = keyMutex.withLock {
        (listOfNotNull(syncDao.folderKey(folderId)) + archived(folderId)).distinctBy { it.keyId }.map(::unwrap)
    }

    suspend fun import(folderId: String, keyId: String, rawKey: ByteArray): FolderKeyMaterial = keyMutex.withLock {
        require(rawKey.size == FOLDER_KEY_BYTES && keyId.isNotBlank()) { "Invalid folder key" }
        val known = (listOfNotNull(syncDao.folderKey(folderId)) + archived(folderId)).distinctBy { it.keyId }
        known.firstOrNull { it.keyId == keyId }?.let {
            require(unwrap(it).bytes.contentEquals(rawKey)) { "Cloud key material does not match its ID" }
        }
        val entries = (known + wrap(FolderKeyMaterial(folderId, keyId, rawKey.copyOf()))).distinctBy { it.keyId }
        val json = JSONArray(entries.map { JSONObject()
            .put("id", it.keyId).put("key", it.wrappedKeyBase64).put("nonce", it.nonceBase64)
            .put("created", it.createdAtMillis) })
        check(history.edit().putString(folderId, json.toString()).commit()) { "Could not preserve existing cloud keys" }
        entries.minBy { it.keyId }.also { syncDao.upsertFolderKey(it) }.let(::unwrap)
    }

    private fun archived(folderId: String): List<FolderKeyEntity> {
        val json = JSONArray(history.getString(folderId, "[]"))
        return List(json.length()) { index -> json.getJSONObject(index).let {
            FolderKeyEntity(folderId, it.getString("id"), it.getString("key"), it.getString("nonce"), it.getLong("created"))
        } }
    }

    private fun wrap(material: FolderKeyMaterial): FolderKeyEntity {
        val nonce = ByteArray(GCM_NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(material.folderId.toByteArray())
        }
        return FolderKeyEntity(material.folderId, material.keyId,
            Base64.getEncoder().encodeToString(cipher.doFinal(material.bytes)),
            Base64.getEncoder().encodeToString(nonce), System.currentTimeMillis())
    }

    private fun unwrap(entity: FolderKeyEntity): FolderKeyMaterial {
        val nonce = Base64.getDecoder().decode(entity.nonceBase64)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            updateAAD(entity.folderId.toByteArray())
        }
        return FolderKeyMaterial(
            entity.folderId,
            entity.keyId,
            cipher.doFinal(Base64.getDecoder().decode(entity.wrappedKeyBase64)),
        )
    }

    private fun masterKey(): SecretKey {
        (keyStore.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    MASTER_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun randomKey(): ByteArray = ByteArray(FOLDER_KEY_BYTES).also(SecureRandom()::nextBytes)

    private companion object {
        val keyMutex = Mutex()
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val MASTER_ALIAS = "syncdroid-folder-key-master-v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FOLDER_KEY_BYTES = 32
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
