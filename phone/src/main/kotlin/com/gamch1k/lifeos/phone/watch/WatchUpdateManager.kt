package com.gamch1k.lifeos.phone.watch

import android.app.Application
import android.net.ConnectivityManager
import android.os.SystemClock
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
import com.gamch1k.lifeos.core.update.TransferMeter
import com.gamch1k.lifeos.core.update.TransferProgress
import com.gamch1k.lifeos.core.update.UpdatePolicy
import com.gamch1k.lifeos.core.update.WatchPlan
import com.gamch1k.lifeos.core.update.WatchPush
import com.gamch1k.lifeos.core.update.WatchUpdateRules
import com.gamch1k.lifeos.core.watchsetup.PreparedApk
import com.gamch1k.lifeos.core.wearlink.ApkOffer
import com.gamch1k.lifeos.core.wearlink.ApkStatusState
import com.gamch1k.lifeos.core.wearlink.ApkTransferHeader
import com.gamch1k.lifeos.core.wearlink.PushPath
import com.gamch1k.lifeos.core.wearlink.TransferVersion
import com.gamch1k.lifeos.core.wearlink.UpdateErrorCode
import com.gamch1k.lifeos.core.wearlink.WatchInfo
import com.gamch1k.lifeos.core.wearlink.WatchUpdateStatus
import com.gamch1k.lifeos.core.wearlink.WearLink
import com.gamch1k.lifeos.phone.ui.Times
import com.gamch1k.lifeos.phone.update.ReleaseDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

/** How the wear APK travels to the watch. */
enum class WatchRoute(val label: String) {
    /** The watch downloads it from Life OS itself, over a Wi-Fi network it asked Android for (transfer v2). */
    WIFI("over Wi-Fi"),

    /** The phone streams it over the Data Layer, which is Bluetooth while the watch is close. */
    PHONE("through the phone over Bluetooth"),
}

sealed interface WatchActivity {
    data object Idle : WatchActivity
    data object Checking : WatchActivity

    /** The phone downloads the wear APK from Life OS. */
    data class Downloading(val release: AppRelease, val progress: TransferProgress) : WatchActivity

    /**
     * The APK is on its way to the watch, [route] says how. [progress] is null
     * before bytes move (the offer, the watch waiting for Wi-Fi); [retry]
     * says why this is another try in the same push (a broken stream that
     * resumes).
     */
    data class Sending(
        val release: AppRelease,
        val route: WatchRoute,
        val progress: TransferProgress?,
        val waitingForWifi: Boolean = false,
        val retry: String? = null,
    ) : WatchActivity

    /** The last check's outcome, for the owner; [problem] marks the ones that need attention. */
    data class Note(val text: String, val problem: Boolean = false) : WatchActivity

    val busy: Boolean get() = this is Checking || this is Downloading || this is Sending
}

/** The words for a [WatchActivity] in progress: a headline and, when bytes move, the numbers. */
object WatchActivityText {
    fun headline(activity: WatchActivity): String = when (activity) {
        WatchActivity.Idle -> ""
        WatchActivity.Checking -> "Checking the watch app…"
        is WatchActivity.Downloading -> "Downloading ${activity.release.versionName} for the watch"
        is WatchActivity.Sending -> when {
            activity.route == WatchRoute.WIFI && activity.waitingForWifi -> "The watch is getting Wi-Fi to download ${activity.release.versionName}…"
            activity.route == WatchRoute.WIFI -> "The watch downloads ${activity.release.versionName} over Wi-Fi"
            activity.progress == null -> "Offering ${activity.release.versionName} to the watch…"
            else -> "Sending ${activity.release.versionName} to the watch ${WatchRoute.PHONE.label}"
        }
        is WatchActivity.Note -> activity.text
    }

