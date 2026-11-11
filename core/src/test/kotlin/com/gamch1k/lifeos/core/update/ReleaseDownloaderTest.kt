package com.gamch1k.lifeos.core.update

import com.gamch1k.lifeos.core.api.AppRelease
import com.gamch1k.lifeos.core.api.Connection
import com.gamch1k.lifeos.core.api.FakeTransport
import com.gamch1k.lifeos.core.api.LifeOsClient
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
import java.security.MessageDigest

/**
 * The phone's download of an APK from a GitHub release (P20-06), then the
 * checks before the installer: what the manifest promised (size, SHA-256),
 * then the archive (package, version, and a signer that is the installed
 * app's own). Anything else is discarded.
 */
class ReleaseDownloaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val repo = "GAMch1k/life-os-app"
    private val apkText = "made-up APK bytes"
    private val apk = apkText.toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val url = "https://github.com/$repo/releases/download/v0.4.6-13/lifeos-phone-0.4.6-13.apk"
    private val assetHost = "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r&sig=x"
    private val release = AppRelease(13, "0.4.6", 34, sha, apk.size.toLong(), url)

    private val transport = FakeTransport()

    /** Connected to another server than the update origin: updates come from GitHub. */
    private val client = LifeOsClient(
        transport,
        { Connection("https://my-own.example.org", FakeTransport.TOKEN) },
        "ua",
        updatesUrl = "https://lifeos.example.com",
        updatesRepo = repo,
        sleep = {},
    )
    private val downloader = ReleaseDownloader(client)
    private val dir get() = File(temp.root, "updates")

    private fun fetch(release: AppRelease = this.release) = runBlocking { downloader.fetch(release, dir, "lifeos-phone-13.apk") {} }

    /** GitHub: the release download redirects to the asset host, which serves [body]. */
    private fun serves(body: String) {
        transport.redirect(302, assetHost)
        transport.json(200, body, mapOf("content-type" to listOf("application/octet-stream")))
    }

    @Test
    fun `an APK from GitHub with the manifest's size and SHA-256 is kept`() {
        serves(apkText)
        val result = fetch()
        assertTrue(result is ReleaseDownloader.Result.Ready)
        assertArrayEquals(apk, (result as ReleaseDownloader.Result.Ready).file.readBytes())
        assertEquals(listOf(url, assetHost), transport.requests.map { it.url })
        assertNull("no token goes to GitHub", transport.requests.firstNotNullOfOrNull { it.headers["CF-Access-Client-Id"] })
    }

    @Test
    fun `a wrong checksum is discarded, nothing is kept`() {
        serves("made-up APK bytez")
        assertEquals(ReleaseDownloader.Result.Failed("The download was discarded: its SHA-256 is not the published one."), fetch())
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test
    fun `a wrong size is discarded, nothing is kept`() {
        serves("$apkText!")
        val result = fetch()
        assertTrue(result is ReleaseDownloader.Result.Failed && result.message.contains("the manifest says ${apk.size}"))
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test
    fun `a redirect away from GitHub is never followed`() {
        transport.redirect(302, "https://evil.example.com/lifeos-phone-0.4.6-13.apk")
        val result = fetch()
        assertTrue(result is ReleaseDownloader.Result.Failed && result.message.contains("not GitHub's download of $repo"))
        assertEquals(listOf(url), transport.requests.map { it.url })
        assertFalse(File(dir, "lifeos-phone-13.apk").exists())
    }

    @Test
    fun `an address of another repository is never requested`() {
        val result = fetch(release.copy(url = url.replace(repo, "someone/life-os-app")))
        assertTrue(result is ReleaseDownloader.Result.Failed && result.message.contains("is not an address updates may come from"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `an APK that passes the checksum but is signed by anyone else is refused before the installer`() {
        serves(apkText)
        assertTrue(fetch() is ReleaseDownloader.Result.Ready)
        // What PackageManager reads from the installed app and from the download (ApkInspector).
        val installed = ApkFacts("com.gamch1k.lifeos", 12, setOf("ab".repeat(32)))
        val signedByOthers = ApkFacts("com.gamch1k.lifeos", 13, setOf("cd".repeat(32)))
        assertEquals(ApkRules.WRONG_CERTIFICATE, ApkRules.check(signedByOthers, release.versionCode, installed, installed.versionCode)?.code)
        assertEquals("a downgrade", ApkRules.NOT_NEWER, ApkRules.check(installed.copy(versionCode = 11), 11, installed, installed.versionCode)?.code)
        assertNull(ApkRules.check(installed.copy(versionCode = 13), release.versionCode, installed, installed.versionCode))
    }
}
