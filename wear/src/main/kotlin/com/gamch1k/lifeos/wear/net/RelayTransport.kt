package com.gamch1k.lifeos.wear.net

import com.gamch1k.lifeos.core.wearlink.RelayCodec
import com.gamch1k.lifeos.core.wearlink.RelayErrorCode
import com.gamch1k.lifeos.core.wearlink.RelayFrame
import com.gamch1k.lifeos.core.wearlink.RelayProtocolException
import com.gamch1k.lifeos.core.wearlink.RelayRequest
import com.gamch1k.lifeos.core.wearlink.RelayResponse
import com.gamch1k.lifeos.core.wearlink.WearLink
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** The watch's view of the Data Layer, so the relay logic can be tested without Play services. */
interface PhoneConnection {
    /** The connected phone that runs the Life OS app, or null. */
    suspend fun phoneNodeId(): String?

    /** A `MessageClient` request and its reply. Throws when the phone doesn't answer in time. */
    suspend fun request(nodeId: String, path: String, payload: ByteArray, timeoutMillis: Long): ByteArray

    /** Opens a channel, writes with [send], reads the reply with [receive], closes it. */
    suspend fun <T> channel(
        nodeId: String,
        path: String,
        timeoutMillis: Long,
        send: (OutputStream) -> Unit,
        receive: (InputStream) -> T,
    ): T
}

/** Sends API requests through the phone (docs/wear.md): small ones as messages, large ones through a channel. */
class RelayTransport(
    private val phone: PhoneConnection,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ApiTransport {
    override suspend fun execute(request: ApiRequest): ApiResult {
        val node = try {
            phone.phoneNodeId()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return ApiResult.Unavailable(Unavailability.NO_PHONE, "no phone connected")

        val body = request.body ?: ByteArray(0)
        var header = RelayRequest(id = newId(), method = request.method, path = request.path, headers = request.headers)
        return try {
            val message = RelayCodec.encodeRequest(header, body)
            var reply = if (message.size <= WearLink.MAX_MESSAGE_BYTES) {
                RelayCodec.decodeResponse(phone.request(node, WearLink.PATH_RELAY, message, request.timeoutMillis))
            } else {
                throughChannel(node, header, body, request.timeoutMillis)
            }
            if (reply.header.error?.code == RelayErrorCode.TOO_LARGE && reply.header.id == header.id) {
                // The same request (same Idempotency-Key) through a channel: the phone replays a write it already ran.
                header = header.copy(id = newId())
                reply = throughChannel(node, header, body, request.timeoutMillis)
            }
            interpret(header, reply)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayProtocolException) {
            ApiResult.Unavailable(Unavailability.PROTOCOL, e.message ?: "unreadable reply")
        } catch (e: Exception) {
            // Timeout, the phone went away mid-request, Play services refused.
            ApiResult.Unavailable(Unavailability.NO_PHONE, e.javaClass.simpleName)
        }
    }

    private suspend fun throughChannel(node: String, header: RelayRequest, body: ByteArray, timeoutMillis: Long): RelayFrame<RelayResponse> =
        phone.channel(
            node,
            WearLink.PATH_RELAY_CHANNEL,
            timeoutMillis,
            send = { output -> RelayCodec.writeRequest(output, header, body) },
            receive = { input -> RelayCodec.readResponse(input) },
        )

    companion object {
        /** What a reply means for the watch; a reply to another request is a protocol error. */
        fun interpret(request: RelayRequest, reply: RelayFrame<RelayResponse>): ApiResult {
            val header = reply.header
            if (header.id != request.id && header.id.isNotEmpty()) {
                return ApiResult.Unavailable(Unavailability.PROTOCOL, "reply to another request")
            }
            val error = header.error
            val status = header.status
            return when {
                error != null -> ApiResult.Unavailable(unavailability(error.code), "phone: ${error.code.name.lowercase()} ${error.message}".trim())
                status != null -> ApiResult.Response(status, reply.body, Route.PHONE)
                else -> ApiResult.Unavailable(Unavailability.PROTOCOL, "reply without status or error")
            }
        }

        fun unavailability(code: RelayErrorCode): Unavailability = when (code) {
            RelayErrorCode.NO_CREDENTIALS -> Unavailability.NO_CREDENTIALS
            RelayErrorCode.RELAY_UNAVAILABLE, RelayErrorCode.UNSUPPORTED_VERSION, RelayErrorCode.TOO_LARGE -> Unavailability.PHONE_NO_RELAY
            RelayErrorCode.UPSTREAM_UNREACHABLE, RelayErrorCode.INTERNAL -> Unavailability.NETWORK
            RelayErrorCode.FORBIDDEN, RelayErrorCode.BAD_REQUEST -> Unavailability.PROTOCOL
        }
    }
}
