package com.gamch1k.lifeos.wear.update

import com.gamch1k.lifeos.core.config.LifeOsEndpoints
import com.gamch1k.lifeos.core.update.GitHubReleases
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
     * and ends the request. A redirect ends the request too: [sink] gets its
     * 3xx status and headers, `location` among them, and its answer is not
     * used. [onProgress] gets the body bytes read so far. Returns the status.
     * Throws [IOException] when no answer came, the body broke off, or no
     * byte arrived for [idleMillis].
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
 * APK over that network from where the phone found it:
 *
 * - a path under `/android/`: from the update origin ([updatesUrl], never the
 *   owner's server; the watch's token goes along only when the two are the
 *   same origin), redirects never followed;
 * - an asset of a GitHub release of [updatesRepo] (P20-06): from GitHub,
 *   without any token, following redirects only to GitHub's own hosts
 *   ([GitHubReleases.mayRedirectTo]).
 *
 * Any other address is not tried. The bytes go into the same [PartialApk]
 * the phone's stream uses, so a download that breaks off is continued by the
 * stream, and a download resumes what an earlier attempt left (an HTTP range
 * request). The announced size and SHA-256 are checked either way.
 *
 * Every outcome but [Outcome.Downloaded] sends the watch back to the phone's
 * stream; the network request is always given back.
 */
class DirectDownload(
    private val networks: WifiNetworks,
    private val http: ApkHttp,
    private val userAgent: String,
    /** Where app updates come from (`lifeos.updatesUrl`) for `/android/` paths, whatever server the watch uses. */
    private val updatesUrl: String = LifeOsEndpoints.UPDATES_URL,
    /** The GitHub repository whose release assets the watch may download (`lifeos.updatesRepo`). */
    private val updatesRepo: String = LifeOsEndpoints.UPDATES_REPO,
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

    /** Where the APK is fetched: [url], with [token]'s headers or none, following GitHub's redirects or none. */
    private data class Target(val url: String, val token: WearCredentials?, val github: Boolean)

    /** The address to download [url] from, or null when the watch doesn't try it (see the class). */
    private fun target(url: String?, credentials: WearCredentials?): Target? = when {
        url == null -> null
        UpdatePolicy.isApkPath(ReleaseApp.WEAR, url) ->
            credentials?.takeIf { it.isComplete }?.let { Target(updatesUrl.trimEnd('/') + url, it, github = false) }
        GitHubReleases.isApkUrl(updatesRepo, ReleaseApp.WEAR, url) -> Target(url, token = null, github = true)
        else -> null
    }

    /**
     * Downloads [url] (a path such as `/android/lifeos-wear-0.4.3-10.apk` on
     * the update origin, or a GitHub release asset of [updatesRepo]) into
     * [partial], for the APK [sha256] of [size] bytes. [onStage] gets each
     * stage, with the byte the download starts at; [onProgress] the position
     * in the APK.
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
        val target = target(url, credentials) ?: return Outcome.Skipped(
            when {
                url != null && UpdatePolicy.isApkPath(ReleaseApp.WEAR, url) -> "no token on the watch"
                else -> "no download address"
            },
        )
        onStage(Stage.WAITING_FOR_WIFI, 0)
        val network = networks.acquire(wifiTimeoutMillis)
            ?: return Outcome.NoWifi("no Wi-Fi within ${wifiTimeoutMillis / 1000} s (is the watch's Wi-Fi on Auto, with a saved network in reach?)")
        return network.use { download(it, target, sha256, size, partial, onStage, onProgress) }
    }

    private suspend fun download(
        network: BoundNetwork,
        target: Target,
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
            // A token belongs to its server: the update origin gets it only when that is the server; GitHub never.
            val token = target.token
            if (token != null && LifeOsEndpoints.sameOrigin(token.baseUrl, updatesUrl)) {
                put("CF-Access-Client-Id", token.clientId)
                put("CF-Access-Client-Secret", token.clientSecret)
            }
            put("User-Agent", userAgent)
            put("Accept", "*/*")
            if (held > 0) put("Range", "bytes=$held-")
        }
        var writer: PartialApk.Writer? = null
        var base = 0L
        var tooLong = false
        var lastCheckpoint = 0L
        var address = target.url
        var redirects = 0
        var refusedRedirect: String? = null
        val status = try {
            withTimeoutOrNull(totalMillis) {
                var answer = 0
                while (true) {
                    var location: String? = null
                    answer = http.get(
                        address,
                        headers,
                        network,
                        idleMillis,
                        sink = { status, responseHeaders ->
                            if (status in 300..399) {
                                location = responseHeaders["location"]?.firstOrNull()
                                return@get null
                            }
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
                    // Only a GitHub download follows a redirect, and only to GitHub's own hosts.
                    if (!target.github || answer !in 300..399) break
                    val next = location?.let { GitHubReleases.resolve(address, it) }
                    if (next == null || !GitHubReleases.mayRedirectTo(updatesRepo, next) || ++redirects > GitHubReleases.MAX_REDIRECTS) {
                        refusedRedirect = GitHubReleases.host(next)
                        break
                    }
                    address = next
                }
                answer
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
        refusedRedirect?.let { host -> return Outcome.Failed("GitHub sent the download to $host, which is not GitHub's download of $updatesRepo") }
        if (written == null) return Outcome.Failed(describe(status, target.github))
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
         * holds [held]: only while bytes are missing, and only with a wear APK's address from the
         * phone, a path under `/android/` with a complete token, or an asset of a GitHub release of
         * [repo] (no token needed). Otherwise the phone streams it.
         */
        fun worthTrying(url: String?, credentials: WearCredentials?, held: Long, size: Long, repo: String = LifeOsEndpoints.UPDATES_REPO): Boolean =
            held < size && url != null && (
                (UpdatePolicy.isApkPath(ReleaseApp.WEAR, url) && credentials?.isComplete == true) ||
                    GitHubReleases.isApkUrl(repo, ReleaseApp.WEAR, url)
                )

        /** The first byte of `Content-Range: bytes 123-456/789`, or null. */
        fun rangeStart(headers: Map<String, List<String>>): Long? =
            headers["content-range"]?.firstOrNull()?.trim()?.let { Regex("^bytes (\\d+)-\\d+/(\\d+|\\*)$").find(it) }?.groupValues?.get(1)?.toLongOrNull()

        private fun describe(status: Int, github: Boolean): String = if (github) {
            when (status) {
                404 -> "the APK is not on GitHub (404)"
                416 -> "GitHub refused the range (416)"
                else -> "GitHub answered $status"
            }
        } else {
            when (status) {
                in 300..399 -> "Life OS answered $status (Cloudflare Access did not let the watch's token in)"
                401, 403 -> "Life OS refused the watch's token ($status)"
                404 -> "the APK is not on Life OS (404)"
                416 -> "Life OS refused the range (416)"
                else -> "Life OS answered $status"
            }
        }
    }
}
