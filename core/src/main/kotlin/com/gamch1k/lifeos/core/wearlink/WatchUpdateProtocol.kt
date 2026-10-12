package com.gamch1k.lifeos.core.wearlink

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

// ---- Watch app updates through the phone (P19-06, docs/wear.md "Install and updates")
//
// Every message carries `v` (WearLink.PROTOCOL_VERSION) like the relay's;
// unknown JSON keys are ignored, so later versions can add fields. Error
// codes are strings (UpdateErrorCode), so a code this version doesn't know
// still decodes.

/** Phone → watch: "which version are you, and can you take an update?" */
@Serializable
data class WatchInfoRequest(
    val v: Int = WearLink.PROTOCOL_VERSION,
    val id: String,
)

/** The watch app's version and what it can do; the reply to a [WatchInfoRequest], or a report of its own. */
@Serializable
data class WatchInfo(
    val v: Int = WearLink.PROTOCOL_VERSION,
    /** The request's id; empty in a report the watch sends by itself ([WearLink.PATH_WATCH_REPORT]). */
    val id: String = "",
    val versionCode: Long,
    val versionName: String,
    /** The watch's API level (Wear OS 6 is 36). */
    val sdkInt: Int,
    /** `Build.SUPPORTED_ABIS`, preferred first. */
    val abis: List<String> = emptyList(),
    /** Such as [WearLink.WATCH_CAN_UPDATE]. */
    val capabilities: List<String> = emptyList(),
    /** "Install unknown apps" is allowed for Life OS on the watch (granted once over ADB). */
    val canInstallPackages: Boolean = false,
    val battery: WatchBattery? = null,
    /** The latest install the watch tried, and how it ended. */
    val lastUpdate: WatchUpdateResult? = null,
) {
    val canUpdate: Boolean get() = WearLink.WATCH_CAN_UPDATE in capabilities
}

@Serializable
data class WatchBattery(val percent: Int, val charging: Boolean)

/** How an update on the watch ended (or that it waits). [at] is an ISO 8601 instant. */
@Serializable
data class WatchUpdateResult(
    val versionCode: Long,
    val versionName: String = "",
    /** One of [WatchUpdateStatus]. */
    val status: String,
    val message: String = "",
    val at: String,
)

object WatchUpdateStatus {
    /** The new version runs. */
    const val INSTALLED = "installed"

    /** PackageInstaller is at work (the watch restarts the app when it is done). */
    const val INSTALLING = "installing"

    /** Android asks the owner to confirm on the watch (a notification, or Life OS's Home). */
    const val WAITING_FOR_CONFIRMATION = "waiting_for_confirmation"

    /** The watch refused the file before installing it (size, SHA-256, package, certificate, version). */
    const val REFUSED = "refused"

    /** PackageInstaller refused or failed it; [WatchUpdateResult.message] says why. */
    const val FAILED = "failed"
}

/** Phone → watch: "I have this APK for you". The size and SHA-256 come before a single byte. */
@Serializable
data class ApkOffer(
    val v: Int = WearLink.PROTOCOL_VERSION,
    val id: String,
    val versionCode: Long,
    val versionName: String,
    val size: Long,
    /** 64 lowercase hex digits. */
    val sha256: String,
)

/** Watch → phone: take it (then open a channel on [WearLink.PATH_APK_TRANSFER]), or why not. */
@Serializable
data class ApkOfferReply(
    val v: Int = WearLink.PROTOCOL_VERSION,
    val id: String,
    val accepted: Boolean,
    val error: UpdateError? = null,
    /** With [UpdateErrorCode.THROTTLED] or [UpdateErrorCode.BUSY]: when to ask again. */
    val retryAfterSeconds: Long? = null,
)

@Serializable
data class UpdateError(val code: String, val message: String = "")

/** The codes in [UpdateError.code]. */
object UpdateErrorCode {
    const val BAD_REQUEST = "bad_request"
    const val UNSUPPORTED_VERSION = "unsupported_version"

    /** The offered versionCode is not higher than the watch's. */
    const val NOT_NEWER = "not_newer"

    /** "Install unknown apps" is not allowed for Life OS on the watch: run the setup again. */
    const val NO_INSTALL_PERMISSION = "no_install_permission"

    /** The watch's battery is low and it is not charging. */
    const val LOW_BATTERY = "low_battery"

    /** Android lets an app update itself silently only once every 30 seconds. */
    const val THROTTLED = "throttled"

    /** Another transfer or install is running. */
    const val BUSY = "busy"

    /** The APK is larger than [WearLink.MAX_APK_BYTES]. */
    const val TOO_LARGE = "too_large"

    /** Not enough free space on the watch. */
    const val STORAGE = "storage"

    /** A transfer that matches no accepted offer. */
    const val UNKNOWN_OFFER = "unknown_offer"

