package com.gamch1k.lifeos.core.api

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.UUID

/**
 * A multipart/form-data body (RFC 7578) that streams its file parts from
 * disk, so a 25 MiB recording never sits in memory.
 */
class MultipartBody(
    val parts: List<Part>,
    val boundary: String = "lifeos-" + UUID.randomUUID().toString().replace("-", ""),
) : RequestBody {
    class Part private constructor(
        val name: String,
        val filename: String?,
        val partType: String?,
        val bytes: ByteArray?,
        val file: File?,
    ) {
        val length: Long get() = bytes?.size?.toLong() ?: file!!.length()

        fun open(): InputStream = bytes?.let(::ByteArrayInputStream) ?: file!!.inputStream()

        companion object {
            fun text(name: String, value: String) = Part(name, null, null, value.toByteArray(Charsets.UTF_8), null)

            fun file(name: String, filename: String, contentType: String, file: File) =
                Part(name, filename, contentType, null, file)

            fun bytes(name: String, filename: String, contentType: String, bytes: ByteArray) =
                Part(name, filename, contentType, bytes, null)
        }
    }

    override val contentType: String get() = "multipart/form-data; boundary=$boundary"

    private fun head(part: Part): ByteArray = buildString {
        append("--").append(boundary).append("\r\n")
        append("Content-Disposition: form-data; name=\"").append(quote(part.name)).append('"')
        part.filename?.let { append("; filename=\"").append(quote(it)).append('"') }
        append("\r\n")
        part.partType?.let { append("Content-Type: ").append(it).append("\r\n") }
        append("\r\n")
    }.toByteArray(Charsets.UTF_8)

    private val tail: ByteArray get() = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    override val contentLength: Long
        get() = parts.sumOf { head(it).size + it.length + CRLF.size } + tail.size

    override fun open(): InputStream {
        val streams = mutableListOf<() -> InputStream>()
        for (part in parts) {
            val head = head(part)
            streams += { ByteArrayInputStream(head) }
            streams += { part.open() }
            streams += { ByteArrayInputStream(CRLF) }
        }
        val tail = tail
        streams += { ByteArrayInputStream(tail) }
        // Open each piece only when it is reached, so at most one file is open.
        val iterator = streams.iterator()
        return SequenceInputStream(
            object : java.util.Enumeration<InputStream> {
                override fun hasMoreElements() = iterator.hasNext()
                override fun nextElement() = iterator.next()()
            },
        )
    }

    private companion object {
        val CRLF = "\r\n".toByteArray(Charsets.UTF_8)

        /** Quotes and line breaks cannot appear inside a quoted header value. */
        fun quote(value: String) = value.replace("\"", "%22").replace("\r", "").replace("\n", "")
    }
}
