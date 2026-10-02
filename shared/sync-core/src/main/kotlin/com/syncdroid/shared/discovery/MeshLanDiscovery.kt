package com.syncdroid.shared.discovery

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LanMeshPeer(
    val deviceId: String,
    val address: InetAddress,
    val port: Int,
    val protocolMajor: Int,
    val lastSeenAtMillis: Long,
)

/** UDP broadcast fallback for devices whose DNS-SD implementation is unavailable or unreliable. */
class MeshLanDiscovery(
    private val localDeviceId: String,
    groupId: String,
    private val discoveryPort: Int = DISCOVERY_PORT,
) : Closeable {
    private val groupTag = meshLanGroupTag(groupId)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutablePeers = MutableStateFlow<Map<String, LanMeshPeer>>(emptyMap())
    val peers: StateFlow<Map<String, LanMeshPeer>> = mutablePeers
    @Volatile private var advertisedPort: Int? = null
    @Volatile private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var announcementJob: Job? = null
    private var closed = false
    val isRunning: Boolean get() = socket != null

    @Synchronized
    fun start(tcpPort: Int) {
        require(tcpPort in 1..65_535)
        check(!closed) { "Mesh discovery is closed" }
        advertisedPort = tcpPort
        if (socket == null) {
            val activeSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = RECEIVE_TIMEOUT_MILLIS
                bind(InetSocketAddress(discoveryPort))
            }
            socket = activeSocket
            receiveJob = scope.launch { receiveLoop(activeSocket) }
            announcementJob = scope.launch {
                while (isActive) {
                    pruneExpired()
                    if (advertisedPort != null) broadcast(QUERY)
                    advertisedPort?.let { broadcast(announcement(it)) }
                    delay(ANNOUNCEMENT_INTERVAL_MILLIS)
                }
            }
        }
        broadcast(QUERY)
        broadcast(announcement(tcpPort))
    }

    @Synchronized
    fun stop() {
        advertisedPort = null
        socket?.close()
        socket = null
        receiveJob?.cancel()
        receiveJob = null
        announcementJob?.cancel()
        announcementJob = null
        mutablePeers.value = emptyMap()
    }

    private fun receiveLoop(activeSocket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!activeSocket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                activeSocket.receive(packet)
                val message = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                when {
                    message == "$QUERY|$groupTag" -> advertisedPort?.let { send(announcement(it), packet.address) }
                    message.startsWith("$ANNOUNCEMENT|") -> acceptAnnouncement(message, packet.address)
                }
            } catch (_: SocketTimeoutException) {
                pruneExpired()
            } catch (_: Throwable) {
                if (activeSocket.isClosed) return
            }
        }
    }

    /*
     * The socket is deliberately created by start() and closed by stop(). A
     * dormant discovery instance therefore owns no UDP descriptor or polling
     * coroutine between scheduled windows.
     */

    private fun acceptAnnouncement(message: String, source: InetAddress) {
        val parts = message.split('|')
        if (parts.size != 5 || parts[0] != ANNOUNCEMENT || parts[1] != groupTag) return
        val deviceId = parts[2].takeIf { it != localDeviceId && it.matches(SAFE_ID) } ?: return
        val tcpPort = parts[3].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return
        val protocol = parts[4].toIntOrNull()?.takeIf { it > 0 } ?: return
        val peer = LanMeshPeer(deviceId, source, tcpPort, protocol, System.currentTimeMillis())
        // The receive loop and the announcement timer both update the map, so update it atomically.
        // Anyone on the network can send announcements, so the map is capped at a mesh-sized limit.
        mutablePeers.update { current ->
            if (deviceId !in current && current.size >= MAX_PEERS) current else current + (deviceId to peer)
        }
    }

    private fun announcement(port: Int) = "$ANNOUNCEMENT|$groupTag|$localDeviceId|$port|$PROTOCOL_MAJOR"
    private fun broadcast(prefix: String) {
        val message = if (prefix == QUERY) "$QUERY|$groupTag" else prefix
        broadcastAddresses().forEach { send(message, it) }
    }
    private fun send(message: String, address: InetAddress) = runCatching {
        val bytes = message.toByteArray(StandardCharsets.UTF_8)
        socket?.send(DatagramPacket(bytes, bytes.size, address, discoveryPort))
    }.getOrNull()

    private fun pruneExpired() {
        val cutoff = System.currentTimeMillis() - PEER_EXPIRY_MILLIS
        mutablePeers.update { current -> current.filterValues { it.lastSeenAtMillis >= cutoff } }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        stop()
        closed = true
        scope.cancel()
    }

    private companion object {
        const val DISCOVERY_PORT = 45_783
        const val PROTOCOL_MAJOR = 2
        const val QUERY = "SDMQ1"
        const val ANNOUNCEMENT = "SDMA1"
        const val RECEIVE_TIMEOUT_MILLIS = 1_000
        const val ANNOUNCEMENT_INTERVAL_MILLIS = 1_000L
        const val PEER_EXPIRY_MILLIS = 15_000L
        const val MAX_PEERS = 256
        val SAFE_ID = Regex("[A-Za-z0-9_-]{8,128}")
    }
}

fun meshLanGroupTag(groupId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(groupId.toByteArray(StandardCharsets.UTF_8))
        .copyOfRange(0, 12)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

private fun broadcastAddresses(): Set<InetAddress> = buildSet {
    add(InetAddress.getByName("255.255.255.255"))
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return@buildSet
    while (interfaces.hasMoreElements()) {
        val network = interfaces.nextElement()
        if (!runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)) continue
        network.interfaceAddresses.mapNotNullTo(this) { it.broadcast }
    }
}
