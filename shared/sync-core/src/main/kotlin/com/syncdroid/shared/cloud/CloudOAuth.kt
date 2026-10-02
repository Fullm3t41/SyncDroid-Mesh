package com.syncdroid.shared.cloud

import java.awt.Desktop
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class CloudProvider(val displayName: String) {
    GOOGLE_DRIVE("Google Drive"),
    ONE_DRIVE("OneDrive"),
}

data class CloudOAuthClient(
    val provider: CloudProvider,
    val clientId: String,
    val authorizationEndpoint: URI,
    val tokenEndpoint: URI,
    val scopes: List<String>,
    /** Google issues Desktop app clients a secret it requires on token requests; it is not confidential. */
    val clientSecret: String? = null,
) {
    init { require(clientId.isNotBlank()) { "${provider.displayName} OAuth client ID is not configured" } }
}

object CloudOAuthConfiguration {
    fun configured(provider: CloudProvider): CloudOAuthClient? {
        val key = when (provider) {
            CloudProvider.GOOGLE_DRIVE -> "SYNCDROID_GOOGLE_CLIENT_ID"
            CloudProvider.ONE_DRIVE -> "SYNCDROID_MICROSOFT_CLIENT_ID"
        }
        val clientId = setting(key) ?: return null
        return when (provider) {
            CloudProvider.GOOGLE_DRIVE -> CloudOAuthClient(
                provider,
                clientId,
                URI("https://accounts.google.com/o/oauth2/v2/auth"),
                URI("https://oauth2.googleapis.com/token"),
                listOf("https://www.googleapis.com/auth/drive.file"),
                setting("SYNCDROID_GOOGLE_CLIENT_SECRET"),
            )
            CloudProvider.ONE_DRIVE -> CloudOAuthClient(
                provider,
                clientId,
                URI("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"),
                URI("https://login.microsoftonline.com/common/oauth2/v2.0/token"),
                listOf("offline_access", "Files.ReadWrite"),
            )
        }
    }

    private fun setting(key: String): String? = System.getProperty(key.lowercase().replace('_', '.'))
        ?.takeIf(String::isNotBlank)
        ?: System.getenv(key)?.takeIf(String::isNotBlank)
        ?: bundledClientId(key)

    private fun bundledClientId(key: String): String? = runCatching {
        val properties = Properties()
        CloudOAuthConfiguration::class.java.getResourceAsStream("/cloud-oauth.properties")?.use(properties::load)
        properties.getProperty(key)?.trim()?.takeIf(String::isNotBlank)
    }.getOrNull()
}

data class CloudOAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val scopes: Set<String>,
) {
    fun usable(nowMillis: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && expiresAtMillis > nowMillis + 60_000
}

interface CloudTokenStore {
    fun load(provider: CloudProvider): CloudOAuthTokens?
    fun save(provider: CloudProvider, tokens: CloudOAuthTokens)
    fun clear(provider: CloudProvider)
}

/** Encrypts desktop OAuth material with a key derived from the installation's existing private identity. */
class LocalSecretCipher(privateKeyBytes: ByteArray, context: String) {
    private val key = MessageDigest.getInstance("SHA-256").digest(
        "syncdroid-local-secret-v1\u0000$context\u0000".toByteArray() + privateKeyBytes,
    )

    fun encrypt(plaintext: ByteArray): String {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val ciphertext = Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(AAD)
            doFinal(plaintext)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + ciphertext)
    }

    fun decrypt(encoded: String): ByteArray {
        val value = Base64.getUrlDecoder().decode(encoded)
        require(value.size > 12) { "Encrypted local secret is invalid" }
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, value.copyOfRange(0, 12)))
            updateAAD(AAD)
            doFinal(value.copyOfRange(12, value.size))
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val AAD = "syncdroid-local-secret-v1".toByteArray()
    }
}

