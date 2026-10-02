package com.syncdroid.app.mesh

import com.syncdroid.app.data.DeviceEntity
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

data class PeerIdentity(
    val deviceId: String,
    val publicKeyEncoded: ByteArray,
)

class DeviceTlsContext private constructor(private val context: SSLContext) {
    fun createServerSocket(port: Int = 0): SSLServerSocket =
        (context.serverSocketFactory.createServerSocket(port) as SSLServerSocket).apply {
            enabledProtocols = arrayOf(TLS_1_2)
            needClientAuth = true
        }

    /** An unconnected socket, so the caller can connect with a timeout. */
    fun createClientSocket(): SSLSocket =
        (context.socketFactory.createSocket() as SSLSocket).apply {
            enabledProtocols = arrayOf(TLS_1_2)
            useClientMode = true
        }

    companion object {
        fun create(
            identity: AndroidDeviceIdentity,
            trustedDevices: Collection<DeviceEntity>,
            allowUnknownPeer: Boolean = false,
        ): DeviceTlsContext {
            val ssl = SSLContext.getInstance(TLS_1_2)
            ssl.init(
                arrayOf<KeyManager>(AndroidIdentityKeyManager(identity)),
                arrayOf<TrustManager>(PinnedPeerTrustManager(emptyMap(), true)),
                null,
            )
            return DeviceTlsContext(ssl)
        }
    }
}

fun SSLSocket.authenticatedPeerIdentity(): PeerIdentity {
    val certificate = session.peerCertificates.singleOrNull() as? X509Certificate
        ?: throw CertificateException("Peer must present exactly one X.509 certificate")
    return PeerIdentity(deviceIdFor(certificate.publicKey), certificate.publicKey.encoded)
}

private class AndroidIdentityKeyManager(
    private val identity: AndroidDeviceIdentity,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = ALIAS
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String = ALIAS
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = identity.tlsCertificateChain()
    override fun getPrivateKey(alias: String?): PrivateKey = identity.tlsPrivateKey()

    private companion object { const val ALIAS = "syncdroid" }
}

private class PinnedPeerTrustManager(
    private val trustedPublicKeys: Map<String, ByteArray>,
    private val allowUnknownPeer: Boolean,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun check(chain: Array<out X509Certificate>?) {
        val certificate = chain?.singleOrNull() ?: throw CertificateException("Peer must present one certificate")
        certificate.checkValidity()
        val deviceId = deviceIdFor(certificate.publicKey)
        val expected = trustedPublicKeys[deviceId]
        if (expected == null) {
            if (allowUnknownPeer) return
            throw CertificateException("Device $deviceId is not trusted")
        }
        if (!expected.contentEquals(certificate.publicKey.encoded)) {
            throw CertificateException("Pinned public key mismatch for $deviceId")
        }
    }
}

// TLS 1.2 lets existing Android Keystore P-256 identities sign with their authorized SHA-256
// digest directly. Some Samsung KeyMint versions reject TLS 1.3's raw ECDSA operation.
private const val TLS_1_2 = "TLSv1.2"
