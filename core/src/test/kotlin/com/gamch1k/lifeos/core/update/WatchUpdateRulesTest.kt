package com.gamch1k.lifeos.core.update

import com.gamch1k.lifeos.core.api.AppRelease
import com.gamch1k.lifeos.core.api.UpdateApps
import com.gamch1k.lifeos.core.api.UpdateManifest
import com.gamch1k.lifeos.core.wearlink.ApkOffer
import com.gamch1k.lifeos.core.wearlink.UpdateErrorCode
import com.gamch1k.lifeos.core.wearlink.WatchBattery
import com.gamch1k.lifeos.core.wearlink.WatchInfo
import com.gamch1k.lifeos.core.wearlink.WatchUpdateResult
import com.gamch1k.lifeos.core.wearlink.WatchUpdateStatus
import com.gamch1k.lifeos.core.wearlink.WearLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The update decision on both sides: what the watch accepts, what the phone pushes, and the APK checks. */
class WatchUpdateRulesTest {
    private val now = Instant.parse("2026-10-12T12:00:00Z")
    private val sha = "a".repeat(64)
    private val release = AppRelease(versionCode = 5, versionName = "0.4.0", minSdk = 33, sha256 = sha, size = 15_000_000, url = "/android/lifeos-wear-0.4.0-5.apk")
    private val manifest = UpdateManifest(1, apps = UpdateApps(wear = release))

    private fun watch(
        versionCode: Long = 4,
        canInstall: Boolean = true,
        battery: WatchBattery? = WatchBattery(80, charging = false),
        lastUpdate: WatchUpdateResult? = null,
        capabilities: List<String> = listOf(WearLink.WATCH_CAN_UPDATE),
        abis: List<String> = emptyList(),
    ) = WatchInfo(
        versionCode = versionCode,
        versionName = "0.3.$versionCode",
        sdkInt = 36,
        abis = abis,
        capabilities = capabilities,
        canInstallPackages = canInstall,
        battery = battery,
        lastUpdate = lastUpdate,
    )

    // ---- The watch: the offer

    private fun offer(versionCode: Long = 5, size: Long = 15_000_000, sha256: String = sha) =
        ApkOffer(id = "offer-1", versionCode = versionCode, versionName = "0.4.0", size = size, sha256 = sha256)

    private fun check(
        offer: ApkOffer = offer(),
        installed: Long = 4,
        canInstall: Boolean = true,
        battery: WatchBattery? = WatchBattery(80, false),
        free: Long = 1_000_000_000,
        lastInstallAt: Instant? = null,
        busy: Boolean = false,
    ) = WatchUpdateRules.checkOffer(offer, installed, canInstall, battery, free, lastInstallAt, busy, now)?.code

    @Test
    fun `the watch takes a newer, well-formed offer`() {
        assertNull(check())
        assertNull("charging overrides a low battery", check(battery = WatchBattery(5, charging = true)))
        assertNull("no battery reading is no reason to refuse", check(battery = null))
    }

    @Test
    fun `the watch refuses the same version and a downgrade`() {
        assertEquals(UpdateErrorCode.NOT_NEWER, check(installed = 5))
        assertEquals(UpdateErrorCode.NOT_NEWER, check(installed = 6))
    }

    @Test
    fun `the watch refuses broken and oversized offers first`() {
        assertEquals(UpdateErrorCode.BAD_REQUEST, check(offer(sha256 = "A".repeat(64))))
        assertEquals(UpdateErrorCode.BAD_REQUEST, check(offer(size = 0)))
        assertEquals(UpdateErrorCode.TOO_LARGE, check(offer(size = WearLink.MAX_APK_BYTES + 1)))
        assertEquals("broken beats not newer", UpdateErrorCode.BAD_REQUEST, check(offer(sha256 = "x"), installed = 9))
    }

    @Test
    fun `the watch refuses without the install permission, on low battery, short of space, or busy`() {
        assertEquals(UpdateErrorCode.NO_INSTALL_PERMISSION, check(canInstall = false))
        assertEquals(UpdateErrorCode.LOW_BATTERY, check(battery = WatchBattery(WatchUpdateRules.MIN_BATTERY_PERCENT - 1, charging = false)))
        assertNull(check(battery = WatchBattery(WatchUpdateRules.MIN_BATTERY_PERCENT, charging = false)))
        assertEquals(UpdateErrorCode.STORAGE, check(free = 20_000_000))
        assertEquals(UpdateErrorCode.BUSY, check(busy = true))
    }

