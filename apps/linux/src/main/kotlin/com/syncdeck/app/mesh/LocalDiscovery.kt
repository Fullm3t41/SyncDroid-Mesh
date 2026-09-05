package com.syncdeck.app.mesh

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PairingOffer(
    val invitationId: String,
    val deviceId: String,
    val address: InetAddress,
    val port: Int,
    val serviceName: String,
)

data class DiscoveredPeer(
    val deviceId: String,
    val address: InetAddress,
    val port: Int,
    val protocolMajor: Int,
    val lastSeenAtMillis: Long,
)

/** Android-compatible UDP pairing discovery fallback. */
class PairingLanDiscovery(
    private val localDeviceId: String,
    private val discoveryPort: Int = PAIRING_PORT,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableOffers = MutableStateFlow<Map<String, PairingOffer>>(emptyMap())
    val offers: StateFlow<Map<String, PairingOffer>> = mutableOffers
    @Volatile private var localOffer: LocalOffer? = null
    @Volatile private var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var broadcastJob: Job? = null
    private var closed = false
    internal val isRunning: Boolean get() = socket != null

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (closed) return
        if (!enabled) {
            stopSocket()
            return
        }
        if (socket != null) return
        val activeSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1_000
            bind(InetSocketAddress(discoveryPort))
        }
        socket = activeSocket
        receiveJob = scope.launch { receiveLoop(activeSocket) }
        broadcastJob = scope.launch {
            while (isActive) {
                sendBroadcast(DISCOVER.toByteArray(StandardCharsets.UTF_8))
                localOffer?.let { sendBroadcast(it.message().toByteArray(StandardCharsets.UTF_8)) }
                delay(1_000)
            }
        }
    }

    fun advertise(tcpPort: Int, invitationId: String) {
        require(tcpPort in 1..65_535 && invitationId.matches(SAFE_ID))
        localOffer = LocalOffer(tcpPort, invitationId)
    }

    fun stopAdvertising() { localOffer = null }

    private fun receiveLoop(activeSocket: DatagramSocket) {
        val buffer = ByteArray(512)
        while (!activeSocket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                activeSocket.receive(packet)
                val message = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                when {
                    message == DISCOVER -> localOffer?.let {
                        send(it.message().toByteArray(StandardCharsets.UTF_8), packet.address, packet.port)
                    }
                    message.startsWith("$OFFER_VERSION|") -> accept(message, packet.address)
                }
            } catch (_: SocketTimeoutException) {
                // Periodically observe closure.
            } catch (_: Throwable) {
                if (activeSocket.isClosed) return
            }
        }
    }

    private fun accept(message: String, source: InetAddress) {
        val parts = message.split('|')
        if (parts.size != 4 || parts[0] != OFFER_VERSION) return
        val invitation = parts[1].takeIf { it.matches(SAFE_ID) } ?: return
        val device = parts[2].takeIf { it.matches(SAFE_ID) && it != localDeviceId } ?: return
        val port = parts[3].toIntOrNull()?.takeIf { it in 1..65_535 } ?: return
        mutableOffers.value = mutableOffers.value + (invitation to PairingOffer(invitation, device, source, port, "LAN pairing"))
    }

    private fun sendBroadcast(bytes: ByteArray) = broadcastAddresses().forEach { send(bytes, it, discoveryPort) }
    private fun send(bytes: ByteArray, address: InetAddress, port: Int) = runCatching {
        socket?.send(DatagramPacket(bytes, bytes.size, address, port))
    }.getOrNull()

    @Synchronized
    private fun stopSocket() {
        socket?.close()
        socket = null
        receiveJob?.cancel()
        receiveJob = null
        broadcastJob?.cancel()
        broadcastJob = null
        mutableOffers.value = emptyMap()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        localOffer = null
        stopSocket()
        scope.cancel()
    }

    private inner class LocalOffer(val port: Int, val invitation: String) {
        fun message() = "$OFFER_VERSION|$invitation|$localDeviceId|$port"
    }

    private companion object {
        const val PAIRING_PORT = 45_782
        const val OFFER_VERSION = "SDPO1"
        const val DISCOVER = "SDPD1"
        val SAFE_ID = Regex("[A-Za-z0-9_-]{8,128}")
    }
}

/** Bonjour/DNS-SD discovery for both ordinary mesh sessions and pairing offers. */
class BonjourDiscovery(private val localDeviceId: String) : Closeable {
    private val mutablePairingOffers = MutableStateFlow<Map<String, PairingOffer>>(emptyMap())
    private val mutablePeers = MutableStateFlow<Map<String, DiscoveredPeer>>(emptyMap())
    val pairingOffers: StateFlow<Map<String, PairingOffer>> = mutablePairingOffers
    val peers: StateFlow<Map<String, DiscoveredPeer>> = mutablePeers
    private var mdns: JmDNS? = null
    private var pairingService: ServiceInfo? = null
    private var meshService: ServiceInfo? = null
    private var meshPort: Int? = null
    private var pairingAdvertisement: Pair<Int, String>? = null
    private var enabled = false
    private var closed = false
    internal val isRunning: Boolean get() = mdns != null

