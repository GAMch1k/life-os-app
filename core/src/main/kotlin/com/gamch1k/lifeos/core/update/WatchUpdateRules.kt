package com.gamch1k.lifeos.core.update

import com.gamch1k.lifeos.core.api.AppRelease
import com.gamch1k.lifeos.core.api.UpdateManifest
import com.gamch1k.lifeos.core.wearlink.ApkOffer
import com.gamch1k.lifeos.core.wearlink.UpdateError
import com.gamch1k.lifeos.core.wearlink.UpdateErrorCode
import com.gamch1k.lifeos.core.wearlink.WatchBattery
import com.gamch1k.lifeos.core.wearlink.WatchInfo
import com.gamch1k.lifeos.core.wearlink.WatchUpdateStatus
import com.gamch1k.lifeos.core.wearlink.WearLink
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** The phone's last push of a wear APK to the watch, kept across restarts. */
@Serializable
data class WatchPush(
    val versionCode: Long,
    /** ISO 8601 instant of the last attempt. */
    val at: String,
    /** Failed attempts at this versionCode in a row (0 once the watch took it). */
    val failures: Int = 0,
    val lastError: String? = null,
)

/** What the phone should do about the watch app now. */
sealed interface WatchPlan {
    /** The watch runs the published version, or a newer one, or nothing is published. */
    data class UpToDate(val release: AppRelease?) : WatchPlan

    /** Download [release] (if not yet), offer it and stream it. */
    data class Push(val release: AppRelease) : WatchPlan

    /** Not now: [reason] for the owner, and when to try again. */
    data class Wait(val release: AppRelease, val reason: String, val until: Instant) : WatchPlan

    /** The watch app can't take updates through the phone: it needs Settings › Watch › Set up the watch app. */
    data class NeedsSetup(val reason: String) : WatchPlan

    /** The manifest's wear entry can't be used (format, hash, URL, a newer Wear OS needed). */
    data class Unusable(val reason: String) : WatchPlan
}

/**
 * The update rules for the watch app (P19-06), on both sides of the link:
 * what the watch accepts ([checkOffer]) and when the phone pushes ([plan]).
 */
object WatchUpdateRules {
    /** Below this, and not charging, nothing is streamed (the watch also refuses). */
    const val MIN_BATTERY_PERCENT = 20

    /**
     * Android lets an app update itself without asking only once per 30
     * seconds; a second install inside that window asks the owner. A few
     * seconds of margin on top.
     */
    val SILENT_INSTALL_GAP: Duration = Duration.ofSeconds(35)

    /** After a push without a result (the watch is installing, or restarting), the phone waits this long before trying again. */
    val RESULT_WAIT: Duration = Duration.ofMinutes(10)

    // ---- The stream's timeouts (the phone)

    /**
     * The stream is cut when the channel takes no byte for this long. A
     * stream that still moves, however slowly, is never cut by this.
     */
    val STREAM_IDLE: Duration = Duration.ofMinutes(2)

    /**
     * After the last byte, how long the phone waits for the watch's verdict:
     * Play services may still hold buffered bytes that reach the watch only
     * now, and the watch checks the archive before it answers.
     */
    val STREAM_VERDICT_WAIT: Duration = Duration.ofMinutes(5)

    /**
     * The longest a whole stream may take: 20 minutes, or the time at
     * 4 KB/s, whichever is longer. 0.4.2's fixed 15 minutes cut the 16.6 MB
     * APK at 91 % and 95 % over a Bluetooth link of about 17 KB/s; the 6.6 MB
     * APK of 0.4.3 takes about 6.5 minutes at that pace.
     */
    fun streamCap(bytes: Long): Duration = maxOf(Duration.ofMinutes(20), Duration.ofSeconds(bytes / 4_000))

    // ---- The watch

