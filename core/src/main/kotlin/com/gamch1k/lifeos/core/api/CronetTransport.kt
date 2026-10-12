package com.gamch1k.lifeos.core.api

import android.content.Context
import com.gamch1k.lifeos.core.netcheck.CronetEngines
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.NetworkException
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [HttpTransport] over the Cronet inside the APK, configured as the network
 * check proved: Chromium's built-in DNS resolver, so it finds the ECH key in
 * the HTTPS record and the hostname never goes out in clear SNI. QUIC stays
 * off: TCP with ECH is what works on the work Wi-Fi.
 */
class CronetTransport(
    private val engine: CronetEngine,
    private val executor: Executor = Executors.newFixedThreadPool(4),
    /** Bodies kept in memory (JSON answers and error pages) stop here. */
    private val memoryLimit: Long = 8L * 1024 * 1024,
) : HttpTransport {

    override suspend fun execute(request: HttpRequest, sink: ResponseSink?, onProgress: ((Long) -> Unit)?): HttpResponse =
        withTimeoutOrNull(request.timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val callback = Callback(sink, onProgress) { result ->
                    if (continuation.isActive) {
                        result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                    }
                }
                val urlRequest = try {
                    val builder = engine.newUrlRequestBuilder(request.url, callback, executor)
                        .setHttpMethod(request.method)
                        .disableCache()
                    request.headers.forEach { (name, value) -> builder.addHeader(name, value) }
                    request.body?.let { body ->
                        builder.addHeader("Content-Type", body.contentType)
                        builder.setUploadDataProvider(StreamUpload(body), executor)
                    }
                    builder.build()
                } catch (error: IllegalArgumentException) {
                    // A malformed URL or header value: nothing was sent.
                    continuation.resumeWithException(TransportException.Local("the request could not be built: ${error.message}", error))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { urlRequest.cancel() }
                urlRequest.start()
            }
        } ?: throw TransportException.Timeout(request.timeoutMillis)

    private inner class Callback(
        private val sink: ResponseSink?,
        private val onProgress: ((Long) -> Unit)?,
        private val done: (Result<HttpResponse>) -> Unit,
    ) : UrlRequest.Callback() {
        private val memory = ByteArrayOutputStream()
        private var output: OutputStream? = null
        private var read = 0L
        private var redirect: HttpResponse? = null
        private var failure: Throwable? = null

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            // Never follow: a redirect means Cloudflare Access wants a login.
            redirect = HttpResponse(info.httpStatusCode, headersOf(info), ByteArray(0), newLocationUrl)
            request.cancel()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            try {
                if (info.httpStatusCode in 200..299) output = sink?.open(info.httpStatusCode, headersOf(info))
            } catch (error: Exception) {
                failure = TransportException.Local("could not save the answer: ${error.message}", error)
                request.cancel()
                return
            }
            request.read(ByteBuffer.allocateDirect(32 * 1024))
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            val chunk = ByteArray(byteBuffer.remaining())
            byteBuffer.get(chunk)
            try {
                val target = output
                if (target != null) {
                    target.write(chunk)
                } else {
                    if (memory.size() + chunk.size > memoryLimit) throw TransportException.TooLarge(memoryLimit)
                    memory.write(chunk)
                }
            } catch (error: TransportException) {
                failure = error
                request.cancel()
                return
            } catch (error: Exception) {
                failure = TransportException.Local("could not save the answer: ${error.message}", error)
                request.cancel()
                return
            }
            read += chunk.size
            onProgress?.invoke(read)
            byteBuffer.clear()
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
            val closed = runCatching { output?.close() }
            closed.exceptionOrNull()?.let { done(Result.failure(TransportException.Local("could not save the answer: ${it.message}", it))); return }
            done(Result.success(HttpResponse(info.httpStatusCode, headersOf(info), memory.toByteArray())))
        }

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
            runCatching { output?.close() }
            done(Result.failure(mapError(error)))
        }

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            runCatching { output?.close() }
            val redirect = redirect
            val failure = failure
            when {
                redirect != null -> done(Result.success(redirect))
                failure != null -> done(Result.failure(failure))
                // Otherwise the coroutine was cancelled and nobody waits for a result.
            }
        }
    }

    /** Streams a [RequestBody] to Cronet; Cronet may rewind it to send it again. */
    private class StreamUpload(private val body: RequestBody) : UploadDataProvider() {
        private var stream: InputStream? = null
        private val scratch = ByteArray(32 * 1024)

        override fun getLength(): Long = body.contentLength

        override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
            try {
                val input = stream ?: body.open().also { stream = it }
                val count = input.read(scratch, 0, minOf(scratch.size, byteBuffer.remaining()))
                if (count < 0) throw java.io.EOFException("the body ended before its declared length")
                byteBuffer.put(scratch, 0, count)
                uploadDataSink.onReadSucceeded(false)
            } catch (error: Exception) {
                uploadDataSink.onReadError(error)
            }
        }

        override fun rewind(uploadDataSink: UploadDataSink) {
            runCatching { stream?.close() }
            stream = null
            uploadDataSink.onRewindSucceeded()
        }

        override fun close() {
            runCatching { stream?.close() }
            stream = null
        }
    }

    companion object {
        /** The app's one engine: embedded Cronet, built-in DNS (ECH), no QUIC. */
        fun create(context: Context): CronetTransport {
            val engine = try {
                CronetEngines.build(context.applicationContext, CronetEngines.Provider.EMBEDDED, quicAllowed = false)
            } catch (error: RuntimeException) {
                throw TransportException.Local("the network stack (Cronet) did not start: ${error.message}", error)
            } ?: throw TransportException.Local("the network stack (Cronet) is not available")
            return CronetTransport(engine)
        }

        private fun headersOf(info: UrlResponseInfo): Map<String, List<String>> =
            info.allHeaders.entries.groupBy({ it.key.lowercase() }, { it.value }).mapValues { (_, lists) -> lists.flatten() }

        internal fun mapError(error: CronetException): TransportException = when (error) {
            is NetworkException -> TransportException.Network(
                detail = NetErrors.describe(error.cronetInternalErrorCode),
                offline = error.errorCode == NetworkException.ERROR_INTERNET_DISCONNECTED ||
                    error.errorCode == NetworkException.ERROR_HOSTNAME_NOT_RESOLVED,
                cause = error,
            )
            else -> TransportException.Network(error.cause?.message ?: error.message ?: error.javaClass.simpleName, offline = false, cause = error)
        }
    }
}
