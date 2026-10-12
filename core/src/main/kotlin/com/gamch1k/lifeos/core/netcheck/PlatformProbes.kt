package com.gamch1k.lifeos.core.netcheck

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** One GET through Android's platform stack (HttpURLConnection), for comparison. Blocking. */
internal object PlatformHttpProbe {
    const val LABEL = "Android platform stack (HttpURLConnection)"

    fun get(url: String): HttpProbeResult {
        val started = SystemClock.elapsedRealtime()
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            // A fresh TLS handshake every run, not a pooled connection.
            connection.setRequestProperty("Connection", "close")
            val status = connection.responseCode
            val body = (if (status >= 400) connection.errorStream else connection.inputStream)
                ?.use { it.readNBytes(64 * 1024) }
                ?.toString(Charsets.UTF_8)
                .orEmpty()
            HttpProbeResult(
                label = LABEL,
                url = url,
                statusCode = status,
                negotiatedProtocol = null,
                totalMillis = SystemClock.elapsedRealtime() - started,
                trace = CloudflareTrace.parse(body),
                timings = null,
                redirectTo = connection.getHeaderField("Location")?.let(CronetProbe::hostAndPath),
                error = null,
            )
        } catch (error: IOException) {
            HttpProbeResult(
                label = LABEL,
                url = url,
                statusCode = null,
                negotiatedProtocol = null,
                totalMillis = SystemClock.elapsedRealtime() - started,
                trace = null,
                timings = null,
                redirectTo = null,
                error = "${error.javaClass.simpleName}: ${error.message}",
            )
        } finally {
            connection.disconnect()
        }
    }
}

/** Asks the system resolver (which honours Private DNS) for the host's HTTPS record. */
internal object SystemDnsProbe {
    suspend fun httpsRecord(network: Network?, hostname: String, executor: Executor): DnsProbeResult {
        val started = SystemClock.elapsedRealtime()
        val outcome: Result<DnsHttpsAnswer>? = withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { continuation ->
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                // getInstance() is deprecated from API 37 in favour of DnsResolver(Context, Looper),
                // which older Android versions (the phone runs 16) do not have.
                @Suppress("DEPRECATION")
                val resolver = DnsResolver.getInstance()
                resolver.rawQuery(
                    network,
                    hostname,
                    DnsResolver.CLASS_IN,
                    DnsHttpsRecords.TYPE_HTTPS,
                    DnsResolver.FLAG_NO_CACHE_LOOKUP,
                    executor,
                    cancel,
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            continuation.resume(runCatching { DnsHttpsRecords.parse(answer) })
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            continuation.resume(Result.failure(error))
                        }
                    },
                )
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        return when {
            outcome == null -> DnsProbeResult(hostname, null, elapsed, "timed out after 10 s")
            outcome.isFailure -> DnsProbeResult(hostname, null, elapsed, describe(outcome.exceptionOrNull()))
            else -> DnsProbeResult(hostname, outcome.getOrNull(), elapsed, null)
        }
    }

    private fun describe(error: Throwable?): String = when (error) {
        is DnsResolver.DnsException -> "DNS error code ${error.code}: ${error.message}"
        null -> "unknown error"
        else -> "${error.javaClass.simpleName}: ${error.message}"
    }
}

/** Facts about the phone and its current network. */
internal object DeviceFactsCollector {
    fun activeNetwork(context: Context): Network? =
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork

    fun collect(context: Context, cronetVersion: String?): DeviceFacts {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        val link = network?.let { connectivity.getLinkProperties(it) }
        return DeviceFacts(
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            network = describe(capabilities),
            networkValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            privateDnsActive = link?.isPrivateDnsActive,
            privateDnsServerName = link?.privateDnsServerName,
            dnsServers = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress },
            cronetVersion = cronetVersion,
        )
    }

    private fun describe(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) return "none"
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "other"
        }
        val viaVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && transport != "VPN"
        return if (viaVpn) "$transport via VPN" else transport
    }
}
