package com.gamch1k.lifeos.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * What an APK, or an installed app, says about itself: its package, its
 * versionCode and the SHA-256 digests (lowercase hex) of its signing
 * certificates.
 */
data class ApkFacts(val packageName: String, val versionCode: Long, val signerSha256: Set<String>)

/** Why an APK may not be installed, or null when it may. */
data class ApkRefusal(val code: String, val message: String)

/**
 * The checks every APK passes before it reaches PackageInstaller, on the
 * phone (the wear APK it downloads for the watch) and on the watch (the APK
 * the phone sends it). Android checks the signature again when it installs;
 * checking here gives a clear reason instead of an installer error, and keeps
 * a wrong file from ever being streamed to the watch.
 */
object ApkRules {
    const val NOT_AN_APK = "not_an_apk"
    const val WRONG_PACKAGE = "wrong_package"
    const val WRONG_CERTIFICATE = "wrong_certificate"
    const val VERSION_MISMATCH = "version_mismatch"
    const val NOT_NEWER = "not_newer"

    /**
     * [archive] (null when Android could not read it) must be [own]'s package,
     * signed by exactly [own]'s certificates, at [expectedVersionCode] (what
     * the manifest or the phone's offer promised), and newer than
     * [installedVersionCode]: a downgrade or a reinstall is refused.
     */
    fun check(archive: ApkFacts?, expectedVersionCode: Long, own: ApkFacts, installedVersionCode: Long): ApkRefusal? = when {
        archive == null -> ApkRefusal(NOT_AN_APK, "the file is not an APK Android can read")
        archive.packageName != own.packageName -> ApkRefusal(WRONG_PACKAGE, "the APK is ${archive.packageName}, not ${own.packageName}")
        archive.signerSha256.isEmpty() || archive.signerSha256 != own.signerSha256 ->
            ApkRefusal(WRONG_CERTIFICATE, "the APK is not signed with the Life OS release key")
        archive.versionCode != expectedVersionCode ->
            ApkRefusal(VERSION_MISMATCH, "the APK is version ${archive.versionCode}, ${expectedVersionCode} was announced")
        archive.versionCode <= installedVersionCode ->
            ApkRefusal(NOT_NEWER, "the APK (${archive.versionCode}) is not newer than the installed version ($installedVersionCode); a downgrade is refused")
        else -> null
    }
}

/** Reads [ApkFacts] through PackageManager. */
object ApkInspector {
    private val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())

    /** The facts of an APK file, or null when it is not one. */
    fun archive(context: Context, file: File): ApkFacts? {
        if (!file.isFile) return null
        val info = runCatching { context.packageManager.getPackageArchiveInfo(file.path, flags) }.getOrNull() ?: return null
        return facts(info)
    }

    /** The facts of this app as installed. */
    fun installed(context: Context): ApkFacts =
        facts(context.packageManager.getPackageInfo(context.packageName, flags))

    private fun facts(info: PackageInfo): ApkFacts {
        val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        return ApkFacts(info.packageName, info.longVersionCode, signers.map { sha256(it.toByteArray()) }.toSet())
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
