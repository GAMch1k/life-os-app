package com.gamch1k.lifeos.core.wearlink

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import java.io.InputStream
import java.io.OutputStream

/**
 * The phone's half of the link, without Android: it turns the watch's
 * requests into calls on [WearTokenSource] and [WearRelay] and their results
 * into replies. The phone's Data Layer listener only moves bytes in and out.
 *
 * Nothing here throws to the caller: every failure becomes an error reply,
 * so the watch always learns why.
 */
class PhoneWearLinkHandler(
    private val tokenSource: WearTokenSource,
    private val relay: WearRelay,
    private val replayCache: RelayReplayCache = RelayReplayCache(),
) {
    /** [WearLink.PATH_CREDENTIALS]: a [CredentialsRequest] in, a [CredentialsReply] out. */
    suspend fun handleCredentials(payload: ByteArray): ByteArray {
        val request = try {
            RelayCodec.json.decodeFromString(CredentialsRequest.serializer(), payload.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            return credentialsReply(CredentialsReply(id = "", error = RelayError(RelayErrorCode.BAD_REQUEST, "unreadable request")))
        } catch (_: IllegalArgumentException) {
            return credentialsReply(CredentialsReply(id = "", error = RelayError(RelayErrorCode.BAD_REQUEST, "unreadable request")))
        }
        if (request.v != WearLink.PROTOCOL_VERSION) {
            return credentialsReply(
                CredentialsReply(id = request.id, error = RelayError(RelayErrorCode.UNSUPPORTED_VERSION, "protocol version ${request.v}")),
            )
        }
        val credentials = try {
            tokenSource.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return credentialsReply(CredentialsReply(id = request.id, error = RelayError(RelayErrorCode.INTERNAL, "token store failed")))
        }
        val reply = when {
            credentials == null -> CredentialsReply(
                id = request.id,
                error = RelayError(RelayErrorCode.NO_CREDENTIALS, "no service token on the phone"),
            )
            !credentials.isComplete -> CredentialsReply(
                id = request.id,
                error = RelayError(RelayErrorCode.NO_CREDENTIALS, "the phone's token or server URL is incomplete"),
            )
            else -> CredentialsReply(id = request.id, credentials = credentials)
        }
        return credentialsReply(reply)
    }

    /** [WearLink.PATH_RELAY]: a request frame in, a reply frame out, at most [WearLink.MAX_MESSAGE_BYTES]. */
    suspend fun handleRelayMessage(payload: ByteArray): ByteArray {
        val frame = try {
            RelayCodec.decodeRequest(payload)
        } catch (e: RelayProtocolException) {
            return RelayCodec.encodeResponse(RelayResponse(id = RelayCodec.peekRequestId(payload), error = RelayError(e.code, e.message ?: "")))
        }
        val reply = execute(frame.header, frame.body)
        val encoded = RelayCodec.encodeResponse(reply.header, reply.body)
        if (encoded.size > WearLink.MAX_MESSAGE_BYTES) {
            // A write that succeeded is in the replay cache: the watch's retry through a channel replays it.
            return RelayCodec.encodeResponse(
                RelayResponse(id = frame.header.id, error = RelayError(RelayErrorCode.TOO_LARGE, "reply of ${encoded.size} bytes")),
            )
        }
        return encoded
    }

    /** [WearLink.PATH_RELAY_CHANNEL]: reads one request frame from [input], writes one reply frame to [output]. */
    suspend fun handleRelayChannel(input: InputStream, output: OutputStream) {
        val frame = try {
            RelayCodec.readRequest(input)
        } catch (e: RelayProtocolException) {
            RelayCodec.writeResponse(output, RelayResponse(id = "", error = RelayError(e.code, e.message ?: "")))
            return
        }
        val reply = execute(frame.header, frame.body)
        RelayCodec.writeResponse(output, reply.header, reply.body)
    }

    internal suspend fun execute(request: RelayRequest, body: ByteArray): RelayFrame<RelayResponse> {
        RelayPolicy.check(request)?.let { return RelayFrame(RelayResponse(id = request.id, error = it), ByteArray(0)) }
        val httpRequest = RelayHttpRequest(
            method = request.method,
            path = request.path,
            headers = RelayPolicy.outgoingHeaders(request),
            body = body.takeIf { it.isNotEmpty() },
        )
        val idempotencyKey = request.headers.entries.firstOrNull { it.key.equals("Idempotency-Key", ignoreCase = true) }?.value
        return try {
            val response = if (RelayPolicy.isWrite(request.method) && !idempotencyKey.isNullOrBlank()) {
                replayCache.getOrExecute("${request.method} ${request.path} $idempotencyKey") { relay.execute(httpRequest) }
            } else {
                relay.execute(httpRequest)
            }
            if (response.body.size > WearLink.MAX_REPLY_BODY_BYTES) {
                RelayFrame(RelayResponse(id = request.id, error = RelayError(RelayErrorCode.TOO_LARGE, "Life OS answered ${response.body.size} bytes")), ByteArray(0))
            } else {
                RelayFrame(RelayResponse(id = request.id, status = response.status, contentType = response.contentType), response.body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WearRelayException) {
            RelayFrame(RelayResponse(id = request.id, error = RelayError(e.code, e.message ?: "")), ByteArray(0))
        } catch (e: Exception) {
            // IOException and anything else from the phone's client: no HTTP response came.
            RelayFrame(RelayResponse(id = request.id, error = RelayError(RelayErrorCode.UPSTREAM_UNREACHABLE, e.javaClass.simpleName)), ByteArray(0))
        }
    }

    private fun credentialsReply(reply: CredentialsReply): ByteArray =
        RelayCodec.json.encodeToString(CredentialsReply.serializer(), reply).toByteArray(Charsets.UTF_8)
}
