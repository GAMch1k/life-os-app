package com.gamch1k.lifeos.core.netcheck

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.CronetProvider
import org.chromium.net.DnsOptions
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.NetworkException
import org.chromium.net.RequestFinishedInfo
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Cronet engines for the network check.
 *
 * Both providers get the same configuration: Chromium's built-in DNS resolver
 * (so it can query HTTPS records and find the ECH key), plus a longer wait for
 * the HTTPS record. ECH itself needs no switch: Chromium 155 uses it
 * opportunistically whenever an HTTPS record carries `ech=` (see
 * docs/network-check.md for the sources).
 */
object CronetEngines {
    enum class Provider(val providerName: String) {
        /** The Cronet inside our APK (org.chromium.net:cronet-bundled). */
        EMBEDDED(CronetProvider.PROVIDER_NAME_APP_PACKAGED),

        /** Android's own Cronet (android.net.http.HttpEngine, updated with the system). */
        HTTP_ENGINE(CronetProvider.PROVIDER_NAME_HTTPENGINE_NATIVE),
    }

    /**
     * Experimental options. `AsyncDNS` is what DnsOptions.useBuiltInDnsResolver
     * produces anyway; `UseDnsHttpsSvcb` lets the resolver wait up to 1 s (at
     * least 200 ms, at most as long again as the A/AAAA lookup took) for the
     * HTTPS record after the address records arrive; Chromium's default is
     * 5–50 ms, after which it connects without ECH.
     */
    fun experimentalOptions(quicAllowed: Boolean): String = """
        {
          "AsyncDNS": {"enable": true},
          "UseDnsHttpsSvcb": {
            "insecure_extra_time_max": "1s",
            "insecure_extra_time_percent": 100,
            "insecure_extra_time_min": "200ms",
            "secure_extra_time_max": "1s",
            "secure_extra_time_percent": 100,
            "secure_extra_time_min": "200ms",
            "use_alpn": $quicAllowed
          }
        }
    """.trimIndent()

    fun provider(context: Context, provider: Provider): CronetProvider? =
        CronetProvider.getAllProviders(context).firstOrNull { it.name == provider.providerName && it.isEnabled }

    @OptIn(markerClass = [DnsOptions.Experimental::class])
    fun build(context: Context, provider: Provider, quicAllowed: Boolean): CronetEngine? {
        val cronetProvider = provider(context, provider) ?: return null
        val builder = cronetProvider.createBuilder()
            .enableHttp2(true)
            .enableQuic(quicAllowed)
            .enableBrotli(true)
            .setDnsOptions(DnsOptions.builder().useBuiltInDnsResolver(true).build())
        (builder as? ExperimentalCronetEngine.Builder)?.setExperimentalOptions(experimentalOptions(quicAllowed))
        return builder.build()
    }
}

/** One GET through a Cronet engine, without following redirects. */
internal object CronetProbe {
    private const val MAX_BODY_BYTES = 64 * 1024
    private const val TIMEOUT_MILLIS = 25_000L

    suspend fun get(
        engine: CronetEngine,
        executor: Executor,
        label: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        parseTrace: Boolean,
    ): HttpProbeResult {
        val started = SystemClock.elapsedRealtime()
        val metrics = CompletableDeferred<RequestFinishedInfo.Metrics?>()
        val outcome = withTimeoutOrNull(TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                val callback = Callback { if (continuation.isActive) continuation.resume(it) }
                val builder = engine.newUrlRequestBuilder(url, callback, executor)
                    .setHttpMethod("GET")
                    .disableCache()
                    .setRequestFinishedListener(
                        object : RequestFinishedInfo.Listener(executor) {
                            override fun onRequestFinished(info: RequestFinishedInfo) {
                                metrics.complete(info.metrics)
                            }
                        },
                    )
                headers.forEach { (name, value) -> builder.addHeader(name, value) }
                val request = builder.build()
                continuation.invokeOnCancellation { request.cancel() }
                request.start()
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        val timings = withTimeoutOrNull(2_000) { metrics.await() }?.let(::timingsOf)
        if (outcome == null) {
            return HttpProbeResult(label, url, null, null, elapsed, null, timings, null, "timed out after ${TIMEOUT_MILLIS / 1000} s")
        }
        return HttpProbeResult(
            label = label,
            url = url,
            statusCode = outcome.info?.httpStatusCode,
            negotiatedProtocol = outcome.info?.negotiatedProtocol,
            totalMillis = elapsed,
            trace = if (parseTrace && outcome.error == null && outcome.info != null) CloudflareTrace.parse(outcome.body) else null,
            timings = timings,
            redirectTo = outcome.redirectTo,
            error = outcome.error,
        )
    }

    private fun timingsOf(metrics: RequestFinishedInfo.Metrics): ConnectionTimings {
        fun span(start: java.util.Date?, end: java.util.Date?): Long? =
            if (start != null && end != null) end.time - start.time else null
        return ConnectionTimings(
            dnsMillis = span(metrics.dnsStart, metrics.dnsEnd),
            connectMillis = span(metrics.connectStart, metrics.connectEnd),
            tlsMillis = span(metrics.sslStart, metrics.sslEnd),
            socketReused = metrics.socketReused,
        )
    }

    class Outcome(val info: UrlResponseInfo?, val body: String, val redirectTo: String?, val error: String?)

    private class Callback(private val done: (Outcome) -> Unit) : UrlRequest.Callback() {
        private val body = ByteArrayOutputStream()
        private var redirect: Pair<UrlResponseInfo, String>? = null

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            // Report the redirect (e.g. to the Access login) instead of following it.
            redirect = info to hostAndPath(newLocationUrl)
            request.cancel()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            request.read(ByteBuffer.allocateDirect(16 * 1024))
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            val chunk = ByteArray(byteBuffer.remaining())
            byteBuffer.get(chunk)
            if (body.size() < MAX_BODY_BYTES) body.write(chunk, 0, minOf(chunk.size, MAX_BODY_BYTES - body.size()))
            byteBuffer.clear()
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            done(Outcome(info, body.toString(Charsets.UTF_8.name()), null, null))
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
            done(Outcome(info, "", null, describe(error)))
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            val redirect = redirect
            if (redirect != null) {
                done(Outcome(redirect.first, "", redirect.second, null))
            } else {
                done(Outcome(info, "", null, "cancelled"))
            }
        }
    }

    private fun describe(error: CronetException): String = when (error) {
        is NetworkException -> "${netErrorName(error.cronetInternalErrorCode)} (${error.cronetInternalErrorCode})"
        else -> error.message ?: error.javaClass.simpleName
    }

    /** Chromium net error names (net/base/net_error_list.h) for the codes this check may meet. */
    private val netErrors = mapOf(
        -2 to "ERR_FAILED",
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

    private fun netErrorName(code: Int): String = netErrors[code] ?: "net error"

    /** Host and path of a URL, without its query (which may carry tokens). */
    fun hostAndPath(url: String): String = try {
        val uri = URI(url)
        "${uri.host}${uri.rawPath ?: ""}"
    } catch (_: Exception) {
        "(unparseable URL)"
    }
}
