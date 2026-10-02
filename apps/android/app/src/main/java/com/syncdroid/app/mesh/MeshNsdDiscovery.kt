package com.syncdroid.app.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredMeshPeer(
    val deviceId: String,
    val serviceName: String,
    val address: InetAddress,
    val port: Int,
    val protocolMajor: Int,
    val lastSeenAtMillis: Long,
)

class MeshNsdDiscovery(
    context: Context,
    private val localDeviceId: String,
    private val advertise: Boolean = true,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val multicastLock = wifi.createMulticastLock("syncdroid-mdns").apply { setReferenceCounted(false) }
    private val mutablePeers = MutableStateFlow<Map<String, DiscoveredMeshPeer>>(emptyMap())
    val peers: StateFlow<Map<String, DiscoveredMeshPeer>> = mutablePeers.asStateFlow()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolutionLock = Any()
    private val pendingResolutions = ArrayDeque<NsdServiceInfo>()
    private var resolutionActive = false
    private var closed = true

    fun start(port: Int = 0) {
        if (advertise) require(port in 1..65535) { "A listening peer port is required" }
        check(registrationListener == null && discoveryListener == null) { "Discovery is already running" }
        closed = false
        multicastLock.acquire()
        if (advertise) register(port)
        discover()
    }

    private fun register(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "SyncDroid-${localDeviceId.take(8)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(ATTRIBUTE_DEVICE_ID, localDeviceId)
            setAttribute(ATTRIBUTE_PROTOCOL, PROTOCOL_MAJOR.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Registered ${serviceInfo.serviceName} on port ${serviceInfo.port}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun discover() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovering $serviceType")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery failed to start: $errorCode")
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery failed to stop: $errorCode")
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!sameServiceType(serviceInfo.serviceType, SERVICE_TYPE)) return
                if (serviceInfo.serviceName.startsWith("SyncDroid-${localDeviceId.take(8)}")) return
                enqueueResolution(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                mutablePeers.value = mutablePeers.value.filterValues { it.serviceName != serviceInfo.serviceName }
            }
        }
        discoveryListener = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun enqueueResolution(serviceInfo: NsdServiceInfo) {
        synchronized(resolutionLock) {
            if (closed) return
            if (pendingResolutions.none { it.serviceName == serviceInfo.serviceName }) {
                pendingResolutions.addLast(serviceInfo)
            }
        }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val service = synchronized(resolutionLock) {
            if (closed || resolutionActive) return
            pendingResolutions.removeFirstOrNull()?.also { resolutionActive = true }
        } ?: return
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Could not resolve ${serviceInfo.serviceName}: $errorCode")
                resolutionFinished()
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                acceptResolved(resolved)
                resolutionFinished()
            }
        })
    }

    private fun resolutionFinished() {
        synchronized(resolutionLock) { resolutionActive = false }
        resolveNext()
    }

    private fun acceptResolved(info: NsdServiceInfo) {
        val id = info.attributes[ATTRIBUTE_DEVICE_ID]?.toString(StandardCharsets.UTF_8) ?: return
        if (id == localDeviceId) return
        val protocol = info.attributes[ATTRIBUTE_PROTOCOL]
            ?.toString(StandardCharsets.UTF_8)
            ?.toIntOrNull() ?: return
        @Suppress("DEPRECATION")
        val address = info.host ?: return
        val peer = DiscoveredMeshPeer(id, info.serviceName, address, info.port, protocol, System.currentTimeMillis())
        mutablePeers.value = mutablePeers.value + (id to peer)
        Log.d(TAG, "Resolved mesh peer ${id.take(8)} at ${address.hostAddress}:${info.port}")
    }

    override fun close() {
        synchronized(resolutionLock) {
            closed = true
            pendingResolutions.clear()
        }
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registrationListener?.let { runCatching { nsd.unregisterService(it) } }
        discoveryListener = null
        registrationListener = null
        mutablePeers.value = emptyMap()
        if (multicastLock.isHeld) multicastLock.release()
    }

    private companion object {
        const val SERVICE_TYPE = "_syncdroid._tcp."
        const val ATTRIBUTE_DEVICE_ID = "id"
        const val ATTRIBUTE_PROTOCOL = "v"
        const val PROTOCOL_MAJOR = 2
        const val TAG = "SyncDroidNsd"
    }
}

internal fun sameServiceType(left: String, right: String): Boolean =
    left.trimEnd('.').equals(right.trimEnd('.'), ignoreCase = true)
