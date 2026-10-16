package com.gamch1k.lifeos.wear.update

import com.gamch1k.lifeos.core.update.PartialApk
import com.gamch1k.lifeos.core.update.ReleaseApp
import com.gamch1k.lifeos.core.update.UpdatePolicy
import com.gamch1k.lifeos.core.wearlink.WearCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream

/** A network the watch asked Android for; [close] gives the request back. */
interface BoundNetwork : AutoCloseable {
    /** `Network.getNetworkHandle()`, what Cronet's `bindToNetwork` takes. */
    val handle: Long

    override fun close()
}

/** Brings up a high-bandwidth network for the watch app: Wi-Fi with internet. */
fun interface WifiNetworks {
    /** A Wi-Fi network with internet within [timeoutMillis], or null. */
    suspend fun acquire(timeoutMillis: Long): BoundNetwork?
}

/** One HTTP GET bound to a network: Cronet on the watch ([CronetApkHttp]), a fake in tests. */
interface ApkHttp {
    /**
     * GETs [url] with [headers] over [network], without following redirects.
     * [sink] gets the status and the headers (names in lower case) once they
     * are known and says where the body goes; null means "not this answer"
     * and ends the request. [onProgress] gets the body bytes read so far.
     * Returns the status. Throws [IOException] when no answer came, the body
     * broke off, or no byte arrived for [idleMillis].
     */
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        network: BoundNetwork,
        idleMillis: Long,
        sink: (status: Int, headers: Map<String, List<String>>) -> OutputStream?,
        onProgress: (Long) -> Unit,
    ): Int
}

/**
 * The watch's own download of an offered APK (transfer v2, docs/wear.md
 * "Install and updates"): ask Android for Wi-Fi (Wear OS brings it up for an
 * app that asks, when the watch's Wi-Fi setting allows it), and download the
 * APK from Life OS over that network with the watch's own token. The bytes go
 * into the same [PartialApk] the phone's stream uses, so a download that
 * breaks off is continued by the stream, and a download resumes what an
 * earlier attempt left (an HTTP range request).
 *
 * Every outcome but [Outcome.Downloaded] sends the watch back to the phone's
 * stream; the network request is always given back.
 */