    /** More or fewer bytes than announced arrived. */
    const val SIZE_MISMATCH = "size_mismatch"
    const val SHA256_MISMATCH = "sha256_mismatch"

    /** The sender gave up, or the stream ended inside the APK. */
    const val ABORTED = "aborted"

    const val INTERNAL = "internal"
}

/** A message or transfer that could not be read; [code] is an [UpdateErrorCode]. */
class UpdateProtocolException(val code: String, message: String) : Exception(message)

/** The JSON of the update messages, with the version check of the rest of the link. */
object WatchUpdateCodec {
    fun <T> encode(serializer: KSerializer<T>, message: T): ByteArray =
        RelayCodec.json.encodeToString(serializer, message).toByteArray(Charsets.UTF_8)

    /** Throws [UpdateProtocolException] for unreadable JSON or another protocol version. */
    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray, version: (T) -> Int): T {
        if (bytes.size > WearLink.MAX_MESSAGE_BYTES) throw UpdateProtocolException(UpdateErrorCode.BAD_REQUEST, "message of ${bytes.size} bytes")
        val message = try {
            RelayCodec.json.decodeFromString(serializer, bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw UpdateProtocolException(UpdateErrorCode.BAD_REQUEST, "unreadable message: ${e.message?.take(120)}")
        } catch (e: IllegalArgumentException) {
            throw UpdateProtocolException(UpdateErrorCode.BAD_REQUEST, "unreadable message: ${e.message?.take(120)}")
        }
        val v = version(message)
        if (v != WearLink.PROTOCOL_VERSION) {
            throw UpdateProtocolException(UpdateErrorCode.UNSUPPORTED_VERSION, "protocol version $v, this app speaks ${WearLink.PROTOCOL_VERSION}")
        }
        return message
    }

    fun encodeInfoRequest(request: WatchInfoRequest) = encode(WatchInfoRequest.serializer(), request)
    fun decodeInfoRequest(bytes: ByteArray) = decode(WatchInfoRequest.serializer(), bytes) { it.v }
    fun encodeInfo(info: WatchInfo) = encode(WatchInfo.serializer(), info)
    fun decodeInfo(bytes: ByteArray) = decode(WatchInfo.serializer(), bytes) { it.v }
    fun encodeOffer(offer: ApkOffer) = encode(ApkOffer.serializer(), offer)
    fun decodeOffer(bytes: ByteArray) = decode(ApkOffer.serializer(), bytes) { it.v }
    fun encodeOfferReply(reply: ApkOfferReply) = encode(ApkOfferReply.serializer(), reply)
    fun decodeOfferReply(bytes: ByteArray) = decode(ApkOfferReply.serializer(), bytes) { it.v }
}

/** Sent first on the transfer channel; must match the accepted [ApkOffer]. */
@Serializable
data class ApkTransferHeader(
    val v: Int = WearLink.PROTOCOL_VERSION,
    /** The accepted offer's id. */
    val id: String,
    val versionCode: Long,
    val size: Long,
    val sha256: String,
)

/** The watch's last word on the channel: the APK arrived intact (and is being installed), or why not. */
@Serializable
data class ApkTransferResult(
    val v: Int = WearLink.PROTOCOL_VERSION,
    val id: String,
    val received: Boolean,
    val error: UpdateError? = null,
)

/**
 * The transfer channel ([WearLink.PATH_APK_TRANSFER]):
 *
 * ```
 * u32 big-endian  header length      phone → watch
 * header          ApkTransferHeader (UTF-8 JSON)
 * chunks          i32 big-endian n, then n bytes (1 ≤ n ≤ MAX_CHUNK_BYTES)
 * end             i32 0              (or -1: the phone gives up)
 * u32 big-endian  result length      watch → phone
 * result          ApkTransferResult (UTF-8 JSON)
 * ```
 *
 * The chunks must add up to exactly `size` bytes with exactly `sha256`; the
 * receiver checks both as the bytes arrive, so a wrong or endless stream is
 * cut off early.
 */
object ApkTransferCodec {
    const val CHUNK_BYTES = 64 * 1024
    const val MAX_CHUNK_BYTES = 256 * 1024
    private const val END = 0
    private const val ABORT = -1
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    // ---- The phone's side

