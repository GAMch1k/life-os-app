package com.gamch1k.lifeos.core.pairing

import com.gamch1k.lifeos.core.secrets.ServerUrl
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * The pairing link (life-os `docs/api/devices.md`, "The pairing link"):
 *
 * ```
 * lifeos://pair?host=<public URL>&code=<code>[&state=<state>]
 * lifeos://pair?error=access_denied&state=<state>
 * ```
 *
 * The first comes from a QR code in the Dashboard's Settings → Devices (no
 * `state`) or from the sign-in page in a Custom Tab (with the `state` the
 * app passed); the second when the person tapped Cancel on that page.
 *
 * [parse] accepts exactly that: the `lifeos` scheme, the `pair` host, no
 * path, port, user or fragment, an `https://` host (an origin: a host and
 * perhaps a port, no path), a well-formed code and state, each parameter at
 * most once. Anything else is null: a pairing link can come from anywhere a
 * link can. Where a code may lead is [PairingTarget]'s to judge.
 */
sealed interface PairingLink {
    /** A code to redeem at [server] (an https URL, no trailing slash). [state] only in the Custom Tab flow. */
    class Code(val server: String, val code: String, val state: String?) : PairingLink {
        /** The server to show the person, with its port if any ("Pair with lifeos.example.com:8443?"). */
        val authority: String get() = authorityOf(server)

        override fun equals(other: Any?) = other is Code && other.server == server && other.code == code && other.state == state
        override fun hashCode() = (server.hashCode() * 31 + code.hashCode()) * 31 + state.hashCode()

        // The code is a credential for five minutes: never in a log line.
        override fun toString() = "PairingLink.Code($server, …)"
    }

    /** The sign-in page sent [error] (`access_denied`: the person tapped Cancel) for the flow [state] started. */
    data class Refused(val error: String, val state: String) : PairingLink

    companion object {
        const val SCHEME = "lifeos"
        const val HOST = "pair"

        private val STATE = Regex("^[A-Za-z0-9_-]{8,128}$")
        private val ERROR = Regex("^[a-z_]{1,64}$")
        private val KNOWN = setOf("host", "code", "state", "error")

        fun parse(link: String): PairingLink? {
            if (link.length > 2048 || link.any { it.isWhitespace() || it.isISOControl() }) return null
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            // URI("lifeos://pair?…") has the authority "pair": no user, no port.
            if (uri.rawAuthority?.lowercase(Locale.ROOT) != HOST) return null
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
            if (uri.rawFragment != null) return null
            val parameters = query(uri.rawQuery ?: return null) ?: return null

            val state = parameters["state"]?.also { if (!STATE.matches(it)) return null }
            val error = parameters["error"]
            if (error != null) {
                if (!ERROR.matches(error) || state == null || "code" in parameters) return null
                return Refused(error, state)
            }
            val server = parameters["host"]?.let(::server) ?: return null
            val code = parameters["code"]?.let(PairingCode::normalize) ?: return null
            return Code(server, code, state)
        }

        /**
         * `https://lifeos.example.com` from the link's `host`: https only,
         * written out in full, an origin (a trailing slash is fine, a path is
         * not: the server's public URL is an origin).
         */
        fun server(host: String): String? {
            if (!host.startsWith("https://", ignoreCase = true)) return null
            val server = ServerUrl.normalize("https://" + host.substring("https://".length)) ?: return null
            val path = runCatching { URI(server).rawPath }.getOrNull() ?: return null
            return server.takeIf { path.isEmpty() }
        }

        /** `lifeos.example.com`, or `lifeos.example.com:8443` with a port: what the person reads. */
        fun authorityOf(server: String): String = runCatching { URI(server).rawAuthority }.getOrNull() ?: server

        /** The decoded parameters, or null when one is malformed or comes twice. Unknown ones are ignored. */
        private fun query(raw: String): Map<String, String>? {
            val result = mutableMapOf<String, String>()
            for (part in raw.split('&')) {
                if (part.isEmpty()) continue
                val name = part.substringBefore('=')
                if (name !in KNOWN) continue
                if ('=' !in part) return null
                val value = runCatching { URLDecoder.decode(part.substringAfter('='), Charsets.UTF_8) }.getOrNull() ?: return null
                if (result.put(name, value) != null) return null
            }
            return result
        }
    }
}

/**
 * Pairing codes: 16 characters from [ALPHABET] in four groups, such as
 * `K7QM-2XRP-9VDA-H4TC`. Case and dashes do not matter when one is redeemed.
 */
object PairingCode {
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 16

    /** `k7qm 2xrp-9vda h4tc` → `K7QM-2XRP-9VDA-H4TC`; null for anything that cannot be a code. */
    fun normalize(input: String): String? {
        val compact = input.filterNot { it.isWhitespace() || it == '-' }.uppercase(Locale.ROOT)
        if (compact.length != LENGTH || compact.any { it !in ALPHABET }) return null
        return compact.chunked(4).joinToString("-")
    }
}
