package com.gamch1k.lifeos.core.netcheck

import java.net.URI
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Verdict {
    /** Embedded Cronet got through with ECH (sni=encrypted). */
    ECH_WORKS,

    /** Embedded Cronet got a trace, but with the hostname in clear. */
    ECH_NOT_USED,

    /** Embedded Cronet got no trace at all. */
    CRONET_FAILED,
}

data class ReportSection(val title: String, val lines: List<String>)

/**
 * Turns a [NetworkCheckResult] into the sections the screen shows and the
 * plain-text report the owner copies. Secrets never reach it: the result only
 * says whether a service token was set.
 */
object NetworkCheckReport {
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz '('VV')'", Locale.ROOT)

    fun verdict(result: NetworkCheckResult): Verdict {
        val trace = result.cronet.trace
        return when {
            trace?.echAccepted == true -> Verdict.ECH_WORKS
            trace == null || !trace.isTrace -> Verdict.CRONET_FAILED
            else -> Verdict.ECH_NOT_USED
        }
    }

    fun sections(result: NetworkCheckResult): List<ReportSection> = listOf(
        ReportSection("Result", verdictLines(result)),
        ReportSection("1. ${result.cronet.label}", probeLines(result.cronet)),
        ReportSection("2. ${result.platform.label}", probeLines(result.platform)),
        ReportSection(
            "2b. ${result.httpEngine?.label ?: HTTP_ENGINE_LABEL}",
            result.httpEngine?.let(::probeLines) ?: listOf("Not available on this device."),
        ),
        ReportSection("3. Device", deviceLines(result)),
        ReportSection("3b. DNS HTTPS record via the system resolver", dnsLines(result.dns)),
        ReportSection("4. Service token request (embedded Cronet)", authLines(result.authenticated)),
    )

    const val HTTP_ENGINE_LABEL = "HttpEngine, Android's own Cronet (extra)"

    fun build(result: NetworkCheckResult): String = buildString {
        appendLine("Life OS network check")
        appendLine("App ${result.appVersion}, ${timeFormat.format(result.startedAt)}")
        appendLine("HTTP/3 (QUIC) allowed: ${if (result.quicAllowed) "yes" else "no"}")
        for (section in sections(result)) {
            appendLine()
            appendLine("[${section.title}]")
            section.lines.forEach(::appendLine)
        }
    }

    fun verdictLines(result: NetworkCheckResult): List<String> {
        val cronet = result.cronet
        val verdict = verdict(result)
        val headline = when (verdict) {
            Verdict.ECH_WORKS -> "GO: embedded Cronet reached Life OS with an encrypted ClientHello (sni=encrypted)."
            Verdict.ECH_NOT_USED -> "NO ECH: embedded Cronet connected, but sent the hostname in clear (sni=${cronet.trace?.sni})."
            Verdict.CRONET_FAILED -> "FAILED: embedded Cronet got no trace (${cronet.error ?: "HTTP ${cronet.statusCode}, not a trace"})."
        }
        val platform = result.platform
        val comparison = when {
            platform.trace?.isTrace == true -> "Platform stack for comparison: sni=${platform.trace.sni}."
            else -> "Platform stack for comparison: failed (${platform.error ?: "HTTP ${platform.statusCode}"})."
        }
        return listOf(headline, comparison) + if (verdict == Verdict.ECH_WORKS) emptyList() else hints(result)
    }

    private fun hints(result: NetworkCheckResult): List<String> = buildList {
        val device = result.device
        if (device.privateDnsActive == true) {
            add(
                "Private DNS is active (${device.privateDnsServerName ?: "automatic"}). Chromium's built-in resolver " +
                    "then hands over to the system resolver, which cannot fetch HTTPS records, so Cronet has no ECH key. " +
                    "Set Settings › Network › Private DNS to Off and run again.",
            )
        }
        val answer = result.dns.answer
        when {
            answer == null -> Unit
            !answer.hasEch -> add(
                "The system resolver got no HTTPS record with an ECH key for ${result.dns.hostname}: this network's DNS " +
                    "may strip HTTPS records, and then there is nothing to encrypt with.",
            )
            device.privateDnsActive != true -> add(
                "DNS does serve an ECH key, yet Cronet did not use it. Run the check again; if it stays like this, " +
                    "Cronet's own DNS queries may be blocked or slower than the connection.",
            )
        }
    }

