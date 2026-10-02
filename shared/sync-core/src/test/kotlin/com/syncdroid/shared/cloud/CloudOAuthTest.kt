package com.syncdroid.shared.cloud

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CloudOAuthTest {
    @Test
    fun encryptedTokenStoreRoundTripsAndClears() {
        val values = mutableMapOf<CloudProvider, String>()
        val store = EncryptedCloudTokenStore(
            LocalSecretCipher(ByteArray(96).also(SecureRandom()::nextBytes), "test"),
            values::get,
            { provider, value -> if (value == null) values.remove(provider) else values[provider] = value },
        )
        val expected = CloudOAuthTokens("access", "refresh", 123456L, setOf("scope-a", "scope-b"))
        store.save(CloudProvider.GOOGLE_DRIVE, expected)
        assertEquals(expected, store.load(CloudProvider.GOOGLE_DRIVE))
        store.clear(CloudProvider.GOOGLE_DRIVE)
        assertNull(store.load(CloudProvider.GOOGLE_DRIVE))
    }

    @Test
    fun callbackSurvivesSpeculativeAndFaviconConnections() {
        ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { server ->
            val browser = thread {
                // A preconnect that never sends a request, then a favicon request, then the redirect.
                Socket(server.inetAddress, server.localPort).close()
                Socket(server.inetAddress, server.localPort).use { socket ->
                    socket.getOutputStream().write("GET /favicon.ico HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes()
                }
                Socket(server.inetAddress, server.localPort).use { socket ->
                    socket.getOutputStream().write("GET /?state=abc&code=the%20code HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    socket.getInputStream().readBytes()
                }
            }

            val query = receiveOAuthCallback(listOf(server), System.currentTimeMillis() + 20_000)

            browser.join()
            assertEquals("abc", query["state"])
            assertEquals("the code", query["code"])
        }
    }
}
