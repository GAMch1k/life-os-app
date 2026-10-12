package com.gamch1k.lifeos.wear.net

import com.gamch1k.lifeos.core.wearlink.PhoneWearLinkHandler
import com.gamch1k.lifeos.core.wearlink.RelayCodec
import com.gamch1k.lifeos.core.wearlink.RelayErrorCode
import com.gamch1k.lifeos.core.wearlink.RelayError
import com.gamch1k.lifeos.core.wearlink.RelayFrame
import com.gamch1k.lifeos.core.wearlink.RelayHttpRequest
import com.gamch1k.lifeos.core.wearlink.RelayHttpResponse
import com.gamch1k.lifeos.core.wearlink.RelayRequest
import com.gamch1k.lifeos.core.wearlink.RelayResponse
import com.gamch1k.lifeos.core.wearlink.WearCredentials
import com.gamch1k.lifeos.core.wearlink.WearLink
import com.gamch1k.lifeos.core.wearlink.WearRelay
import com.gamch1k.lifeos.core.wearlink.WearRelayException
import com.gamch1k.lifeos.core.wearlink.WearTokenSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The watch's relay against the phone's real handler (core), with the Data
 * Layer replaced by direct calls: the whole protocol, end to end.
 */
class RelayTransportTest {
    private class FakePhone(val handler: PhoneWearLinkHandler?, var failRequests: Boolean = false) : PhoneConnection {
        val paths = mutableListOf<String>()

        override suspend fun phoneNodeId(): String? = if (handler == null) null else "phone-node"

        override suspend fun request(nodeId: String, path: String, payload: ByteArray, timeoutMillis: Long): ByteArray {
            paths += path
            if (failRequests) throw IOException("the phone did not answer")
            return handler!!.handleRelayMessage(payload)
        }

        override suspend fun <T> channel(nodeId: String, path: String, timeoutMillis: Long, send: (OutputStream) -> Unit, receive: (InputStream) -> T): T {
            paths += path
            val request = ByteArrayOutputStream().also(send).toByteArray()
            val reply = ByteArrayOutputStream()
            handler!!.handleRelayChannel(ByteArrayInputStream(request), reply)
            return receive(ByteArrayInputStream(reply.toByteArray()))
        }
    }

    private class Server(var respond: (RelayHttpRequest) -> RelayHttpResponse) : WearRelay {
        val calls = mutableListOf<RelayHttpRequest>()
        override suspend fun execute(request: RelayHttpRequest): RelayHttpResponse {
            calls += request
            return respond(request)
        }
    }

    private val token = WearTokenSource { WearCredentials("https://lifeos.example", "id.access", "secret") }

    private fun transport(server: WearRelay, phone: FakePhone = FakePhone(PhoneWearLinkHandler(token, server))) = phone to RelayTransport(phone)

    @Test
    fun `a small request goes as a message and the answer comes back`() = runTest {
        val server = Server { RelayHttpResponse(200, "application/json", """{"items":[]}""".toByteArray()) }
        val (phone, relay) = transport(server)
        val result = relay.execute(ApiRequest("GET", "/api/tasks/v1/tasks?limit=1", mapOf("Accept" to "application/json")))
        result as ApiResult.Response
        assertEquals(200, result.status)
        assertEquals(Route.PHONE, result.route)
        assertEquals("""{"items":[]}""", result.text)
        assertEquals(listOf(WearLink.PATH_RELAY), phone.paths)
        assertEquals("/api/tasks/v1/tasks?limit=1", server.calls.single().path)
    }

    @Test
    fun `an audio upload goes through a channel, byte for byte`() = runTest {
        val audio = ByteArray(200_000) { (it * 7).toByte() }
        val server = Server { RelayHttpResponse(202, "application/json", """{"id":"job"}""".toByteArray()) }
        val (phone, relay) = transport(server)
        val result = relay.execute(ApiRequest("POST", "/api/voice/v1/audio-jobs", mapOf("Content-Type" to "audio/ogg", "Idempotency-Key" to "c1"), audio))
        assertEquals(202, (result as ApiResult.Response).status)
        assertEquals(listOf(WearLink.PATH_RELAY_CHANNEL), phone.paths)
        assertArrayEquals(audio, server.calls.single().body)
        assertEquals("user", server.calls.single().headers["X-Life-OS-Actor"])
    }