    @Test
    fun `the watch respects Android's 30 seconds between silent installs`() {
        assertEquals(UpdateErrorCode.THROTTLED, check(lastInstallAt = now.minusSeconds(10)))
        assertNull(check(lastInstallAt = now.minus(WatchUpdateRules.SILENT_INSTALL_GAP)))
        assertNull("a clock set back does not block", check(lastInstallAt = now.plusSeconds(3600)))
        assertEquals(25, WatchUpdateRules.retryAfter(now.minusSeconds(10), now))
    }

    // ---- The phone: when to push

    private fun plan(
        watch: WatchInfo = watch(),
        last: WatchPush? = null,
        manual: Boolean = false,
        manifest: UpdateManifest = this.manifest,
        source: UpdateSource = UpdateSource.UpdateOrigin,
    ) = WatchUpdateRules.plan(manifest, source, watch, last, now, manual)

    @Test
    fun `a wear APK from GitHub Releases is pushed like one from the update origin, and only from its own source`() {
        val github = UpdateSource.GitHubRepo("GAMch1k/life-os-app")
        val fromGitHub = release.copy(url = "https://github.com/GAMch1k/life-os-app/releases/download/v0.4.0-5/lifeos-wear-0.4.0-5.apk")
        assertEquals(WatchPlan.Push(fromGitHub), plan(manifest = UpdateManifest(1, apps = UpdateApps(wear = fromGitHub)), source = github))
        assertTrue("an /android/ path in GitHub's manifest", plan(source = github) is WatchPlan.Unusable)
        assertTrue("a GitHub address in the update origin's manifest", plan(manifest = UpdateManifest(1, apps = UpdateApps(wear = fromGitHub))) is WatchPlan.Unusable)
        val otherRepo = release.copy(url = "https://github.com/someone/else/releases/download/v0.4.0-5/lifeos-wear-0.4.0-5.apk")
        assertTrue("another repository's release", plan(manifest = UpdateManifest(1, apps = UpdateApps(wear = otherRepo)), source = github) is WatchPlan.Unusable)
    }

    @Test
    fun `a newer wear version is pushed`() {
        assertEquals(WatchPlan.Push(release), plan())
    }

    @Test
    fun `the same or a newer watch version is up to date`() {
        assertEquals(WatchPlan.UpToDate(release), plan(watch(versionCode = 5)))
        assertEquals(WatchPlan.UpToDate(release), plan(watch(versionCode = 6)))
        assertEquals(WatchPlan.UpToDate(null), plan(manifest = UpdateManifest(1)))
    }

    @Test
    fun `an unusable wear entry is never pushed`() {
        assertTrue(plan(manifest = UpdateManifest(1, apps = UpdateApps(wear = release.copy(sha256 = "nope")))) is WatchPlan.Unusable)
        assertTrue(plan(manifest = UpdateManifest(1, apps = UpdateApps(wear = release.copy(url = "/android/lifeos-phone-0.4.0-5.apk")))) is WatchPlan.Unusable)
        assertTrue(plan(manifest = UpdateManifest(1, apps = UpdateApps(wear = release.copy(minSdk = 37)))) is WatchPlan.Unusable)
        assertTrue(plan(manifest = UpdateManifest(2, apps = UpdateApps(wear = release))) is WatchPlan.Unusable)
    }

    @Test
    fun `an old watch app or one without the install permission needs the setup`() {
        assertTrue(plan(watch(capabilities = emptyList())) is WatchPlan.NeedsSetup)
        assertTrue(plan(watch(canInstall = false)) is WatchPlan.NeedsSetup)
    }

    @Test
    fun `nothing is streamed to a watch with a low battery, even on Update now`() {
        val low = watch(battery = WatchBattery(10, charging = false))
        assertTrue(plan(low) is WatchPlan.Wait)
        assertTrue(plan(low, manual = true) is WatchPlan.Wait)
        assertEquals(WatchPlan.Push(release), plan(watch(battery = WatchBattery(10, charging = true))))
    }