    @Synchronized
    fun setEnabled(nextEnabled: Boolean) {
        if (closed || enabled == nextEnabled) return
        enabled = nextEnabled
        if (nextEnabled) {
            try {
                val active = JmDNS.create(localAddress(), "SyncDeck-${localDeviceId.take(8)}")
                mdns = active
                active.addServiceListener(PAIRING_TYPE, listener(active, PAIRING_TYPE, ::acceptPairing, ::losePairing))
                active.addServiceListener(MESH_TYPE, listener(active, MESH_TYPE, ::acceptMesh, ::loseMesh))
                meshPort?.let(::registerMeshService)
                pairingAdvertisement?.let { (port, invitationId) -> registerPairingService(port, invitationId) }
                runCatching { active.list(MESH_TYPE, 1_000).forEach(::acceptMesh) }
            } catch (error: Throwable) {
                closeMdns()
                enabled = false
                throw error
            }
        } else {
            closeMdns()
        }
    }

    @Synchronized
    fun advertisePairing(port: Int, invitationId: String) {
        stopPairingAdvertisement()
        pairingAdvertisement = port to invitationId
        if (enabled) registerPairingService(port, invitationId)
    }

    private fun registerPairingService(port: Int, invitationId: String) {
        val active = mdns ?: return
        val info = ServiceInfo.create(
            PAIRING_TYPE,
            "SyncDeck-Pair-${localDeviceId.take(8)}",
            port,
            0,
            0,
            mapOf("id" to localDeviceId, "invite" to invitationId),
        )
        active.registerService(info)
        pairingService = info
    }

    @Synchronized
    fun stopPairingAdvertisement() {
        pairingAdvertisement = null
        val active = mdns
        pairingService?.let { info -> if (active != null) runCatching { active.unregisterService(info) } }
        pairingService = null
    }

    @Synchronized
    fun advertiseMesh(port: Int) {
        require(port in 1..65_535)
        meshPort = port
        if (enabled) {
            registerMeshService(port)
        }
    }

    private fun registerMeshService(port: Int) {
        if (meshService != null) return
        val active = mdns ?: return
        val info = ServiceInfo.create(
            MESH_TYPE,
            "SyncDeck-${localDeviceId.take(8)}",
            port,
            0,
            0,
            mapOf("id" to localDeviceId, "v" to "2"),
        )
        active.registerService(info)
        meshService = info
    }

    private fun listener(
        active: JmDNS,
        type: String,
        accept: (ServiceInfo) -> Unit,
        lose: (String) -> Unit,
    ) = object : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) { active.requestServiceInfo(type, event.name, true) }
        override fun serviceResolved(event: ServiceEvent) = accept(event.info)
        override fun serviceRemoved(event: ServiceEvent) = lose(event.name)
    }

    private fun acceptPairing(info: ServiceInfo) {
        val id = info.getPropertyString("id") ?: return
        val invitation = info.getPropertyString("invite") ?: return
        if (id == localDeviceId || !invitation.matches(Regex("[A-Za-z0-9_-]{8,128}"))) return
        val address = info.inet4Addresses.firstOrNull() ?: info.inetAddresses.firstOrNull() ?: return
        mutablePairingOffers.value = mutablePairingOffers.value + (
            invitation to PairingOffer(invitation, id, address, info.port, info.name)
        )
    }

    private fun losePairing(name: String) {
        mutablePairingOffers.value = mutablePairingOffers.value.filterValues { it.serviceName != name }
    }

    private fun acceptMesh(info: ServiceInfo) {
        val id = info.getPropertyString("id") ?: return
        if (id == localDeviceId) return
        val version = info.getPropertyString("v")?.toIntOrNull() ?: return
        val address = info.inet4Addresses.firstOrNull() ?: info.inetAddresses.firstOrNull() ?: return
        mutablePeers.value = mutablePeers.value + (
            id to DiscoveredPeer(id, address, info.port, version, System.currentTimeMillis())
        )
    }

    private fun loseMesh(name: String) {
        // Android service names end with the first eight device-ID characters.
        mutablePeers.value = mutablePeers.value.filterKeys { !name.endsWith(it.take(8), ignoreCase = true) }
    }

    private fun closeMdns() {
        val active = mdns ?: return
        pairingService?.let { runCatching { active.unregisterService(it) } }
        pairingService = null
        meshService?.let { runCatching { active.unregisterService(it) } }
        meshService = null
        runCatching { active.close() }
        mdns = null
        mutablePairingOffers.value = emptyMap()
        mutablePeers.value = emptyMap()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        enabled = false
        pairingAdvertisement = null
        closeMdns()
    }

    private companion object {
        const val PAIRING_TYPE = "_syncdroid-pair._tcp.local."
        const val MESH_TYPE = "_syncdroid._tcp.local."
    }
}

private fun localAddress(): InetAddress {
    val interfaces = NetworkInterface.getNetworkInterfaces()
    while (interfaces.hasMoreElements()) {
        val network = interfaces.nextElement()
        if (!network.isUp || network.isLoopback || network.isVirtual) continue
        val addresses = network.inetAddresses
        while (addresses.hasMoreElements()) {
            val address = addresses.nextElement()
            if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) return address
        }
    }
    return InetAddress.getLoopbackAddress()
}

private fun broadcastAddresses(): Set<InetAddress> = buildSet {
    add(InetAddress.getByName("255.255.255.255"))
    val interfaces = NetworkInterface.getNetworkInterfaces()
    while (interfaces.hasMoreElements()) interfaces.nextElement().interfaceAddresses.mapNotNullTo(this) { it.broadcast }
}
