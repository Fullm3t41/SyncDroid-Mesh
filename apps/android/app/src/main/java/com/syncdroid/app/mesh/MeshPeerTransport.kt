package com.syncdroid.app.mesh

import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthenticatedPeerConnection internal constructor(
    private val socket: SSLSocket,
    initialPeer: PeerIdentity,
) : Closeable {
    var peer: PeerIdentity = initialPeer
        private set
    private val input = DataInputStream(socket.inputStream.buffered())
    private val output = DataOutputStream(socket.outputStream.buffered())

    suspend fun send(message: ByteArray) = withContext(Dispatchers.IO) {
        require(message.size <= MAX_MESSAGE_BYTES) { "Mesh message is too large" }
        synchronized(output) {
            output.writeInt(message.size)
            output.write(message)
            output.flush()
        }
    }

    suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        val size = input.readInt()
        require(size in 0..MAX_MESSAGE_BYTES) { "Invalid mesh message size" }
        ByteArray(size).also(input::readFully)
    }

    /** Discards incoming messages until the peer hangs up or [timeoutMillis] passes. */
    internal suspend fun drainUntilClosed(timeoutMillis: Long) {
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            runCatching {
                while (true) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.toInt()
                    var unread = input.readInt().also { require(it in 0..MAX_MESSAGE_BYTES) }
                    while (unread > 0) {
                        val skipped = input.skipBytes(unread)
                        if (skipped > 0) unread -= skipped else { input.readByte(); unread-- }
                    }
                }
            }
        }
    }

    override fun close() = socket.close()

    internal fun bindAuthenticatedPeer(value: PeerIdentity) {
        peer = value
    }
}

class MeshPeerServer(
    private val tls: DeviceTlsContext,
    private val onConnection: suspend (AuthenticatedPeerConnection) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    private var serverSocket: SSLServerSocket? = null
    private var scope: CoroutineScope? = null
    val port: Int get() = serverSocket?.localPort ?: 0

    fun start(): Int {
        check(running.compareAndSet(false, true)) { "Peer server is already running" }
        val server = tls.createServerSocket()
        serverSocket = server
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope
        serverScope.launch {
            while (isActive && running.get()) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrElse {
                    if (running.get()) throw it else break
                }
                launch {
                    runCatching {
                        socket.startHandshake()
                        AuthenticatedPeerConnection(socket, socket.authenticatedPeerIdentity()).use { onConnection(it) }
                    }.onFailure { error ->
                        Log.e(TAG, "Incoming peer connection failed", error)
                        runCatching { socket.close() }
                    }
                }
            }
        }
        return server.localPort
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        scope?.cancel()
        serverSocket = null
        scope = null
    }
}

class MeshPeerClient(private val tls: DeviceTlsContext) {
    suspend fun connect(address: InetAddress, port: Int): AuthenticatedPeerConnection = withContext(Dispatchers.IO) {
        val socket = tls.createClientSocket(address.hostAddress ?: address.hostName, port)
        socket.startHandshake()
        AuthenticatedPeerConnection(socket, socket.authenticatedPeerIdentity())
    }
}

private const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024
private const val TAG = "SyncDroidMesh"
