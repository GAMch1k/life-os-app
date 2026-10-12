package com.gamch1k.lifeos.core.netcheck

import java.time.ZonedDateTime

/**
 * Everything one run of the network check found. It never holds a secret:
 * the service token only shows up as whether it was set.
 */
data class NetworkCheckResult(
    val startedAt: ZonedDateTime,
    val appVersion: String,
    val quicAllowed: Boolean,
    val device: DeviceFacts,
    /** Embedded Cronet with its built-in DNS resolver: the stack under test. */
    val cronet: HttpProbeResult,
    /** Android's own Cronet (HttpEngine), when the device has it. */
    val httpEngine: HttpProbeResult?,
    /** Android's platform stack (HttpURLConnection), for comparison. */
    val platform: HttpProbeResult,
    /** The HTTPS DNS record as the system resolver sees it. */
    val dns: DnsProbeResult,
    /** The service-token request through embedded Cronet; null when no token is stored. */
    val authenticated: HttpProbeResult?,
)

/** What one HTTP request through one network stack produced. */
data class HttpProbeResult(
    val label: String,
    val url: String,
    val statusCode: Int?,
    val negotiatedProtocol: String?,
    val totalMillis: Long,
    val trace: CloudflareTrace?,
    val timings: ConnectionTimings?,
    /** Where a 3xx pointed (only its host and path); null otherwise. */
    val redirectTo: String?,
    /** The failure, or null when a response arrived. */
    val error: String?,
)

/** Connection phases of a Cronet request, in milliseconds (null when not measured). */
data class ConnectionTimings(
    val dnsMillis: Long?,
    val connectMillis: Long?,
    val tlsMillis: Long?,
    val socketReused: Boolean,
)

data class DnsProbeResult(
    val hostname: String,
    val answer: DnsHttpsAnswer?,
    val totalMillis: Long,
    val error: String?,
)

data class DeviceFacts(
    val androidRelease: String,
    val sdkInt: Int,
    val deviceModel: String,
    /** e.g. "Wi-Fi", "mobile data", "Wi-Fi via VPN", "none". */
    val network: String,
    val networkValidated: Boolean?,
    /** LinkProperties.isPrivateDnsActive; null when there is no active network. */
    val privateDnsActive: Boolean?,
    /** LinkProperties.getPrivateDnsServerName: set only in strict mode ("Private DNS provider hostname"). */
    val privateDnsServerName: String?,
    val dnsServers: List<String>,
    /** The embedded Cronet's version, e.g. 155.0.8059.16. */
    val cronetVersion: String?,
)
