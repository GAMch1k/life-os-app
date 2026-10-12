package com.gamch1k.lifeos.core.netcheck

/**
 * The `key=value` lines that Cloudflare's edge returns from `/cdn-cgi/trace`.
 *
 * `sni=encrypted` means the edge received an Encrypted Client Hello (ECH), so
 * the hostname never crossed the network in clear; `sni=plaintext` means it did.
 */
data class CloudflareTrace(val fields: Map<String, String>) {
    val sni: String? get() = fields["sni"]
    val http: String? get() = fields["http"]
    val tls: String? get() = fields["tls"]
    val warp: String? get() = fields["warp"]

    /** True when the edge saw an encrypted ClientHello. */
    val echAccepted: Boolean get() = sni == "encrypted"

    /** Whether the body looked like a trace at all (and not, say, a login page). */
    val isTrace: Boolean get() = "sni" in fields && "h" in fields

    /**
     * The lines the check shows and reports, in a fixed order. The client IP
     * (`ip=`) is deliberately left out.
     */
    fun summaryLines(): List<String> = SHOWN_KEYS.mapNotNull { key -> fields[key]?.let { "$key=$it" } }

    companion object {
        val SHOWN_KEYS = listOf("sni", "http", "tls", "warp", "kex", "colo")

        fun parse(body: String): CloudflareTrace {
            val fields = LinkedHashMap<String, String>()
            for (raw in body.lineSequence()) {
                val line = raw.trim()
                val separator = line.indexOf('=')
                if (separator <= 0) continue
                val key = line.substring(0, separator)
                if (key.any { !it.isLetterOrDigit() && it != '_' && it != '-' }) continue
                fields.putIfAbsent(key, line.substring(separator + 1).trim())
            }
            return CloudflareTrace(fields)
        }
    }
}