    /**
     * Writes [header] and [header.size] bytes of [apk] in chunks of
     * [chunkBytes], then the end mark. [onProgress] gets the bytes sent so far.
     * When [apk] ends early, or [cancelled] turns true, it writes the abort
     * mark and throws.
     */
    fun send(
        output: OutputStream,
        header: ApkTransferHeader,
        apk: InputStream,
        chunkBytes: Int = CHUNK_BYTES,
        cancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ) {
        require(chunkBytes in 1..MAX_CHUNK_BYTES) { "chunk size $chunkBytes" }
        writeJsonFrame(output, WatchUpdateCodec.encode(ApkTransferHeader.serializer(), header))
        val buffer = ByteArray(chunkBytes)
        var sent = 0L
        while (sent < header.size) {
            if (cancelled()) {
                abort(output)
                throw IOException("transfer cancelled after $sent bytes")
            }
            val wanted = minOf(chunkBytes.toLong(), header.size - sent).toInt()
            val count = apk.read(buffer, 0, wanted)
            if (count <= 0) {
                abort(output)
                throw IOException("the APK ended after $sent of ${header.size} bytes")
            }
            writeInt(output, count)
            output.write(buffer, 0, count)
            sent += count
            onProgress(sent)
        }
        writeInt(output, END)
        output.flush()
    }

    /** The phone gives up mid-transfer; the watch discards what it has. */
    fun abort(output: OutputStream) {
        writeInt(output, ABORT)
        output.flush()
    }

    fun readResult(input: InputStream): ApkTransferResult =
        WatchUpdateCodec.decode(ApkTransferResult.serializer(), readJsonFrame(input)) { it.v }

    // ---- The watch's side

    /** Reads and checks the header: version, size limits, a well-formed SHA-256. */
    fun readHeader(input: InputStream): ApkTransferHeader {
        val header = WatchUpdateCodec.decode(ApkTransferHeader.serializer(), readJsonFrame(input)) { it.v }
        if (header.size <= 0) throw UpdateProtocolException(UpdateErrorCode.BAD_REQUEST, "size ${header.size}")
        if (header.size > WearLink.MAX_APK_BYTES) throw UpdateProtocolException(UpdateErrorCode.TOO_LARGE, "${header.size} bytes, at most ${WearLink.MAX_APK_BYTES}")
        if (!SHA256.matches(header.sha256)) throw UpdateProtocolException(UpdateErrorCode.BAD_REQUEST, "the SHA-256 is not 64 lowercase hex digits")
        return header
    }

    /**
     * Reads the chunks into [sink], checking them against [header] as they
     * come: at most [header.size] bytes, exactly that many at the end mark,
     * and the announced SHA-256. Throws [UpdateProtocolException] otherwise;
     * the caller deletes what [sink] got.
     */
    fun readBody(input: InputStream, header: ApkTransferHeader, sink: OutputStream, onProgress: (Long) -> Unit = {}) {
        val data = DataInputStream(input)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)
        var received = 0L
        try {
            while (true) {
                val length = data.readInt()
                when {
                    length == END -> break
                    length == ABORT -> throw UpdateProtocolException(UpdateErrorCode.ABORTED, "the phone gave up after $received bytes")
                    length < 0 || length > MAX_CHUNK_BYTES -> throw UpdateProtocolException(UpdateErrorCode.BAD_REQUEST, "chunk length $length")
                    received + length > header.size ->
                        throw UpdateProtocolException(UpdateErrorCode.SIZE_MISMATCH, "more than the announced ${header.size} bytes")
                }
                var left = length
                while (left > 0) {
                    val count = data.read(buffer, 0, minOf(left, buffer.size))
                    if (count < 0) throw EOFException()
                    digest.update(buffer, 0, count)
                    sink.write(buffer, 0, count)
                    left -= count
                }
                received += length
                onProgress(received)
            }
        } catch (_: EOFException) {
            throw UpdateProtocolException(UpdateErrorCode.ABORTED, "the stream ended after $received of ${header.size} bytes")
        }
        if (received != header.size) {
            throw UpdateProtocolException(UpdateErrorCode.SIZE_MISMATCH, "$received bytes arrived, ${header.size} were announced")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != header.sha256) throw UpdateProtocolException(UpdateErrorCode.SHA256_MISMATCH, "the SHA-256 of what arrived is not the announced one")
        sink.flush()
    }

    fun writeResult(output: OutputStream, result: ApkTransferResult) {
        writeJsonFrame(output, WatchUpdateCodec.encode(ApkTransferResult.serializer(), result))
        output.flush()
    }

    // ---- Framing

    private fun writeJsonFrame(output: OutputStream, json: ByteArray) {
        require(json.size <= WearLink.MAX_HEADER_BYTES) { "frame header too large" }
        writeInt(output, json.size)
        output.write(json)
    }

    private fun readJsonFrame(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        try {
            val length = data.readInt()
            if (length <= 0 || length > WearLink.MAX_HEADER_BYTES) throw UpdateProtocolException(UpdateErrorCode.BAD_REQUEST, "header length $length out of range")
            return ByteArray(length).also { data.readFully(it) }
        } catch (_: EOFException) {
            throw UpdateProtocolException(UpdateErrorCode.ABORTED, "the stream ended inside a header")
        }
    }

    private fun writeInt(output: OutputStream, value: Int) {
        output.write(ByteBuffer.allocate(4).putInt(value).array())
    }
}
