package com.gamch1k.lifeos.core.wearlink

/**
 * The phone side of the link, as the phone app implements it.
 *
 * The phone app's `Application` implements [WearLinkHost]; the phone's
 * Data Layer listener (phone/…/wearlink/PhoneWearLinkService) asks it for
 * the two pieces below. Until it does, the listener hands over the token from
 * the network check's token store and relays nothing, so the watch calls
 * Life OS directly. See docs/wear.md, "What the phone implements".
 */
interface WearLinkHost {
    val wearTokenSource: WearTokenSource
    val wearRelay: WearRelay
}

/**
 * Where the phone gets the server URL and service token it hands to the
 * watch. The phone app's token store implements it.
 */
fun interface WearTokenSource {
    /**
     * The credentials the watch should use, or null when the phone has none
     * yet. With one service token per device, this is the watch's own token;
     * otherwise the phone's. Called on a background thread.
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
 *   [RelayHttpRequest.headers] (the actor is already among them on writes)
 *   and the `CF-Access-Client-Id` / `CF-Access-Client-Secret` headers;
 * - not follow redirects (a 302 to the Access login means the token was
 *   refused), and return every HTTP response, whatever its status;
 * - throw [WearRelayException] with [RelayErrorCode.NO_CREDENTIALS] when it
 *   has no token, or [RelayErrorCode.UPSTREAM_UNREACHABLE] when no HTTP
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
