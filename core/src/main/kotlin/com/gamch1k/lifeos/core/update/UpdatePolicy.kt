package com.gamch1k.lifeos.core.update

import com.gamch1k.lifeos.core.api.AppRelease
import com.gamch1k.lifeos.core.api.UpdateManifest
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** What the update manifest means for the installed app. */
sealed interface UpdateDecision {
    /** The published versionCode is not higher than ours (a lower one is refused, never installed). */
    data class UpToDate(val published: AppRelease?) : UpdateDecision

    data class Available(val release: AppRelease) : UpdateDecision

    /** Newer, but it needs a newer Android than this phone has. */
    data class NeedsNewerAndroid(val release: AppRelease) : UpdateDecision

    /** A manifest format this app does not understand (formatVersion other than 1). */
    data class UnsupportedFormat(val formatVersion: Int) : UpdateDecision

    /** The entry is unusable (bad SHA-256, size, or URL, or a URL of another source); never downloaded. */
    data class Invalid(val reason: String) : UpdateDecision
}

/** The two apps of the manifest (`apps.phone`, `apps.wear`). */
enum class ReleaseApp(val key: String) {
    PHONE("phone"),
    WEAR("wear"),
    ;

    fun release(manifest: UpdateManifest): AppRelease? = when (this) {
        PHONE -> manifest.apps.phone
        WEAR -> manifest.apps.wear
    }
}

/**
 * The update rules of docs/operations/android-releases.md in the life-os
 * repository: install an app's entry (`apps.phone` on the phone, `apps.wear`
 * for the watch) only if its versionCode is greater than the installed one
 * and the device's API level is at least its minSdk, and only from an address
 * of the [UpdateSource] the manifest came from: a path under `/android/` on
 * the update origin, or an asset of a GitHub release of the update
 * repository. Any other address is refused before a byte is downloaded.
 */
object UpdatePolicy {
    const val FORMAT_VERSION = 1
    private val SHA256 = Regex("^[0-9a-f]{64}$")

    private fun apkPath(app: ReleaseApp) = Regex("^/android/lifeos-${app.key}-[0-9A-Za-z][0-9A-Za-z._+-]*-[0-9]+\\.apk$")

    /** The phone app's own update, from a manifest of [source]. */
    fun decide(manifest: UpdateManifest, installedVersionCode: Long, sdkInt: Int, source: UpdateSource): UpdateDecision =
        decide(ReleaseApp.PHONE, manifest, installedVersionCode, sdkInt, source)

    /**
     * [app]'s entry of a manifest of [source] against the installed
     * [installedVersionCode] on a device with [sdkInt] (for the watch: the
     * version and API level it reports). A lower or equal versionCode is
     * never offered (no downgrade, whatever the source).
     */
    fun decide(app: ReleaseApp, manifest: UpdateManifest, installedVersionCode: Long, sdkInt: Int, source: UpdateSource): UpdateDecision {
        if (manifest.formatVersion != FORMAT_VERSION) return UpdateDecision.UnsupportedFormat(manifest.formatVersion)
        val release = app.release(manifest) ?: return UpdateDecision.UpToDate(null)
        if (release.versionCode <= installedVersionCode) return UpdateDecision.UpToDate(release)
        validate(app, release, source)?.let { return UpdateDecision.Invalid(it) }
        if (sdkInt < release.minSdk) return UpdateDecision.NeedsNewerAndroid(release)
        return UpdateDecision.Available(release)
    }

    /** Why [release] of a manifest of [source] can't be downloaded at all, or null when its hash, size and URL are usable. */
    fun validate(app: ReleaseApp, release: AppRelease, source: UpdateSource): String? = when {
        !SHA256.matches(release.sha256) -> "the SHA-256 is not 64 lowercase hex digits"
        release.size <= 0 -> "the size is not positive"
        !isApkUrl(app, release.url, source) -> when (source) {
            UpdateSource.UpdateOrigin -> "the URL is not a ${app.key} APK under /android/"
            is UpdateSource.GitHubRepo -> "the URL is not a ${app.key} APK of a ${source.repo} release on GitHub"
        }
        else -> null
    }

    /** Whether [url] is one of [app]'s APKs at [source]: a path under `/android/`, or an asset of a release on GitHub. */
    fun isApkUrl(app: ReleaseApp, url: String, source: UpdateSource): Boolean = when (source) {
        UpdateSource.UpdateOrigin -> isApkPath(app, url)
        is UpdateSource.GitHubRepo -> GitHubReleases.isApkUrl(source.repo, app, url)
    }

    /** Whether [url] is the path of one of [app]'s APKs under `/android/` (the watch checks the phone's offer with it). */
    fun isApkPath(app: ReleaseApp, url: String): Boolean = apkPath(app).matches(url) && ".." !in url

    /** Whether an automatic check is due, at most one per [interval]. */
    fun checkDue(lastCheck: Instant?, now: Instant, interval: Duration): Boolean =
        lastCheck == null || lastCheck.isAfter(now) || Duration.between(lastCheck, now) >= interval

    /**
     * Where to resume a download of [expectedSize] bytes when [existing]
     * bytes are already on disk: null starts over (nothing yet, or the file is
     * complete or too long and must be checked or deleted).
     */
    fun resumeFrom(existing: Long, expectedSize: Long): Long? = if (existing in 1 until expectedSize) existing else null
}

/** Checks a downloaded APK against the manifest before it goes near the installer. */
object ApkIntegrity {
    sealed interface Result {
        data object Ok : Result
        data class WrongSize(val actual: Long, val expected: Long) : Result
        data class WrongSha256(val actual: String, val expected: String) : Result
    }

    fun check(file: File, expectedSize: Long, expectedSha256: String): Result {
        val size = file.length()
        if (size != expectedSize) return Result.WrongSize(size, expectedSize)
        val actual = sha256(file)
        return if (actual == expectedSha256.lowercase()) Result.Ok else Result.WrongSha256(actual, expectedSha256)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
