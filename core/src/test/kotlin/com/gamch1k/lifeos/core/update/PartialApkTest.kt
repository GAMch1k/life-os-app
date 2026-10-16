package com.gamch1k.lifeos.core.update

import com.gamch1k.lifeos.core.wearlink.ApkTransferCodec
import com.gamch1k.lifeos.core.wearlink.ApkTransferHeader
import com.gamch1k.lifeos.core.wearlink.UpdateErrorCode
import com.gamch1k.lifeos.core.wearlink.UpdateProtocolException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random

/** The watch's partly received APK (transfer v2): what counts as held, resuming, and damaged files going back to byte 0. */
class PartialApkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val apk = Random(43).nextBytes(300_000)
    private val sha = sha256(apk)
    private val size = apk.size.toLong()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun hex(digest: MessageDigest) = digest.digest().joinToString("") { "%02x".format(it) }

    private fun partial() = PartialApk(File(folder.root, "updates-partial"))

    /** Writes apk[from until to] through a writer opened at [from], updating its digest like the stream does. */
    private fun write(partial: PartialApk, from: Int, to: Int, close: Boolean = true): PartialApk.Writer {
        val writer = partial.open(sha, size, from.toLong())
        writer.digest.update(apk, from, to - from)
        writer.write(apk, from, to - from)
        if (close) writer.close()
        return writer
    }

    @Test
    fun `nothing is held at first`() {
        assertEquals(0, partial().offset(sha, size))
    }

    @Test
    fun `the bytes written and closed are held, and a resume goes on from there to the right SHA-256`() {
        val partial = partial()
        write(partial, 0, 100_000)
        assertEquals(100_000, partial.offset(sha, size))
        // The resumed writer's digest has already seen the first 100,000 bytes.
        val writer = write(partial, 100_000, apk.size, close = false)
        writer.close()
        assertEquals(sha, hex(writer.digest))
        assertEquals(size, partial.offset(sha, size))
        val target = File(folder.root, "lifeos-wear-10.apk")
        assertTrue(partial.complete(target))
        assertArrayEquals(apk, target.readBytes())
        assertEquals("the partial file is gone once taken", 0, partial.offset(sha, size))
    }

    @Test
    fun `only checkpointed bytes count after a crash, and what follows them is cut`() {
        val partial = partial()
        val writer = partial.open(sha, size, 0)
        writer.write(apk, 0, 64_000)
        writer.checkpoint()
        writer.write(apk, 64_000, 50_000)
        writer.flush()
        // The process dies here: no close. A new PartialApk (the next start) reads the record.
        val restarted = partial()
        assertEquals(64_000, restarted.offset(sha, size))
        val resumed = write(restarted, 64_000, apk.size, close = false)
        resumed.close()
        assertEquals(sha, hex(resumed.digest))
        val target = File(folder.root, "out.apk")
        assertTrue(restarted.complete(target))
        assertArrayEquals(apk, target.readBytes())
    }

    @Test
    fun `the bytes of another APK, or of another size, are discarded`() {
        val partial = partial()
        write(partial, 0, 100_000)
        assertEquals(0, partial.offset("b".repeat(64), size))
        assertEquals("the other APK's offer dropped them", 0, partial.offset(sha, size))
        write(partial, 0, 100_000)
        assertEquals(0, partial.offset(sha, size + 1))
        assertEquals(0, partial.offset(sha, size))
    }

    @Test
    fun `a complete file with the wrong SHA-256 goes back to byte 0`() {
        val partial = partial()
        write(partial, 0, apk.size)
        assertEquals(size, partial.offset(sha, size))
        flipByte(File(folder.root, "updates-partial/incoming.part"), 200_000)
        assertEquals(0, partial.offset(sha, size))
        assertFalse(File(folder.root, "updates-partial/incoming.part").exists())
    }

    @Test
    fun `a damaged prefix is caught by the whole file's SHA-256 at the end of the resumed stream, then dropped`() {
        val partial = partial()
        write(partial, 0, 150_000)
        flipByte(File(folder.root, "updates-partial/incoming.part"), 1_000)
        // Not detectable before the end: the record still says 150,000 bytes.
        val held = partial.offset(sha, size)
        assertEquals(150_000, held)
        val header = ApkTransferHeader(id = "o", versionCode = 10, size = size, sha256 = sha, offset = held)
        val stream = ByteArrayOutputStream().also { out ->
            ApkTransferCodec.send(out, header, ByteArrayInputStream(apk, held.toInt(), apk.size - held.toInt()))
        }.toByteArray()
        val input = ByteArrayInputStream(stream)
        val writer = partial.open(sha, size, ApkTransferCodec.readHeader(input).offset)
        try {
            ApkTransferCodec.readBody(input, header, writer, writer.digest)
            fail("the damaged file must not pass")
        } catch (e: UpdateProtocolException) {
            assertEquals(UpdateErrorCode.SHA256_MISMATCH, e.code)
        }
        // What the watch does with a wrong SHA-256: drop it all; the next offer says 0.
        writer.abandon()
        partial.discard()
        assertEquals(0, partial.offset(sha, size))
    }

    @Test
    fun `a missing or unreadable record, or a file shorter than its record, holds nothing`() {
        val partial = partial()
        write(partial, 0, 100_000)
        File(folder.root, "updates-partial/incoming.json").writeText("{not json")
        assertEquals(0, partial.offset(sha, size))

        write(partial, 0, 100_000)
        RandomAccessFile(File(folder.root, "updates-partial/incoming.part"), "rw").use { it.setLength(50_000) }
        assertEquals(0, partial.offset(sha, size))

        write(partial, 0, 100_000)
        File(folder.root, "updates-partial/incoming.json").delete()
        assertEquals(0, partial.offset(sha, size))
        assertFalse("the orphaned bytes are deleted", File(folder.root, "updates-partial/incoming.part").exists())
    }

    @Test
    fun `opening at an offset beyond what is held is refused`() {
        val partial = partial()
        write(partial, 0, 10_000)
        try {
            partial.open(sha, size, 20_000)
            fail("only 10,000 bytes are held")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `opening at an earlier offset drops the bytes after it, at 0 everything`() {
        val partial = partial()
        write(partial, 0, 100_000)
        partial.open(sha, size, 40_000).close()
        assertEquals(40_000, partial.offset(sha, size))
        partial.open(sha, size, 0).close()
        assertEquals(0, partial.offset(sha, size))
    }

    private fun flipByte(file: File, at: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(at)
            val b = raf.read()
            raf.seek(at)
            raf.write(b xor 0xff)
        }
    }
}
