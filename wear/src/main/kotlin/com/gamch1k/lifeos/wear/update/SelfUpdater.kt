package com.gamch1k.lifeos.wear.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.gamch1k.lifeos.core.update.ApkInspector
import com.gamch1k.lifeos.core.update.ApkRules
import com.gamch1k.lifeos.core.update.PartialApk
import com.gamch1k.lifeos.core.update.TransferMeter
import com.gamch1k.lifeos.core.update.TransferProgress
import com.gamch1k.lifeos.core.update.WatchUpdateRules
import com.gamch1k.lifeos.core.wearlink.ApkOffer
import com.gamch1k.lifeos.core.wearlink.ApkOfferReply
import com.gamch1k.lifeos.core.wearlink.ApkRoute
import com.gamch1k.lifeos.core.wearlink.ApkStatus
import com.gamch1k.lifeos.core.wearlink.ApkStatusState
import com.gamch1k.lifeos.core.wearlink.ApkTransferCodec
import com.gamch1k.lifeos.core.wearlink.ApkTransferHeader
import com.gamch1k.lifeos.core.wearlink.ApkTransferResult
import com.gamch1k.lifeos.core.wearlink.TransferVersion
import com.gamch1k.lifeos.core.wearlink.UpdateError
import com.gamch1k.lifeos.core.wearlink.UpdateErrorCode
import com.gamch1k.lifeos.core.wearlink.UpdateProtocolException
import com.gamch1k.lifeos.core.wearlink.WatchBattery
import com.gamch1k.lifeos.core.wearlink.WatchInfo
import com.gamch1k.lifeos.core.wearlink.WatchUpdateCodec
import com.gamch1k.lifeos.core.wearlink.WatchUpdateResult
import com.gamch1k.lifeos.core.wearlink.WatchUpdateStatus
import com.gamch1k.lifeos.core.wearlink.WearCredentials
import com.gamch1k.lifeos.core.wearlink.WearLink
import com.gamch1k.lifeos.wear.BuildConfig
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.link.PhoneLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.time.Instant

/**
 * The watch's end of updates through the phone (P19-06, docs/wear.md
 * "Install and updates"):
 *
 * - answers the phone's version question ([info]);
 * - accepts or refuses an offer ([WatchUpdateRules.checkOffer]); with
 *   transfer v2 (0.4.3) it answers with the bytes it already holds of that
 *   APK ([PartialApk]) and whether it downloads the APK itself over Wi-Fi
 *   ([DirectDownload]), and answers the phone's status requests about that
 *   download ([handleStatusRequest]);
 * - receives the APK from the phone's stream into private storage (in v2
 *   from the held bytes on), checks size and SHA-256 while it arrives, then
 *   the package, the signing certificate (its own) and a higher versionCode
 *   ([ApkRules]);
 * - installs it with a PackageInstaller session and USER_ACTION_NOT_REQUIRED,
 *   silent because the app updates itself, holds UPDATE_PACKAGES_WITHOUT_USER_ACTION
 *   and "Install unknown apps" (granted once at setup);
 * - when Android asks anyway, offers a one-tap confirmation (a notification,
 *   the dialog itself while the app is open, and "Install update" on Home);
 * - reports the result to the phone, after the update on the next start.
 *
 * Every part that must not be cut off (waiting for the stream, the download,
 * the stream, the checks, the install) holds [keepAlive]: a wake lock and
 * the foreground service with the progress notification
 * ([WatchUpdateService]). [progress] is what Connection and that
 * notification show.
 */
