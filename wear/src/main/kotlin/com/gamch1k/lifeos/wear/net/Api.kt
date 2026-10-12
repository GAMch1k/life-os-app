package com.gamch1k.lifeos.wear.net

import com.gamch1k.lifeos.core.wearlink.HttpOutcome

/** One Life OS API call from the watch. [path] holds path and query, already encoded. */
class ApiRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    /** How long to wait for the answer. Uploads get longer. */
    val timeoutMillis: Long = 30_000,
)

/** How a request reached Life OS. */
enum class Route { PHONE, DIRECT }

/** Why a path could not deliver a request. */
enum class Unavailability {
    /** No phone with the Life OS app is connected. */
    NO_PHONE,

    /** The phone app is there but does not relay (or speaks another protocol version). */
    PHONE_NO_RELAY,

    /** No service token: the watch has none yet (direct) or the phone has none (relay). */
    NO_CREDENTIALS,

    /** The network failed: no connection, DNS, TLS, timeout. */
    NETWORK,

    /** A bug: a refused or unreadable frame. */
    PROTOCOL,
}

sealed interface ApiResult {
    /** Life OS answered, whatever the status. */
    class Response(val status: Int, val body: ByteArray, val route: Route) : ApiResult {
        val outcome: HttpOutcome get() = HttpOutcome.of(status)
        val text: String get() = body.toString(Charsets.UTF_8)
        override fun toString() = "Response($status via $route)"
    }

    /** This path got no answer from Life OS. */
    data class Unavailable(val reason: Unavailability, val detail: String = "") : ApiResult
}

fun interface ApiTransport {
    suspend fun execute(request: ApiRequest): ApiResult
}

/**
 * Relay first, direct second (docs/wear.md, "Why the phone first"): a
 * request goes through the phone when one is connected; when the phone gives
 * no HTTP answer (it is away, doesn't relay, or couldn't reach Life OS), the
 * watch tries its own connection. An HTTP answer through the phone, whatever
 * its status, is final: the direct path would reach the same server with the
 * same token.
 */
class RoutingTransport(
    private val phone: ApiTransport,
    private val direct: ApiTransport,
) : ApiTransport {
    override suspend fun execute(request: ApiRequest): ApiResult {
        val viaPhone = phone.execute(request)
        if (viaPhone is ApiResult.Response) return viaPhone
        viaPhone as ApiResult.Unavailable
        if (viaPhone.reason == Unavailability.PROTOCOL) return viaPhone
        val viaDirect = direct.execute(request)
        if (viaDirect is ApiResult.Response) return viaDirect
        viaDirect as ApiResult.Unavailable
        // Report the more useful of the two reasons: the phone's, unless there simply was no phone.
        return if (viaPhone.reason == Unavailability.NO_PHONE) viaDirect else viaPhone
    }
}