    /**
     * Whether the watch takes [offer]: null to accept, else the reason. The
     * order matters for the owner: a broken or useless offer is refused first,
     * then what only time or the owner can fix.
     */
    fun checkOffer(
        offer: ApkOffer,
        installedVersionCode: Long,
        canInstall: Boolean,
        battery: WatchBattery?,
        freeBytes: Long,
        lastInstallAt: Instant?,
        busy: Boolean,
        now: Instant,
    ): UpdateError? {
        val sha = Regex("^[0-9a-f]{64}$")
        return when {
            offer.id.isBlank() || offer.size <= 0 || !sha.matches(offer.sha256) -> UpdateError(UpdateErrorCode.BAD_REQUEST, "the offer is incomplete")
            offer.size > WearLink.MAX_APK_BYTES -> UpdateError(UpdateErrorCode.TOO_LARGE, "${offer.size} bytes, at most ${WearLink.MAX_APK_BYTES}")
            offer.versionCode <= installedVersionCode -> UpdateError(UpdateErrorCode.NOT_NEWER, "the watch runs $installedVersionCode")
            !canInstall -> UpdateError(UpdateErrorCode.NO_INSTALL_PERMISSION, "\"Install unknown apps\" is not allowed for Life OS on the watch")
            battery != null && !battery.charging && battery.percent < MIN_BATTERY_PERCENT ->
                UpdateError(UpdateErrorCode.LOW_BATTERY, "the watch's battery is at ${battery.percent} %")
            freeBytes < offer.size * 2 + FREE_SPACE_MARGIN -> UpdateError(UpdateErrorCode.STORAGE, "not enough free space on the watch")
            busy -> UpdateError(UpdateErrorCode.BUSY, "another update is running")
            lastInstallAt != null && lastInstallAt.isAfter(now.minus(SILENT_INSTALL_GAP)) && !lastInstallAt.isAfter(now) ->
                UpdateError(UpdateErrorCode.THROTTLED, "the last install was less than ${SILENT_INSTALL_GAP.seconds} s ago")
            else -> null
        }
    }

    /** With [UpdateErrorCode.THROTTLED]: seconds until the window is over. */
    fun retryAfter(lastInstallAt: Instant, now: Instant): Long =
        Duration.between(now, lastInstallAt.plus(SILENT_INSTALL_GAP)).seconds.coerceIn(1, SILENT_INSTALL_GAP.seconds)

    private const val FREE_SPACE_MARGIN = 20L * 1024 * 1024

    // ---- The phone

    /**
     * What to do for [watch] (what it last reported) given the [manifest] of
     * [source]. [manual] is the owner's "Update now": it skips the backoff and
     * the wait for a result, never the battery rule or Android's 30 seconds.
     */
    fun plan(manifest: UpdateManifest, source: UpdateSource, watch: WatchInfo, last: WatchPush?, now: Instant, manual: Boolean): WatchPlan {
        val decision = UpdatePolicy.decide(ReleaseApp.WEAR, manifest, watch.versionCode, watch.sdkInt, source)
        val release = when (decision) {
            is UpdateDecision.UpToDate -> return WatchPlan.UpToDate(decision.published)
            is UpdateDecision.UnsupportedFormat -> return WatchPlan.Unusable("the update manifest has format ${decision.formatVersion}; this app reads ${UpdatePolicy.FORMAT_VERSION}")
            is UpdateDecision.Invalid -> return WatchPlan.Unusable("the published watch app is unusable: ${decision.reason}")
            is UpdateDecision.NeedsNewerAndroid -> return WatchPlan.Unusable("version ${decision.release.versionName} needs API ${decision.release.minSdk}; the watch has ${watch.sdkInt}")
            is UpdateDecision.Available -> decision.release
        }
        if (!watch.canUpdate) return WatchPlan.NeedsSetup("the watch app (${watch.versionName}) is older than updates through the phone")
        if (!watch.canInstallPackages) return WatchPlan.NeedsSetup("\"Install unknown apps\" is not allowed for Life OS on the watch")
        if (!WatchAbi.runs(watch.abis)) {
            return WatchPlan.Unusable(
                "the watch app is built for ${WatchAbi.APK_ABIS.joinToString()} only and the watch reports ${watch.abis.joinToString()}; " +
                    "Android would refuse it (INSTALL_FAILED_NO_MATCHING_ABIS)",
            )
        }
        val battery = watch.battery
        if (battery != null && !battery.charging && battery.percent < MIN_BATTERY_PERCENT) {
            return WatchPlan.Wait(release, "the watch's battery is at ${battery.percent} %; it updates once charged", now.plus(Duration.ofMinutes(30)))
        }
        val result = watch.lastUpdate
        if (!manual && result != null && result.versionCode == release.versionCode && result.status == WatchUpdateStatus.WAITING_FOR_CONFIRMATION) {
            return WatchPlan.Wait(release, "confirm the update on the watch", now.plus(Duration.ofHours(6)))
        }
        val attempt = last?.takeIf { it.versionCode == release.versionCode }
        val at = attempt?.at?.let { runCatching { Instant.parse(it) }.getOrNull() }?.takeIf { !it.isAfter(now) }
        if (attempt != null && at != null) {
            val gap = when {
                manual -> SILENT_INSTALL_GAP
                attempt.failures > 0 -> backoff(attempt.failures)
                else -> RESULT_WAIT
            }
            val until = at.plus(gap)
            if (until.isAfter(now)) {
                val reason = when {
                    manual -> "Android allows one silent update every 30 seconds"
                    attempt.failures > 0 -> "the last try failed${attempt.lastError?.let { ": $it" } ?: ""}; trying again later"
                    else -> "waiting for the watch to report the install"
                }
                return WatchPlan.Wait(release, reason, until)
            }
        }
        return WatchPlan.Push(release)
    }