    fun probeLines(probe: HttpProbeResult): List<String> = buildList {
        val target = pathOf(probe.url)
        if (probe.error != null) {
            add("GET $target failed after ${probe.totalMillis} ms: ${probe.error}")
        } else {
            val protocol = probe.negotiatedProtocol?.takeIf { it.isNotBlank() && it != "unknown" }?.let { " ($it)" } ?: ""
            add("GET $target → HTTP ${probe.statusCode} in ${probe.totalMillis} ms$protocol")
        }
        probe.redirectTo?.let { add("redirected to $it") }
        probe.trace?.let { trace ->
            if (trace.isTrace) add(trace.summaryLines().joinToString("  ")) else add("the response is not a Cloudflare trace")
        }
        probe.timings?.let { add(timingLine(it)) }
    }

    private fun timingLine(timings: ConnectionTimings): String {
        if (timings.socketReused) return "connection: reused"
        fun part(name: String, millis: Long?) = millis?.let { "$name $it ms" }
        val parts = listOfNotNull(
            part("dns", timings.dnsMillis),
            part("connect", timings.connectMillis),
            part("tls", timings.tlsMillis),
        )
        return "connection: " + parts.ifEmpty { listOf("no timings") }.joinToString(", ")
    }

    fun dnsLines(dns: DnsProbeResult): List<String> {
        if (dns.error != null) return listOf("HTTPS query for ${dns.hostname} failed after ${dns.totalMillis} ms: ${dns.error}")
        val answer = dns.answer ?: return listOf("HTTPS query for ${dns.hostname}: no answer")
        val head = "HTTPS query for ${dns.hostname} in ${dns.totalMillis} ms: " + when {
            answer.rcode != 0 -> "DNS error (rcode ${answer.rcode})"
            answer.records.isEmpty() -> "no HTTPS record"
            else -> "${answer.records.size} record(s)"
        }
        return listOf(head) + answer.records.map { record ->
            val ech = record.echConfigListSize?.let { size ->
                val names = record.echPublicNames.ifEmpty { listOf("?") }.joinToString(",")
                "ech=yes ($size bytes, public name $names)"
            } ?: "ech=no"
            val alpn = if (record.alpn.isEmpty()) "" else "alpn=${record.alpn.joinToString(",")}  "
            "priority ${record.priority}, target ${record.targetName}  $alpn$ech"
        }
    }

    fun deviceLines(result: NetworkCheckResult): List<String> {
        val device = result.device
        val privateDns = when (device.privateDnsActive) {
            null -> "unknown (no active network)"
            false -> "not active"
            true -> device.privateDnsServerName?.let { "ACTIVE, strict ($it)" } ?: "ACTIVE, automatic"
        }
        val validated = when (device.networkValidated) {
            true -> ", validated"
            false -> ", not validated"
            null -> ""
        }
        return listOf(
            "Android ${device.androidRelease} (SDK ${device.sdkInt}), ${device.deviceModel}",
            "Network: ${device.network}$validated",
            "Private DNS: $privateDns",
            "DNS servers: ${device.dnsServers.ifEmpty { listOf("none reported") }.joinToString(", ")}",
            "Cronet (embedded): ${device.cronetVersion ?: "unavailable"}",
        )
    }

    fun authLines(probe: HttpProbeResult?): List<String> {
        if (probe == null) return listOf("No service token stored, skipped.")
        val meaning = when {
            probe.error != null -> null
            probe.statusCode == 200 -> "The service token works: Access let the request through to Life OS."
            probe.statusCode in 300..399 && probe.redirectTo?.contains("cloudflareaccess.com") == true ->
                "Sent to the Access login: no Service Auth policy accepts this token."
            probe.statusCode == 401 -> "Access refused the token: wrong or partial ID/secret, or it expired."
            probe.statusCode == 403 -> "Forbidden: Access or Life OS refused the request."
            else -> null
        }
        return listOf("Service token: set (values omitted)") + probeLines(probe) + listOfNotNull(meaning)
    }

    private fun pathOf(url: String): String = try {
        val uri = URI(url)
        uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
    } catch (_: Exception) {
        url
    }
}
