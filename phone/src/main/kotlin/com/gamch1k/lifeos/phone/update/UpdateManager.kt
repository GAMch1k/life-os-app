package com.gamch1k.lifeos.phone.update

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.api.AppRelease
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.api.LifeOsJson
import com.gamch1k.lifeos.core.update.UpdateDecision
import com.gamch1k.lifeos.core.update.UpdatePolicy
import com.gamch1k.lifeos.phone.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val fraction: Float) : UpdateState
    data class ReadyToInstall(val release: AppRelease) : UpdateState
    data class NeedsPermission(val release: AppRelease) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class WaitingForConfirmation(val release: AppRelease, val confirm: Intent?) : UpdateState
    data class Failed(val message: String, val release: AppRelease?) : UpdateState
}

/**
 * Self-update from Life OS (P18-09), by the contract in the life-os
 * repository's docs/operations/android-releases.md: read `/android/updates`,
 * take `apps.phone` only if its versionCode is higher, download it through
 * the authenticated client, check size and SHA-256, check that the archive is
 * this app at that version, and hand it to PackageInstaller with
 * USER_ACTION_NOT_REQUIRED. Android then installs it only if the release key
 * signed it. It asks the owner to confirm once, on the first self-update
 * after a browser install (the browser is the installer of record); once Life
 * OS installed the current version, later updates are silent.
 *
 * When: on app start (at most every 6 hours; only tells), daily from
 * [UpdateWorker] on an unmetered network (downloads, and installs at once
 * while the app is not on screen), and on "Check for updates" (does it all).
 */
