package com.gamch1k.lifeos.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * The watch's partly received APK (transfer v2, docs/wear.md "Install and
 * updates"): the bytes of one APK, named by its SHA-256 and size, kept across
 * a broken stream or download so that the next attempt resumes instead of
 * starting over. The phone's stream and the watch's own download write the
 * same file, so either can go on where the other stopped.
 *
 * Next to the bytes, a small record says whose they are and how many are
 * durable: written, flushed and synced ([Writer.checkpoint]). Only those
 * count, and a resumed file is only kept if the SHA-256 of the whole file is
 * the announced one at the end; otherwise it is deleted and the next attempt
 * starts from byte 0.
 */
class PartialApk(private val dir: File) {
    private val data = File(dir, "incoming.part")
    private val record = File(dir, "incoming.json")

    @Serializable
    private data class Record(val sha256: String, val size: Long, val length: Long)

    /**
     * The durable bytes held of the APK [sha256] of [size] bytes: what an
     * offer's reply says, and where a stream or download resumes. 0, with
     * anything else deleted, when the files belong to another APK or don't
     * agree with each other. A complete file counts only if its SHA-256 is
     * right.
     */
    @Synchronized
    fun offset(sha256: String, size: Long): Long {
        val held = read()
        if (held == null || held.sha256 != sha256 || held.size != size || held.length !in 0..size || data.length() < held.length) {
            if (held != null || data.exists()) discard()
            return 0
        }
        if (held.length == size && sha256(data, size) != sha256) {
            discard()
            return 0
        }
        return held.length
    }

    /**
     * Opens the file for the bytes from [offset] on (anything after it is
     * dropped); [offset] must not exceed what [offset] says is held of this
     * APK. The writer's digest has already seen the bytes before [offset].
     */
    @Synchronized
    fun open(sha256: String, size: Long, offset: Long): Writer {
        require(offset in 0..size) { "offset $offset outside the $size bytes" }
        if (offset > 0) require(offset <= offset(sha256, size)) { "only ${offset(sha256, size)} bytes are held, not $offset" }
        dir.mkdirs()
        if (offset == 0L) data.delete()
        RandomAccessFile(data, "rw").use { it.setLength(offset) }
        val digest = MessageDigest.getInstance("SHA-256")
        if (offset > 0) hashInto(digest, data, offset)
        writeRecord(Record(sha256, size, offset))
        return Writer(sha256, size, offset, digest)
    }

    /** Deletes the bytes and the record. */
    @Synchronized
    fun discard() {
        data.delete()
        record.delete()
    }

    /** Moves the complete file to [target] (its SHA-256 is checked by the caller); false when that fails. */
    @Synchronized
    fun complete(target: File): Boolean {
        target.delete()
        val moved = data.renameTo(target)
        record.delete()
        if (!moved) data.delete()
        return moved
    }

    /**
     * Appends to the partial file. [checkpoint] makes what was written count
     * (flush, sync, record); [close] checkpoints too, so a broken transfer
     * keeps every byte that arrived.
     */
    inner class Writer internal constructor(
        private val sha256: String,
        private val size: Long,
        start: Long,
        /**
         * Has seen the bytes before the start. Whoever writes updates it with
         * what it writes ([com.gamch1k.lifeos.core.wearlink.ApkTransferCodec.readBody]
         * does), and finishes it.
         */
        val digest: MessageDigest,
    ) : OutputStream() {
        private val file = FileOutputStream(data, true)
        private val out = BufferedOutputStream(file, 64 * 1024)
        private var closed = false

        /** The bytes in the file, durable or not yet. */
        var length: Long = start
            private set

        @Synchronized
        override fun write(b: Int) {
            if (closed) throw java.io.IOException("closed")
            out.write(b)
            length++
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw java.io.IOException("closed")
            out.write(b, off, len)
            length += len
        }

        @Synchronized
        override fun flush() = out.flush()

        /** Makes every byte written so far count: flushed, synced, and recorded. */
        @Synchronized
        fun checkpoint() {
            if (closed) return
            out.flush()
            file.fd.sync()
            synchronized(this@PartialApk) { writeRecord(Record(sha256, size, length)) }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            try {
                checkpoint()
            } finally {
                closed = true
                runCatching { out.close() }
            }
        }

        /** Closes without recording more (the bytes are about to be deleted). */
        @Synchronized
        fun abandon() {
            closed = true
            runCatching { out.close() }
        }
    }

    private fun read(): Record? = runCatching { json.decodeFromString(Record.serializer(), record.readText()) }.getOrNull()

    /** Written to a temporary file and renamed, so a cut never leaves half a record. */
    private fun writeRecord(value: Record) {
        dir.mkdirs()
        val temp = File(dir, "incoming.json.tmp")
        FileOutputStream(temp).use { out ->
            out.write(json.encodeToString(Record.serializer(), value).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!temp.renameTo(record)) {
            temp.delete()
            record.delete()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun hashInto(digest: MessageDigest, file: File, length: Long) {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    left -= count
                }
            }
        }

        fun sha256(file: File, length: Long): String {
            val digest = MessageDigest.getInstance("SHA-256")
            hashInto(digest, file, length)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
