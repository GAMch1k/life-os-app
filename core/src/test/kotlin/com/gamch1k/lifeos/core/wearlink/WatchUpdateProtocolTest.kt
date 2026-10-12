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
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random

/** The watch-update messages and the APK transfer: versions, wire names, chunking, limits, abort. */
class WatchUpdateProtocolTest {
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ---- Messages

    @Test
    fun `the version request and the watch's answer round-trip`() {
        val request = WatchInfoRequest(id = "r-1")
        assertEquals(request, WatchUpdateCodec.decodeInfoRequest(WatchUpdateCodec.encodeInfoRequest(request)))
        val info = WatchInfo(
            id = "r-1",
            versionCode = 5,
            versionName = "0.4.0",
            sdkInt = 36,
            abis = listOf("arm64-v8a", "armeabi-v7a"),
            capabilities = listOf(WearLink.WATCH_CAN_UPDATE),
            canInstallPackages = true,
            battery = WatchBattery(64, charging = false),
            lastUpdate = WatchUpdateResult(5, "0.4.0", WatchUpdateStatus.INSTALLED, "", "2026-10-12T12:00:00Z"),
        )
        val decoded = WatchUpdateCodec.decodeInfo(WatchUpdateCodec.encodeInfo(info))
        assertEquals(info, decoded)
        assertTrue(decoded.canUpdate)
    }

    @Test
    fun `the wire names are stable`() {
        val json = WatchUpdateCodec.encodeOffer(ApkOffer(id = "o", versionCode = 5, versionName = "0.4.0", size = 10, sha256 = "a".repeat(64))).toString(Charsets.UTF_8)
        assertEquals("""{"v":1,"id":"o","versionCode":5,"versionName":"0.4.0","size":10,"sha256":"${"a".repeat(64)}"}""", json)
        val reply = WatchUpdateCodec.encodeOfferReply(ApkOfferReply(id = "o", accepted = false, error = UpdateError(UpdateErrorCode.THROTTLED, "wait"), retryAfterSeconds = 25))
        assertEquals("""{"v":1,"id":"o","accepted":false,"error":{"code":"throttled","message":"wait"},"retryAfterSeconds":25}""", reply.toString(Charsets.UTF_8))
        assertEquals("/lifeos/watch-info", WearLink.PATH_WATCH_INFO)
        assertEquals("/lifeos/watch-report", WearLink.PATH_WATCH_REPORT)
        assertEquals("/lifeos/apk-offer", WearLink.PATH_APK_OFFER)
        assertEquals("/lifeos/apk-transfer", WearLink.PATH_APK_TRANSFER)
    }

    @Test
    fun `an old watch app's answer (0_3, no capabilities) reads as one that can't update`() {
        val info = WatchUpdateCodec.decodeInfo("""{"v":1,"id":"x","versionCode":4,"versionName":"0.3.1","sdkInt":36}""".toByteArray())
        assertFalse(info.canUpdate)
        assertFalse(info.canInstallPackages)
        assertNull(info.battery)
    }

    @Test
    fun `unknown keys and unknown error codes are kept readable`() {
        val reply = WatchUpdateCodec.decodeOfferReply("""{"v":1,"id":"o","accepted":false,"error":{"code":"from_the_future"},"extra":[1,2]}""".toByteArray())
        assertEquals("from_the_future", reply.error?.code)
    }

    @Test
    fun `another protocol version and unreadable JSON are refused with a code`() {
        assertCode(UpdateErrorCode.UNSUPPORTED_VERSION) { WatchUpdateCodec.decodeOffer("""{"v":2,"id":"o","versionCode":5,"versionName":"x","size":1,"sha256":"a"}""".toByteArray()) }
        assertCode(UpdateErrorCode.BAD_REQUEST) { WatchUpdateCodec.decodeOffer("{not json".toByteArray()) }
        assertCode(UpdateErrorCode.BAD_REQUEST) { WatchUpdateCodec.decodeOffer("""{"v":1,"id":"o"}""".toByteArray()) }
        assertCode(UpdateErrorCode.BAD_REQUEST) { WatchUpdateCodec.decodeInfo(ByteArray(WearLink.MAX_MESSAGE_BYTES + 1) { ' '.code.toByte() }) }
    }

