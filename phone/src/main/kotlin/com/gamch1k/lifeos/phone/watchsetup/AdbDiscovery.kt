package com.gamch1k.lifeos.phone.watchsetup

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Finds the port Wireless debugging listens on for connections: the watch
 * announces it over mDNS as `_adb-tls-connect._tcp`. libadb-android's own
 * discovery only reports a daemon on the phone itself, so this one matches
 * the watch's address instead.
 */
class AdbDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    /** The connection port [host] announces, or null when none came within [timeoutMillis]. */
    suspend fun connectPort(host: String, timeoutMillis: Long): Int? {
        val manager = nsd ?: return null
        val wanted = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        val found = CompletableDeferred<Int>()
        val callbacks = mutableListOf<NsdManager.ServiceInfoCallback>()
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                    override fun onServiceLost() = Unit
                    override fun onServiceInfoCallbackUnregistered() = Unit

                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        if (info.hostAddresses.any { it == wanted } && info.port in 1..65535) found.complete(info.port)
                    }
                }
                synchronized(callbacks) { callbacks += callback }
                runCatching { manager.registerServiceInfoCallback(serviceInfo, executor, callback) }
            }
        }
        return try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            withTimeoutOrNull(timeoutMillis) { found.await() }
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            runCatching { manager.stopServiceDiscovery(discovery) }
            synchronized(callbacks) { callbacks.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } } }
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_adb-tls-connect._tcp"
    }
}
