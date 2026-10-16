package com.gamch1k.lifeos.core.wearlink

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlinx.serialization.Serializable
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
        assertEquals("""{"v":1,"id":"o","versionCode":5,"versionName":"0.4.0","size":10,"sha256":"${"a".repeat(64)}","transfer":1}""", json)
        val v2 = ApkOffer(id = "o", versionCode = 10, versionName = "0.4.3", size = 10, sha256 = "a".repeat(64), transfer = 2, url = "/android/lifeos-wear-0.4.3-10.apk")
        assertEquals(
            """{"v":1,"id":"o","versionCode":10,"versionName":"0.4.3","size":10,"sha256":"${"a".repeat(64)}","transfer":2,"url":"/android/lifeos-wear-0.4.3-10.apk"}""",
            WatchUpdateCodec.encodeOffer(v2).toString(Charsets.UTF_8),
        )
        val reply = WatchUpdateCodec.encodeOfferReply(ApkOfferReply(id = "o", accepted = false, error = UpdateError(UpdateErrorCode.THROTTLED, "wait"), retryAfterSeconds = 25))
        assertEquals(
            """{"v":1,"id":"o","accepted":false,"error":{"code":"throttled","message":"wait"},"retryAfterSeconds":25,"transfer":1,"offset":0,"download":false}""",
            reply.toString(Charsets.UTF_8),
        )
        val accepted = WatchUpdateCodec.encodeOfferReply(ApkOfferReply(id = "o", accepted = true, transfer = 2, offset = 4096, download = true))
        assertEquals("""{"v":1,"id":"o","accepted":true,"transfer":2,"offset":4096,"download":true}""", accepted.toString(Charsets.UTF_8))
        val header = WatchUpdateCodec.encode(ApkTransferHeader.serializer(), ApkTransferHeader(id = "o", versionCode = 10, size = 10, sha256 = "a".repeat(64), offset = 4))
        assertEquals("""{"v":1,"id":"o","versionCode":10,"size":10,"sha256":"${"a".repeat(64)}","offset":4}""", header.toString(Charsets.UTF_8))
        val status = WatchUpdateCodec.encodeStatus(
            ApkStatus(id = "o", state = ApkStatusState.NEEDS_STREAM, route = ApkRoute.PHONE, received = 4, size = 10, offset = 4, error = UpdateError(UpdateErrorCode.NO_WIFI, "none")),
        )
        assertEquals(
            """{"v":1,"id":"o","state":"needs_stream","route":"phone","received":4,"size":10,"offset":4,"error":{"code":"no_wifi","message":"none"}}""",
            status.toString(Charsets.UTF_8),
        )
        assertEquals("""{"v":1,"id":"o"}""", WatchUpdateCodec.encodeStatusRequest(ApkStatusRequest(id = "o")).toString(Charsets.UTF_8))
        assertEquals("/lifeos/watch-info", WearLink.PATH_WATCH_INFO)
        assertEquals("/lifeos/watch-report", WearLink.PATH_WATCH_REPORT)
        assertEquals("/lifeos/apk-offer", WearLink.PATH_APK_OFFER)
        assertEquals("/lifeos/apk-transfer", WearLink.PATH_APK_TRANSFER)
        assertEquals("/lifeos/apk-status", WearLink.PATH_APK_STATUS)
        assertEquals("apk_transfer_v1", WearLink.WATCH_CAN_UPDATE)
        assertEquals("apk_transfer_v2", WearLink.WATCH_CAN_RESUME)
        assertEquals(
            listOf("waiting_for_wifi", "downloading", "needs_stream", "receiving", "verifying", "installing", "failed", "unknown"),
            listOf(
                ApkStatusState.WAITING_FOR_WIFI, ApkStatusState.DOWNLOADING, ApkStatusState.NEEDS_STREAM, ApkStatusState.RECEIVING,
                ApkStatusState.VERIFYING, ApkStatusState.INSTALLING, ApkStatusState.FAILED, ApkStatusState.UNKNOWN,
            ),
        )
    }

    // ---- Transfer v1/v2 negotiation (0.4.3)

    /** The offer, its reply and the transfer header exactly as a 0.4.0–0.4.2 app declares them. */
    @Serializable
    private data class OldOffer(val v: Int = 1, val id: String, val versionCode: Long, val versionName: String, val size: Long, val sha256: String)

    @Serializable
    private data class OldOfferReply(
        val v: Int = 1,
        val id: String,
        val accepted: Boolean,
        val error: UpdateError? = null,
        val retryAfterSeconds: Long? = null,
    )

    @Serializable
    private data class OldHeader(val v: Int = 1, val id: String, val versionCode: Long, val size: Long, val sha256: String)

    @Test
    fun `a 0_4_2 watch reads a 0_4_3 phone's offer and header, and its reply means a whole v1 stream`() {
        val offer = ApkOffer(id = "o", versionCode = 10, versionName = "0.4.3", size = 6_000_000, sha256 = "a".repeat(64), transfer = TransferVersion.CURRENT, url = "/android/lifeos-wear-0.4.3-10.apk")
        val old = WatchUpdateCodec.decode(OldOffer.serializer(), WatchUpdateCodec.encodeOffer(offer)) { it.v }
        assertEquals(OldOffer(id = "o", versionCode = 10, versionName = "0.4.3", size = 6_000_000, sha256 = "a".repeat(64)), old)
        // What the 0.4.2 watch answers, read by the 0.4.3 phone.
        val reply = WatchUpdateCodec.decodeOfferReply(WatchUpdateCodec.encode(OldOfferReply.serializer(), OldOfferReply(id = "o", accepted = true)))
        assertEquals(TransferVersion.V1, reply.transfer)
        assertEquals(0, reply.offset)
        assertFalse(reply.download)
        assertEquals(PushPath.Stream(0), PushPath.after(reply, offer.size))
        // The phone's header for it: offset 0, which the old watch doesn't know and ignores.
        val header = ApkTransferHeader(id = "o", versionCode = 10, size = offer.size, sha256 = offer.sha256, offset = 0)
        val oldHeader = WatchUpdateCodec.decode(OldHeader.serializer(), WatchUpdateCodec.encode(ApkTransferHeader.serializer(), header)) { it.v }
        assertEquals(OldHeader(id = "o", versionCode = 10, size = 6_000_000, sha256 = "a".repeat(64)), oldHeader)
    }

    @Test
    fun `a 0_4_3 watch reads a 0_4_2 phone's offer and header as v1`() {
        val offer = WatchUpdateCodec.decodeOffer(WatchUpdateCodec.encode(OldOffer.serializer(), OldOffer(id = "o", versionCode = 10, versionName = "0.4.3", size = 10, sha256 = "a".repeat(64))))
        assertEquals(TransferVersion.V1, offer.transfer)
        assertNull(offer.url)
        assertEquals(TransferVersion.V1, TransferVersion.agreed(offer))
        val stream = ByteArrayOutputStream().apply {
            val json = WatchUpdateCodec.encode(OldHeader.serializer(), OldHeader(id = "o", versionCode = 10, size = 10, sha256 = "a".repeat(64)))
            DataOutputStream(this).apply { writeInt(json.size); write(json) }
        }.toByteArray()
        assertEquals(0, ApkTransferCodec.readHeader(ByteArrayInputStream(stream)).offset)
    }

    @Test
    fun `both sides use the lower transfer version`() {
        fun offer(transfer: Int) = ApkOffer(id = "o", versionCode = 10, versionName = "0.4.3", size = 10, sha256 = "a".repeat(64), transfer = transfer)
        assertEquals(TransferVersion.V1, TransferVersion.agreed(offer(1)))
        assertEquals(TransferVersion.V2, TransferVersion.agreed(offer(2)))
        assertEquals("a later phone gets what this watch speaks", TransferVersion.V2, TransferVersion.agreed(offer(3)))
        assertEquals("nonsense counts as v1", TransferVersion.V1, TransferVersion.agreed(offer(0)))
    }

    @Test
    fun `the phone's path after an accepted reply`() {
        val size = 1_000L
        fun reply(transfer: Int, offset: Long = 0, download: Boolean = false) = ApkOfferReply(id = "o", accepted = true, transfer = transfer, offset = offset, download = download)
        assertEquals(PushPath.Stream(0), PushPath.after(reply(1), size))
        assertEquals("a v1 watch's other fields mean nothing", PushPath.Stream(0), PushPath.after(reply(1, offset = 500, download = true), size))
        assertEquals(PushPath.Stream(0), PushPath.after(reply(2), size))
        assertEquals(PushPath.Stream(400), PushPath.after(reply(2, offset = 400), size))
        assertEquals("a whole file: only the end mark", PushPath.Stream(size), PushPath.after(reply(2, offset = size), size))
        assertEquals("an offset past the end starts over", PushPath.Stream(0), PushPath.after(reply(2, offset = size + 1), size))
        assertEquals(PushPath.Stream(0), PushPath.after(reply(2, offset = -1), size))
        assertEquals(PushPath.WatchDownloads, PushPath.after(reply(2, offset = 400, download = true), size))
    }

    @Test
    fun `the watch info says whether the watch speaks v2`() {
        val v1 = WatchInfo(versionCode = 9, versionName = "0.4.2", sdkInt = 36, capabilities = listOf(WearLink.WATCH_CAN_UPDATE))
        assertTrue(v1.canUpdate)
        assertFalse(v1.canResume)
        val v2 = v1.copy(versionCode = 10, capabilities = listOf(WearLink.WATCH_CAN_UPDATE, WearLink.WATCH_CAN_RESUME))
        assertTrue(v2.canResume)
    }

    @Test
    fun `status requests and answers round-trip, and a later state still decodes`() {
        val request = ApkStatusRequest(id = "o")
        assertEquals(request, WatchUpdateCodec.decodeStatusRequest(WatchUpdateCodec.encodeStatusRequest(request)))
        val status = ApkStatus(id = "o", state = ApkStatusState.DOWNLOADING, route = ApkRoute.WIFI, received = 1_000, size = 6_000, bytesPerSecond = 2_000_000)
        assertEquals(status, WatchUpdateCodec.decodeStatus(WatchUpdateCodec.encodeStatus(status)))
        val later = WatchUpdateCodec.decodeStatus("""{"v":1,"id":"o","state":"from_the_future","extra":true}""".toByteArray())
        assertEquals("from_the_future", later.state)
        assertEquals(0, later.received)
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
    fun `a resumed transfer sends only the bytes after the offset, and the whole file's SHA-256 is checked`() {
        val apk = Random(11).nextBytes(150_000)
        val offset = 70_000
        val header = header(apk).copy(offset = offset.toLong())
        val progress = mutableListOf<Long>()
        val stream = ByteArrayOutputStream().also { out ->
            ApkTransferCodec.send(out, header, ByteArrayInputStream(apk, offset, apk.size - offset), 64 * 1024, onProgress = { progress += it })
        }.toByteArray()
        assertEquals(listOf(70_000L + 65_536, 150_000L), progress)
        val input = ByteArrayInputStream(stream)
        val read = ApkTransferCodec.readHeader(input)
        assertEquals(offset.toLong(), read.offset)
        // The receiver's digest has seen the bytes it already held.
        val digest = MessageDigest.getInstance("SHA-256").apply { update(apk, 0, offset) }
        val sink = ByteArrayOutputStream()
        val received = mutableListOf<Long>()
        ApkTransferCodec.readBody(input, read, sink, digest) { received += it }
        assertArrayEquals(apk.copyOfRange(offset, apk.size), sink.toByteArray())
        assertEquals(progress, received)
    }

    @Test
    fun `a resumed transfer over a wrong prefix fails the SHA-256`() {
        val apk = Random(12).nextBytes(10_000)
        val header = header(apk).copy(offset = 4_000)
        val stream = ByteArrayOutputStream().also { ApkTransferCodec.send(it, header, ByteArrayInputStream(apk, 4_000, 6_000)) }.toByteArray()
        val input = ByteArrayInputStream(stream)
        val wrong = apk.copyOf(4_000).also { it[17] = (it[17].toInt() xor 1).toByte() }
        assertCode(UpdateErrorCode.SHA256_MISMATCH) {
            ApkTransferCodec.readBody(input, ApkTransferCodec.readHeader(input), ByteArrayOutputStream(), MessageDigest.getInstance("SHA-256").apply { update(wrong) })
        }
    }

    @Test
    fun `an offset at the end sends just the end mark, one outside the file is refused`() {
        val apk = Random(13).nextBytes(1_000)
        val atEnd = ByteArrayOutputStream().also { ApkTransferCodec.send(it, header(apk).copy(offset = 1_000), ByteArrayInputStream(ByteArray(0))) }.toByteArray()
        val input = ByteArrayInputStream(atEnd)
        val sink = ByteArrayOutputStream()
        ApkTransferCodec.readBody(input, ApkTransferCodec.readHeader(input), sink, MessageDigest.getInstance("SHA-256").apply { update(apk) })
        assertEquals(0, sink.size())
        val outside = ByteArrayOutputStream().apply {
            val json = WatchUpdateCodec.encode(ApkTransferHeader.serializer(), header(apk).copy(offset = 1_001))
            DataOutputStream(this).apply { writeInt(json.size); write(json) }
        }.toByteArray()
        assertCode(UpdateErrorCode.BAD_REQUEST) { receive(outside) }
        try {
            ApkTransferCodec.send(ByteArrayOutputStream(), header(apk).copy(offset = -1), ByteArrayInputStream(apk))
            fail("a negative offset is the phone's bug")
        } catch (_: IllegalArgumentException) {
        }
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
