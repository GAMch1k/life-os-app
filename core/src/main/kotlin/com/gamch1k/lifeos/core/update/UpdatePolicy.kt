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

    /** The entry is unusable (bad SHA-256, size, or URL); never downloaded. */
    data class Invalid(val reason: String) : UpdateDecision
}

/**
 * The update rules of docs/operations/android-releases.md in the life-os
 * repository: install `apps.phone` only if its versionCode is greater than
 * ours and the device's API level is at least its minSdk.
 */
object UpdatePolicy {
    const val FORMAT_VERSION = 1
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val APK_PATH = Regex("^/android/lifeos-phone-[0-9A-Za-z][0-9A-Za-z._+-]*-[0-9]+\\.apk$")

    fun decide(manifest: UpdateManifest, installedVersionCode: Long, sdkInt: Int): UpdateDecision {
        if (manifest.formatVersion != FORMAT_VERSION) return UpdateDecision.UnsupportedFormat(manifest.formatVersion)
        val release = manifest.apps.phone ?: return UpdateDecision.UpToDate(null)
        if (release.versionCode <= installedVersionCode) return UpdateDecision.UpToDate(release)
        if (!SHA256.matches(release.sha256)) return UpdateDecision.Invalid("the SHA-256 is not 64 lowercase hex digits")
        if (release.size <= 0) return UpdateDecision.Invalid("the size is not positive")
        if (!APK_PATH.matches(release.url) || ".." in release.url) return UpdateDecision.Invalid("the URL is not a phone APK under /android/")
        if (sdkInt < release.minSdk) return UpdateDecision.NeedsNewerAndroid(release)
        return UpdateDecision.Available(release)
    }

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