    @Test
    fun `after a push the phone waits for the watch's report, Update now only for the 30 seconds`() {
        val sent = WatchUpdateRules.sent(null, 5, now.minus(Duration.ofMinutes(2)))
        assertTrue(plan(last = sent) is WatchPlan.Wait)
        assertEquals(WatchPlan.Push(release), plan(last = sent, manual = true))
        assertTrue(plan(last = WatchUpdateRules.sent(null, 5, now.minusSeconds(5)), manual = true) is WatchPlan.Wait)
        assertEquals(WatchPlan.Push(release), plan(last = WatchUpdateRules.sent(null, 5, now.minus(WatchUpdateRules.RESULT_WAIT))))
        assertEquals("a push of another version doesn't hold this one", WatchPlan.Push(release), plan(last = WatchUpdateRules.sent(null, 4, now.minusSeconds(5))))
    }

    @Test
    fun `failures back off, a new version starts over`() {
        val once = WatchUpdateRules.failed(null, 5, now.minus(Duration.ofMinutes(20)), "sha256_mismatch")
        assertEquals(1, once.failures)
        assertTrue(plan(last = once) is WatchPlan.Wait)
        assertEquals(WatchPlan.Push(release), plan(last = WatchUpdateRules.failed(null, 5, now.minus(Duration.ofMinutes(31)), "x")))
        val twice = WatchUpdateRules.failed(once, 5, now.minus(Duration.ofMinutes(31)), "x")
        assertEquals(2, twice.failures)
        assertTrue("the second failure waits 2 hours", plan(last = twice) is WatchPlan.Wait)
        assertEquals(1, WatchUpdateRules.failed(twice, 6, now, "x").failures)
        assertEquals(Duration.ofMinutes(30), WatchUpdateRules.backoff(1))
        assertEquals(Duration.ofHours(2), WatchUpdateRules.backoff(2))
        assertEquals(Duration.ofHours(8), WatchUpdateRules.backoff(3))
        assertEquals(Duration.ofHours(24), WatchUpdateRules.backoff(4))
        assertEquals(Duration.ofHours(24), WatchUpdateRules.backoff(40))
        assertEquals(WatchPlan.Push(release), plan(last = twice, manual = true))
    }

    @Test
    fun `a waiting confirmation is not pushed again automatically`() {
        val waiting = WatchUpdateResult(5, "0.4.0", WatchUpdateStatus.WAITING_FOR_CONFIRMATION, "", now.toString())
        assertTrue(plan(watch(lastUpdate = waiting)) is WatchPlan.Wait)
        assertEquals(WatchPlan.Push(release), plan(watch(lastUpdate = waiting), manual = true))
    }

    // ---- The APK checks on both sides

    private val own = ApkFacts("com.gamch1k.lifeos", 4, setOf("ab".repeat(32)))

    @Test
    fun `an APK of this package, signed by the release key, at the announced newer version passes`() {
        assertNull(ApkRules.check(own.copy(versionCode = 5), expectedVersionCode = 5, own = own, installedVersionCode = 4))
    }

    @Test
    fun `wrong package, wrong certificate, version mismatch and downgrade are refused`() {
        assertEquals(ApkRules.NOT_AN_APK, ApkRules.check(null, 5, own, 4)?.code)
        assertEquals(ApkRules.WRONG_PACKAGE, ApkRules.check(own.copy(packageName = "com.example", versionCode = 5), 5, own, 4)?.code)
        assertEquals(ApkRules.WRONG_CERTIFICATE, ApkRules.check(own.copy(versionCode = 5, signerSha256 = setOf("cd".repeat(32))), 5, own, 4)?.code)
        assertEquals(ApkRules.WRONG_CERTIFICATE, ApkRules.check(own.copy(versionCode = 5, signerSha256 = emptySet()), 5, own, 4)?.code)
        assertEquals(
            "an extra signer is another certificate set",
            ApkRules.WRONG_CERTIFICATE,
            ApkRules.check(own.copy(versionCode = 5, signerSha256 = own.signerSha256 + "cd".repeat(32)), 5, own, 4)?.code,
        )
        assertEquals(ApkRules.VERSION_MISMATCH, ApkRules.check(own.copy(versionCode = 6), 5, own, 4)?.code)
        assertEquals(ApkRules.NOT_NEWER, ApkRules.check(own.copy(versionCode = 4), 4, own, 4)?.code)
        assertEquals(ApkRules.NOT_NEWER, ApkRules.check(own.copy(versionCode = 3), 3, own, 4)?.code)
    }

