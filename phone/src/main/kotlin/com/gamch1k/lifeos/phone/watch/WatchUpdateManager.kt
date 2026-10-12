package com.gamch1k.lifeos.phone.watch

import android.app.Application
import android.net.ConnectivityManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.gamch1k.lifeos.core.api.AppRelease
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.api.LifeOsJson
import com.gamch1k.lifeos.core.update.ApkInspector
import com.gamch1k.lifeos.core.update.ApkRules
import com.gamch1k.lifeos.core.update.ReleaseApp
import com.gamch1k.lifeos.core.update.UpdatePolicy
import com.gamch1k.lifeos.core.update.WatchPlan
import com.gamch1k.lifeos.core.update.WatchPush
import com.gamch1k.lifeos.core.update.WatchUpdateRules
import com.gamch1k.lifeos.core.watchsetup.PreparedApk
import com.gamch1k.lifeos.core.wearlink.ApkOffer
import com.gamch1k.lifeos.core.wearlink.ApkTransferHeader
import com.gamch1k.lifeos.core.wearlink.UpdateErrorCode
import com.gamch1k.lifeos.core.wearlink.WatchInfo
import com.gamch1k.lifeos.core.wearlink.WatchUpdateStatus
import com.gamch1k.lifeos.phone.ui.Times
import com.gamch1k.lifeos.phone.update.ReleaseDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What Settings › Watch shows. */
data class WatchStatus(
    /** What the watch app last told the phone, and when. */
    val watch: WatchInfo? = null,
    val seenAt: Instant? = null,
    /** `ro.product.cpu.abilist` from the setup, else what the watch app reports. */
    val abis: List<String> = emptyList(),
    val activity: WatchActivity = WatchActivity.Idle,
)

sealed interface WatchActivity {
    data object Idle : WatchActivity
    data object Checking : WatchActivity
    data class Downloading(val release: AppRelease, val fraction: Float) : WatchActivity
    data class Sending(val release: AppRelease, val fraction: Float) : WatchActivity

    /** The last check's outcome, for the owner; [problem] marks the ones that need attention. */
    data class Note(val text: String, val problem: Boolean = false) : WatchActivity
}

/**
 * Updates the watch app through the phone (P19-06, docs/wear.md "Install and
 * updates"). When a check runs, it asks the watch its version over the Data
 * Layer, and when Life OS publishes a newer `apps.wear` it downloads the APK,
 * checks it (size, SHA-256, package, version, the release key), offers it to
 * the watch and streams it. The watch checks it again and installs it itself
 * with `USER_ACTION_NOT_REQUIRED`; it reports the result when it starts again.
 *
 * Checks run daily (with the phone's own update, on an unmetered network),
 * when the app opens (at most every 6 hours), when the watch app reports in
 * (it just started, or finished an install), and on "Update now". Backoff
 * after failures, Android's 30 seconds, and the watch's battery are
 * [WatchUpdateRules.plan]'s.
 */
