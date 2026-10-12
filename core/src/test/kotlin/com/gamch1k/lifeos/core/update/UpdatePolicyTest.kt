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

    @Test
    fun `a higher versionCode is an update`() {
        val decision = UpdatePolicy.decide(manifest(release(3)), installedVersionCode = 2, sdkInt = 36)
        assertEquals(UpdateDecision.Available(release(3)), decision)
    }

    @Test
    fun `the same or a lower versionCode is refused`() {
        assertTrue(UpdatePolicy.decide(manifest(release(2)), 2, 36) is UpdateDecision.UpToDate)
        assertTrue(UpdatePolicy.decide(manifest(release(1)), 2, 36) is UpdateDecision.UpToDate)
    }

    @Test
    fun `versionCodes compare as numbers, not as version names`() {
        assertTrue(UpdatePolicy.decide(manifest(release(10)), 9, 36) is UpdateDecision.Available)
        assertTrue(UpdatePolicy.decide(manifest(release(9)), 10, 36) is UpdateDecision.UpToDate)
    }

    @Test
    fun `an app that was never published is nothing to do`() {
        assertEquals(UpdateDecision.UpToDate(null), UpdatePolicy.decide(manifest(null), 2, 36))
    }

    @Test
    fun `another manifest format is not read`() {
        assertEquals(UpdateDecision.UnsupportedFormat(2), UpdatePolicy.decide(manifest(release(3), formatVersion = 2), 2, 36))
    }

    @Test
    fun `a release for a newer Android is not offered`() {
        assertTrue(UpdatePolicy.decide(manifest(release(3, minSdk = 37)), 2, 36) is UpdateDecision.NeedsNewerAndroid)
    }

    @Test
    fun `entries with a bad hash, size or URL are refused before any download`() {
        assertTrue(UpdatePolicy.decide(manifest(release(3, sha256 = "A".repeat(64))), 2, 36) is UpdateDecision.Invalid)
        assertTrue(UpdatePolicy.decide(manifest(release(3, sha256 = "abc")), 2, 36) is UpdateDecision.Invalid)
        assertTrue(UpdatePolicy.decide(manifest(release(3, size = 0)), 2, 36) is UpdateDecision.Invalid)
        assertTrue(UpdatePolicy.decide(manifest(release(3, url = "https://evil.example.com/x.apk")), 2, 36) is UpdateDecision.Invalid)
        assertTrue(UpdatePolicy.decide(manifest(release(3, url = "/android/lifeos-wear-0.3.0-3.apk")), 2, 36) is UpdateDecision.Invalid)
        assertTrue(UpdatePolicy.decide(manifest(release(3, url = "/android/../lifeos-phone-0.3.0-3.apk")), 2, 36) is UpdateDecision.Invalid)
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
