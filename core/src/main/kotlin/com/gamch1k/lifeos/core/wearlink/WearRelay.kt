package com.gamch1k.lifeos.core.wearlink

/**
 * The phone side of the link, as the phone app implements it.
 *
 * The phone app's `Application` implements [WearLinkHost] (since 0.3.0);
 * the phone's Data Layer listener (phone/…/wearlink/PhoneWearLinkService)
 * asks it for the two pieces below. Without it, the listener would hand over
 * the token store's token and relay nothing, so the watch would call Life OS
 * directly. See docs/wear.md, "What the phone app implements".
 */
interface WearLinkHost {
    val wearTokenSource: WearTokenSource
    val wearRelay: WearRelay
}

/**
 * Where the phone gets the server URL and the credentials it hands to the
 * watch. The phone app's [com.gamch1k.lifeos.core.pairing.WatchKeys]
 * implements it.
 */
fun interface WearTokenSource {
    /**
     * The credentials the watch should use, or null when the phone has none
     * for it: the phone's service token, and once the phone is paired the
     * watch's own device key. Called on a background thread.
     */
    suspend fun load(): WearCredentials?
}

/**
 * Runs one Life OS API request for the watch with the phone's own HTTP client
 * (the ECH-capable one, see docs/network-check.md).
 *
 * The implementation must:
 * - send [RelayHttpRequest.method] to the Life OS base URL plus
 *   [RelayHttpRequest.path] (path and query, already encoded), with
 *   [RelayHttpRequest.headers] (the actor is already among them on writes),
 *   the watch's device key as `Authorization: Bearer` once the phone is
 *   paired (then without the actor), and the `CF-Access-Client-Id` /
 *   `CF-Access-Client-Secret` headers when the phone has a service token;
 * - not follow redirects (a 302 to the Access login means the token was
 *   refused), and return every HTTP response, whatever its status;
 * - throw [WearRelayException] with [RelayErrorCode.NO_CREDENTIALS] when it
 *   has no credential for the watch, or [RelayErrorCode.UPSTREAM_UNREACHABLE] when no HTTP
 *   response came (any other exception counts as unreachable too).
 *
 * It is called on a background thread and may suspend; the watch waits about
 * 30 seconds for small requests and 2 minutes for uploads.
 */
fun interface WearRelay {
    suspend fun execute(request: RelayHttpRequest): RelayHttpResponse

    companion object {
        /** A relay that never relays: the watch then calls Life OS directly. */
        val UNAVAILABLE = WearRelay {
            throw WearRelayException(RelayErrorCode.RELAY_UNAVAILABLE, "this phone app does not relay requests yet")
        }
    }
}

class RelayHttpRequest(
    val method: String,
    /** Path and query, starting with `/api/`. */
    val path: String,
    val headers: Map<String, String>,
    /** Null for requests without a body. */
    val body: ByteArray?,
)

class RelayHttpResponse(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
)

class WearRelayException(
    val code: RelayErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