    /** "43% · 2.3 of 5.4 MB · 25 KB/s · about 2 min left", or null. */
    fun numbers(activity: WatchActivity): String? = when (activity) {
        is WatchActivity.Downloading -> activity.progress.detail()
        is WatchActivity.Sending -> activity.progress?.detail()
        else -> null
    }
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
 *
 * A push (download, checks, offer, the watch's own download or the stream)
 * runs in [WatchUpdateService], a foreground service with a wake lock, so
 * neither leaving the app nor WorkManager's 10 minutes ends it. Where Android
 * doesn't let the app start one (a background start: the daily job, a report
 * from the watch while the app is closed), it runs where the check runs, as
 * before 0.4.3.
 *
 * Transfer v2 (a 0.4.3 watch): the watch first tries to download the APK
 * itself over Wi-Fi, and the phone follows it; otherwise, or when that fails,
 * the phone streams it, and a broken stream resumes from the bytes the watch
 * holds, up to [MAX_BREAKS] times per push. A 0.4.0–0.4.2 watch gets the
 * whole stream (v1), once per push.
 */
class WatchUpdateManager(
    private val app: Application,
    private val client: LifeOsClient,
    private val settings: DataStore<Preferences>,
    private val link: WatchLink = WatchLink(app),
    /** Starts the push in the foreground service; false when Android doesn't allow it now. */
    private val startService: (Trigger) -> Boolean = { WatchUpdateService.start(app, it) },
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

    /**
     * One check for [trigger]. [inService] is true inside [WatchUpdateService]:
     * then a push runs here; otherwise it moves into the service when Android
     * allows it.
     */
    suspend fun check(trigger: Trigger, inService: Boolean = false) = mutex.withLock {
        val now = Instant.now()
        val prefs = settings.data.first()
        // The service's check repeats the one that started it, which recorded the time.
        if (!inService && (trigger == Trigger.START || trigger == Trigger.WATCH_REPORT)) {
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
                if (!inService && startService(trigger)) {
                    // The service checks again and pushes; its progress shows here and in its notification.
                    activity(WatchActivity.Checking)
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
        val meter = TransferMeter(release.size)
        activity(WatchActivity.Downloading(release, meter.last))
        val download = try {
            downloader.fetch(release, dir, fileName(release)) { fraction ->
                activity(WatchActivity.Downloading(release, meter.update((fraction * release.size).toLong())))
            }
        } catch (cancelled: CancellationException) {
            activity(WatchActivity.Note("Cancelled. The download resumes on the next try."))
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
        val delivery = try {
            deliver(node, release, file)
        } catch (cancelled: CancellationException) {
            activity(
                WatchActivity.Note(
                    if (watch.canResume) "Cancelled. The watch keeps what it received; the next try resumes there." else "Cancelled.",
                ),
            )
            throw cancelled
        }
        when (delivery) {
            is Delivery.Sent -> {
                settings.edit { it[PUSH] = LifeOsJson.encodeToString(WatchPush.serializer(), WatchUpdateRules.sent(last, release.versionCode, now)) }
                activity(WatchActivity.Note("Sent ${release.versionName} to the watch ${delivery.route.label}; it installs it and restarts Life OS."))
            }
            is Delivery.NotNow -> activity(WatchActivity.Note("The watch can't take ${release.versionName} now: ${delivery.text}."))
            Delivery.UpToDate -> activity(WatchActivity.Note("The watch app is up to date."))
            is Delivery.Failed -> fail(last, release, now, delivery.why)
        }
    }

    private sealed interface Delivery {
        data class Sent(val route: WatchRoute) : Delivery

        /** Not a failure of this version (busy, Android's 30 seconds, battery): the next check tries again. */
        data class NotNow(val text: String) : Delivery

        data object UpToDate : Delivery

        data class Failed(val why: String) : Delivery
    }

    /**
     * Offers [file] and gets it onto the watch: the watch's own download
     * (v2), or the stream from where the watch stands. A v2 watch gets
     * another offer after a broken stream or a lost download, which resumes;
     * a v1 watch gets one stream.
     */
    private suspend fun deliver(node: String, release: AppRelease, file: File): Delivery {
        val size = file.length()
        var breaks = 0
        var busyRetries = 0
        var tryWifi = true
        var retry: String? = null
        while (true) {
            val offer = ApkOffer(
                id = UUID.randomUUID().toString(),
                versionCode = release.versionCode,
                versionName = release.versionName,
                size = size,
                sha256 = release.sha256,
                transfer = TransferVersion.CURRENT,
                // The watch tries Wi-Fi once per push; after that the phone streams.
                url = release.url.takeIf { tryWifi },
            )
            activity(WatchActivity.Sending(release, WatchRoute.PHONE, progress = null, retry = retry))
            val reply = try {
                link.offer(node, offer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = "the watch did not answer the offer (${e.message ?: e.javaClass.simpleName})"
                // Only a v2 watch is offered again, and only after it broke off before.
                if (breaks in 1 until MAX_BREAKS) {
                    breaks++
                    retry = why
                    delay(RETRY_DELAY_MILLIS)
                    continue
                }
                return Delivery.Failed(why)
            }
            if (!reply.accepted) {
                val error = reply.error
                val text = error?.message?.ifEmpty { null } ?: error?.code ?: "no reason given"
                return when (error?.code) {
                    // The watch is still finishing the last try (a broken stream it hasn't noticed yet).
                    UpdateErrorCode.BUSY -> if (busyRetries++ < MAX_BUSY_RETRIES) {
                        delay((reply.retryAfterSeconds ?: 10L).coerceIn(5, 30) * 1_000)
                        continue
                    } else {
                        Delivery.NotNow(text)
                    }
                    // Not failures of this version: try again on the next check.
                    UpdateErrorCode.THROTTLED, UpdateErrorCode.LOW_BATTERY -> Delivery.NotNow(text)
                    UpdateErrorCode.NOT_NEWER -> Delivery.UpToDate
                    else -> Delivery.Failed("the watch refused it: $text")
                }
            }
            val resumable = reply.transfer >= TransferVersion.V2
            val from = when (val path = PushPath.after(reply, size)) {
                is PushPath.Stream -> path.offset
                PushPath.WatchDownloads -> {
                    tryWifi = false
                    when (val followed = followDownload(node, offer, release)) {
                        Followed.Installing -> return Delivery.Sent(WatchRoute.WIFI)
                        is Followed.NeedsStream -> {
                            retry = followed.why?.let { "no download over Wi-Fi ($it)" }
                            followed.offset
                        }
                        is Followed.Failed -> return Delivery.Failed(followed.why)
                        is Followed.Lost -> {
                            if (++breaks < MAX_BREAKS) {
                                retry = followed.why
                                delay(RETRY_DELAY_MILLIS)
                                continue
                            }
                            return Delivery.Failed(followed.why)
                        }
                    }
                }
            }
            val header = ApkTransferHeader(id = offer.id, versionCode = offer.versionCode, size = size, sha256 = offer.sha256, offset = from)
            val meter = TransferMeter(size, start = from)
            activity(WatchActivity.Sending(release, WatchRoute.PHONE, meter.last, retry = retry))
            val result = try {
                link.transfer(node, header, file) { position ->
                    activity(WatchActivity.Sending(release, WatchRoute.PHONE, meter.update(position), retry = retry))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val why = "the transfer broke off at ${meter.last.percent}% (${e.message ?: e.javaClass.simpleName})"
                if (resumable && ++breaks < MAX_BREAKS) {
                    retry = "$why; resuming"
                    delay(RETRY_DELAY_MILLIS)
                    continue
                }
                return Delivery.Failed(why)
            }
            if (result.received) return Delivery.Sent(WatchRoute.PHONE)
            val error = result.error
            val text = error?.message?.ifEmpty { null } ?: error?.code ?: "no reason given"
            // A resumed file whose SHA-256 came out wrong: the watch dropped it, once more from byte 0.
            if (resumable && from > 0 && error?.code == UpdateErrorCode.SHA256_MISMATCH && ++breaks < MAX_BREAKS) {
                retry = "the resumed file was damaged; starting over"
                continue
            }
            return Delivery.Failed("the watch discarded it: $text")
        }
    }

    private sealed interface Followed {
        data object Installing : Followed

        /** Wi-Fi did not work out ([why]); stream from [offset]. */
        data class NeedsStream(val offset: Long, val why: String?) : Followed

        /** The update failed for good on the watch. */
        data class Failed(val why: String) : Followed

        /** The watch stopped answering, or forgot the offer: offer again. */
        data class Lost(val why: String) : Followed
    }

    /** Follows the watch's own download on [WearLink.PATH_APK_STATUS] until it installs, needs the stream, or fails. */
    private suspend fun followDownload(node: String, offer: ApkOffer, release: AppRelease): Followed {
        activity(WatchActivity.Sending(release, WatchRoute.WIFI, progress = null, waitingForWifi = true))
        var misses = 0
        var lastSeen: Pair<String, Long>? = null
        var changedAt = SystemClock.elapsedRealtime()
        var checked = false
        while (true) {
            delay(STATUS_POLL_MILLIS)
            val status = try {
                link.status(node, offer.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // After its checks the watch commits the install, which ends its process at once.
                if (checked && misses >= 1) return Followed.Installing
                if (++misses >= STATUS_MISSES) return Followed.Lost("the watch stopped answering during its download (${e.message ?: e.javaClass.simpleName})")
                continue
            }
            misses = 0
            val now = SystemClock.elapsedRealtime()
            if (lastSeen != (status.state to status.received)) {
                lastSeen = status.state to status.received
                changedAt = now
            } else if (now - changedAt > STATUS_STALL_MILLIS) {
                return Followed.Lost("the watch's download stalled")
            }
            val total = status.size.takeIf { it > 0 } ?: offer.size
            when (status.state) {
                ApkStatusState.WAITING_FOR_WIFI -> activity(WatchActivity.Sending(release, WatchRoute.WIFI, progress = null, waitingForWifi = true))
                ApkStatusState.DOWNLOADING, ApkStatusState.VERIFYING -> {
                    checked = status.state == ApkStatusState.VERIFYING
                    activity(WatchActivity.Sending(release, WatchRoute.WIFI, TransferProgress(status.received, total, status.bytesPerSecond)))
                }
                ApkStatusState.INSTALLING -> return Followed.Installing
                ApkStatusState.NEEDS_STREAM -> return Followed.NeedsStream(status.offset.coerceIn(0, offer.size), status.error?.message?.ifEmpty { null } ?: status.error?.code)
                ApkStatusState.FAILED -> return Followed.Failed("the watch refused it: ${status.error?.message?.ifEmpty { null } ?: status.error?.code ?: "no reason given"}")
                ApkStatusState.UNKNOWN -> return Followed.Lost("the watch no longer knows the offer")
                // A state of a later version: keep following; the stall guard ends it.
            }
        }
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
        /** Broken streams or lost downloads per push before it counts as failed (v2 watches only). */
        const val MAX_BREAKS = 4
        const val MAX_BUSY_RETRIES = 3
        const val RETRY_DELAY_MILLIS = 5_000L
        const val STATUS_POLL_MILLIS = 2_000L
        const val STATUS_MISSES = 5

        /** The watch gives up on Wi-Fi after about 45 s and on a download that stops after 30 s; this is the phone's safety net. */
        const val STATUS_STALL_MILLIS = 3 * 60_000L
        val START_INTERVAL: Duration = Duration.ofHours(6)
        val REPORT_INTERVAL: Duration = Duration.ofMinutes(1)
        val LAST_CHECK = stringPreferencesKey("watch-update-last-check")
        val PUSH = stringPreferencesKey("watch-update-push")
        val WATCH_INFO = stringPreferencesKey("watch-info")
        val WATCH_SEEN_AT = stringPreferencesKey("watch-seen-at")
        val WATCH_ABIS = stringPreferencesKey("watch-abis")
    }
}
