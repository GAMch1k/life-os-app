package com.gamch1k.lifeos.core.wearlink

import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import kotlinx.coroutines.CancellationException

/**
 * The phone app's [WearLinkHost] (docs/wear.md, "What the phone implements"):
 * the token store hands the watch its credentials, and [LifeOsClient.relay],
 * the phone's ECH-capable client, runs the watch's requests.
 */
class PhoneWearLinkHost(override val wearTokenSource: WearTokenSource, client: LifeOsClient) : WearLinkHost {
    override val wearRelay: WearRelay = client.asWearRelay()
}

/**
 * [WearRelay] over [LifeOsClient.relay]: no redirect followed, every HTTP
 * answer returned as it is; no token is [RelayErrorCode.NO_CREDENTIALS], no
 * answer (offline, reset, timeout, anything else) is
 * [RelayErrorCode.UPSTREAM_UNREACHABLE]. The phone gives up a little before
 * the watch does (about 30 s for small requests, 2 minutes for uploads), so
 * the watch gets the phone's verdict rather than its own timeout.
 */
fun LifeOsClient.asWearRelay(): WearRelay = WearRelay { request ->
    val timeout = if ((request.body?.size ?: 0) > SMALL_BODY_BYTES) UPLOAD_TIMEOUT_MILLIS else SMALL_TIMEOUT_MILLIS
    try {
        val response = relay(request.method, request.path, request.headers, request.body, timeout)
        RelayHttpResponse(response.status, response.contentType, response.body)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: LifeOsException.NotSignedIn) {
        throw WearRelayException(RelayErrorCode.NO_CREDENTIALS, "the phone has no service token", error)
    } catch (error: Exception) {
        throw WearRelayException(RelayErrorCode.UPSTREAM_UNREACHABLE, error.message ?: error.javaClass.simpleName, error)
    }
}

private const val SMALL_BODY_BYTES = WearLink.MAX_MESSAGE_BYTES
private const val SMALL_TIMEOUT_MILLIS = 25_000L
private const val UPLOAD_TIMEOUT_MILLIS = 110_000L
