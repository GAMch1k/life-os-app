package com.gamch1k.lifeos.core.wearlink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class PhoneWearLinkHandlerTest {
    private val credentials = WearCredentials("https://lifeos.example", "id.access", "secret")

    private class RecordingRelay(var respond: suspend (RelayHttpRequest) -> RelayHttpResponse) : WearRelay {
        val calls = mutableListOf<RelayHttpRequest>()
        override suspend fun execute(request: RelayHttpRequest): RelayHttpResponse {
            calls += request
            return respond(request)
        }
    }

    private fun ok(body: String, status: Int = 200) = RelayHttpResponse(status, "application/json", body.toByteArray())

    private fun handler(relay: WearRelay, tokens: WearTokenSource = WearTokenSource { credentials }) =
        PhoneWearLinkHandler(tokens, relay)

    private fun relayMessage(method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0), id: String = "req-1") =
        RelayCodec.encodeRequest(RelayRequest(id = id, method = method, path = path, headers = headers), body)

    @Test
    fun `credentials are handed over with the request id`() = runTest {
        val reply = handler(WearRelay.UNAVAILABLE).handleCredentials("""{"v":1,"id":"c-1"}""".toByteArray())
        val decoded = RelayCodec.json.decodeFromString(CredentialsReply.serializer(), reply.toString(Charsets.UTF_8))
        assertEquals("c-1", decoded.id)
        assertEquals(credentials, decoded.credentials)
        assertNull(decoded.error)
    }

    @Test
    fun `a phone without a token says so`() = runTest {
        val reply = handler(WearRelay.UNAVAILABLE, WearTokenSource { null }).handleCredentials("""{"v":1,"id":"c-2"}""".toByteArray())
        val decoded = RelayCodec.json.decodeFromString(CredentialsReply.serializer(), reply.toString(Charsets.UTF_8))
        assertEquals(RelayErrorCode.NO_CREDENTIALS, decoded.error?.code)
        assertNull(decoded.credentials)

        val garbage = handler(WearRelay.UNAVAILABLE).handleCredentials("{{".toByteArray())
        assertEquals(
            RelayErrorCode.BAD_REQUEST,
            RelayCodec.json.decodeFromString(CredentialsReply.serializer(), garbage.toString(Charsets.UTF_8)).error?.code,
        )
    }

    @Test
    fun `a relayed read reaches the phone's client and its answer comes back`() = runTest {
        val relay = RecordingRelay { ok("""{"items":[]}""") }
        val reply = RelayCodec.decodeResponse(handler(relay).handleRelayMessage(relayMessage("GET", "/api/tasks/v1/tasks?limit=1", mapOf("Accept" to "application/json"))))
        assertEquals("req-1", reply.header.id)
        assertEquals(200, reply.header.status)
        assertEquals("""{"items":[]}""", reply.body.toString(Charsets.UTF_8))
        assertEquals("/api/tasks/v1/tasks?limit=1", relay.calls.single().path)
        assertEquals(mapOf("Accept" to "application/json"), relay.calls.single().headers)
        assertNull(relay.calls.single().body)
    }

    @Test
    fun `HTTP errors are answers, not relay errors`() = runTest {
        val relay = RecordingRelay { ok("""{"error":{"code":"not_found"}}""", 404) }
        val reply = RelayCodec.decodeResponse(
            handler(relay).handleRelayMessage(relayMessage("GET", "/api/voice/v1/audio-jobs/0f8fad5b-d9cb-469f-a165-70867728950e")),
        )
        assertEquals(404, reply.header.status)
        assertNull(reply.header.error)
    }

    @Test
    fun `forbidden calls never reach the phone's client`() = runTest {
        val relay = RecordingRelay { ok("{}") }
        val reply = RelayCodec.decodeResponse(handler(relay).handleRelayMessage(relayMessage("GET", "/api/finance/v1/accounts")))
        assertEquals(RelayErrorCode.FORBIDDEN, reply.header.error?.code)
        assertEquals(0, relay.calls.size)
    }

    @Test
    fun `relay failures become error codes`() = runTest {
        suspend fun errorOf(failure: Exception): RelayError? {
            val reply = RelayCodec.decodeResponse(
                handler(RecordingRelay { throw failure }).handleRelayMessage(relayMessage("GET", "/api/tasks/v1/tasks")),
            )
            assertEquals("req-1", reply.header.id)
            return reply.header.error
        }
        assertEquals(RelayErrorCode.NO_CREDENTIALS, errorOf(WearRelayException(RelayErrorCode.NO_CREDENTIALS, "none"))?.code)
        assertEquals(RelayErrorCode.UPSTREAM_UNREACHABLE, errorOf(IOException("reset"))?.code)
        assertEquals(RelayErrorCode.UPSTREAM_UNREACHABLE, errorOf(IllegalStateException("boom"))?.code)
        val unavailable = RelayCodec.decodeResponse(handler(WearRelay.UNAVAILABLE).handleRelayMessage(relayMessage("GET", "/api/tasks/v1/tasks")))
        assertEquals(RelayErrorCode.RELAY_UNAVAILABLE, unavailable.header.error?.code)
    }

    @Test
    fun `an unreadable frame still gets a reply with its id`() = runTest {
        val bytes = RelayCodec.encodeRequest(RelayRequest(id = "keep", method = "GET", path = "/api/tasks/v1/tasks")).let { it.copyOf(it.size + 3) }
        val reply = RelayCodec.decodeResponse(handler(WearRelay.UNAVAILABLE).handleRelayMessage(bytes))
        assertEquals("keep", reply.header.id)
        assertEquals(RelayErrorCode.BAD_REQUEST, reply.header.error?.code)
    }

    @Test
    fun `a reply too large for a message asks for a channel, and the channel replays the write`() = runTest {
        val big = "x".repeat(WearLink.MAX_MESSAGE_BYTES + 1)
        val relay = RecordingRelay { ok(big, 202) }
        val handler = handler(relay)
        val headers = mapOf("Idempotency-Key" to "capture-1", "Content-Type" to "multipart/form-data; boundary=b")

        val viaMessage = RelayCodec.decodeResponse(handler.handleRelayMessage(relayMessage("POST", "/api/voice/v1/audio-jobs", headers, "audio".toByteArray())))
        assertEquals(RelayErrorCode.TOO_LARGE, viaMessage.header.error?.code)

        val out = ByteArrayOutputStream()
        val request = RelayRequest(id = "req-2", method = "POST", path = "/api/voice/v1/audio-jobs", headers = headers)
        handler.handleRelayChannel(ByteArrayInputStream(RelayCodec.encodeRequest(request, "audio".toByteArray())), out)
        val viaChannel = RelayCodec.readResponse(ByteArrayInputStream(out.toByteArray()))
        assertEquals("req-2", viaChannel.header.id)
        assertEquals(202, viaChannel.header.status)
        assertEquals(big.length, viaChannel.body.size)
        assertEquals("the write ran once", 1, relay.calls.size)
    }

    @Test
    fun `an upload through a channel carries its body and the owner as actor`() = runTest {
        val audio = ByteArray(300_000) { it.toByte() }
        val relay = RecordingRelay { ok("""{"id":"job"}""", 202) }
        val out = ByteArrayOutputStream()
        val request = RelayRequest(id = "up", method = "POST", path = "/api/voice/v1/audio-jobs", headers = mapOf("Content-Type" to "multipart/form-data; boundary=b"))
        handler(relay).handleRelayChannel(ByteArrayInputStream(RelayCodec.encodeRequest(request, audio)), out)
        assertArrayEquals(audio, relay.calls.single().body)
        assertEquals("user", relay.calls.single().headers["X-Life-OS-Actor"])
        assertEquals(202, RelayCodec.readResponse(ByteArrayInputStream(out.toByteArray())).header.status)
    }

    @Test
    fun `a retried write with the same key runs once, a new key runs again`() = runTest {
        var jobs = 0
        val relay = RecordingRelay { jobs++; ok("""{"id":"job-$jobs"}""", 202) }
        val handler = handler(relay)
        fun upload(key: String, id: String) = relayMessage("POST", "/api/voice/v1/audio-jobs", mapOf("Idempotency-Key" to key, "Content-Type" to "audio/ogg"), "a".toByteArray(), id)

        val first = RelayCodec.decodeResponse(handler.handleRelayMessage(upload("capture-1", "try-1")))
        val retry = RelayCodec.decodeResponse(handler.handleRelayMessage(upload("capture-1", "try-2")))
        val other = RelayCodec.decodeResponse(handler.handleRelayMessage(upload("capture-2", "try-3")))
        assertEquals("""{"id":"job-1"}""", first.body.toString(Charsets.UTF_8))
        assertEquals("""{"id":"job-1"}""", retry.body.toString(Charsets.UTF_8))
        assertEquals("try-2", retry.header.id)
        assertEquals("""{"id":"job-2"}""", other.body.toString(Charsets.UTF_8))
        assertEquals(2, relay.calls.size)
    }

    @Test
    fun `a new job attempt's key is not answered with the failed attempt's job`() = runTest {
        var jobs = 0
        val relay = RecordingRelay { jobs++; ok("""{"id":"job-$jobs"}""", 202) }
        val handler = handler(relay)
        fun upload(key: String) = relayMessage("POST", "/api/voice/v1/audio-jobs", mapOf("Idempotency-Key" to key, "Content-Type" to "audio/ogg"), "a".toByteArray())

        handler.handleRelayMessage(upload("capture-1"))
        val newAttempt = RelayCodec.decodeResponse(handler.handleRelayMessage(upload("capture-1-r1")))
        val itsRetry = RelayCodec.decodeResponse(handler.handleRelayMessage(upload("capture-1-r1")))
        assertEquals("""{"id":"job-2"}""", newAttempt.body.toString(Charsets.UTF_8))
        assertEquals("""{"id":"job-2"}""", itsRetry.body.toString(Charsets.UTF_8))
        assertEquals(2, relay.calls.size)
    }

    @Test
    fun `only a success is replayed, a write that got 5xx, 429 or a refused token runs again`() = runTest {
        val answers = ArrayDeque(listOf(503, 429, 401, 202, 202))
        val relay = RecordingRelay { val status = answers.removeFirst(); ok("""{"status":$status}""", status) }
        val handler = handler(relay)
        val upload = relayMessage("POST", "/api/voice/v1/audio-jobs", mapOf("Idempotency-Key" to "capture-1", "Content-Type" to "audio/ogg"), "a".toByteArray())
        val statuses = List(5) { RelayCodec.decodeResponse(handler.handleRelayMessage(upload)).header.status }
        assertEquals(listOf(503, 429, 401, 202, 202), statuses)
        assertEquals("the success is replayed, not run again", 4, relay.calls.size)
    }

    @Test
    fun `retries waiting on a write that failed get its answer, later ones run again`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cache = RelayReplayCache()
        var runs = 0
        val first = async { cache.getOrExecute("k") { runs++; gate.await(); ok("busy", 503) } }
        yield() // the first try is running
        val waiting = async { cache.getOrExecute("k") { runs++; ok("never") } }
        yield() // the retry waits for it
        gate.complete(Unit)
        assertEquals(503, first.await().status)
        assertEquals(503, waiting.await().status)
        assertEquals(200, cache.getOrExecute("k") { runs++; ok("later") }.status)
        assertEquals(2, runs)
    }

    @Test
    fun `a write whose first try failed runs again on retry`() = runTest {
        var fail = true
        val relay = RecordingRelay { if (fail) throw IOException("down") else ok("{}", 202) }
        val handler = handler(relay)
        val upload = relayMessage("POST", "/api/voice/v1/audio-jobs", mapOf("Idempotency-Key" to "k", "Content-Type" to "audio/ogg"), "a".toByteArray())
        assertEquals(RelayErrorCode.UPSTREAM_UNREACHABLE, RelayCodec.decodeResponse(handler.handleRelayMessage(upload)).header.error?.code)
        fail = false
        assertEquals(202, RelayCodec.decodeResponse(handler.handleRelayMessage(upload)).header.status)
        assertEquals(2, relay.calls.size)
    }

    @Test
    fun `concurrent retries of one write wait for the first`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cache = RelayReplayCache()
        var runs = 0
        val first = async { cache.getOrExecute("k") { runs++; gate.await(); ok("one") } }
        val second = async { cache.getOrExecute("k") { runs++; ok("two") } }
        gate.complete(Unit)
        assertEquals("one", first.await().body.toString(Charsets.UTF_8))
        assertEquals("one", second.await().body.toString(Charsets.UTF_8))
        assertEquals(1, runs)
    }

    @Test
    fun `replayed replies expire`() = runTest {
        var now = 0L
        val cache = RelayReplayCache(ttlMillis = 1000, clock = { now })
        var runs = 0
        cache.getOrExecute("k") { runs++; ok("a") }
        now = 500
        cache.getOrExecute("k") { runs++; ok("b") }
        now = 2000
        assertEquals("c", cache.getOrExecute("k") { runs++; ok("c") }.body.toString(Charsets.UTF_8))
        assertEquals(2, runs)
    }
}
