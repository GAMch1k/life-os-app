package com.gamch1k.lifeos.core.update

import com.gamch1k.lifeos.core.api.AppRelease
import com.gamch1k.lifeos.core.api.UpdateApps
import com.gamch1k.lifeos.core.api.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

class UpdatePolicyTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun release(versionCode: Long, minSdk: Int = 34, sha256: String = "a".repeat(64), size: Long = 100, url: String = "/android/lifeos-phone-0.3.0-$versionCode.apk") =
        AppRelease(versionCode, "0.3.0", minSdk, sha256, size, url)

    private fun manifest(phone: AppRelease?, formatVersion: Int = 1) = UpdateManifest(formatVersion, apps = UpdateApps(phone = phone))

    /** The update origin's manifest, the owner's case; the GitHub tests below name their source. */
    private val origin = UpdateSource.UpdateOrigin

    private fun decide(manifest: UpdateManifest, installedVersionCode: Long, sdkInt: Int) =
        UpdatePolicy.decide(manifest, installedVersionCode, sdkInt, origin)

    private fun decide(app: ReleaseApp, manifest: UpdateManifest, installedVersionCode: Long, sdkInt: Int) =
        UpdatePolicy.decide(app, manifest, installedVersionCode, sdkInt, origin)

    @Test
    fun `a higher versionCode is an update`() {
        val decision = decide(manifest(release(3)), installedVersionCode = 2, sdkInt = 36)
        assertEquals(UpdateDecision.Available(release(3)), decision)
    }

    @Test
    fun `the same or a lower versionCode is refused`() {
        assertTrue(decide(manifest(release(2)), 2, 36) is UpdateDecision.UpToDate)
        assertTrue(decide(manifest(release(1)), 2, 36) is UpdateDecision.UpToDate)
    }

    @Test
    fun `versionCodes compare as numbers, not as version names`() {
        assertTrue(decide(manifest(release(10)), 9, 36) is UpdateDecision.Available)
        assertTrue(decide(manifest(release(9)), 10, 36) is UpdateDecision.UpToDate)
    }

    @Test
    fun `an app that was never published is nothing to do`() {
        assertEquals(UpdateDecision.UpToDate(null), decide(manifest(null), 2, 36))
    }

    @Test
    fun `another manifest format is not read`() {
        assertEquals(UpdateDecision.UnsupportedFormat(2), decide(manifest(release(3), formatVersion = 2), 2, 36))
    }

    @Test
    fun `a release for a newer Android is not offered`() {
        assertTrue(decide(manifest(release(3, minSdk = 37)), 2, 36) is UpdateDecision.NeedsNewerAndroid)
    }

    @Test
    fun `entries with a bad hash, size or URL are refused before any download`() {
        assertTrue(decide(manifest(release(3, sha256 = "A".repeat(64))), 2, 36) is UpdateDecision.Invalid)
        assertTrue(decide(manifest(release(3, sha256 = "abc")), 2, 36) is UpdateDecision.Invalid)
        assertTrue(decide(manifest(release(3, size = 0)), 2, 36) is UpdateDecision.Invalid)
        assertTrue(decide(manifest(release(3, url = "https://evil.example.com/x.apk")), 2, 36) is UpdateDecision.Invalid)
        assertTrue(decide(manifest(release(3, url = "/android/lifeos-wear-0.3.0-3.apk")), 2, 36) is UpdateDecision.Invalid)
        assertTrue(decide(manifest(release(3, url = "/android/../lifeos-phone-0.3.0-3.apk")), 2, 36) is UpdateDecision.Invalid)
    }

    @Test
    fun `the wear entry is decided against the watch's version and API level`() {
        val wear = release(5, minSdk = 33, url = "/android/lifeos-wear-0.4.0-5.apk")
        val manifest = UpdateManifest(1, apps = UpdateApps(phone = release(5), wear = wear))
        assertEquals(UpdateDecision.Available(wear), decide(ReleaseApp.WEAR, manifest, installedVersionCode = 4, sdkInt = 36))
        assertTrue(decide(ReleaseApp.WEAR, manifest, 5, 36) is UpdateDecision.UpToDate)
        assertTrue("a downgrade is never offered", decide(ReleaseApp.WEAR, manifest, 6, 36) is UpdateDecision.UpToDate)
        assertTrue(decide(ReleaseApp.WEAR, manifest, 4, 32) is UpdateDecision.NeedsNewerAndroid)
        assertEquals(UpdateDecision.UpToDate(null), decide(ReleaseApp.WEAR, manifest(release(5)), 4, 36))
    }

    @Test
    fun `each app takes only its own APK`() {
        val phoneApkAsWear = UpdateManifest(1, apps = UpdateApps(wear = release(5, url = "/android/lifeos-phone-0.4.0-5.apk")))
        assertTrue(decide(ReleaseApp.WEAR, phoneApkAsWear, 4, 36) is UpdateDecision.Invalid)
        assertEquals(null, UpdatePolicy.validate(ReleaseApp.WEAR, release(5, url = "/android/lifeos-wear-0.4.0-5.apk"), origin))
        assertEquals("the URL is not a wear APK under /android/", UpdatePolicy.validate(ReleaseApp.WEAR, release(5), origin))
    }

    // ---- A manifest from GitHub Releases (P20-06)

    private val repo = "GAMch1k/life-os-app"
    private val github = UpdateSource.GitHubRepo(repo)
    private fun asset(app: String, code: Long, repo: String = this.repo) =
        "https://github.com/$repo/releases/download/v0.4.6-$code/lifeos-$app-0.4.6-$code.apk"

    @Test
    fun `GitHub's manifest names its own repository's release assets`() {
        assertEquals(UpdateDecision.Available(release(13, url = asset("phone", 13))), UpdatePolicy.decide(manifest(release(13, url = asset("phone", 13))), 12, 36, github))
        val wear = release(13, url = asset("wear", 13))
        assertEquals(null, UpdatePolicy.validate(ReleaseApp.WEAR, wear, github))
        assertEquals("the repository's name is matched as GitHub does, in any case", null,
            UpdatePolicy.validate(ReleaseApp.WEAR, release(13, url = asset("wear", 13, "gamch1k/LIFE-OS-APP")), github))
    }

    @Test
    fun `GitHub's manifest may not send the app to another repository, another site, or the update origin`() {
        val refused = listOf(
            asset("phone", 13, "someone/life-os-app"),
            asset("phone", 13, "GAMch1k/life-os-app-fork"),
            "https://evil.example.com/GAMch1k/life-os-app/releases/download/v0.4.6-13/lifeos-phone-0.4.6-13.apk",
            "https://github.com.evil.example/GAMch1k/life-os-app/releases/download/v0.4.6-13/lifeos-phone-0.4.6-13.apk",
            "http://github.com/GAMch1k/life-os-app/releases/download/v0.4.6-13/lifeos-phone-0.4.6-13.apk",
            "https://github.com:8443/GAMch1k/life-os-app/releases/download/v0.4.6-13/lifeos-phone-0.4.6-13.apk",
            "https://github.com/GAMch1k/life-os-app/releases/download/v0.4.6-13/lifeos-phone-0.4.6-13.apk?x=1",
            "https://github.com/GAMch1k/life-os-app/releases/download/../../other/lifeos-phone-0.4.6-13.apk",
            "https://github.com/GAMch1k/life-os-app/raw/main/lifeos-phone-0.4.6-13.apk",
            asset("wear", 13),
            "/android/lifeos-phone-0.4.6-13.apk",
        )
        for (url in refused) {
            val decision = UpdatePolicy.decide(manifest(release(13, url = url)), 12, 36, github)
            assertEquals(url, UpdateDecision.Invalid("the URL is not a phone APK of a $repo release on GitHub"), decision)
        }
    }

    @Test
    fun `the update origin's manifest may not send the app to GitHub`() {
        assertEquals(
            UpdateDecision.Invalid("the URL is not a phone APK under /android/"),
            UpdatePolicy.decide(manifest(release(13, url = asset("phone", 13))), 12, 36, origin),
        )
    }

    @Test
    fun `a downgrade from GitHub is never offered`() {
        assertTrue(UpdatePolicy.decide(manifest(release(12, url = asset("phone", 12))), 13, 36, github) is UpdateDecision.UpToDate)
        assertTrue(UpdatePolicy.decide(manifest(release(13, url = asset("phone", 13))), 13, 36, github) is UpdateDecision.UpToDate)
    }

    @Test
    fun `automatic checks are throttled`() {
        val now = Instant.parse("2026-10-12T12:00:00Z")
        val sixHours = Duration.ofHours(6)
        assertTrue(UpdatePolicy.checkDue(null, now, sixHours))
        assertFalse(UpdatePolicy.checkDue(now.minus(Duration.ofHours(5)), now, sixHours))
        assertTrue(UpdatePolicy.checkDue(now.minus(sixHours), now, sixHours))
        assertTrue("a clock set back does not block checks", UpdatePolicy.checkDue(now.plusSeconds(60), now, sixHours))
    }

    @Test
    fun `a partial download resumes, a complete or oversized one does not`() {
        assertNull(UpdatePolicy.resumeFrom(0, 100))
        assertEquals(40L, UpdatePolicy.resumeFrom(40, 100))
        assertNull(UpdatePolicy.resumeFrom(100, 100))
        assertNull(UpdatePolicy.resumeFrom(120, 100))
    }

    @Test
    fun `the APK must have the manifest's size and SHA-256`() {
        val bytes = "not really an apk".toByteArray()
        val apk = temp.newFile("update.apk").apply { writeBytes(bytes) }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(ApkIntegrity.Result.Ok, ApkIntegrity.check(apk, bytes.size.toLong(), sha))
        assertEquals(ApkIntegrity.Result.WrongSize(bytes.size.toLong(), 5), ApkIntegrity.check(apk, 5, sha))
        val wrong = ApkIntegrity.check(apk, bytes.size.toLong(), "0".repeat(64))
        assertTrue(wrong is ApkIntegrity.Result.WrongSha256)
        assertEquals(sha, (wrong as ApkIntegrity.Result.WrongSha256).actual)
    }
}