    // ---- The transfer

    private fun header(apk: ByteArray, id: String = "offer-1", size: Long = apk.size.toLong(), sha: String = sha256(apk)) =
        ApkTransferHeader(id = id, versionCode = 5, size = size, sha256 = sha)

    private fun sent(apk: ByteArray, header: ApkTransferHeader = header(apk), chunk: Int = ApkTransferCodec.CHUNK_BYTES): ByteArray =
        ByteArrayOutputStream().also { ApkTransferCodec.send(it, header, ByteArrayInputStream(apk), chunk) }.toByteArray()

    private fun receive(stream: ByteArray): Pair<ApkTransferHeader, ByteArray> {
        val input = ByteArrayInputStream(stream)
        val header = ApkTransferCodec.readHeader(input)
        val sink = ByteArrayOutputStream()
        ApkTransferCodec.readBody(input, header, sink)
        return header to sink.toByteArray()
    }

    @Test
    fun `an APK arrives whole, in chunks, with its header`() {
        val apk = Random(7).nextBytes(200_000)
        val (header, body) = receive(sent(apk, chunk = 64 * 1024))
        assertEquals(header(apk), header)
        assertArrayEquals(apk, body)
    }

    @Test
    fun `chunks are as large as asked, the last one shorter, then the end mark`() {
        val apk = ByteArray(10) { it.toByte() }
        val stream = sent(apk, chunk = 4)
        val headerLength = java.nio.ByteBuffer.wrap(stream, 0, 4).int
        val chunks = java.nio.ByteBuffer.wrap(stream, 4 + headerLength, stream.size - 4 - headerLength)
        val lengths = mutableListOf<Int>()
        while (chunks.hasRemaining()) {
            val length = chunks.int
            lengths += length
            chunks.position(chunks.position() + maxOf(length, 0))
        }
        assertEquals(listOf(4, 4, 2, 0), lengths)
        assertArrayEquals(apk, receive(stream).second)
    }

    @Test
    fun `progress counts the bytes sent and received`() {
        val apk = ByteArray(10)
        val progress = mutableListOf<Long>()
        val stream = ByteArrayOutputStream().also { ApkTransferCodec.send(it, header(apk), ByteArrayInputStream(apk), 4, onProgress = { n -> progress += n }) }.toByteArray()
        assertEquals(listOf(4L, 8L, 10L), progress)
        val input = ByteArrayInputStream(stream)
        val received = mutableListOf<Long>()
        ApkTransferCodec.readBody(input, ApkTransferCodec.readHeader(input), ByteArrayOutputStream()) { received += it }
        assertEquals(listOf(4L, 8L, 10L), received)
    }

    @Test
    fun `a wrong SHA-256 is refused after the last byte`() {
        val apk = ByteArray(1000) { 1 }
        assertCode(UpdateErrorCode.SHA256_MISMATCH) { receive(sent(apk, header(apk, sha = "0".repeat(64)))) }
    }

    @Test
    fun `more bytes than announced are cut off at once, fewer are refused at the end`() {
        val apk = ByteArray(1000) { 2 }
        // A sender that announces 500 bytes and sends 1000.
        val tooLong = ByteArrayOutputStream().apply {
            val shortHeader = header(apk, size = 500)
            val headerJson = WatchUpdateCodec.encode(ApkTransferHeader.serializer(), shortHeader)
            DataOutputStream(this).apply {
                writeInt(headerJson.size)
                write(headerJson)
                writeInt(1000)
                write(apk)
                writeInt(0)
            }
        }.toByteArray()
        assertCode(UpdateErrorCode.SIZE_MISMATCH) { receive(tooLong) }
        // A sender that announces 1000 and ends after 500.
        val tooShort = ByteArrayOutputStream().apply {
            val headerJson = WatchUpdateCodec.encode(ApkTransferHeader.serializer(), header(apk))
            DataOutputStream(this).apply {
                writeInt(headerJson.size)
                write(headerJson)
                writeInt(500)
                write(apk, 0, 500)
                writeInt(0)
            }
        }.toByteArray()
        assertCode(UpdateErrorCode.SIZE_MISMATCH) { receive(tooShort) }
    }

