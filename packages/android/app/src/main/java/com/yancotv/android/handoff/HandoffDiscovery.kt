package com.yancotv.android.handoff

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.yancotv.shared.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A YancoTV receiver found on the LAN, resolved to a reachable host:port. */
data class DiscoveredTv(val name: String, val host: String, val port: Int)

/**
 * MK.26.A.2 — discovers YancoTV receivers on the LAN via NSD (DNS-SD / mDNS).
 * Browses the `_yancotv._tcp` service the receiver advertises; resolved hosts
 * feed [devices]. This is a CONVENIENCE over manual pairing, not a replacement:
 * mDNS is the #1 field-flaky path (router/AP multicast suppression, Doze), so
 * the picker keeps the typed-IP option alongside whatever this finds.
 *
 * Lifecycle: [start] when the picker opens, [stop] when it closes. NsdManager
 * owns its own multicast lock, so no extra permission/lock is needed here.
 */
class HandoffDiscovery(context: Context, private val logger: Logger) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _devices = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val devices: StateFlow<List<DiscoveredTv>> = _devices.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null

    /** Idempotent. Begins browsing; discovered hosts arrive asynchronously in [devices]. */
    fun start() {
        if (listener != null) return
        val l =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}

                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    logger.warn("Handoff discovery start failed: $errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

                override fun onServiceFound(info: NsdServiceInfo) {
                    resolve(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    _devices.value = _devices.value.filterNot { it.name == info.serviceName }
                }
            }
        listener = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { logger.warn("Handoff discoverServices failed: ${it.message}") }
    }

    /** Idempotent. Stops browsing and clears the resolved set. */
    fun stop() {
        listener?.let { active -> runCatching { nsd.stopServiceDiscovery(active) } }
        listener = null
        _devices.value = emptyList()
    }

    // resolveService(..) + NsdServiceInfo.getHost() are deprecated at API 34 in
    // favour of registerServiceInfoCallback / getHostAddresses, but both work on
    // min-SDK 24 (and the canonical Fire TV target is API 28). Revisit if/when
    // targetSdk forces it.
    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) {
        val resolveListener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    logger.warn("Handoff resolve failed: $errorCode")
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    val tv = DiscoveredTv(resolved.serviceName ?: host, host, resolved.port)
                    _devices.value =
                        (_devices.value.filterNot { it.host == tv.host } + tv).sortedBy { it.name }
                }
            }
        runCatching { nsd.resolveService(info, resolveListener) }
            .onFailure { logger.warn("Handoff resolveService failed: ${it.message}") }
    }

    companion object {
        /** Trailing dot is required by NsdManager for the registration type. */
        const val SERVICE_TYPE = "_yancotv._tcp."
    }
}