    @Test
    fun `a reply too large for a message is fetched again through a channel without a second write`() = runTest {
        val big = ByteArray(WearLink.MAX_MESSAGE_BYTES + 10) { 'x'.code.toByte() }
        val server = Server { RelayHttpResponse(200, "application/json", big) }
        val (phone, relay) = transport(server)
        val result = relay.execute(ApiRequest("POST", "/api/tasks/v1/tasks/0f8fad5b-d9cb-469f-a165-70867728950e/complete", mapOf("Idempotency-Key" to "tap-1")))
        assertEquals(big.size, (result as ApiResult.Response).body.size)
        assertEquals(listOf(WearLink.PATH_RELAY, WearLink.PATH_RELAY_CHANNEL), phone.paths)
        assertEquals(1, server.calls.size)
    }

    @Test
    fun `no phone, a silent phone and phone errors become reasons to go direct`() = runTest {
        val (_, noPhone) = transport(WearRelay.UNAVAILABLE, FakePhone(null))
        assertEquals(Unavailability.NO_PHONE, (noPhone.execute(ApiRequest("GET", "/api/tasks/v1/tasks")) as ApiResult.Unavailable).reason)

        val silent = FakePhone(PhoneWearLinkHandler(token, WearRelay.UNAVAILABLE), failRequests = true)
        assertEquals(Unavailability.NO_PHONE, (RelayTransport(silent).execute(ApiRequest("GET", "/api/tasks/v1/tasks")) as ApiResult.Unavailable).reason)

        val (_, notWired) = transport(WearRelay.UNAVAILABLE)
        assertEquals(Unavailability.PHONE_NO_RELAY, (notWired.execute(ApiRequest("GET", "/api/tasks/v1/tasks")) as ApiResult.Unavailable).reason)

        val (_, offline) = transport(WearRelay { throw IOException("no network") })
        assertEquals(Unavailability.NETWORK, (offline.execute(ApiRequest("GET", "/api/tasks/v1/tasks")) as ApiResult.Unavailable).reason)

        val (_, noToken) = transport(WearRelay { throw WearRelayException(RelayErrorCode.NO_CREDENTIALS, "none") })
        assertEquals(Unavailability.NO_CREDENTIALS, (noToken.execute(ApiRequest("GET", "/api/tasks/v1/tasks")) as ApiResult.Unavailable).reason)
    }

    @Test
    fun `a refused request is a protocol problem, not a reason to go direct`() = runTest {
        val server = Server { RelayHttpResponse(200, null, ByteArray(0)) }
        val (_, relay) = transport(server)
        val result = relay.execute(ApiRequest("DELETE", "/api/tasks/v1/tasks/0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertEquals(Unavailability.PROTOCOL, (result as ApiResult.Unavailable).reason)
        assertEquals(0, server.calls.size)
    }

    @Test
    fun `every relay error code maps to one reason, and only request errors keep the watch from going direct`() {
        RelayErrorCode.entries.forEach { code ->
            val reason = RelayTransport.unavailability(code)
            assertEquals(code.name, code.watchMayGoDirect, reason != Unavailability.PROTOCOL)
        }
    }

    @Test
    fun `a reply to another request is refused`() {
        val request = RelayRequest(id = "mine", method = "GET", path = "/api/tasks/v1/tasks")
        val other = RelayFrame(RelayResponse(id = "theirs", status = 200), ByteArray(0))
        assertEquals(Unavailability.PROTOCOL, (RelayTransport.interpret(request, other) as ApiResult.Unavailable).reason)
        val mine = RelayFrame(RelayResponse(id = "mine", status = 404), ByteArray(0))
        assertEquals(404, (RelayTransport.interpret(request, mine) as ApiResult.Response).status)
        val error = RelayFrame(RelayResponse(id = "mine", error = RelayError(RelayErrorCode.UPSTREAM_UNREACHABLE)), ByteArray(0))
        assertEquals(Unavailability.NETWORK, (RelayTransport.interpret(request, error) as ApiResult.Unavailable).reason)
    }

    @Test
    fun `request ids are fresh per request`() = runTest {
        val ids = mutableListOf<String>()
        val phone = object : PhoneConnection {
            override suspend fun phoneNodeId() = "n"
            override suspend fun request(nodeId: String, path: String, payload: ByteArray, timeoutMillis: Long): ByteArray {
                val id = RelayCodec.decodeRequest(payload).header.id
                ids += id
                return RelayCodec.encodeResponse(RelayResponse(id = id, status = 200))
            }
            override suspend fun <T> channel(nodeId: String, path: String, timeoutMillis: Long, send: (OutputStream) -> Unit, receive: (InputStream) -> T): T =
                throw IOException("unused")
        }
        val relay = RelayTransport(phone)
        repeat(3) { relay.execute(ApiRequest("GET", "/api/tasks/v1/tasks")) }
        assertEquals(3, ids.toSet().size)
        assertTrue(ids.all { it.length == 36 })
    }
}