class EncryptedCloudTokenStore(
    private val cipher: LocalSecretCipher,
    private val read: (CloudProvider) -> String?,
    private val write: (CloudProvider, String?) -> Unit,
) : CloudTokenStore {
    override fun load(provider: CloudProvider): CloudOAuthTokens? = read(provider)?.let { encoded ->
        runCatching { decodeTokens(String(cipher.decrypt(encoded), Charsets.UTF_8)) }.getOrNull()
    }

    override fun save(provider: CloudProvider, tokens: CloudOAuthTokens) {
        write(provider, cipher.encrypt(encodeTokens(tokens).toByteArray(Charsets.UTF_8)))
    }

    override fun clear(provider: CloudProvider) = write(provider, null)

    private fun encodeTokens(value: CloudOAuthTokens): String = listOf(
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.accessToken.toByteArray()),
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.refreshToken.toByteArray()),
        value.expiresAtMillis.toString(),
        value.scopes.sorted().joinToString(" "),
    ).joinToString("\n")

    private fun decodeTokens(value: String): CloudOAuthTokens {
        val lines = value.lines()
        require(lines.size >= 4)
        return CloudOAuthTokens(
            String(Base64.getUrlDecoder().decode(lines[0]), Charsets.UTF_8),
            String(Base64.getUrlDecoder().decode(lines[1]), Charsets.UTF_8),
            lines[2].toLong(),
            lines[3].split(' ').filter(String::isNotBlank).toSet(),
        )
    }
}

class DesktopCloudOAuth(
    private val tokenStore: CloudTokenStore,
    private val configuration: (CloudProvider) -> CloudOAuthClient? = CloudOAuthConfiguration::configured,
    private val openBrowser: (URI) -> Unit = { Desktop.getDesktop().browse(it) },
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(),
) {
    fun connected(provider: CloudProvider): Boolean = tokenStore.load(provider)?.refreshToken?.isNotBlank() == true

    suspend fun connect(provider: CloudProvider): CloudOAuthTokens = withContext(Dispatchers.IO) {
        val client = configuration(provider)
            ?: error("${provider.displayName} is not configured in this build. Add its OAuth desktop client ID first.")
        ServerSocket(0, CALLBACK_BACKLOG, InetAddress.getByName("127.0.0.1")).use { callback ->
            // Entra's desktop registration uses the exact http://localhost redirect and
            // ignores its ephemeral port. Google documents the literal IPv4 loopback form.
            val callbackHost = if (provider == CloudProvider.ONE_DRIVE) "localhost" else "127.0.0.1"
            // A browser may resolve localhost to the IPv6 loopback first, so listen there as well.
            val ipv6Callback = if (callbackHost != "localhost") null else runCatching {
                ServerSocket(callback.localPort, CALLBACK_BACKLOG, InetAddress.getByName("::1"))
            }.getOrNull()
            val redirect = "http://$callbackHost:${callback.localPort}"
            val verifier = randomUrlToken(64)
            val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val state = randomUrlToken(32)
            val query = linkedMapOf(
                "client_id" to client.clientId,
                "redirect_uri" to redirect,
                "response_type" to "code",
                "scope" to client.scopes.joinToString(" "),
                "state" to state,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
            ).apply {
                if (provider == CloudProvider.GOOGLE_DRIVE) {
                    put("access_type", "offline")
                    put("prompt", "consent")
                }
            }
            openBrowser(URI(client.authorizationEndpoint.toString() + "?" + form(query)))
            val callbackValues = try {
                receiveOAuthCallback(listOfNotNull(callback, ipv6Callback), System.currentTimeMillis() + AUTH_TIMEOUT_MILLIS)
            } finally {
                ipv6Callback?.close()
            }
            require(callbackValues["state"] == state) { "Cloud sign-in response could not be verified" }
            callbackValues["error"]?.let { error(it.replace('_', ' ')) }
            val code = callbackValues["code"] ?: error("Cloud sign-in did not return an authorization code")
            exchange(client, code, redirect, verifier).also { tokenStore.save(provider, it) }
        }
    }

    suspend fun accessToken(provider: CloudProvider): String = withContext(Dispatchers.IO) {
        val stored = tokenStore.load(provider) ?: error("Connect ${provider.displayName} first")
        if (stored.usable()) return@withContext stored.accessToken
        val client = configuration(provider)
            ?: error("${provider.displayName} OAuth client ID is not configured")
        refresh(client, stored).also { tokenStore.save(provider, it) }.accessToken
    }

    fun disconnect(provider: CloudProvider) = tokenStore.clear(provider)

    private fun exchange(client: CloudOAuthClient, code: String, redirect: String, verifier: String): CloudOAuthTokens {
        val body = linkedMapOf(
            "client_id" to client.clientId,
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to redirect,
            "grant_type" to "authorization_code",
        )
        client.clientSecret?.let { body["client_secret"] = it }
        return tokenRequest(client, body, previousRefreshToken = null)
    }

    private fun refresh(client: CloudOAuthClient, previous: CloudOAuthTokens): CloudOAuthTokens {
        val values = linkedMapOf(
            "client_id" to client.clientId,
            "refresh_token" to previous.refreshToken,
            "grant_type" to "refresh_token",
        )
        if (client.provider == CloudProvider.ONE_DRIVE) values["scope"] = client.scopes.joinToString(" ")
        client.clientSecret?.let { values["client_secret"] = it }
        return tokenRequest(client, values, previous.refreshToken)
    }

    private fun tokenRequest(
        client: CloudOAuthClient,
        values: Map<String, String>,
        previousRefreshToken: String?,
    ): CloudOAuthTokens {
        val request = HttpRequest.newBuilder(client.tokenEndpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form(values)))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) {
            runCatching { JSONObject(response.body()).optString("error_description") }.getOrNull()
                ?.takeIf(String::isNotBlank) ?: "${client.provider.displayName} token request failed (${response.statusCode()})"
        }
        val json = JSONObject(response.body())
        val access = json.getString("access_token")
        val refresh = json.optString("refresh_token").takeIf(String::isNotBlank) ?: previousRefreshToken.orEmpty()
        require(refresh.isNotBlank()) { "${client.provider.displayName} did not grant background access" }
        val expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1_000
        val scopes = json.optString("scope", client.scopes.joinToString(" "))
            .split(' ').filter(String::isNotBlank).toSet()
        return CloudOAuthTokens(access, refresh, expiresAt, scopes)
    }

    private fun form(values: Map<String, String>) = values.entries.joinToString("&") { (key, value) ->
        "${encode(key)}=${encode(value)}"
    }

    private fun randomUrlToken(bytes: Int) = base64Url(ByteArray(bytes).also(SecureRandom()::nextBytes))
    private fun base64Url(value: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        const val AUTH_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        const val CALLBACK_BACKLOG = 16
    }
}