class SelfUpdater(
    private val context: Context,
    private val store: WatchUpdateStore,
    private val phone: PhoneLink,
    private val foreground: () -> Boolean,
    private val keepAlive: UpdateKeepAlive,
    private val directDownload: DirectDownload,
    private val credentials: () -> WearCredentials?,
    private val scope: CoroutineScope,
) {
    private val dir = File(context.filesDir, "updates")

    /** The partly received APK, kept across a broken stream or download (transfer v2). */
    private val partial = PartialApk(File(context.filesDir, "updates-partial"))

    /** Held by whatever receives, downloads, checks or installs an APK: one update at a time. */
    private val mutex = Mutex()

    /** Offers are answered one after the other. */
    private val offerMutex = Mutex()
    private val reportMutex = Mutex()

    private class Accepted(val offer: ApkOffer, val at: Instant)

    /** The offer accepted last, and when; a transfer must match it. */
    @Volatile
    private var accepted: Accepted? = null

    /** The watch's own download of the accepted offer, while it runs. */
    @Volatile
    private var download: Job? = null

    private val mutableProgress = MutableStateFlow<UpdateProgress?>(null)

    /** The update in progress, or for a while how the last one ended; null when there is none. */
    val progress: StateFlow<UpdateProgress?> = mutableProgress.asStateFlow()

    @Volatile
    private var lastPublished = 0L

    /** The offer and state the phone last heard in a status reply (the Wi-Fi route lets it hear "installing"). */
    private val phoneHeard = MutableStateFlow<Pair<String, String>?>(null)

    // ---- The phone asks

    fun info(id: String = ""): WatchInfo = WatchInfo(
        id = id,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        versionName = BuildConfig.VERSION_NAME,
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
        capabilities = listOf(WearLink.WATCH_CAN_UPDATE, WearLink.WATCH_CAN_RESUME),
        canInstallPackages = canInstall(),
        battery = battery(),
        lastUpdate = store.result.value,
    )

    /** [WearLink.PATH_WATCH_INFO]. */
    fun handleInfoRequest(payload: ByteArray): ByteArray {
        val id = try {
            WatchUpdateCodec.decodeInfoRequest(payload).id
        } catch (_: UpdateProtocolException) {
            ""
        }
        return WatchUpdateCodec.encodeInfo(info(id))
    }

    /**
     * [WearLink.PATH_APK_OFFER]. Runs while the Data Layer's request is
     * open, so the keep-alive (and with it the foreground service) starts
     * while Play services has the app's listener bound.
     */
    suspend fun handleOffer(payload: ByteArray): ByteArray = offerMutex.withLock {
        val offer = try {
            WatchUpdateCodec.decodeOffer(payload)
        } catch (e: UpdateProtocolException) {
            return@withLock WatchUpdateCodec.encodeOfferReply(ApkOfferReply(id = "", accepted = false, error = UpdateError(e.code, e.message.orEmpty())))
        }
        replaceLostDownload()
        val now = Instant.now()
        val lastInstall = store.lastInstallAt
        val error = WatchUpdateRules.checkOffer(
            offer = offer,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
            canInstall = canInstall(),
            battery = battery(),
            freeBytes = context.filesDir.usableSpace,
            lastInstallAt = lastInstall,
            busy = mutex.isLocked,
            now = now,
        )
        if (error != null) return@withLock refuseOffer(offer, error, lastInstall, now)
        val transfer = TransferVersion.agreed(offer)
        if (transfer < TransferVersion.V2) {
            // A 0.4.0–0.4.2 phone: the whole APK through its stream, as before.
            accepted = Accepted(offer, now)
            awaitStream(waiting(offer, offset = 0, error = null))
            return@withLock WatchUpdateCodec.encodeOfferReply(ApkOfferReply(id = offer.id, accepted = true))
        }
        val held = withContext(Dispatchers.IO) { partial.offset(offer.sha256, offer.size) }
        val wifi = DirectDownload.worthTrying(offer.url, credentials(), held, offer.size)
        if (wifi && !mutex.tryLock()) {
            return@withLock refuseOffer(offer, UpdateError(UpdateErrorCode.BUSY, "another update is running"), lastInstall, now)
        }
        accepted = Accepted(offer, now)
        if (wifi) {
            startDownload(offer)
        } else {
            awaitStream(waiting(offer, offset = held, error = null))
        }
        WatchUpdateCodec.encodeOfferReply(ApkOfferReply(id = offer.id, accepted = true, transfer = transfer, offset = held, download = wifi))
    }

    private fun refuseOffer(offer: ApkOffer, error: UpdateError, lastInstall: Instant?, now: Instant): ByteArray {
        val retryAfter = when (error.code) {
            UpdateErrorCode.THROTTLED -> lastInstall?.let { WatchUpdateRules.retryAfter(it, now) }
            // Usually a stream that broke on the phone's side and ends here a moment later.
            UpdateErrorCode.BUSY -> BUSY_RETRY_SECONDS
            else -> null
        }
        return WatchUpdateCodec.encodeOfferReply(ApkOfferReply(id = offer.id, accepted = false, error = error, retryAfterSeconds = retryAfter))
    }

    /**
     * A new offer while the watch still waits for Wi-Fi or downloads an
     * earlier one: the phone lost track of it, so the new offer replaces it.
     * What was downloaded stays in the partial file, and the new offer's
     * reply counts it.
     */
    private suspend fun replaceLostDownload() {
        val job = download?.takeIf { it.isActive } ?: return
        val state = mutableProgress.value?.state
        if (state == ApkStatusState.WAITING_FOR_WIFI || state == ApkStatusState.DOWNLOADING) {
            job.cancelAndJoin()
            publish(null)
        }
    }

    /** [WearLink.PATH_APK_STATUS] (transfer v2): how the accepted offer goes. */
    fun handleStatusRequest(payload: ByteArray): ByteArray {
        val request = try {
            WatchUpdateCodec.decodeStatusRequest(payload)
        } catch (e: UpdateProtocolException) {
            return WatchUpdateCodec.encodeStatus(ApkStatus(id = "", state = ApkStatusState.UNKNOWN, error = UpdateError(e.code, e.message.orEmpty())))
        }
        val current = mutableProgress.value?.takeIf { it.offerId == request.id }
            ?: return WatchUpdateCodec.encodeStatus(UpdateProgress.unknown(request.id))
        phoneHeard.value = current.offerId to current.state
        return WatchUpdateCodec.encodeStatus(current.status())
    }

    // ---- The watch's own download (transfer v2)

    /** Runs [DirectDownload] for [offer]. The caller locked [mutex]; the download job owns it now. */
    private fun startDownload(offer: ApkOffer) {
        keepAlive.acquire()
        publish(progressOf(offer, ApkStatusState.WAITING_FOR_WIFI, ApkRoute.WIFI))
        download = scope.launch(Dispatchers.IO) {
            var locked = true
            fun unlock() {
                if (locked) {
                    locked = false
                    mutex.unlock()
                }
            }
            try {
                var meter: TransferMeter? = null
                val outcome = try {
                    directDownload.run(
                        url = offer.url,
                        credentials = credentials(),
                        sha256 = offer.sha256,
                        size = offer.size,
                        partial = partial,
                        onStage = { stage, start ->
                            when (stage) {
                                DirectDownload.Stage.WAITING_FOR_WIFI -> publish(progressOf(offer, ApkStatusState.WAITING_FOR_WIFI, ApkRoute.WIFI))
                                DirectDownload.Stage.DOWNLOADING -> {
                                    val started = TransferMeter(offer.size, start).also { meter = it }
                                    publish(progressOf(offer, ApkStatusState.DOWNLOADING, ApkRoute.WIFI, started.last))
                                }
                            }
                        },
                        onProgress = { position ->
                            meter?.let { publishMoving(progressOf(offer, ApkStatusState.DOWNLOADING, ApkRoute.WIFI, it.update(position))) }
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DirectDownload.Outcome.Failed("the download failed (${e.message ?: e.javaClass.simpleName})")
                }
                if (outcome == DirectDownload.Outcome.Downloaded) {
                    // From here on nothing cancels it: the checks and the install run to their end.
                    withContext(NonCancellable) {
                        val apk = takeWhole(offer, ApkRoute.WIFI, meter?.last?.bytesPerSecond) ?: return@withContext
                        if (!verify(apk, offer, ApkRoute.WIFI)) return@withContext
                        accepted = null
                        publish(progressOf(offer, ApkStatusState.INSTALLING, ApkRoute.WIFI))
                        // The commit ends this process; let the phone hear "installing" first (it asks every 2 s).
                        withTimeoutOrNull(INSTALL_HANDOFF_MILLIS) { phoneHeard.first { it == offer.id to ApkStatusState.INSTALLING } }
                        install(apk, offer)
                    }
                    return@launch
                }
                val error = when (outcome) {
                    is DirectDownload.Outcome.NoWifi -> UpdateError(UpdateErrorCode.NO_WIFI, outcome.why)
                    is DirectDownload.Outcome.Skipped -> UpdateError(UpdateErrorCode.DOWNLOAD_FAILED, outcome.why)
                    is DirectDownload.Outcome.Failed -> UpdateError(UpdateErrorCode.DOWNLOAD_FAILED, outcome.why)
                    DirectDownload.Outcome.Downloaded -> null
                }
                val held = partial.offset(offer.sha256, offer.size)
                // The phone's stream of the same offer takes over; the offer's 10 minutes start again.
                accepted = Accepted(offer, Instant.now())
                unlock()
                awaitStream(waiting(offer, held, error))
            } finally {
                unlock()
                keepAlive.release()
            }
        }
    }

    private fun progressOf(offer: ApkOffer, state: String, route: String, progress: TransferProgress? = null) =
        UpdateProgress(offer.id, offer.versionCode, offer.versionName, offer.size, state, route, progress)

    private fun waiting(offer: ApkOffer, offset: Long, error: UpdateError?) =
        UpdateProgress(offer.id, offer.versionCode, offer.versionName, offer.size, ApkStatusState.NEEDS_STREAM, ApkRoute.PHONE, offset = offset, error = error)

    /**
     * Publishes [waiting] and keeps the app awake until the phone's stream
     * begins (or another offer comes), at most [STREAM_WAIT_MILLIS]; then the
     * waiting state is dropped. A stream that comes later still works while
     * the offer is valid.
     */
    private fun awaitStream(waiting: UpdateProgress) {
        publish(waiting)
        keepAlive.acquire()
        scope.launch {
            try {
                withTimeoutOrNull(STREAM_WAIT_MILLIS) { mutableProgress.first { it != waiting } }
            } finally {
                keepAlive.release()
            }
            mutableProgress.compareAndSet(waiting, null)
        }
    }

    // ---- The phone's stream

    /**
     * [WearLink.PATH_APK_TRANSFER]: receive, check, answer, then install. The
     * caller holds [keepAlive] while this runs.
     */
    suspend fun handleTransfer(input: InputStream, output: OutputStream) {
        val header = try {
            ApkTransferCodec.readHeader(input)
        } catch (e: UpdateProtocolException) {
            ApkTransferCodec.writeResult(output, ApkTransferResult(id = "", received = false, error = UpdateError(e.code, e.message.orEmpty())))
            return
        }
        val offer = accepted?.takeIf { taken ->
            val offer = taken.offer
            offer.id == header.id && offer.size == header.size && offer.sha256 == header.sha256 && offer.versionCode == header.versionCode &&
                Duration.between(taken.at, Instant.now()) < OFFER_LIFETIME
        }?.offer
        if (offer == null) {
            refuse(output, header, UpdateError(UpdateErrorCode.UNKNOWN_OFFER, "no accepted offer matches this transfer"))
            return
        }
        if (!mutex.tryLock()) {
            refuse(output, header, UpdateError(UpdateErrorCode.BUSY, "another update is running"))
            return
        }
        try {
            accepted = null
            val apk = receive(input, output, header, offer) ?: return
            install(apk, offer)
        } finally {
            mutex.unlock()
        }
    }

    /** Receives into the partial file, then checks the archive; null (and an answer saying why) when it is not taken. */
    private suspend fun receive(input: InputStream, output: OutputStream, header: ApkTransferHeader, offer: ApkOffer): File? = withContext(Dispatchers.IO) {
        // A v1 phone starts at 0, which drops whatever is held; a v2 phone starts where the watch said.
        val held = partial.offset(header.sha256, header.size)
        if (header.offset > held) {
            refuse(output, header, UpdateError(UpdateErrorCode.ABORTED, "the watch holds $held bytes of it, not ${header.offset}; offer it again"))
            return@withContext null
        }
        val meter = TransferMeter(header.size, header.offset)
        publish(progressOf(offer, ApkStatusState.RECEIVING, ApkRoute.PHONE, meter.last))
        val writer = try {
            partial.open(header.sha256, header.size, header.offset)
        } catch (e: Exception) {
            val error = UpdateError(UpdateErrorCode.STORAGE, "could not store the APK (${e.message ?: e.javaClass.simpleName})")
            refuse(output, header, error)
            failed(offer, ApkRoute.PHONE, error)
            return@withContext null
        }
        var checkpointed = header.offset
        try {
            ApkTransferCodec.readBody(input, header, writer, writer.digest) { position ->
                if (position - checkpointed >= CHECKPOINT_BYTES) {
                    writer.checkpoint()
                    checkpointed = position
                }
                publishMoving(progressOf(offer, ApkStatusState.RECEIVING, ApkRoute.PHONE, meter.update(position)))
            }
        } catch (e: UpdateProtocolException) {
            val error = UpdateError(e.code, e.message.orEmpty())
            if (e.code == UpdateErrorCode.ABORTED) {
                // The stream broke off: keep what arrived, the next offer resumes there.
                writer.close()
                refuse(output, header, error)
                broken(offer, error)
            } else {
                // Too many bytes, too few, or the wrong SHA-256: nothing of it is worth keeping.
                writer.abandon()
                partial.discard()
                refuse(output, header, error)
                failed(offer, ApkRoute.PHONE, error)
            }
            return@withContext null
        } catch (e: CancellationException) {
            writer.close()
            throw e
        } catch (e: Exception) {
            // The channel broke: the phone sees that itself. What arrived stays for a resume.
            writer.close()
            broken(offer, UpdateError(UpdateErrorCode.ABORTED, "the stream broke off (${e.message ?: e.javaClass.simpleName})"))
            return@withContext null
        }
        writer.close()
        val apk = takeWhole(offer, ApkRoute.PHONE, meter.last.bytesPerSecond)
        if (apk == null) {
            refuse(output, header, UpdateError(UpdateErrorCode.STORAGE, "could not store the APK"))
            return@withContext null
        }
        if (!verify(apk, offer, ApkRoute.PHONE, onRefused = { refuse(output, header, it) })) return@withContext null
        ApkTransferCodec.writeResult(output, ApkTransferResult(id = header.id, received = true))
        publish(progressOf(offer, ApkStatusState.INSTALLING, ApkRoute.PHONE))
        apk
    }

    /** A stream that broke off: the watch waits for the phone's next offer, which resumes. */
    private fun broken(offer: ApkOffer, error: UpdateError) {
        val waiting = waiting(offer, partial.offset(offer.sha256, offer.size), error)
        publish(waiting)
        expireLater(waiting)
    }

    private fun refuse(output: OutputStream, header: ApkTransferHeader, error: UpdateError) {
        runCatching { ApkTransferCodec.writeResult(output, ApkTransferResult(id = header.id, received = false, error = error)) }
    }

    // ---- Checking and installing

    /** Moves the whole partial file to the APK's place; null (and [failed]) when that doesn't work. */
    private suspend fun takeWhole(offer: ApkOffer, route: String, bytesPerSecond: Long?): File? = withContext(Dispatchers.IO) {
        publish(progressOf(offer, ApkStatusState.VERIFYING, route, TransferProgress(offer.size, offer.size, bytesPerSecond)))
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, apkName(offer.versionCode))
        if (partial.complete(apk)) return@withContext apk
        failed(offer, route, UpdateError(UpdateErrorCode.STORAGE, "could not store the APK"))
        null
    }

    /** The package, the certificate and the version ([ApkRules]); false (recorded and reported) when refused. */
    private suspend fun verify(apk: File, offer: ApkOffer, route: String, onRefused: (UpdateError) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val refusal = ApkRules.check(ApkInspector.archive(context, apk), offer.versionCode, ApkInspector.installed(context), BuildConfig.VERSION_CODE.toLong())
            ?: return@withContext true
        apk.delete()
        val error = UpdateError(refusal.code, refusal.message)
        onRefused(error)
        failed(offer, route, error)
        store.save(WatchUpdateResult(offer.versionCode, offer.versionName, WatchUpdateStatus.REFUSED, refusal.message, Instant.now().toString()))
        report()
        false
    }

    private fun failed(offer: ApkOffer, route: String, error: UpdateError) {
        val value = UpdateProgress(offer.id, offer.versionCode, offer.versionName, offer.size, ApkStatusState.FAILED, route, error = error)
        publish(value)
        expireLater(value)
    }

    private suspend fun install(apk: File, offer: ApkOffer) {
        if (install(apk, offer.versionCode, offer.versionName)) return
        val why = store.result.value?.message?.ifEmpty { null } ?: "Android did not take the update"
        failed(offer, mutableProgress.value?.route ?: ApkRoute.PHONE, UpdateError(UpdateErrorCode.INTERNAL, why))
    }

    /** Commits [apk]; false when Android didn't take it (recorded and reported). */
    private suspend fun install(apk: File, versionCode: Long, versionName: String): Boolean = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        // A session left by an earlier attempt (never confirmed, or cut off) goes first.
        installer.mySessions.filter { it.appPackageName == context.packageName }.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        store.installing(versionCode, versionName, Instant.now())
        var sessionId = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                // Silent: the app updates itself and holds UPDATE_PACKAGES_WITHOUT_USER_ACTION
                // and "Install unknown apps". Within 30 s of the last one, Android asks.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(InstallResultReceiver.EXTRA_VERSION_CODE, versionCode)
                    .putExtra(InstallResultReceiver.EXTRA_VERSION_NAME, versionName)
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pending.intentSender)
            }
            true
        } catch (e: Exception) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            if (e is CancellationException) throw e
            store.save(WatchUpdateResult(versionCode, versionName, WatchUpdateStatus.FAILED, "Android did not take the update: ${e.message}", Instant.now().toString()))
            report()
            false
        }
    }

    /** "Install update" on Home: commit the waiting APK again, now with the app on screen (Android then shows its dialog). */
    suspend fun installWaiting(): Boolean {
        val waiting = store.result.value?.takeIf { it.status == WatchUpdateStatus.WAITING_FOR_CONFIRMATION } ?: return false
        val apk = File(dir, apkName(waiting.versionCode)).takeIf { it.isFile } ?: return false
        if (!mutex.tryLock()) return false
        try {
            install(apk, waiting.versionCode, waiting.versionName)
        } finally {
            mutex.unlock()
        }
        return true
    }

    /** PackageInstaller's verdict ([InstallResultReceiver]). */
    suspend fun onInstallStatus(status: Int, message: String?, confirm: Intent?, versionCode: Long, versionName: String) {
        val now = Instant.now().toString()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                store.save(WatchUpdateResult(versionCode, versionName, WatchUpdateStatus.WAITING_FOR_CONFIRMATION, "", now), pending = true)
                publish(null)
                if (confirm != null) {
                    if (foreground()) {
                        runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    notifyConfirm(confirm, versionName)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Usually never seen: the update ends this process. The next start says it ([onAppStart]).
                store.save(WatchUpdateResult(versionCode, versionName, WatchUpdateStatus.INSTALLED, "", now))
                publish(null)
                cancelConfirmNotification()
            }
            else -> {
                val why = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled on the watch" else "${statusName(status)}: ${message ?: "no reason given"}"
                store.save(WatchUpdateResult(versionCode, versionName, WatchUpdateStatus.FAILED, "Android refused the update ($why)", now))
                mutableProgress.value?.takeIf { it.versionCode == versionCode }?.let { current ->
                    val value = current.copy(state = ApkStatusState.FAILED, progress = null, error = UpdateError(UpdateErrorCode.INTERNAL, "Android refused the update ($why)"))
                    publish(value)
                    expireLater(value)
                }
                cancelConfirmNotification()
                withContext(Dispatchers.IO) { File(dir, apkName(versionCode)).delete() }
            }
        }
        report()
    }

    /** Every start (and MY_PACKAGE_REPLACED): after an update the new version says so, once, to the phone. */
    suspend fun onAppStart() = reportMutex.withLock {
        val pending = store.pendingVersionCode
        if (pending > 0 && BuildConfig.VERSION_CODE >= pending) {
            store.save(WatchUpdateResult(pending, BuildConfig.VERSION_NAME, WatchUpdateStatus.INSTALLED, "", Instant.now().toString()))
            cancelConfirmNotification()
            withContext(Dispatchers.IO) {
                dir.deleteRecursively()
                partial.discard()
            }
        }
        reportLocked()
    }

    /** Sends the unreported result to the phone (a no-op when there is none, or no phone). */
    suspend fun report() = reportMutex.withLock { reportLocked() }

    private suspend fun reportLocked() {
        if (store.reported) return
        val result = store.result.value ?: return
        val sent = try {
            phone.send(WearLink.PATH_WATCH_REPORT, WatchUpdateCodec.encodeInfo(info()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (sent) store.markReported(result)
    }

    // ---- Progress

    private fun publish(value: UpdateProgress?) {
        lastPublished = SystemClock.elapsedRealtime()
        mutableProgress.value = value
    }

    /** While bytes move: at most two updates a second, but a new state, and the last byte, at once. */
    private fun publishMoving(value: UpdateProgress) {
        val previous = mutableProgress.value
        val last = value.progress?.let { it.done >= it.total } == true
        if (!last && previous?.offerId == value.offerId && previous.state == value.state &&
            SystemClock.elapsedRealtime() - lastPublished < PROGRESS_INTERVAL_MILLIS
        ) {
            return
        }
        publish(value)
    }

    /** Drops an ended state ([value]) after a while, unless something newer replaced it. */
    private fun expireLater(value: UpdateProgress) {
        scope.launch {
            delay(STREAM_WAIT_MILLIS)
            mutableProgress.compareAndSet(value, null)
        }
    }

    // ---- Helpers

    private fun canInstall() = context.packageManager.canRequestPackageInstalls()

    private fun battery(): WatchBattery? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: return null
        return WatchBattery(percent, manager.isCharging)
    }

    private fun notifyConfirm(confirm: Intent, versionName: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val tap = PendingIntent.getActivity(context, 0, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(context.getString(R.string.update_ready, versionName))
            .setContentText(context.getString(R.string.update_tap_to_install))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .build()
        // Without notification permission this does nothing; Home still offers "Install update".
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelConfirmNotification() {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun apkName(versionCode: Long) = "lifeos-wear-$versionCode.apk"

    private companion object {
        val OFFER_LIFETIME: Duration = Duration.ofMinutes(10)
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_ID = 1906

        /** The phone asks again after this when the watch is busy (it keeps it within 5–30 s). */
        const val BUSY_RETRY_SECONDS = 10L

        /** How long the watch stays awake for a stream that hasn't begun, and shows an ended state. */
        const val STREAM_WAIT_MILLIS = 2 * 60_000L

        /** On the Wi-Fi route, how long the install waits for the phone to hear "installing". */
        const val INSTALL_HANDOFF_MILLIS = 5_000L

        /** The partial file is synced and recorded every this many bytes (about every 15 s at 17 KB/s). */
        const val CHECKPOINT_BYTES = 256L * 1024
        const val PROGRESS_INTERVAL_MILLIS = 500L

        fun statusName(status: Int) = when (status) {
            PackageInstaller.STATUS_FAILURE -> "failure"
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