    @Test
    fun `the phone checks the wear APK against a fresh watch too`() {
        assertNull(ApkRules.check(own.copy(versionCode = 5), 5, own.copy(versionCode = 99), installedVersionCode = 0))
    }

    // ---- The watch's ABIs

    @Test
    fun `the ABI list is read from getprop's answer`() {
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), WatchAbi.parse("arm64-v8a,armeabi-v7a,armeabi\n"))
        assertEquals("the Galaxy Watch 8", listOf("armeabi-v7a", "armeabi"), WatchAbi.parse("armeabi-v7a,armeabi\n"))
        assertEquals(emptyList<String>(), WatchAbi.parse(""))
        assertEquals("junk is dropped", listOf("arm64-v8a"), WatchAbi.parse("arm64-v8a, ; rm -rf"))
    }

    @Test
    fun `the wear APK carries armeabi-v7a, which the Galaxy Watch 8 runs (BUG-45)`() {
        assertEquals(listOf("armeabi-v7a"), WatchAbi.APK_ABIS)
        // The owner's Galaxy Watch 8 on 0.4.2 (9): 32-bit apps only. 0.4.3 and 0.4.4 (arm64-v8a only) were refused here.
        val v045 = release.copy(versionCode = 12, versionName = "0.4.5", size = 6_500_000, url = "/android/lifeos-wear-0.4.5-12.apk")
        val manifest045 = UpdateManifest(1, apps = UpdateApps(wear = v045))
        val galaxyWatch8 = watch(versionCode = 9, abis = listOf("armeabi-v7a", "armeabi"))
        assertTrue(WatchAbi.runs(galaxyWatch8.abis))
        assertEquals(WatchPlan.Push(v045), plan(galaxyWatch8, manifest = manifest045))
        assertEquals(WatchPlan.Push(v045), plan(galaxyWatch8, manual = true, manifest = manifest045))
        // A 64-bit watch that runs 32-bit apps too.
        assertTrue(WatchAbi.runs(listOf("arm64-v8a", "armeabi-v7a", "armeabi")))
        assertEquals(WatchPlan.Push(release), plan(watch(abis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"))))
        assertTrue("not known yet: the benefit of the doubt", WatchAbi.runs(emptyList()))
        assertEquals(WatchPlan.Push(release), plan(watch(abis = emptyList())))
    }

    @Test
    fun `a watch that can't run armeabi-v7a is refused, saying why`() {
        for (abis in listOf(listOf("arm64-v8a"), listOf("armeabi"), listOf("x86_64", "x86"))) {
            assertFalse(abis.toString(), WatchAbi.runs(abis))
            val refused = plan(watch(abis = abis))
            assertTrue("$abis: $refused", refused is WatchPlan.Unusable)
            val reason = (refused as WatchPlan.Unusable).reason
            assertEquals(
                "the watch app is built for armeabi-v7a only and the watch reports ${abis.joinToString()}; " +
                    "Android would refuse it (INSTALL_FAILED_NO_MATCHING_ABIS)",
                reason,
            )
            // Update now doesn't change that.
            assertTrue(plan(watch(abis = abis), manual = true) is WatchPlan.Unusable)
        }
    }

    // ---- The phone's stream timeouts (0.4.3)

    @Test
    fun `a slow stream that still moves is not cut, unlike 0_4_2's fixed 15 minutes`() {
        // 0.4.2's 16.6 MB APK at 17 KB/s needed about 16 minutes.
        assertTrue(WatchUpdateRules.streamCap(16_600_000) > Duration.ofSeconds(16_600_000L / 17_000))
        // 0.4.3's 6 MB APK at a poor 4 KB/s still fits.
        assertTrue(WatchUpdateRules.streamCap(6_000_000) >= Duration.ofSeconds(6_000_000L / 4_000))
        assertEquals("never less than 20 minutes", Duration.ofMinutes(20), WatchUpdateRules.streamCap(1_000))
        assertEquals(Duration.ofMinutes(2), WatchUpdateRules.STREAM_IDLE)
    }
}
