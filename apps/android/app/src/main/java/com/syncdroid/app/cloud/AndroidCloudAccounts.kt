package com.syncdroid.app.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.syncdroid.shared.cloud.CloudProvider
import java.security.KeyStore
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AndroidCloudAccounts private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("cloud-accounts", Context.MODE_PRIVATE)
    private val configuration = Properties().apply {
        AndroidCloudAccounts::class.java.getResourceAsStream("/cloud-oauth.properties")?.use(::load)
    }
    private val mutableRevision = MutableStateFlow(0)
    val revision = mutableRevision.asStateFlow()
    private val mutex = Mutex()

    fun configured(provider: CloudProvider): Boolean = clientId(provider).isNotBlank()
    fun connected(provider: CloudProvider): Boolean = preferences.contains(provider.name)
    fun signInIntent(provider: CloudProvider) = Intent(context, CloudSignInActivity::class.java).putExtra("provider", provider.name)

    suspend fun disconnect(provider: CloudProvider) = mutex.withLock {
        preferences.edit().remove(provider.name).apply()
        mutableRevision.value++
    }

    suspend fun authorizeGoogle(): AuthorizationResult {
        check(configured(CloudProvider.GOOGLE_DRIVE)) { "Google sign-in is not available in this build yet" }
        return suspendCancellableCoroutine { continuation ->
            Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_SCOPE))).build())
                .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        }
    }

    fun saveGoogle(result: AuthorizationResult) {
        val token = requireNotNull(result.accessToken) { "Google did not grant access to Drive" }
        save(CloudProvider.GOOGLE_DRIVE, org.json.JSONObject().put("access", token)
            .put("expires", System.currentTimeMillis() + 45 * 60_000).toString())
    }

    fun microsoftIntent(service: AuthorizationService): Intent {
        check(configured(CloudProvider.ONE_DRIVE)) { "Microsoft sign-in is not available in this build yet" }
        val config = AuthorizationServiceConfiguration(
            Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
            Uri.parse("https://login.microsoftonline.com/common/oauth2/v2.0/token"))
        val request = net.openid.appauth.AuthorizationRequest.Builder(config, clientId(CloudProvider.ONE_DRIVE),
            ResponseTypeValues.CODE, Uri.parse(MICROSOFT_REDIRECT))
            .setScopes("offline_access", "Files.ReadWrite").build()
        return service.getAuthorizationRequestIntent(request)
    }

    suspend fun completeMicrosoft(intent: Intent, service: AuthorizationService) {
        val error = AuthorizationException.fromIntent(intent)
        if (error != null) throw error
        val response = requireNotNull(AuthorizationResponse.fromIntent(intent)) { "Sign-in was cancelled" }
        val state = AuthState(response, null)
        suspendCancellableCoroutine<Unit> { continuation ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { token, failure ->
                if (failure != null || token == null) {
                    if (continuation.isActive) continuation.resumeWithException(failure ?: IllegalStateException("No cloud token returned"))
                } else if (continuation.isActive) {
                    try {
                        state.update(token, null)
                        check(!state.refreshToken.isNullOrBlank()) { "Microsoft did not grant background access" }
                        save(CloudProvider.ONE_DRIVE, state.jsonSerializeString())
                        continuation.resume(Unit)
                    } catch (e: Exception) { continuation.resumeWithException(e) }
                }
            }
        }
    }

    suspend fun accessToken(provider: CloudProvider): String = mutex.withLock {
        val stored = read(provider) ?: error("Connect ${provider.displayName} first")
        if (provider == CloudProvider.GOOGLE_DRIVE) {
            val cached = org.json.JSONObject(stored)
            if (cached.getLong("expires") > System.currentTimeMillis()) return@withLock cached.getString("access")
            val result = authorizeGoogle()
            check(!result.hasResolution()) { "Open Cloud sync and reconnect Google Drive to renew access" }
            saveGoogle(result)
            return@withLock requireNotNull(result.accessToken)
        }
        val state = AuthState.jsonDeserialize(stored)
        val service = AuthorizationService(context)
        try {
            suspendCancellableCoroutine { continuation ->
                state.performActionWithFreshTokens(service) { access, _, error ->
                    if (continuation.isActive) {
                        if (error != null || access == null) continuation.resumeWithException(error ?: IllegalStateException("Reconnect OneDrive"))
                        else try { save(provider, state.jsonSerializeString()); continuation.resume(access) }
                        catch (failure: Exception) { continuation.resumeWithException(failure) }
                    }
                }
            }
        } finally { service.dispose() }
    }

    private fun clientId(provider: CloudProvider): String = configuration.getProperty(
        if (provider == CloudProvider.GOOGLE_DRIVE) "SYNCDROID_GOOGLE_ANDROID_CLIENT_ID" else "SYNCDROID_MICROSOFT_CLIENT_ID", "").trim()

    @Synchronized
    private fun masterKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    private fun save(provider: CloudProvider, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, masterKey()); updateAAD(provider.name.toByteArray())
        }
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        check(preferences.edit().putString(provider.name, encoded).commit()) { "Could not save cloud account" }
        mutableRevision.value++
    }

    private fun read(provider: CloudProvider): String? = preferences.getString(provider.name, null)?.let {
        val bytes = Base64.decode(it, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(provider.name.toByteArray())
        }
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
    }

    companion object {
        const val MICROSOFT_REDIRECT = "com.syncdroid.app:/oauth2redirect/microsoft"
        private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val KEY_ALIAS = "syncdroid-cloud-oauth-v1"
        @Volatile private var instance: AndroidCloudAccounts? = null
        fun get(context: Context): AndroidCloudAccounts = instance ?: synchronized(this) {
            instance ?: AndroidCloudAccounts(context.applicationContext).also { instance = it }
        }
    }
}
