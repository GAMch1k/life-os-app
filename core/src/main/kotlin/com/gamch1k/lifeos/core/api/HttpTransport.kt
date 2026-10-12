package com.gamch1k.lifeos.core.api

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One HTTP request as [LifeOsClient] builds it. Redirects are never followed. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: RequestBody? = null,
    /** The whole exchange, from the first byte sent to the last byte read. */
    val timeoutMillis: Long = 30_000,
)

/** A request body that can be read more than once (Cronet rewinds on retries). */
interface RequestBody {
    val contentType: String
    val contentLength: Long
    fun open(): InputStream

    class Bytes(override val contentType: String, val bytes: ByteArray) : RequestBody {
        override val contentLength: Long get() = bytes.size.toLong()
        override fun open(): InputStream = ByteArrayInputStream(bytes)
    }

    class FromFile(override val contentType: String, val file: File) : RequestBody {
        override val contentLength: Long get() = file.length()
        override fun open(): InputStream = file.inputStream()
    }
}

class HttpResponse(
    val status: Int,
    /** Header names in lower case. */
    val headers: Map<String, List<String>>,
    /** The body, unless a [ResponseSink] took it. */
    val body: ByteArray,
    /** Set for a 3xx: where the server wanted to send us. */
    val redirectLocation: String? = null,
) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    val contentType: String? get() = header("content-type")
}

/**
 * Takes a response body instead of memory, e.g. a download into a file.
 * [open] is called once the status is known; null keeps the body in memory
 * (used for error answers, so their message can still be read).
 */
fun interface ResponseSink {
    fun open(status: Int, headers: Map<String, List<String>>): OutputStream?
}

/** The network layer under [LifeOsClient]: Cronet in the app, a fake in tests. */
interface HttpTransport {
    /**
     * Performs one exchange. Throws [TransportException] when no HTTP answer
     * arrived (no network, reset, timeout); any HTTP status is a response.
     */
    suspend fun execute(
        request: HttpRequest,
        sink: ResponseSink? = null,
        onProgress: ((bytesRead: Long) -> Unit)? = null,
    ): HttpResponse
}

/** No HTTP answer came back. */
sealed class TransportException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /**
     * The connection failed. [offline] is true when the phone has no network
     * or cannot resolve the name; [detail] names the Chromium error, such as
     * `ERR_CONNECTION_RESET (-101)`.
     */
    class Network(val detail: String, val offline: Boolean, cause: Throwable? = null) :
        TransportException("network error: $detail", cause)

    class Timeout(val millis: Long) : TransportException("no answer within ${millis / 1000} s")

    /** The answer was larger than the client keeps in memory. */
    class TooLarge(val limit: Long) : TransportException("the answer is larger than $limit bytes")
}
