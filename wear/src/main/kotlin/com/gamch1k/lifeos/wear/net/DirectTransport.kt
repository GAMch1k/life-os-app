package com.gamch1k.lifeos.wear.net

import android.content.Context
import com.gamch1k.lifeos.core.netcheck.CronetEngines
import com.gamch1k.lifeos.core.wearlink.RelayPolicy
import com.gamch1k.lifeos.core.wearlink.WearCredentials
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.NetworkException
import org.chromium.net.apihelpers.UploadDataProviders
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Calls Life OS from the watch itself, when the phone is away: embedded
 * Cronet with Chromium's own DNS resolver, so ECH hides the hostname on
 * networks that block it (docs/network-check.md). The watch's default network
 * is used, which is Wi-Fi or LTE once the phone is out of Bluetooth range.
 *
 * A small client of its own on purpose: the phone app's HTTP client lives in
 * core and is the phone agent's; this one can be swapped for it later behind
 * [ApiTransport].
 */
class DirectTransport(
    context: Context,
    private val credentials: () -> WearCredentials?,
) : ApiTransport {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "lifeos-cronet") }
    private val engine: CronetEngine? by lazy {
        try {
            CronetEngines.build(appContext, CronetEngines.Provider.EMBEDDED, quicAllowed = false)
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun execute(request: ApiRequest): ApiResult {
        val token = credentials()?.takeIf { it.isComplete }
            ?: return ApiResult.Unavailable(Unavailability.NO_CREDENTIALS, "no token on the watch")
        val engine = engine ?: return ApiResult.Unavailable(Unavailability.NETWORK, "Cronet is not available")
        val url = token.baseUrl.trimEnd('/') + request.path
        val outcome = withTimeoutOrNull(request.timeoutMillis) {
            suspendCancellableCoroutine<ApiResult> { continuation ->
                val callback = Callback { if (continuation.isActive) continuation.resume(it) }
                val builder = engine.newUrlRequestBuilder(url, callback, executor)
                    .setHttpMethod(request.method)
                    .disableCache()
                    .addHeader("CF-Access-Client-Id", token.clientId)
                    .addHeader("CF-Access-Client-Secret", token.clientSecret)
                request.headers.forEach { (name, value) -> builder.addHeader(name, value) }
                if (RelayPolicy.isWrite(request.method)) builder.addHeader(RelayPolicy.ACTOR_HEADER, RelayPolicy.ACTOR)
                request.body?.let { body ->
                    if (request.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                        builder.addHeader("Content-Type", "application/octet-stream")
                    }
                    builder.setUploadDataProvider(UploadDataProviders.create(body), executor)
                }
                val urlRequest = builder.build()
                continuation.invokeOnCancellation { urlRequest.cancel() }
                urlRequest.start()
            }
        } ?: return ApiResult.Unavailable(Unavailability.NETWORK, "timed out after ${request.timeoutMillis / 1000} s")
        return outcome
    }

    /** Collects one response; a redirect is returned as its status (it means Access wants a login). */
    private class Callback(private val done: (ApiResult) -> Unit) : UrlRequest.Callback() {
        private val body = ByteArrayOutputStream()
        private var redirectStatus: Int? = null

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            redirectStatus = info.httpStatusCode
            request.cancel()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            request.read(ByteBuffer.allocateDirect(32 * 1024))
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            val chunk = ByteArray(byteBuffer.remaining())
            byteBuffer.get(chunk)
            if (body.size() + chunk.size > MAX_BODY_BYTES) {
                request.cancel()
                return
            }
            body.write(chunk)
            byteBuffer.clear()
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            done(ApiResult.Response(info.httpStatusCode, body.toByteArray(), Route.DIRECT))
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
            val detail = (error as? NetworkException)?.let { "net error ${it.cronetInternalErrorCode}" } ?: error.javaClass.simpleName
            done(ApiResult.Unavailable(Unavailability.NETWORK, detail))
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            val redirect = redirectStatus
            done(
                if (redirect != null) {
                    ApiResult.Response(redirect, ByteArray(0), Route.DIRECT)
                } else {
                    ApiResult.Unavailable(Unavailability.NETWORK, "cancelled")
                },
            )
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}
