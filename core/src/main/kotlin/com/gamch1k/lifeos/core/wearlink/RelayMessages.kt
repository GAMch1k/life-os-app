package com.gamch1k.lifeos.core.wearlink

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One Life OS API request the watch asks the phone to run. It travels as the
 * header of a frame ([RelayCodec]); the body, [bodyLength] bytes, follows it.
 *
 * The watch never sends the service token or the actor: the phone adds the
 * `CF-Access-Client-*` headers, and [RelayPolicy] adds `X-Life-OS-Actor: user`
 * to writes.
 */
@Serializable
data class RelayRequest(
    val v: Int = WearLink.PROTOCOL_VERSION,
    /** A fresh UUID per request; the reply carries the same id. */
    val id: String,
    val method: String,
    /** Path and query on the Life OS host, such as `/api/tasks/v1/tasks?limit=1`. */
    val path: String,
    /** Only the headers in [RelayPolicy.FORWARDED_HEADERS] are passed on. */
    val headers: Map<String, String> = emptyMap(),
    val bodyLength: Int = 0,
)

/**
 * The phone's answer to a [RelayRequest]: either Life OS's HTTP response
 * ([status], whatever it is, and a body of [bodyLength] bytes after the
 * header), or an [error] when no HTTP response came.
 */
@Serializable
data class RelayResponse(
    val v: Int = WearLink.PROTOCOL_VERSION,
    val id: String,
    val status: Int? = null,
    val contentType: String? = null,
    val bodyLength: Int = 0,
    val error: RelayError? = null,
)

@Serializable
data class RelayError(val code: RelayErrorCode, val message: String = "")

/** Why the phone could not return an HTTP response. */
@Serializable
enum class RelayErrorCode {
    /** The frame could not be read. */
    @SerialName("bad_request")
    BAD_REQUEST,

    /** The other side speaks another [WearLink.PROTOCOL_VERSION]. */
    @SerialName("unsupported_version")
    UNSUPPORTED_VERSION,

    /** [RelayPolicy] does not let this method, path or header through. */
    @SerialName("forbidden")
    FORBIDDEN,

    /** The phone has no service token. */
    @SerialName("no_credentials")
    NO_CREDENTIALS,

    /** The phone app does not relay requests (no [WearRelay] wired yet). */
    @SerialName("relay_unavailable")
    RELAY_UNAVAILABLE,

    /** The phone could not reach Life OS (no network, DNS, TLS, timeout). */
    @SerialName("upstream_unreachable")
    UPSTREAM_UNREACHABLE,

    /** The reply does not fit in a message; send the request through a channel. */
    @SerialName("too_large")
    TOO_LARGE,

    /** Anything else that went wrong on the phone. */
    @SerialName("internal")
    INTERNAL,
    ;

    /**
     * Whether the watch should try its own connection (direct Cronet) after
     * this error. Only errors about the request itself rule that out.
     */
    val watchMayGoDirect: Boolean get() = this != FORBIDDEN && this != BAD_REQUEST
}

/** Watch → phone: "send me the server and the token". */
@Serializable
data class CredentialsRequest(
    val v: Int = WearLink.PROTOCOL_VERSION,
    val id: String,
)

/** Phone → watch: the credentials, or why there are none. */
@Serializable
data class CredentialsReply(
    val v: Int = WearLink.PROTOCOL_VERSION,
    val id: String,
    val credentials: WearCredentials? = null,
    val error: RelayError? = null,
)

/**
 * What the watch needs to call Life OS by itself: the base URL (scheme and
 * host, no trailing slash) and a Cloudflare Access service token. Its
 * toString never shows the secret.
 */
@Serializable
class WearCredentials(
    val baseUrl: String,
    val clientId: String,
    val clientSecret: String,
) {
    val isComplete: Boolean
        get() = baseUrl.startsWith("https://") && clientId.isNotBlank() && clientSecret.isNotBlank()

    override fun toString() = "WearCredentials($baseUrl, …)"

    override fun equals(other: Any?) = other is WearCredentials &&
        other.baseUrl == baseUrl && other.clientId == clientId && other.clientSecret == clientSecret

    override fun hashCode() = (baseUrl.hashCode() * 31 + clientId.hashCode()) * 31 + clientSecret.hashCode()
}

/** What an HTTP status from Life OS (behind Cloudflare Access) means for the caller. */
enum class HttpOutcome {
    SUCCESS,

    /** Access refused the token (401/403) or redirected to its login (3xx): a new token is needed. */
    AUTH,

    /** Worth retrying later: 408, 425, 429 and 5xx (Cloudflare's 52x included). */
    RETRYABLE,

    /** The request itself was refused (other 4xx); retrying the same request won't help. */
    PERMANENT,
    ;

    companion object {
        fun of(status: Int): HttpOutcome = when {
            status in 200..299 -> SUCCESS
            status in 300..399 || status == 401 || status == 403 -> AUTH
            status == 408 || status == 425 || status == 429 || status >= 500 -> RETRYABLE
            else -> PERMANENT
        }
    }
}
