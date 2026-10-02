package com.syncdroid.app.mesh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredPairingOffer(
    val invitationId: String,
    val deviceId: String,
    val address: InetAddress,
    val port: Int,
    val serviceName: String,
)

class PairingNsd(context: Context, private val localDeviceId: String) : AutoCloseable {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)
    private val multicastLock = wifi.createMulticastLock("syncdroid-pairing-mdns").apply { setReferenceCounted(false) }
    private val mutableOffers = MutableStateFlow<Map<String, DiscoveredPairingOffer>>(emptyMap())
    val offers: StateFlow<Map<String, DiscoveredPairingOffer>> = mutableOffers.asStateFlow()
    private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var discovery: NsdManager.DiscoveryListener? = null
    private val resolutionLock = Any()
    private val pendingResolutions = ArrayDeque<NsdServiceInfo>()
    private var resolutionActive = false

    fun advertise(port: Int, invitationId: String) {
        acquireLock()
        val info = NsdServiceInfo().apply {
            serviceName = "SyncDroid-Pair-${localDeviceId.take(8)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", localDeviceId)
            setAttribute("invite", invitationId)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun discover() {
        if (discovery != null) return
        acquireLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Discovery reports the type with or without its trailing dot depending on the Android version.
                if (!sameServiceType(serviceInfo.serviceType, SERVICE_TYPE)) return
                enqueueResolution(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                mutableOffers.value = mutableOffers.value.filterValues { it.serviceName != serviceInfo.serviceName }
            }
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /** Before Android 14, a resolve fails while another is still in progress, so resolve one at a time. */
    private fun enqueueResolution(serviceInfo: NsdServiceInfo) {
        synchronized(resolutionLock) {
            if (discovery == null) return
            if (pendingResolutions.none { it.serviceName == serviceInfo.serviceName }) pendingResolutions.addLast(serviceInfo)
        }
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val service = synchronized(resolutionLock) {
            if (resolutionActive) return
            pendingResolutions.removeFirstOrNull()?.also { resolutionActive = true }
        } ?: return
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = resolutionFinished()
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                accept(resolved)
                resolutionFinished()
            }
        })
    }

    private fun resolutionFinished() {
        synchronized(resolutionLock) { resolutionActive = false }
        resolveNext()
    }

    private fun accept(info: NsdServiceInfo) {
        val id = info.attributes["id"]?.toString(StandardCharsets.UTF_8) ?: return
        val invitation = info.attributes["invite"]?.toString(StandardCharsets.UTF_8) ?: return
        if (id == localDeviceId || invitation.isBlank()) return
        @Suppress("DEPRECATION")
        val address = info.host ?: return
        mutableOffers.value = mutableOffers.value + (invitation to DiscoveredPairingOffer(
            invitation, id, address, info.port, info.serviceName,
        ))
    }

    private fun acquireLock() {
        if (!multicastLock.isHeld) multicastLock.acquire()
    }

    override fun close() {
        synchronized(resolutionLock) { pendingResolutions.clear() }
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discovery = null
        registration = null
        mutableOffers.value = emptyMap()
        if (multicastLock.isHeld) multicastLock.release()
    }

    private companion object { const val SERVICE_TYPE = "_syncdroid-pair._tcp." }
}