    @Test
    fun `the sender's abort and a stream that ends mid-chunk are aborts`() {
        val apk = ByteArray(1000) { 3 }
        val aborted = ByteArrayOutputStream().also { out ->
            try {
                ApkTransferCodec.send(out, header(apk), ByteArrayInputStream(apk), 100, cancelled = { out.size() > 300 })
                fail("send should stop")
            } catch (_: IOException) {
            }
        }.toByteArray()
        assertCode(UpdateErrorCode.ABORTED) { receive(aborted) }
        val full = sent(apk)
        assertCode(UpdateErrorCode.ABORTED) { receive(full.copyOf(full.size - 600)) }
        assertCode(UpdateErrorCode.ABORTED) { receive(full.copyOf(3)) }
    }

    @Test
    fun `a source shorter than the header says is aborted by the sender`() {
        val apk = ByteArray(100)
        val out = ByteArrayOutputStream()
        try {
            ApkTransferCodec.send(out, header(apk, size = 200), ByteArrayInputStream(apk), 64)
            fail("send should fail")
        } catch (_: IOException) {
        }
        assertCode(UpdateErrorCode.ABORTED) { receive(out.toByteArray()) }
    }

    @Test
    fun `the header's limits - size, the SHA-256's form, chunk lengths, the version`() {
        val apk = ByteArray(10)
        val huge = ByteArrayOutputStream().apply {
            val json = WatchUpdateCodec.encode(ApkTransferHeader.serializer(), header(apk, size = WearLink.MAX_APK_BYTES + 1))
            DataOutputStream(this).apply { writeInt(json.size); write(json) }
        }.toByteArray()
        assertCode(UpdateErrorCode.TOO_LARGE) { receive(huge) }
        assertCode(UpdateErrorCode.BAD_REQUEST) { receive(sent(apk, header(apk, sha = "nope"))) }
        val zeroSize = ByteArrayOutputStream().apply {
            val json = WatchUpdateCodec.encode(ApkTransferHeader.serializer(), header(apk, size = 0))
            DataOutputStream(this).apply { writeInt(json.size); write(json); writeInt(0) }
        }.toByteArray()
        assertCode(UpdateErrorCode.BAD_REQUEST) { receive(zeroSize) }
        val hugeChunk = ByteArrayOutputStream().apply {
            val json = WatchUpdateCodec.encode(ApkTransferHeader.serializer(), header(apk))
            DataOutputStream(this).apply { writeInt(json.size); write(json); writeInt(ApkTransferCodec.MAX_CHUNK_BYTES + 1) }
        }.toByteArray()
        assertCode(UpdateErrorCode.BAD_REQUEST) { receive(hugeChunk) }
        val v2 = ByteArrayOutputStream().apply {
            val json = WatchUpdateCodec.encode(ApkTransferHeader.serializer(), header(apk).copy(v = 2))
            DataOutputStream(this).apply { writeInt(json.size); write(json) }
        }.toByteArray()
        assertCode(UpdateErrorCode.UNSUPPORTED_VERSION) { receive(v2) }
        assertCode(UpdateErrorCode.BAD_REQUEST) { receive(byteArrayOf(0x7f, 0, 0, 0)) }
    }

    @Test
    fun `the watch's verdict round-trips`() {
        val result = ApkTransferResult(id = "offer-1", received = false, error = UpdateError(UpdateErrorCode.SHA256_MISMATCH, "no"))
        val out = ByteArrayOutputStream().also { ApkTransferCodec.writeResult(it, result) }
        assertEquals(result, ApkTransferCodec.readResult(ByteArrayInputStream(out.toByteArray())))
    }

    private fun assertCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: UpdateProtocolException) {
            assertEquals(e.message, code, e.code)
        }
    }
}