class DirectDownload(
    private val networks: WifiNetworks,
    private val http: ApkHttp,
    private val userAgent: String,
    /** How long Wi-Fi may take to come up; the phone's stream is the fallback. */
    private val wifiTimeoutMillis: Long = WIFI_TIMEOUT_MILLIS,
    private val idleMillis: Long = 30_000,
    private val totalMillis: Long = 10 * 60_000,
) {
    sealed interface Outcome {
        /** The whole APK is in the [PartialApk], with the announced size and SHA-256. */
        data object Downloaded : Outcome

        /** Not tried: no usable address, or no token on the watch. */
        data class Skipped(val why: String) : Outcome

        /** No Wi-Fi network came up in time. */
        data class NoWifi(val why: String) : Outcome

        /** Tried and failed; what arrived intact stays for the stream. */
        data class Failed(val why: String) : Outcome
    }

    enum class Stage { WAITING_FOR_WIFI, DOWNLOADING }

    /**
     * Downloads [url] (a path such as `/android/lifeos-wear-0.4.3-10.apk`)
     * from [credentials]' Life OS into [partial], for the APK [sha256] of
     * [size] bytes. [onStage] gets each stage, with the byte the download
     * starts at; [onProgress] the position in the APK.
     */
    suspend fun run(
        url: String?,
        credentials: WearCredentials?,
        sha256: String,
        size: Long,
        partial: PartialApk,
        onStage: (Stage, Long) -> Unit,
        onProgress: (Long) -> Unit,
    ): Outcome {
        if (url == null || !UpdatePolicy.isApkPath(ReleaseApp.WEAR, url)) return Outcome.Skipped("no download address")
        val token = credentials?.takeIf { it.isComplete } ?: return Outcome.Skipped("no token on the watch")
        onStage(Stage.WAITING_FOR_WIFI, 0)
        val network = networks.acquire(wifiTimeoutMillis)
            ?: return Outcome.NoWifi("no Wi-Fi within ${wifiTimeoutMillis / 1000} s (is the watch's Wi-Fi on Auto, with a saved network in reach?)")
        return network.use { download(it, token, url, sha256, size, partial, onStage, onProgress) }
    }

    private suspend fun download(
        network: BoundNetwork,
        token: WearCredentials,
        url: String,
        sha256: String,
        size: Long,
        partial: PartialApk,
        onStage: (Stage, Long) -> Unit,
        onProgress: (Long) -> Unit,
    ): Outcome {
        val held = partial.offset(sha256, size)
        if (held == size) return Outcome.Downloaded
        onStage(Stage.DOWNLOADING, held)
        val headers = buildMap {
            put("CF-Access-Client-Id", token.clientId)
            put("CF-Access-Client-Secret", token.clientSecret)
            put("User-Agent", userAgent)
            put("Accept", "*/*")
            if (held > 0) put("Range", "bytes=$held-")
        }
        var writer: PartialApk.Writer? = null
        var base = 0L
        var tooLong = false
        var lastCheckpoint = 0L
        val status = try {
            withTimeoutOrNull(totalMillis) {
                http.get(
                    token.baseUrl.trimEnd('/') + url,
                    headers,
                    network,
                    idleMillis,
                    sink = { status, responseHeaders ->
                        val from = when {
                            status == 206 && held > 0 && rangeStart(responseHeaders) == held -> held
                            status == 200 -> 0L
                            else -> null
                        }
                        from?.let { start ->
                            base = start
                            lastCheckpoint = start
                            partial.open(sha256, size, start).also { writer = it }.let { opened ->
                                // More than announced is cut off at once, like the phone's stream.
                                object : OutputStream() {
                                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                                    override fun write(b: ByteArray, off: Int, len: Int) {
                                        if (opened.length + len > size) {
                                            tooLong = true
                                            throw IOException("more than the announced $size bytes")
                                        }
                                        opened.digest.update(b, off, len)
                                        opened.write(b, off, len)
                                    }
                                    override fun flush() = opened.flush()
                                    override fun close() = Unit
                                }
                            }
                        }
                    },
                    onProgress = { read ->
                        val position = base + read
                        if (position - lastCheckpoint >= CHECKPOINT_BYTES) {
                            writer?.checkpoint()
                            lastCheckpoint = position
                        }
                        onProgress(position)
                    },
                )
            }
        } catch (e: CancellationException) {
            writer?.close()
            throw e
        } catch (e: IOException) {
            if (tooLong) {
                writer?.abandon()
                partial.discard()
                return Outcome.Failed("Life OS sent more than the announced $size bytes")
            }
            writer?.close()
            return Outcome.Failed("the download broke off (${e.message ?: e.javaClass.simpleName})")
        }
        val written = writer
        if (status == null) {
            written?.close()
            return Outcome.Failed("the download took longer than ${totalMillis / 60_000} minutes")
        }
        if (written == null) return Outcome.Failed(describe(status))
        written.close()
        if (written.length != size) return Outcome.Failed("the download ended after ${written.length} of $size bytes")
        val actual = written.digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != sha256) {
            // Damaged (a wrong earlier part, or a wrong file): the next attempt starts from byte 0.
            partial.discard()
            return Outcome.Failed("the downloaded file's SHA-256 is not the announced one")
        }
        return Outcome.Downloaded
    }

    companion object {
        const val WIFI_TIMEOUT_MILLIS = 45_000L
        private const val CHECKPOINT_BYTES = 256L * 1024

        /**
         * Whether the watch tries its own download of an offered APK of [size] bytes, of which it
         * holds [held]: only with a wear APK's address from the phone and a complete token, and
         * only while bytes are missing. Otherwise the phone streams it.
         */
        fun worthTrying(url: String?, credentials: WearCredentials?, held: Long, size: Long): Boolean =
            held < size && url != null && UpdatePolicy.isApkPath(ReleaseApp.WEAR, url) && credentials?.isComplete == true

        /** The first byte of `Content-Range: bytes 123-456/789`, or null. */
        fun rangeStart(headers: Map<String, List<String>>): Long? =
            headers["content-range"]?.firstOrNull()?.trim()?.let { Regex("^bytes (\\d+)-\\d+/(\\d+|\\*)$").find(it) }?.groupValues?.get(1)?.toLongOrNull()

        private fun describe(status: Int): String = when (status) {
            in 300..399 -> "Life OS answered $status (Cloudflare Access did not let the watch's token in)"
            401, 403 -> "Life OS refused the watch's token ($status)"
            404 -> "the APK is not on Life OS (404)"
            416 -> "Life OS refused the range (416)"
            else -> "Life OS answered $status"
        }
    }
}
