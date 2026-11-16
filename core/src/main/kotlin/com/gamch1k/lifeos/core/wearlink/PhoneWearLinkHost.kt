package com.gamch1k.lifeos.core.wearlink

import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.pairing.WatchKeys
import kotlinx.coroutines.CancellationException

/**
 * The phone app's [WearLinkHost] (docs/wear.md, "What the phone implements"):
 * [WatchKeys] hands the watch its credentials (its own device key once the
 * phone is paired), and [LifeOsClient.relay], the phone's ECH-capable client,
 * runs the watch's requests with the watch's key.
 */
class PhoneWearLinkHost(override val wearTokenSource: WearTokenSource, override val wearRelay: WearRelay) : WearLinkHost {
    constructor(keys: WatchKeys, client: LifeOsClient) : this(keys, client.asWearRelay(keys))
}

/**
 * [WearRelay] over [LifeOsClient.relay]: the watch's key from [keys] (the
 * service token alone while the phone is not paired), no redirect followed,
 * every HTTP answer returned as it is. No credential for the watch is
 * [RelayErrorCode.NO_CREDENTIALS]; no answer (offline, reset, timeout,
 * anything else) is [RelayErrorCode.UPSTREAM_UNREACHABLE]. Life OS's 401 to
 * the watch's key is noted, so Settings › Watch offers a new key; it is
 * still returned to the watch as it is. The phone gives up a little before
 * the watch does (about 30 s for small requests, 2 minutes for uploads), so
 * the watch gets the phone's verdict rather than its own timeout.
 */
fun LifeOsClient.asWearRelay(keys: WatchKeys): WearRelay = WearRelay { request ->
    val timeout = if ((request.body?.size ?: 0) > SMALL_BODY_BYTES) UPLOAD_TIMEOUT_MILLIS else SMALL_TIMEOUT_MILLIS
    val credential = try {
        keys.credential()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: LifeOsException) {
        // Making the watch's key failed: offline means try again later; anything else needs the phone.
        val code = if (error is LifeOsException.Offline || error is LifeOsException.Timeout || error is LifeOsException.Server) {
            RelayErrorCode.UPSTREAM_UNREACHABLE
        } else {
            RelayErrorCode.NO_CREDENTIALS
        }
        throw WearRelayException(code, "the phone could not make the watch's key: ${error.message}", error)
    }
    val watchKey = when (credential) {
        WatchKeys.Credential.ServiceTokenOnly -> null
        is WatchKeys.Credential.Key -> credential.key
        is WatchKeys.Credential.Missing -> throw WearRelayException(RelayErrorCode.NO_CREDENTIALS, credential.why)
    }
    val response = try {
        relay(request.method, request.path, request.headers, request.body, timeout, watchKey)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: LifeOsException.NotSignedIn) {
        throw WearRelayException(RelayErrorCode.NO_CREDENTIALS, "the phone has no credentials", error)
    } catch (error: Exception) {
        throw WearRelayException(RelayErrorCode.UPSTREAM_UNREACHABLE, error.message ?: error.javaClass.simpleName, error)
    }
    if (watchKey != null) {
        when {
            // Only a sign-in code means the key stopped working; identity_expired (a slow upload) never does.
            response.status == 401 && LifeOsClient.errorCode(response.body)?.let { it in LifeOsClient.SIGN_IN_CODES } == true -> keys.refused(watchKey)
            response.status in 200..299 -> keys.accepted(watchKey)
        }
    }
    RelayHttpResponse(response.status, response.contentType, response.body)
}

private const val SMALL_BODY_BYTES = WearLink.MAX_MESSAGE_BYTES
private const val SMALL_TIMEOUT_MILLIS = 25_000L
private const val UPLOAD_TIMEOUT_MILLIS = 110_000L
