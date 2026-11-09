package com.gamch1k.lifeos.phone.update

import com.gamch1k.lifeos.core.api.AppRelease
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.update.ApkIntegrity
import com.gamch1k.lifeos.core.update.UpdatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Downloads a release's APK from the update origin (LifeOsClient.download)
 * into a directory of its own, resuming a partial file, and keeps it only when its
 * size and SHA-256 are the manifest's. Used for the phone's own update and
 * for the wear APK the phone sends to the watch.
 */
class ReleaseDownloader(private val client: LifeOsClient) {
    sealed interface Result {
        data class Ready(val file: File) : Result

        data class Failed(val message: String) : Result
    }

    /**
     * [dir] holds only this download: other files in it are deleted.
     * [onProgress] gets the fraction done. Cancellation keeps the partial
     * file for a resume.
     */
    suspend fun fetch(release: AppRelease, dir: File, fileName: String, onProgress: (Float) -> Unit): Result = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val target = File(dir, fileName)
        val part = File(dir, "$fileName.part")
        dir.listFiles()?.filter { it != target && it != part }?.forEach { it.deleteRecursively() }
        if (target.isFile && ApkIntegrity.check(target, release.size, release.sha256) == ApkIntegrity.Result.Ok) {
            return@withContext Result.Ready(target)
        }
        target.delete()
        val resume = UpdatePolicy.resumeFrom(part.length(), release.size)
        if (resume == null) part.delete()
        var base = 0L
        onProgress((resume ?: 0L).toFloat() / release.size)
        try {
            client.download(
                release.url,
                sink = { status, _ ->
                    val append = status == 206 && resume != null
                    base = if (append) resume else 0L
                    FileOutputStream(part, append)
                },
                rangeStart = resume,
                onProgress = { read -> onProgress(((base + read).toFloat() / release.size).coerceIn(0f, 1f)) },
            )
        } catch (error: LifeOsException) {
            if (error is LifeOsException.Api && error.status == 416) part.delete()
            return@withContext Result.Failed("The download stopped: ${error.message}")
        }
        when (val result = ApkIntegrity.check(part, release.size, release.sha256)) {
            ApkIntegrity.Result.Ok -> {
                part.renameTo(target)
                Result.Ready(target)
            }
            is ApkIntegrity.Result.WrongSize -> {
                part.delete()
                Result.Failed("The download was discarded: it has ${result.actual} bytes, the manifest says ${result.expected}.")
            }
            is ApkIntegrity.Result.WrongSha256 -> {
                part.delete()
                Result.Failed("The download was discarded: its SHA-256 is not the published one.")
            }
        }
    }
}