/**
 * Waits for the browser's sign-in redirect. Browsers also open speculative connections that send
 * nothing, and ask for /favicon.ico, so each connection is read with its own short timeout and any
 * request without an OAuth response is answered and skipped instead of ending the sign-in.
 */
internal fun receiveOAuthCallback(servers: List<ServerSocket>, deadlineMillis: Long): Map<String, String> {
    servers.forEach { it.soTimeout = CALLBACK_ACCEPT_POLL_MILLIS }
    while (System.currentTimeMillis() < deadlineMillis) {
        for (server in servers) {
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            }
            socket.use { connection ->
                connection.soTimeout = CALLBACK_READ_TIMEOUT_MILLIS
                val query = runCatching { readCallbackQuery(connection) }.getOrNull()
                if (query != null && ("state" in query || "error" in query)) {
                    val message = if (query["error"] == null) {
                        "Cloud account connected. You can close this browser tab."
                    } else {
                        "Cloud account connection was cancelled."
                    }
                    runCatching { respond(connection, "200 OK", message) }
                    return query
                }
                runCatching { respond(connection, "404 Not Found", "Not found") }
            }
        }
    }
    error("Cloud sign-in timed out")
}

private fun readCallbackQuery(socket: java.net.Socket): Map<String, String>? {
    val reader = socket.getInputStream().bufferedReader()
    val requestLine = reader.readLine() ?: return null
    while (!reader.readLine().isNullOrEmpty()) Unit
    val target = requestLine.split(' ').getOrNull(1) ?: return null
    return URI(target).rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { pair ->
        val parts = pair.split('=', limit = 2)
        URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
    }
}

private fun respond(socket: java.net.Socket, status: String, message: String) {
    val html = "<!doctype html><meta charset=utf-8><title>SyncDroid-Mesh</title><body style='font-family:system-ui;padding:3rem'><h2>$message</h2></body>"
    val bytes = html.toByteArray()
    socket.getOutputStream().buffered().use { output ->
        output.write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
    }
}

private const val CALLBACK_ACCEPT_POLL_MILLIS = 250
private const val CALLBACK_READ_TIMEOUT_MILLIS = 10_000