class WatchUpdateManager(
    private val app: Application,
    private val client: LifeOsClient,
    private val settings: DataStore<Preferences>,
    private val link: WatchLink = WatchLink(app),
) {
    enum class Trigger { START, DAILY, WATCH_REPORT, MANUAL }

    private val mutableStatus = MutableStateFlow(WatchStatus())
    val status: StateFlow<WatchStatus> = mutableStatus.asStateFlow()

    private val mutex = Mutex()
    private val dir = File(app.filesDir, "updates-wear")
    private val downloader = ReleaseDownloader(client)

    /** Fills [status] from what was stored (Settings opens before any check). */
    suspend fun load() {
        val prefs = settings.data.first()
        mutableStatus.value = mutableStatus.value.copy(
            watch = prefs[WATCH_INFO]?.let { decode(WatchInfo.serializer(), it) },
            seenAt = prefs[WATCH_SEEN_AT]?.let { runCatching { Instant.parse(it) }.getOrNull() },
            abis = prefs[WATCH_ABIS]?.let { decode(ListSerializer(String.serializer()), it) }
                ?: prefs[WATCH_INFO]?.let { decode(WatchInfo.serializer(), it) }?.abis.orEmpty(),
        )
    }

    suspend fun check(trigger: Trigger) = mutex.withLock {
        val now = Instant.now()
        val prefs = settings.data.first()
        if (trigger == Trigger.START || trigger == Trigger.WATCH_REPORT) {
            val last = prefs[LAST_CHECK]?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val interval = if (trigger == Trigger.START) START_INTERVAL else REPORT_INTERVAL
            if (!UpdatePolicy.checkDue(last, now, interval)) return@withLock
        }
        if (trigger == Trigger.MANUAL) activity(WatchActivity.Checking)
        val node = link.watchNode()
        if (node == null) {
            if (trigger == Trigger.MANUAL) activity(WatchActivity.Note("No watch with Life OS is connected to this phone.", problem = true))
            return@withLock
        }
        val watch = try {
            link.info(node)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            activity(
                WatchActivity.Note(
                    "The watch app doesn't answer the update check: it is older than 0.4.0 or not installed. Set it up from here once.",
                    problem = true,
                ),
            )
            return@withLock
        }
        remember(watch, now)
        val manifest = try {
            client.updateManifest()
        } catch (e: CancellationException) {
            throw e
        } catch (error: LifeOsException) {
            if (trigger == Trigger.MANUAL) activity(WatchActivity.Note("The update check failed: ${error.message}", problem = true))
            return@withLock
        }
        settings.edit { it[LAST_CHECK] = now.toString() }
        val last = prefs[PUSH]?.let { decode(WatchPush.serializer(), it) }
        when (val plan = WatchUpdateRules.plan(manifest, watch, last, now, manual = trigger == Trigger.MANUAL)) {
            is WatchPlan.UpToDate -> {
                activity(WatchActivity.Note("The watch app is up to date."))
                withIo { dir.deleteRecursively() }
            }
            is WatchPlan.Wait -> activity(WatchActivity.Note("Version ${plan.release.versionName} waits: ${plan.reason}."))
            is WatchPlan.NeedsSetup -> activity(WatchActivity.Note("Updates through the phone need the setup: ${plan.reason}.", problem = true))
            is WatchPlan.Unusable -> activity(WatchActivity.Note(plan.reason.replaceFirstChar { it.uppercase() } + ".", problem = true))
            is WatchPlan.Push -> {
                if (trigger != Trigger.MANUAL && isMetered() && !downloaded(plan.release)) {
                    activity(WatchActivity.Note("Version ${plan.release.versionName} for the watch downloads on Wi-Fi."))
                    return@withLock
                }
                push(node, watch, plan.release, last, now)
            }
        }
    }

    /** The watch app reported in (it started after an update, or an install ended). */
    suspend fun onWatchReport(info: WatchInfo) {
        mutex.withLock { remember(info, Instant.now()) }
        check(Trigger.WATCH_REPORT)
    }

    /**
     * For the setup: the newest `apps.wear`, downloaded and checked. Throws
     * with a readable message.
     */
    suspend fun prepareForSetup(onProgress: (Float) -> Unit): PreparedApk = mutex.withLock {
        val manifest = try {
            client.updateManifest()
        } catch (error: LifeOsException) {
            if (error is LifeOsException.Api && error.status == 404) throw IllegalStateException("nothing is published on Life OS yet")
            throw IllegalStateException(error.message ?: "the update check failed")
        }
        if (manifest.formatVersion != UpdatePolicy.FORMAT_VERSION) throw IllegalStateException("the update manifest has format ${manifest.formatVersion}")
        val release = ReleaseApp.WEAR.release(manifest) ?: throw IllegalStateException("no watch app is published yet")
        UpdatePolicy.validate(ReleaseApp.WEAR, release)?.let { throw IllegalStateException("the published watch app is unusable: $it") }
        val file = when (val result = downloader.fetch(release, dir, fileName(release), onProgress)) {
            is ReleaseDownloader.Result.Ready -> result.file
            is ReleaseDownloader.Result.Failed -> throw IllegalStateException(result.message)
        }
        verify(file, release, installedOnWatch = 0)?.let { throw IllegalStateException(it) }
        PreparedApk(file, release.versionCode, release.versionName)
    }

    /** Sign out: no downloaded APK stays behind; what the watch reported stays (it is no secret). */
    suspend fun forget() = mutex.withLock {
        withIo { dir.deleteRecursively() }
        settings.edit {
            it.remove(PUSH)
            it.remove(LAST_CHECK)
        }
        activity(WatchActivity.Idle)
    }

    /** The setup read the watch's ABIs over ADB. */
    suspend fun rememberAbis(abis: List<String>) {
        settings.edit { it[WATCH_ABIS] = LifeOsJson.encodeToString(ListSerializer(String.serializer()), abis) }
        mutableStatus.value = mutableStatus.value.copy(abis = abis)
    }

    suspend fun rememberWatch(info: WatchInfo) = mutex.withLock { remember(info, Instant.now()) }

    private suspend fun push(node: String, watch: WatchInfo, release: AppRelease, last: WatchPush?, now: Instant) {
        activity(WatchActivity.Downloading(release, 0f))
        val download = try {
            downloader.fetch(release, dir, fileName(release)) { activity(WatchActivity.Downloading(release, it)) }
        } catch (cancelled: CancellationException) {
            activity(WatchActivity.Idle)
            throw cancelled
        }
        val file = when (download) {
            is ReleaseDownloader.Result.Ready -> download.file
            is ReleaseDownloader.Result.Failed -> {
                activity(WatchActivity.Note(download.message, problem = true))
                return
            }
        }
        verify(file, release, watch.versionCode)?.let { problem ->
            withIo { file.delete() }
            fail(last, release, now, "the downloaded watch app was refused: $problem")
            return
        }
        val offer = ApkOffer(id = UUID.randomUUID().toString(), versionCode = release.versionCode, versionName = release.versionName, size = file.length(), sha256 = release.sha256)
        activity(WatchActivity.Sending(release, 0f))
        try {
            val reply = link.offer(node, offer)
            if (!reply.accepted) {
                val error = reply.error
                val text = error?.message?.ifEmpty { null } ?: error?.code ?: "no reason given"
                when (error?.code) {
                    // Not failures of this version: try again on the next check.
                    UpdateErrorCode.THROTTLED, UpdateErrorCode.BUSY, UpdateErrorCode.LOW_BATTERY ->
                        activity(WatchActivity.Note("The watch can't take ${release.versionName} now: $text."))
                    UpdateErrorCode.NOT_NEWER -> activity(WatchActivity.Note("The watch app is up to date."))
                    else -> fail(last, release, now, "the watch refused it: $text")
                }
                return
            }
            val result = link.transfer(node, ApkTransferHeader(id = offer.id, versionCode = offer.versionCode, size = offer.size, sha256 = offer.sha256), file) {
                activity(WatchActivity.Sending(release, it))
            }
            if (!result.received) {
                fail(last, release, now, "the watch discarded it: ${result.error?.message?.ifEmpty { null } ?: result.error?.code ?: "no reason given"}")
                return
            }
        } catch (cancelled: CancellationException) {
            activity(WatchActivity.Idle)
            throw cancelled
        } catch (error: Exception) {
            fail(last, release, now, "the transfer broke off (${error.message ?: error.javaClass.simpleName})")
            return
        }
        settings.edit { it[PUSH] = LifeOsJson.encodeToString(WatchPush.serializer(), WatchUpdateRules.sent(last, release.versionCode, now)) }
        activity(WatchActivity.Note("Sent ${release.versionName} to the watch; it installs it and restarts Life OS."))
    }

    private suspend fun fail(last: WatchPush?, release: AppRelease, now: Instant, why: String) {
        val attempt = WatchUpdateRules.failed(last, release.versionCode, now, why)
        settings.edit { it[PUSH] = LifeOsJson.encodeToString(WatchPush.serializer(), attempt) }
        val retry = now.plus(WatchUpdateRules.backoff(attempt.failures))
        activity(WatchActivity.Note("Version ${release.versionName} did not reach the watch: $why. Next try after ${retryText(retry)}.", problem = true))
    }

    /** The wear APK's size and SHA-256 were checked by the download; now its package, version and signer. */
    private suspend fun verify(file: File, release: AppRelease, installedOnWatch: Long): String? = withIo {
        val own = ApkInspector.installed(app)
        // The phone app and the watch app share one package name and one release key.
        ApkRules.check(ApkInspector.archive(app, file), release.versionCode, own, installedOnWatch)?.message
    }

    private suspend fun remember(info: WatchInfo, now: Instant) {
        val prefs = settings.edit {
            it[WATCH_INFO] = LifeOsJson.encodeToString(WatchInfo.serializer(), info.copy(id = ""))
            it[WATCH_SEEN_AT] = now.toString()
            val result = info.lastUpdate
            val push = it[PUSH]?.let { text -> decode(WatchPush.serializer(), text) }
            if (result != null && push != null && result.versionCode == push.versionCode) {
                val resultAt = runCatching { Instant.parse(result.at) }.getOrNull()
                val pushAt = runCatching { Instant.parse(push.at) }.getOrNull()
                when {
                    result.status == WatchUpdateStatus.INSTALLED -> it.remove(PUSH)
                    // An install that failed on the watch counts like a push that failed: the backoff grows.
                    (result.status == WatchUpdateStatus.FAILED || result.status == WatchUpdateStatus.REFUSED) &&
                        resultAt != null && pushAt != null && resultAt.isAfter(pushAt) ->
                        it[PUSH] = LifeOsJson.encodeToString(
                            WatchPush.serializer(),
                            WatchUpdateRules.failed(push, push.versionCode, now, "the watch's install failed: ${result.message}"),
                        )
                }
            }
        }
        val abis = prefs[WATCH_ABIS]?.let { decode(ListSerializer(String.serializer()), it) } ?: info.abis
        mutableStatus.value = mutableStatus.value.copy(watch = info, seenAt = now, abis = abis)
    }

    private fun downloaded(release: AppRelease) = File(dir, fileName(release)).length() == release.size

    private fun fileName(release: AppRelease) = "lifeos-wear-${release.versionCode}.apk"

    private fun isMetered(): Boolean = app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    private fun activity(value: WatchActivity) {
        mutableStatus.value = mutableStatus.value.copy(activity = value)
    }

    private fun retryText(at: Instant): String = Times.short(at)

    private suspend fun <T> withIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T? =
        runCatching { LifeOsJson.decodeFromString(serializer, text) }.getOrNull()

    private companion object {
        val START_INTERVAL: Duration = Duration.ofHours(6)
        val REPORT_INTERVAL: Duration = Duration.ofMinutes(1)
        val LAST_CHECK = stringPreferencesKey("watch-update-last-check")
        val PUSH = stringPreferencesKey("watch-update-push")
        val WATCH_INFO = stringPreferencesKey("watch-info")
        val WATCH_SEEN_AT = stringPreferencesKey("watch-seen-at")
        val WATCH_ABIS = stringPreferencesKey("watch-abis")
    }
}
