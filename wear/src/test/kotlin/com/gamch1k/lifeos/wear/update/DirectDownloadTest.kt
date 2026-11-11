package com.gamch1k.lifeos.wear.update

import com.gamch1k.lifeos.core.config.LifeOsEndpoints
import com.gamch1k.lifeos.core.update.PartialApk
import com.gamch1k.lifeos.core.wearlink.WearCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

/** The watch's own download over Wi-Fi (transfer v2): when it is tried, the network request, ranges, and every way back to the phone. */
class DirectDownloadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val apk = Random(10).nextBytes(400_000)
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val size = apk.size.toLong()
    private val url = "/android/lifeos-wear-0.4.3-10.apk"
    private val token = WearCredentials("https://lifeos.example.com/", "id.access", "secret")

    private fun partial() = PartialApk(File(folder.root, "updates-partial"))

    /** Android's Wi-Fi as the watch sees it: a network within the timeout, or none. */
    private class FakeNetworks(var available: Boolean = true) : WifiNetworks {
        val asked = mutableListOf<Long>()
        var open = 0
        var closed = 0

        override suspend fun acquire(timeoutMillis: Long): BoundNetwork? {
            asked += timeoutMillis
            if (!available) return null
            open++
            return object : BoundNetwork {
                override val handle = 42L
                override fun close() {
                    closed++
                }
            }
        }
    }

    /** Life OS behind Access: serves [body] (honouring ranges unless told not to), or a status. */
    private inner class FakeHttp : ApkHttp {
        var status: Int? = null
        var honourRange = true
        var body: ByteArray = apk

        /** Breaks the connection after this many body bytes. */
        var breakAfter: Int? = null

        /** Addresses that answer with a redirect to another, as GitHub's release downloads do. */
        val redirects = mutableMapOf<String, String>()
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val networks = mutableListOf<Long>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
            network: BoundNetwork,
            idleMillis: Long,
            sink: (status: Int, headers: Map<String, List<String>>) -> OutputStream?,
            onProgress: (Long) -> Unit,
        ): Int {
            requests += url to headers
            networks += network.handle
            redirects[url]?.let { location ->
                // As CronetApkHttp reports a redirect: its status and where it leads, and no body.
                sink(302, mapOf("location" to listOf(location)))
                return 302
            }
            status?.let { fixed ->
                assertNull("an error answer has no body to keep", sink(fixed, emptyMap()))
                return fixed
            }
            val from = headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()?.takeIf { honourRange } ?: 0
            val code = if (from > 0) 206 else 200
            val responseHeaders = if (code == 206) mapOf("content-range" to listOf("bytes $from-${body.size - 1}/${body.size}")) else emptyMap()
            val out = sink(code, responseHeaders) ?: return code
            var read = 0L
            var position = from
            while (position < body.size) {
                val n = minOf(32 * 1024, body.size - position)
                breakAfter?.let { limit -> if (read + n > limit) throw IOException("connection reset") }
                out.write(body, position, n)
                position += n
                read += n
                onProgress(read)
            }
            return code
        }
    }

    /** The update origin is the watch's server here, as for the owner ("The update origin" below covers the rest). */
    private fun download(networks: WifiNetworks, http: ApkHttp) =
        DirectDownload(networks, http, userAgent = "LifeOS-Wear/test", updatesUrl = "https://lifeos.example.com", wifiTimeoutMillis = 45_000)

    private class Seen {
        val stages = mutableListOf<Pair<DirectDownload.Stage, Long>>()
        val positions = mutableListOf<Long>()
    }

    private fun fetch(direct: DirectDownload, partial: PartialApk, seen: Seen = Seen(), url: String? = this.url, credentials: WearCredentials? = token) = runBlocking {
        direct.run(url, credentials, sha, size, partial, onStage = { stage, at -> seen.stages += stage to at }, onProgress = { seen.positions += it })
    }

    private fun taken(partial: PartialApk): ByteArray {
        val target = File(folder.root, "taken.apk")
        assertTrue(partial.complete(target))
        return target.readBytes()
    }

    // ---- When the watch tries Wi-Fi at all

    @Test
    fun `Wi-Fi is tried only with a wear APK's address, a complete token, and bytes missing`() {
        assertTrue(DirectDownload.worthTrying(url, token, held = 0, size = size))
        assertTrue(DirectDownload.worthTrying(url, token, held = size - 1, size = size))
        assertFalse("the phone sent no address (a 0.4.2 phone, or Wi-Fi already tried)", DirectDownload.worthTrying(null, token, 0, size))
        assertFalse(DirectDownload.worthTrying("/android/lifeos-phone-0.4.3-10.apk", token, 0, size))
        assertFalse(DirectDownload.worthTrying("https://elsewhere.example/android/lifeos-wear-0.4.3-10.apk", token, 0, size))
        assertFalse(DirectDownload.worthTrying("/android/../lifeos-wear-0.4.3-10.apk", token, 0, size))
        assertFalse("no token on the watch", DirectDownload.worthTrying(url, null, 0, size))
        assertFalse(DirectDownload.worthTrying(url, WearCredentials("http://lifeos.example.com", "id", "secret"), 0, size))
        assertFalse(DirectDownload.worthTrying(url, WearCredentials("https://lifeos.example.com", "", "secret"), 0, size))
        assertFalse("the whole file is already there: the stream's end mark is enough", DirectDownload.worthTrying(url, token, size, size))
    }

    @Test
    fun `without a usable address or token nothing is asked of Android`() {
        val networks = FakeNetworks()
        val http = FakeHttp()
        assertTrue(fetch(download(networks, http), partial(), url = null) is DirectDownload.Outcome.Skipped)
        assertTrue(fetch(download(networks, http), partial(), credentials = null) is DirectDownload.Outcome.Skipped)
        assertTrue(networks.asked.isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    // ---- The network

    @Test
    fun `no Wi-Fi in time sends the watch back to the phone, and nothing is downloaded`() {
        val networks = FakeNetworks(available = false)
        val http = FakeHttp()
        val seen = Seen()
        val outcome = fetch(download(networks, http), partial(), seen)
        assertTrue(outcome is DirectDownload.Outcome.NoWifi)
        assertTrue((outcome as DirectDownload.Outcome.NoWifi).why, "Auto" in outcome.why)
        assertEquals(listOf(45_000L), networks.asked)
        assertEquals(listOf(DirectDownload.Stage.WAITING_FOR_WIFI to 0L), seen.stages)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `a download over the Wi-Fi network it asked for, with the watch's token, then the network is given back`() {
        val networks = FakeNetworks()
        val http = FakeHttp()
        val seen = Seen()
        val partial = partial()
        assertEquals(DirectDownload.Outcome.Downloaded, fetch(download(networks, http), partial, seen))
        assertArrayEquals(apk, taken(partial))
        val (requested, headers) = http.requests.single()
        assertEquals("https://lifeos.example.com/android/lifeos-wear-0.4.3-10.apk", requested)
        assertEquals("id.access", headers["CF-Access-Client-Id"])
        assertEquals("secret", headers["CF-Access-Client-Secret"])
        assertEquals("LifeOS-Wear/test", headers["User-Agent"])
        assertNull(headers["Range"])
        assertEquals("bound to the requested network", listOf(42L), http.networks)
        assertEquals(1, networks.open)
        assertEquals(1, networks.closed)
        assertEquals(listOf(DirectDownload.Stage.WAITING_FOR_WIFI to 0L, DirectDownload.Stage.DOWNLOADING to 0L), seen.stages)
        assertEquals(size, seen.positions.last())
    }

    // ---- The update origin

    @Test
    fun `the APK comes from the update origin whatever server the watch uses, and that server's token stays home`() {
        val http = FakeHttp()
        val partial = partial()
        val ownServer = WearCredentials("https://my-own.example.org", "own.access", "own-secret")
        assertEquals(DirectDownload.Outcome.Downloaded, fetch(download(FakeNetworks(), http), partial, credentials = ownServer))
        assertArrayEquals(apk, taken(partial))
        val (requested, headers) = http.requests.single()
        assertEquals("https://lifeos.example.com/android/lifeos-wear-0.4.3-10.apk", requested)
        assertNull(headers["CF-Access-Client-Id"])
        assertNull(headers["CF-Access-Client-Secret"])
        assertEquals("LifeOS-Wear/test", headers["User-Agent"])
    }

    @Test
    fun `by default the watch downloads from the build's update origin, not from its server`() {
        val http = FakeHttp()
        val direct = DirectDownload(FakeNetworks(), http, userAgent = "LifeOS-Wear/test")
        fetch(direct, partial(), credentials = WearCredentials("https://my-own.example.org", "own.access", "own-secret"))
        assertEquals(LifeOsEndpoints.UPDATES_URL + url, http.requests.single().first)
    }

    // ---- GitHub Releases (P20-06): an app not connected to the update origin

    private val repo = "GAMch1k/life-os-app"
    private val githubUrl = "https://github.com/$repo/releases/download/v0.4.6-13/lifeos-wear-0.4.6-13.apk"
    private val assetHost = "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r&sig=x"

    private fun fromGitHub(networks: WifiNetworks, http: ApkHttp) =
        DirectDownload(networks, http, userAgent = "LifeOS-Wear/test", updatesUrl = "https://lifeos.example.com", updatesRepo = repo, wifiTimeoutMillis = 45_000)

    @Test
    fun `a wear APK of a GitHub release is worth trying without a token, another repository's never`() {
        assertTrue(DirectDownload.worthTrying(githubUrl, null, held = 0, size = size, repo = repo))
        assertTrue(DirectDownload.worthTrying(githubUrl, token, held = 0, size = size, repo = repo))
        assertFalse(DirectDownload.worthTrying(githubUrl.replace(repo, "someone/else"), token, 0, size, repo = repo))
        assertFalse("the phone's APK", DirectDownload.worthTrying(githubUrl.replace("wear", "phone"), token, 0, size, repo = repo))
        assertFalse(DirectDownload.worthTrying(githubUrl.replace("https://", "http://"), token, 0, size, repo = repo))
    }

    @Test
    fun `the APK comes from GitHub through its redirect, with no token, the range kept`() {
        val http = FakeHttp().apply { redirects[githubUrl] = assetHost }
        val partial = partial()
        val seen = Seen()
        assertEquals(DirectDownload.Outcome.Downloaded, fetch(fromGitHub(FakeNetworks(), http), partial, seen, url = githubUrl, credentials = token))
        assertArrayEquals(apk, taken(partial))
        assertEquals(listOf(githubUrl, assetHost), http.requests.map { it.first })
        for ((_, headers) in http.requests) {
            assertNull("no token goes to GitHub", headers["CF-Access-Client-Id"])
            assertNull(headers["CF-Access-Client-Secret"])
            assertEquals("LifeOS-Wear/test", headers["User-Agent"])
        }
        assertEquals(size, seen.positions.last())

        // A download that broke off resumes from GitHub too.
        val broken = FakeHttp().apply {
            redirects[githubUrl] = assetHost
            breakAfter = 100_000
        }
        val resumed = partial()
        resumed.discard()
        assertTrue(fetch(fromGitHub(FakeNetworks(), broken), resumed, url = githubUrl, credentials = null) is DirectDownload.Outcome.Failed)
        broken.breakAfter = null
        broken.requests.clear()
        assertEquals(DirectDownload.Outcome.Downloaded, fetch(fromGitHub(FakeNetworks(), broken), resumed, url = githubUrl, credentials = null))
        assertTrue(broken.requests.all { it.second["Range"]?.startsWith("bytes=") == true })
        assertArrayEquals(apk, taken(resumed))
    }

    @Test
    fun `a redirect away from GitHub is never followed`() {
        val http = FakeHttp().apply { redirects[githubUrl] = "https://evil.example.com/lifeos-wear.apk" }
        val outcome = fetch(fromGitHub(FakeNetworks(), http), partial(), url = githubUrl, credentials = null)
        assertEquals(DirectDownload.Outcome.Failed("GitHub sent the download to evil.example.com, which is not GitHub's download of $repo"), outcome)
        assertEquals(listOf(githubUrl), http.requests.map { it.first })
    }

    @Test
    fun `endless redirects end`() {
        val http = FakeHttp().apply { redirects[githubUrl] = githubUrl }
        assertTrue(fetch(fromGitHub(FakeNetworks(), http), partial(), url = githubUrl, credentials = null) is DirectDownload.Outcome.Failed)
        assertEquals(6, http.requests.size)
    }

    @Test
    fun `a file from GitHub with another SHA-256 is thrown away`() {
        val http = FakeHttp().apply {
            redirects[githubUrl] = assetHost
            body = apk.copyOf().also { it[0] = (it[0] + 1).toByte() }
        }
        val partial = partial()
        assertEquals(
            DirectDownload.Outcome.Failed("the downloaded file's SHA-256 is not the announced one"),
            fetch(fromGitHub(FakeNetworks(), http), partial, url = githubUrl, credentials = null),
        )
        assertEquals("the next try starts from byte 0", 0L, partial.offset(sha, size))
    }

    @Test
    fun `an address of another repository or site is not tried at all`() {
        for (url in listOf(githubUrl.replace(repo, "someone/else"), "https://evil.example.com/android/lifeos-wear-0.4.6-13.apk")) {
            val networks = FakeNetworks()
            assertEquals(DirectDownload.Outcome.Skipped("no download address"), fetch(fromGitHub(networks, FakeHttp()), partial(), url = url, credentials = token))
            assertTrue("no Wi-Fi was even asked for", networks.asked.isEmpty())
        }
    }

    @Test
    fun `an Access redirect on the update origin is still never followed`() {
        val http = FakeHttp().apply { redirects["https://lifeos.example.com$url"] = "https://team.cloudflareaccess.com/cdn-cgi/access/login" }
        val outcome = fetch(fromGitHub(FakeNetworks(), http), partial(), url = url, credentials = token)
        assertEquals(DirectDownload.Outcome.Failed("Life OS answered 302 (Cloudflare Access did not let the watch's token in)"), outcome)
        assertEquals(1, http.requests.size)
    }

    // ---- Resuming

    @Test
    fun `a broken download keeps its bytes, and the next one asks for the rest with a range`() {
        val networks = FakeNetworks()
        val http = FakeHttp().apply { breakAfter = 300_000 }
        val partial = partial()
        val first = fetch(download(networks, http), partial)
        assertTrue(first is DirectDownload.Outcome.Failed)
        assertEquals("the network is given back after a failure too", 1, networks.closed)
        val held = partial.offset(sha, size)
        assertTrue("held $held", held in 256L * 1024..300_000)
        http.breakAfter = null
        val seen = Seen()
        assertEquals(DirectDownload.Outcome.Downloaded, fetch(download(networks, http), partial, seen))
        assertEquals("bytes=$held-", http.requests.last().second["Range"])
        assertEquals(DirectDownload.Stage.DOWNLOADING to held, seen.stages.last())
        assertTrue(seen.positions.first() > held)
        assertArrayEquals(apk, taken(partial))
    }

    @Test
    fun `what the download leaves is where the phone's stream resumes`() {
        val partial = partial()
        fetch(download(FakeNetworks(), FakeHttp().apply { breakAfter = 300_000 }), partial)
        // The phone's offer reply and the stream use the same file.
        val held = partial.offset(sha, size)
        val writer = partial.open(sha, size, held)
        writer.digest.update(apk, held.toInt(), (size - held).toInt())
        writer.write(apk, held.toInt(), (size - held).toInt())
        writer.close()
        assertEquals(sha, writer.digest.digest().joinToString("") { "%02x".format(it) })
        assertArrayEquals(apk, taken(partial))
    }

    @Test
    fun `a server that ignores the range sends the whole file, which replaces what was held`() {
        val partial = partial()
        fetch(download(FakeNetworks(), FakeHttp().apply { breakAfter = 300_000 }), partial)
        assertTrue(partial.offset(sha, size) > 0)
        val http = FakeHttp().apply { honourRange = false }
        assertEquals(DirectDownload.Outcome.Downloaded, fetch(download(FakeNetworks(), http), partial))
        assertArrayEquals(apk, taken(partial))
    }

    // ---- Failures

    @Test
    fun `Access refusing the token or redirecting to its login is a failure that keeps the held bytes`() {
        val partial = partial()
        fetch(download(FakeNetworks(), FakeHttp().apply { breakAfter = 300_000 }), partial)
        val held = partial.offset(sha, size)
        for (status in listOf(302, 403, 404, 503)) {
            val networks = FakeNetworks()
            val outcome = fetch(download(networks, FakeHttp().apply { this.status = status }), partial)
            assertTrue("$status", outcome is DirectDownload.Outcome.Failed)
            assertTrue((outcome as DirectDownload.Outcome.Failed).why, "$status" in outcome.why)
            assertEquals(1, networks.closed)
            assertEquals(held, partial.offset(sha, size))
        }
    }

    @Test
    fun `a file with the wrong SHA-256 is dropped, so the next attempt starts from byte 0`() {
        val partial = partial()
        val damaged = apk.copyOf().also { it[123_456] = (it[123_456].toInt() xor 1).toByte() }
        val outcome = fetch(download(FakeNetworks(), FakeHttp().apply { body = damaged }), partial)
        assertTrue(outcome is DirectDownload.Outcome.Failed)
        assertTrue((outcome as DirectDownload.Outcome.Failed).why, "SHA-256" in outcome.why)
        assertEquals(0, partial.offset(sha, size))
    }

    @Test
    fun `more bytes than announced are cut off and dropped, fewer are a failure`() {
        val partial = partial()
        val tooLong = fetch(download(FakeNetworks(), FakeHttp().apply { body = apk + ByteArray(10_000) }), partial)
        assertTrue(tooLong is DirectDownload.Outcome.Failed)
        assertEquals(0, partial.offset(sha, size))
        val tooShort = fetch(download(FakeNetworks(), FakeHttp().apply { body = apk.copyOf(350_000) }), partial)
        assertTrue(tooShort is DirectDownload.Outcome.Failed)
        assertTrue((tooShort as DirectDownload.Outcome.Failed).why, "350000 of $size" in tooShort.why)
    }

    @Test
    fun `a range answer that starts elsewhere is not taken`() {
        val partial = partial()
        fetch(download(FakeNetworks(), FakeHttp().apply { breakAfter = 300_000 }), partial)
        val held = partial.offset(sha, size)
        assertEquals(held, DirectDownload.rangeStart(mapOf("content-range" to listOf("bytes $held-399999/400000"))))
        assertEquals(0L, DirectDownload.rangeStart(mapOf("content-range" to listOf("bytes 0-399999/*"))))
        assertNull(DirectDownload.rangeStart(mapOf("content-range" to listOf("items 0-1/2"))))
        assertNull(DirectDownload.rangeStart(emptyMap()))
    }
}
