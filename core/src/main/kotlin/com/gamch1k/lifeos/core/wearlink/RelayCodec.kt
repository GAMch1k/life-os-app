package com.gamch1k.lifeos.core.wearlink

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** A frame: a JSON header and the raw body bytes after it. */
class RelayFrame<H>(val header: H, val body: ByteArray)

/** A frame that could not be read, with the error code to answer it with. */
class RelayProtocolException(val code: RelayErrorCode, message: String) : Exception(message)

/**
 * The wire format of relay requests and replies, the same in a message and in
 * a channel:
 *
 * ```
 * u32 big-endian  header length
 * header          UTF-8 JSON (RelayRequest or RelayResponse)
 * body            header.bodyLength bytes, raw (audio stays binary)
 * ```
 *
 * Unknown JSON keys are ignored, so a later version can add fields; a
 * different `v` is refused with `unsupported_version`.
 */
object RelayCodec {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encodeRequest(request: RelayRequest, body: ByteArray = ByteArray(0)): ByteArray =
        encode(RelayRequest.serializer(), request.copy(bodyLength = body.size), body)

    fun encodeResponse(response: RelayResponse, body: ByteArray = ByteArray(0)): ByteArray =
        encode(RelayResponse.serializer(), response.copy(bodyLength = body.size), body)

    fun decodeRequest(bytes: ByteArray): RelayFrame<RelayRequest> =
        decode(bytes, RelayRequest.serializer(), WearLink.MAX_CHANNEL_BODY_BYTES) { it.v to it.bodyLength }

    fun decodeResponse(bytes: ByteArray): RelayFrame<RelayResponse> =
        decode(bytes, RelayResponse.serializer(), WearLink.MAX_REPLY_BODY_BYTES) { it.v to it.bodyLength }

    fun writeRequest(output: OutputStream, request: RelayRequest, body: ByteArray) {
        output.write(encodeRequest(request, body))
        output.flush()
    }

    fun writeResponse(output: OutputStream, response: RelayResponse, body: ByteArray = ByteArray(0)) {
        output.write(encodeResponse(response, body))
        output.flush()
    }

    fun readRequest(input: InputStream): RelayFrame<RelayRequest> =
        read(input, RelayRequest.serializer(), WearLink.MAX_CHANNEL_BODY_BYTES) { it.v to it.bodyLength }

    fun readResponse(input: InputStream): RelayFrame<RelayResponse> =
        read(input, RelayResponse.serializer(), WearLink.MAX_REPLY_BODY_BYTES) { it.v to it.bodyLength }

    /** The id of a request frame, if its header is readable at all (to answer even a refused request). */
    fun peekRequestId(bytes: ByteArray): String = try {
        val length = ByteBuffer.wrap(bytes, 0, 4).int
        val header = json.parseToJsonElement(bytes.copyOfRange(4, 4 + length).toString(Charsets.UTF_8))
        ((header as? JsonObject)?.get("id") as? JsonPrimitive)?.content ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun <H> encode(serializer: KSerializer<H>, header: H, body: ByteArray): ByteArray {
        val headerBytes = json.encodeToString(serializer, header).toByteArray(Charsets.UTF_8)
        require(headerBytes.size <= WearLink.MAX_HEADER_BYTES) { "frame header too large" }
        val out = ByteArrayOutputStream(4 + headerBytes.size + body.size)
        out.write(ByteBuffer.allocate(4).putInt(headerBytes.size).array())
        out.write(headerBytes)
        out.write(body)
        return out.toByteArray()
    }

    private fun <H> decode(
        bytes: ByteArray,
        serializer: KSerializer<H>,
        maxBody: Int,
        versionAndLength: (H) -> Pair<Int, Int>,
    ): RelayFrame<H> {
        if (bytes.size < 4) throw RelayProtocolException(RelayErrorCode.BAD_REQUEST, "frame shorter than its length prefix")
        val headerLength = ByteBuffer.wrap(bytes, 0, 4).int
        checkHeaderLength(headerLength)
        if (bytes.size < 4 + headerLength) throw RelayProtocolException(RelayErrorCode.BAD_REQUEST, "frame cut inside its header")
        val header = parseHeader(bytes.copyOfRange(4, 4 + headerLength), serializer)
        val (version, bodyLength) = versionAndLength(header)
        checkVersion(version)
        checkBodyLength(bodyLength, maxBody)
        if (bytes.size != 4 + headerLength + bodyLength) {
            throw RelayProtocolException(RelayErrorCode.BAD_REQUEST, "frame body is ${bytes.size - 4 - headerLength} bytes, header says $bodyLength")
        }
        return RelayFrame(header, bytes.copyOfRange(4 + headerLength, bytes.size))
    }

    private fun <H> read(
        input: InputStream,
        serializer: KSerializer<H>,
        maxBody: Int,
        versionAndLength: (H) -> Pair<Int, Int>,
    ): RelayFrame<H> {
        val data = DataInputStream(input)
        try {
            val headerLength = data.readInt()
            checkHeaderLength(headerLength)
            val headerBytes = ByteArray(headerLength).also { data.readFully(it) }
            val header = parseHeader(headerBytes, serializer)
            val (version, bodyLength) = versionAndLength(header)
            checkVersion(version)
            checkBodyLength(bodyLength, maxBody)
            val body = ByteArray(bodyLength).also { data.readFully(it) }
            return RelayFrame(header, body)
        } catch (_: EOFException) {
            throw RelayProtocolException(RelayErrorCode.BAD_REQUEST, "stream ended inside a frame")
        }
    }

    private fun <H> parseHeader(bytes: ByteArray, serializer: KSerializer<H>): H = try {
        json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
    } catch (e: SerializationException) {
        throw RelayProtocolException(RelayErrorCode.BAD_REQUEST, "unreadable frame header: ${e.message?.take(120)}")
    } catch (e: IllegalArgumentException) {
        throw RelayProtocolException(RelayErrorCode.BAD_REQUEST, "unreadable frame header: ${e.message?.take(120)}")
    }

    private fun checkHeaderLength(length: Int) {
        if (length <= 0 || length > WearLink.MAX_HEADER_BYTES) {
            throw RelayProtocolException(RelayErrorCode.BAD_REQUEST, "frame header length $length out of range")
        }
    }

    private fun checkVersion(version: Int) {
        if (version != WearLink.PROTOCOL_VERSION) {
            throw RelayProtocolException(
                RelayErrorCode.UNSUPPORTED_VERSION,
                "protocol version $version, this app speaks ${WearLink.PROTOCOL_VERSION}",
            )
        }
    }

    private fun checkBodyLength(length: Int, max: Int) {
        if (length < 0 || length > max) {
            throw RelayProtocolException(RelayErrorCode.BAD_REQUEST, "frame body length $length out of range")
        }
    }
}
