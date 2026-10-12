package com.gamch1k.lifeos.core.wearlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class RelayCodecTest {
    private val audio = ByteArray(70_000) { (it % 251).toByte() }

    @Test
    fun `a request with a binary body survives a message round trip`() {
        val request = RelayRequest(
            id = "6f2c1d4e-0000-4000-8000-000000000001",
            method = "POST",
            path = "/api/voice/v1/audio-jobs",
            headers = mapOf("Content-Type" to "multipart/form-data; boundary=x", "Idempotency-Key" to "k1"),
        )
        val frame = RelayCodec.decodeRequest(RelayCodec.encodeRequest(request, audio))
        assertEquals(request.copy(bodyLength = audio.size), frame.header)
        assertArrayEquals(audio, frame.body)
    }

    @Test
    fun `a response survives a channel round trip, frame after frame`() {
        val out = ByteArrayOutputStream()
        RelayCodec.writeResponse(out, RelayResponse(id = "a", status = 202, contentType = "application/json"), """{"id":"j"}""".toByteArray())
        RelayCodec.writeResponse(out, RelayResponse(id = "b", error = RelayError(RelayErrorCode.NO_CREDENTIALS, "none")))
        val input = ByteArrayInputStream(out.toByteArray())

        val first = RelayCodec.readResponse(input)
        assertEquals("a", first.header.id)
        assertEquals(202, first.header.status)
        assertEquals("""{"id":"j"}""", first.body.toString(Charsets.UTF_8))
        assertNull(first.header.error)

        val second = RelayCodec.readResponse(input)
        assertEquals("b", second.header.id)
        assertNull(second.header.status)
        assertEquals(RelayErrorCode.NO_CREDENTIALS, second.header.error?.code)
        assertEquals(0, second.body.size)
    }

    @Test
    fun `error codes use their wire names`() {
        val bytes = RelayCodec.encodeResponse(RelayResponse(id = "x", error = RelayError(RelayErrorCode.UPSTREAM_UNREACHABLE)))
        val header = String(bytes, 4, ByteBuffer.wrap(bytes, 0, 4).int, Charsets.UTF_8)
        assertTrue(header, "\"upstream_unreachable\"" in header)
        assertFalse(header, "\"status\"" in header) // nulls stay out
    }

    @Test
    fun `the body length always comes from the body, not from the caller`() {
        val bytes = RelayCodec.encodeRequest(RelayRequest(id = "x", method = "GET", path = "/api/tasks/v1/tasks", bodyLength = 999))
        assertEquals(0, RelayCodec.decodeRequest(bytes).header.bodyLength)
    }

    @Test
    fun `unknown header fields from a later version are ignored`() {
        val json = """{"v":1,"id":"x","method":"GET","path":"/api/tasks/v1/tasks","bodyLength":0,"priority":"high"}"""
        val frame = RelayCodec.decodeRequest(frameOf(json))
        assertEquals("x", frame.header.id)
    }

    @Test
    fun `another protocol version is refused as unsupported_version`() {
        val json = """{"v":2,"id":"x","method":"GET","path":"/api/tasks/v1/tasks","bodyLength":0}"""
        assertProtocolError(RelayErrorCode.UNSUPPORTED_VERSION) { RelayCodec.decodeRequest(frameOf(json)) }
    }

    @Test
    fun `truncated and malformed frames are bad requests`() {
        val good = RelayCodec.encodeRequest(RelayRequest(id = "x", method = "POST", path = "/api/voice/v1/audio-jobs"), audio)
        assertProtocolError(RelayErrorCode.BAD_REQUEST) { RelayCodec.decodeRequest(good.copyOf(good.size - 1)) }
        assertProtocolError(RelayErrorCode.BAD_REQUEST) { RelayCodec.decodeRequest(good.copyOf(10)) }
        assertProtocolError(RelayErrorCode.BAD_REQUEST) { RelayCodec.decodeRequest(byteArrayOf(0, 0)) }
        assertProtocolError(RelayErrorCode.BAD_REQUEST) { RelayCodec.decodeRequest(frameOf("not json")) }
        assertProtocolError(RelayErrorCode.BAD_REQUEST) { RelayCodec.decodeRequest(ByteBuffer.allocate(8).putInt(Int.MAX_VALUE).array()) }
        assertProtocolError(RelayErrorCode.BAD_REQUEST) { RelayCodec.readRequest(ByteArrayInputStream(good.copyOf(good.size - 5))) }
    }

    @Test
    fun `a body larger than the limit is refused before it is read`() {
        val json = """{"v":1,"id":"x","method":"POST","path":"/api/voice/v1/audio-jobs","bodyLength":${WearLink.MAX_CHANNEL_BODY_BYTES + 1}}"""
        assertProtocolError(RelayErrorCode.BAD_REQUEST) { RelayCodec.readRequest(ByteArrayInputStream(frameOf(json))) }
    }

    @Test
    fun `the request id can be read even from a refused frame`() {
        val json = """{"v":7,"id":"keep-me","method":"GET","path":"/","bodyLength":0}"""
        assertEquals("keep-me", RelayCodec.peekRequestId(frameOf(json)))
        assertEquals("", RelayCodec.peekRequestId(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `credentials never show their secret in toString`() {
        val credentials = WearCredentials("https://lifeos.example", "abc.access", "s3cret-value")
        assertFalse("s3cret" in credentials.toString())
        assertFalse("abc" in credentials.toString())
        assertTrue(credentials.isComplete)
        assertFalse(WearCredentials("http://lifeos.example", "abc.access", "x").isComplete)
        assertFalse(WearCredentials("https://lifeos.example", "", "x").isComplete)
    }

    private fun frameOf(headerJson: String): ByteArray {
        val header = headerJson.toByteArray()
        return ByteBuffer.allocate(4 + header.size).putInt(header.size).put(header).array()
    }

    private fun assertProtocolError(code: RelayErrorCode, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: RelayProtocolException) {
            assertEquals(e.message, code, e.code)
        }
    }
}