class UpdateManager(
    private val app: Application,
    private val client: LifeOsClient,
    private val settings: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val foreground: StateFlow<Boolean>,
) {
    enum class Trigger { START, DAILY, MANUAL }

    private val stateFlow = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = stateFlow.asStateFlow()

    private val mutex = Mutex()
    private val dir = File(app.filesDir, "updates")
    private val downloader = ReleaseDownloader(client)

    @Volatile
    private var inFlight: AppRelease? = null

    val canInstall: Boolean get() = app.packageManager.canRequestPackageInstalls()

    /** Settings › Apps › Special access › Install unknown apps, for this app. */
    val permissionIntent: Intent
        get() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun check(trigger: Trigger) = mutex.withLock {
        val now = Instant.now()
        if (trigger == Trigger.START && !UpdatePolicy.checkDue(lastCheck(), now, START_INTERVAL)) return@withLock
        if (trigger == Trigger.MANUAL) stateFlow.value = UpdateState.Checking
        val manifest = try {
            client.updateManifest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LifeOsException) {
            if (error is LifeOsException.Api && error.status == 404) {
                // Nothing published yet.
                settings.edit { it[LAST_CHECK] = now.toString() }
                stateFlow.value = UpdateState.UpToDate
            } else if (trigger == Trigger.MANUAL) {
                stateFlow.value = UpdateState.Failed(error.message ?: "The check failed.", null)
            }
            return@withLock
        }
        settings.edit { it[LAST_CHECK] = now.toString() }
        when (val decision = UpdatePolicy.decide(manifest, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT)) {
            is UpdateDecision.UpToDate -> {
                stateFlow.value = UpdateState.UpToDate
                dir.deleteRecursively()
            }
            is UpdateDecision.NeedsNewerAndroid -> stateFlow.value = UpdateState.Failed(
                "Version ${decision.release.versionName} needs Android API ${decision.release.minSdk}; this phone has ${Build.VERSION.SDK_INT}.",
                null,
            )
            is UpdateDecision.UnsupportedFormat -> stateFlow.value = UpdateState.Failed(
                "This version cannot read the update manifest (format ${decision.formatVersion}). Install the new version by hand from the server's /android/ page.",
                null,
            )
            is UpdateDecision.Invalid -> stateFlow.value = UpdateState.Failed("The published update is unusable: ${decision.reason}.", null)
            is UpdateDecision.Available -> when (trigger) {
                Trigger.START -> stateFlow.value = UpdateState.Available(decision.release)
                Trigger.DAILY -> {
                    if (!canInstall) {
                        stateFlow.value = UpdateState.NeedsPermission(decision.release)
                    } else if (download(decision.release) && !foreground.value) {
                        install(decision.release)
                    }
                }
                Trigger.MANUAL -> downloadAndInstall(decision.release)
            }
        }
    }

    /** The banner's and the Settings button's action for the current state. */
    fun act() {
        scope.launch {
            mutex.withLock {
                when (val current = stateFlow.value) {
                    is UpdateState.Available -> downloadAndInstall(current.release)
                    is UpdateState.ReadyToInstall -> install(current.release)
                    is UpdateState.NeedsPermission -> app.startActivity(permissionIntent)
                    is UpdateState.WaitingForConfirmation -> {
                        val confirm = current.confirm
                        if (confirm == null || runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
                            install(current.release)
                        }
                    }
                    is UpdateState.Failed -> current.release?.let { downloadAndInstall(it) }
                    else -> Unit
                }
            }
        }
    }

    /** Back from the "Install unknown apps" setting: carry on if it was granted. */
    fun onResume() {
        if (stateFlow.value !is UpdateState.NeedsPermission || !canInstall) return
        scope.launch {
            mutex.withLock {
                val current = stateFlow.value
                if (current is UpdateState.NeedsPermission && canInstall) downloadAndInstall(current.release)
            }
        }
    }

    private suspend fun downloadAndInstall(release: AppRelease) {
        if (!canInstall) {
            stateFlow.value = UpdateState.NeedsPermission(release)
            return
        }
        if (download(release)) install(release)
    }

    private fun apkFile(release: AppRelease) = File(dir, "lifeos-phone-${release.versionCode}.apk")

    /** Downloads (resuming a partial file) and verifies; true when [apkFile] is ready. */
    private suspend fun download(release: AppRelease): Boolean {
        stateFlow.value = UpdateState.Downloading(release, 0f)
        val result = try {
            downloader.fetch(release, dir, apkFile(release).name) { fraction -> stateFlow.value = UpdateState.Downloading(release, fraction) }
        } catch (cancelled: CancellationException) {
            // The worker was stopped (Wi-Fi left, time limit): the partial file stays for a resume.
            stateFlow.value = UpdateState.Available(release)
            throw cancelled
        }
        return when (result) {
            is ReleaseDownloader.Result.Ready -> {
                stateFlow.value = UpdateState.ReadyToInstall(release)
                true
            }
            is ReleaseDownloader.Result.Failed -> {
                stateFlow.value = UpdateState.Failed(result.message, release)
                false
            }
        }
    }

    private suspend fun install(release: AppRelease) = withContext(Dispatchers.IO) {
        val file = apkFile(release)
        val packageManager = app.packageManager
        val archive = packageManager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        val problem = when {
            !file.isFile -> "the downloaded file is gone"
            archive == null -> "the download is not an APK"
            archive.packageName != app.packageName -> "the APK is ${archive.packageName}, not this app"
            archive.longVersionCode != release.versionCode -> "the APK is version ${archive.longVersionCode}, the manifest says ${release.versionCode}"
            archive.longVersionCode <= BuildConfig.VERSION_CODE -> "the APK is not newer than this app (a downgrade is refused)"
            else -> null
        }
        if (problem != null) {
            file.delete()
            stateFlow.value = UpdateState.Failed("The update was refused: $problem.", release)
            return@withContext
        }
        stateFlow.value = UpdateState.Installing(release)
        val installer = packageManager.packageInstaller
        // A session left by an earlier attempt (cancelled, or never confirmed) goes first.
        installer.mySessions.filter { it.appPackageName == app.packageName }.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setSize(file.length())
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                // Silent when this app installed the current version and holds
                // UPDATE_PACKAGES_WITHOUT_USER_ACTION; otherwise (the first
                // update after a browser install) Android asks once.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            sessionId = installer.createSession(params)
            persistInFlight(release)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(app, InstallResultReceiver::class.java).setPackage(app.packageName)
                val pending = PendingIntent.getBroadcast(app, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            if (error is CancellationException) {
                stateFlow.value = UpdateState.ReadyToInstall(release)
                throw error
            }
            stateFlow.value = UpdateState.Failed("Android did not take the update: ${error.message}", release)
        }
    }

    /** The release being installed, also on disk: the installer's answer may wake a fresh process. */
    private suspend fun persistInFlight(release: AppRelease) {
        inFlight = release
        withContext(NonCancellable) { settings.edit { it[INSTALLING] = LifeOsJson.encodeToString(AppRelease.serializer(), release) } }
    }

    private suspend fun releaseInFlight(): AppRelease? = inFlight ?: settings.data.first()[INSTALLING]?.let {
        runCatching { LifeOsJson.decodeFromString(AppRelease.serializer(), it) }.getOrNull()
    }?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }?.also { inFlight = it }

    /** PackageInstaller wants the owner to confirm. */
    internal suspend fun onPendingUserAction(confirm: Intent?) {
        val release = releaseInFlight()
        if (release != null) stateFlow.value = UpdateState.WaitingForConfirmation(release, confirm)
        if (foreground.value && confirm != null) {
            runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    internal suspend fun onInstallFailed(status: Int, message: String?) {
        val release = releaseInFlight()
        stateFlow.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED && release != null) {
            UpdateState.ReadyToInstall(release)
        } else {
            UpdateState.Failed("Android refused the update (${statusName(status)}): ${message ?: "no reason given"}", release)
        }
    }

    internal fun onInstalled() {
        inFlight = null
        stateFlow.value = UpdateState.UpToDate
    }

    /**
     * Called once per launch: "Updated to x.y" when this launch runs a higher
     * versionCode than the last one, else null.
     */
    suspend fun noteLaunch(): String? {
        val previous = settings.data.first()[LAST_RUN_VERSION]
        val now = BuildConfig.VERSION_CODE.toLong()
        if (previous == now) return null
        settings.edit {
            it[LAST_RUN_VERSION] = now
            it.remove(INSTALLING)
        }
        if (previous != null && previous < now) {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            return "Updated to ${BuildConfig.VERSION_NAME}"
        }
        return null
    }

    /** Sign out: no downloaded APK stays behind. */
    suspend fun forget() {
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
        inFlight = null
        stateFlow.value = UpdateState.Idle
        settings.edit {
            it.remove(LAST_CHECK)
            it.remove(INSTALLING)
        }
    }

    private suspend fun lastCheck(): Instant? =
        settings.data.first()[LAST_CHECK]?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private companion object {
        val START_INTERVAL: Duration = Duration.ofHours(6)
        val LAST_CHECK = stringPreferencesKey("update-last-check")
        val INSTALLING = stringPreferencesKey("update-installing")
        val LAST_RUN_VERSION = longPreferencesKey("last-run-version-code")

        fun statusName(status: Int) = when (status) {
            PackageInstaller.STATUS_FAILURE -> "failure"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
            PackageInstaller.STATUS_FAILURE_INVALID -> "invalid"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "storage"
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> "timeout"
            else -> "status $status"
        }
    }
}