    /** 30 minutes after the first failure, then ×4 each time, at most a day. */
    fun backoff(failures: Int): Duration {
        if (failures <= 0) return Duration.ZERO
        var minutes = 30L
        repeat(minOf(failures - 1, 6)) { minutes *= 4 }
        return Duration.ofMinutes(minOf(minutes, 24 * 60L))
    }

    /** The attempt to store after a push that failed with [error]. */
    fun failed(last: WatchPush?, versionCode: Long, now: Instant, error: String): WatchPush {
        val failures = if (last?.versionCode == versionCode) last.failures + 1 else 1
        return WatchPush(versionCode, now.toString(), failures, error.take(200))
    }

    /** The attempt to store when the watch took the APK; it reports the install on its own. */
    fun sent(last: WatchPush?, versionCode: Long, now: Instant): WatchPush {
        val failures = if (last?.versionCode == versionCode) last.failures else 0
        return WatchPush(versionCode, now.toString(), failures, last?.lastError?.takeIf { last.versionCode == versionCode })
    }
}

/**
 * The watch's ABIs (`ro.product.cpu.abilist`, read over ADB at setup, or
 * `Build.SUPPORTED_ABIS` from the watch app). The wear APK carries embedded
 * Cronet's native code for [APK_ABIS] only; a watch that runs none of them
 * would refuse it with INSTALL_FAILED_NO_MATCHING_ABIS, so the phone doesn't
 * push it there.
 *
 * Since 0.4.5 (BUG-45) that is armeabi-v7a: the Galaxy Watch 8 (Wear OS 6)
 * runs 32-bit apps only and reports `armeabi-v7a, armeabi`. 0.4.3 and 0.4.4
 * carried arm64-v8a only, on the wrong belief that its Exynos W1000 runs a
 * 64-bit user space, and never reached it.
 */
object WatchAbi {
    /** The ABIs whose native code the wear APK carries (`ndk.abiFilters` in wear/build.gradle.kts). */
    val APK_ABIS = listOf("armeabi-v7a")

    fun parse(abilist: String): List<String> = abilist.split(',').map { it.trim() }.filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

    /** Whether a watch reporting [abis] can install the wear APK; an empty list (not known) is given the benefit of the doubt. */
    fun runs(abis: List<String>): Boolean = abis.isEmpty() || abis.any { it in APK_ABIS }
}
