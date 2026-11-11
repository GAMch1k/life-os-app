package com.gamch1k.lifeos.wear.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import com.gamch1k.lifeos.core.netcheck.CronetEngines
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.NetworkException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wi-Fi for the watch app's own download, as Google's Wear OS network
 * guidance has it: `ConnectivityManager.requestNetwork` for a Wi-Fi network
 * with internet. While the phone is connected over Bluetooth, the watch's
 * default network is the phone's proxy; Wear OS turns Wi-Fi on for an app
 * that asks (when the watch's Wi-Fi setting is Auto and a saved network is in
 * reach) and keeps it up until the request is released ([BoundNetwork.close]).
 * The phone can't switch the watch's Wi-Fi on.
 *
 * Requests are bound to the network one by one ([CronetApkHttp]), not the
 * whole process, so the relay and the other calls keep their usual route.
 */
class AndroidWifiNetworks(context: Context) : WifiNetworks {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override suspend fun acquire(timeoutMillis: Long): BoundNetwork? {
        val manager = connectivity ?: return null
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val available = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.complete(network)
            }
        }
        try {
            manager.requestNetwork(request, callback)
        } catch (_: RuntimeException) {
            // No CHANGE_NETWORK_STATE, or too many requests: no Wi-Fi this time.
            return null
        }
        var handedOver = false
        try {
            val network = withTimeoutOrNull(timeoutMillis) { available.await() } ?: return null
            handedOver = true
            return object : BoundNetwork {
                override val handle: Long = network.networkHandle

                override fun close() {
                    runCatching { manager.unregisterNetworkCallback(callback) }
                }
            }
        } finally {
            if (!handedOver) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
}

/**
 * [ApkHttp] over the embedded Cronet (Chromium's DNS resolver, no QUIC, as
 * everywhere in the apps), each request bound to the given network with
 * `UrlRequest.Builder.bindToNetwork`. Chromium may resolve a network-bound
 * request through that network's system resolver, without the HTTPS record
 * and so without ECH; on a network that blocks the hostname the download
 * fails and the watch falls back to the phone.
 */
class CronetApkHttp(context: Context) : ApkHttp {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "lifeos-apk-download") }
    private val engine: CronetEngine? by lazy {
        try {
            CronetEngines.build(appContext, CronetEngines.Provider.EMBEDDED, quicAllowed = false)
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        network: BoundNetwork,
        idleMillis: Long,
        sink: (status: Int, headers: Map<String, List<String>>) -> OutputStream?,
        onProgress: (Long) -> Unit,
    ): Int {
        val engine = engine ?: throw IOException("Cronet is not available")
        val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            return suspendCancellableCoroutine { continuation ->
                val callback = Callback(sink, onProgress) { result ->
                    if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
                val builder = engine.newUrlRequestBuilder(url, callback, executor)
                    .setHttpMethod("GET")
                    .disableCache()
                    .bindToNetwork(network.handle)
                headers.forEach { (name, value) -> builder.addHeader(name, value) }
                val request = builder.build()
                continuation.invokeOnCancellation { request.cancel() }
                watchdogScope.launch {
                    while (true) {
                        delay(1_000)
                        if (SystemClock.elapsedRealtime() - callback.lastByteAt > idleMillis) {
                            callback.fail(IOException("no byte for ${idleMillis / 1000} s"))
                            request.cancel()
                            break
                        }
                    }
                }
                request.start()
            }
        } finally {
            watchdogScope.cancel()
        }
    }

    private class Callback(
        private val sink: (Int, Map<String, List<String>>) -> OutputStream?,
        private val onProgress: (Long) -> Unit,
        private val done: (Result<Int>) -> Unit,
    ) : UrlRequest.Callback() {
        @Volatile
        var lastByteAt: Long = SystemClock.elapsedRealtime()

        @Volatile
        private var failure: Throwable? = null
        private var output: OutputStream? = null
        private var status = 0
        private var read = 0L

        fun fail(error: Throwable) {
            if (failure == null) failure = error
        }

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            // Never followed here: on Life OS a redirect means Cloudflare Access wants a login. The
            // caller sees the status and where it led (DirectDownload follows GitHub's own redirects).
            status = info.httpStatusCode
            val headers = info.allHeaders.entries.groupBy({ it.key.lowercase() }, { it.value }).mapValues { (_, lists) -> lists.flatten() }
            runCatching { sink(status, headers + ("location" to listOf(newLocationUrl)))?.close() }
            request.cancel()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            lastByteAt = SystemClock.elapsedRealtime()
            status = info.httpStatusCode
            val headers = info.allHeaders.entries.groupBy({ it.key.lowercase() }, { it.value }).mapValues { (_, lists) -> lists.flatten() }
            output = try {
                sink(status, headers)
            } catch (error: Exception) {
                fail(IOException("could not store the download: ${error.message}", error))
                request.cancel()
                return
            }
            if (output == null) {
                // Not an answer to keep (an error page, a refused range): the status says why.
                request.cancel()
                return
            }
            request.read(ByteBuffer.allocateDirect(32 * 1024))
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            lastByteAt = SystemClock.elapsedRealtime()
            byteBuffer.flip()
            val chunk = ByteArray(byteBuffer.remaining())
            byteBuffer.get(chunk)
            try {
                output?.write(chunk)
            } catch (error: IOException) {
                fail(error)
                request.cancel()
                return
            }
            read += chunk.size
            onProgress(read)
            byteBuffer.clear()
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            done(Result.success(info.httpStatusCode))
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
            val detail = (error as? NetworkException)?.let { "net error ${it.cronetInternalErrorCode}" } ?: error.javaClass.simpleName
            done(Result.failure(failure ?: IOException(detail, error)))
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            val failed = failure
            done(
                when {
                    failed != null -> Result.failure(failed)
                    // A redirect, or an answer the sink didn't want: the status, no body.
                    status != 0 && output == null -> Result.success(status)
                    else -> Result.failure(IOException("cancelled"))
                },
            )
        }
    }
}
