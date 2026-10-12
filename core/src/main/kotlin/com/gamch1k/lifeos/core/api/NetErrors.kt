package com.gamch1k.lifeos.core.api

/** Chromium net error names (net/base/net_error_list.h) for the codes the app may meet. */
object NetErrors {
    private val names = mapOf(
        -2 to "ERR_FAILED",
        -3 to "ERR_ABORTED",
        -7 to "ERR_TIMED_OUT",
        -10 to "ERR_ACCESS_DENIED",
        -21 to "ERR_NETWORK_CHANGED",
        -100 to "ERR_CONNECTION_CLOSED",
        -101 to "ERR_CONNECTION_RESET",
        -102 to "ERR_CONNECTION_REFUSED",
        -103 to "ERR_CONNECTION_ABORTED",
        -104 to "ERR_CONNECTION_FAILED",
        -105 to "ERR_NAME_NOT_RESOLVED",
        -106 to "ERR_INTERNET_DISCONNECTED",
        -107 to "ERR_SSL_PROTOCOL_ERROR",
        -109 to "ERR_ADDRESS_UNREACHABLE",
        -113 to "ERR_SSL_VERSION_OR_CIPHER_MISMATCH",
        -118 to "ERR_CONNECTION_TIMED_OUT",
        -137 to "ERR_NAME_RESOLUTION_FAILED",
        -138 to "ERR_NETWORK_ACCESS_DENIED",
        -159 to "ERR_SSL_UNRECOGNIZED_NAME_ALERT",
        -183 to "ERR_ECH_NOT_NEGOTIATED",
        -184 to "ERR_ECH_FALLBACK_CERTIFICATE_INVALID",
        -200 to "ERR_CERT_COMMON_NAME_INVALID",
        -201 to "ERR_CERT_DATE_INVALID",
        -202 to "ERR_CERT_AUTHORITY_INVALID",
        -324 to "ERR_EMPTY_RESPONSE",
        -356 to "ERR_QUIC_PROTOCOL_ERROR",
        -803 to "ERR_DNS_TIMED_OUT",
    )

    fun name(code: Int): String = names[code] ?: "net error"

    fun describe(code: Int): String = "${name(code)} ($code)"
}
