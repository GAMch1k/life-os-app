package com.gamch1k.lifeos.core.watchsetup

/** What the owner types from the watch's Wireless debugging screens: a host and, usually, a port. */
data class WatchAddress(val host: String, val port: Int?) {
    override fun toString() = if (port == null) host else if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
        private val HOSTNAME = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*$""")

        /**
         * `192.168.1.23:37421`, `192.168.1.23`, `[fe80::1]:37421`; spaces are
         * dropped, a full-width colon counts. Null for anything else.
         */
        fun parse(input: String): WatchAddress? {
            val text = input.filterNot { it.isWhitespace() }.replace('：', ':')
            if (text.isEmpty()) return null
            val (host, portText) = when {
                text.startsWith("[") -> {
                    val end = text.indexOf(']').takeIf { it > 1 } ?: return null
                    val rest = text.substring(end + 1)
                    if (rest.isNotEmpty() && !rest.startsWith(":")) return null
                    text.substring(1, end) to rest.removePrefix(":").ifEmpty { null }
                }
                text.count { it == ':' } == 1 -> text.substringBefore(':') to text.substringAfter(':')
                text.count { it == ':' } > 1 -> return null
                else -> text to null
            }
            val port = portText?.let { p -> p.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null }
            val ipv4 = IPV4.matchEntire(host)
            val valid = when {
                ipv4 != null -> ipv4.groupValues.drop(1).all { it.toInt() in 0..255 }
                ':' in host -> host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
                else -> HOSTNAME.matches(host) && host.any { it.isLetter() }
            }
            return if (valid) WatchAddress(host, port) else null
        }
    }
}

/** The six-digit Wi-Fi pairing code from "Pair new device". */
object PairingCode {
    /** The digits, without the spaces some screens put in; null unless exactly six digits. */
    fun parse(input: String): String? = input.filterNot { it.isWhitespace() || it == '-' }.takeIf { it.length == 6 && it.all(Char::isDigit) }
}

/** The phone's own Wi-Fi address, to tell "wrong network" from "the watch is off". */
data class WifiAddress(val address: String, val prefixLength: Int)

object Subnet {
    /** Whether [host] is in [wifi]'s IPv4 subnet; null when that can't be said (IPv6, a hostname). */
    fun contains(wifi: WifiAddress, host: String): Boolean? {
        val own = ipv4(wifi.address) ?: return null
        val other = ipv4(host) ?: return null
        val prefix = wifi.prefixLength.coerceIn(0, 32)
        if (prefix == 0) return true
        val mask = (-1 shl (32 - prefix))
        return (own and mask) == (other and mask)
    }

    private fun ipv4(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var value = 0
        for (part in parts) {
            val octet = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            value = (value shl 8) or octet
        }
        return value
    }
}

/** What the watch's shell answers. */
object ShellOutput {
    sealed interface Install {
        data object Success : Install

        /** [code] such as `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, or `UNKNOWN`. */
        data class Failure(val code: String, val detail: String) : Install
    }

    private val FAILURE = Regex("""Failure \[([A-Z0-9_]+)(?::\s*([^\]]*))?]""")

    /** `cmd package install`'s answer: `Success`, or `Failure [CODE: detail]`. */
    fun install(output: String): Install {
        if (output.lineSequence().any { it.trim() == "Success" }) return Install.Success
        val match = FAILURE.find(output) ?: return Install.Failure("UNKNOWN", output.trim().take(300).ifEmpty { "no answer" })
        return Install.Failure(match.groupValues[1], match.groupValues[2].trim())
    }

    /** `appops get <package> <op>`: `REQUEST_INSTALL_PACKAGES: allow; time=…`. */
    fun appOpMode(output: String, op: String): String? =
        Regex("""^\s*${Regex.escape(op)}:\s*([a-z_]+)""", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)

    /** A few installer failures, said plainly. */
    fun explain(failure: Install.Failure): String = when (failure.code) {
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -> "a Life OS signed with another key is installed on the watch; uninstall it there first"
        "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> "the watch is out of storage"
        "INSTALL_FAILED_OLDER_SDK" -> "the watch's Wear OS is too old for this version"
        "INSTALL_FAILED_NO_MATCHING_ABIS" -> "the watch's processor runs neither of the APK's ABIs"
        "INSTALL_FAILED_VERSION_DOWNGRADE" -> "the watch already has a newer Life OS"
        "INSTALL_FAILED_USER_RESTRICTED" -> "the watch blocks installs over ADB"
        "INSTALL_PARSE_FAILED_NO_CERTIFICATES", "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" -> "the APK's signature is broken"
        else -> failure.detail.ifEmpty { "no reason given" }
    }
}
