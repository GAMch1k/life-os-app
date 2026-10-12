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
import androidx.core.app.NotificationCompat
import com.gamch1k.lifeos.core.update.ApkInspector
import com.gamch1k.lifeos.core.update.ApkRules
import com.gamch1k.lifeos.core.update.WatchUpdateRules
import com.gamch1k.lifeos.core.wearlink.ApkOffer
import com.gamch1k.lifeos.core.wearlink.ApkOfferReply
import com.gamch1k.lifeos.core.wearlink.ApkTransferCodec
import com.gamch1k.lifeos.core.wearlink.ApkTransferHeader
import com.gamch1k.lifeos.core.wearlink.ApkTransferResult
import com.gamch1k.lifeos.core.wearlink.UpdateError
import com.gamch1k.lifeos.core.wearlink.UpdateErrorCode
import com.gamch1k.lifeos.core.wearlink.UpdateProtocolException
import com.gamch1k.lifeos.core.wearlink.WatchBattery
import com.gamch1k.lifeos.core.wearlink.WatchInfo
import com.gamch1k.lifeos.core.wearlink.WatchUpdateCodec
import com.gamch1k.lifeos.core.wearlink.WatchUpdateResult
import com.gamch1k.lifeos.core.wearlink.WatchUpdateStatus
import com.gamch1k.lifeos.core.wearlink.WearLink
import com.gamch1k.lifeos.wear.BuildConfig
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.link.PhoneLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * - accepts or refuses an offer ([WatchUpdateRules.checkOffer]);
 * - receives the APK into private storage, checks size and SHA-256 while it
 *   arrives, then the package, the signing certificate (its own) and a higher
 *   versionCode ([ApkRules]);
 * - installs it with a PackageInstaller session and USER_ACTION_NOT_REQUIRED,
 *   silent because the app updates itself, holds UPDATE_PACKAGES_WITHOUT_USER_ACTION
 *   and "Install unknown apps" (granted once at setup);
 * - when Android asks anyway, offers a one-tap confirmation (a notification,
 *   the dialog itself while the app is open, and "Install update" on Home);
 * - reports the result to the phone, after the update on the next start.
 */
class SelfUpdater(
    private val context: Context,
    private val store: WatchUpdateStore,
    private val phone: PhoneLink,
    private val foreground: () -> Boolean,
) {
    private val dir = File(context.filesDir, "updates")
    private val mutex = Mutex()
    private val reportMutex = Mutex()

    /** The offer accepted last, and when; a transfer must match it. */
    @Volatile
    private var accepted: Pair<ApkOffer, Instant>? = null

    // ---- The phone asks

    fun info(id: String = ""): WatchInfo = WatchInfo(
        id = id,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        versionName = BuildConfig.VERSION_NAME,
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
        capabilities = listOf(WearLink.WATCH_CAN_UPDATE),
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

    /** [WearLink.PATH_APK_OFFER]. */
    fun handleOffer(payload: ByteArray): ByteArray {
        val offer = try {
            WatchUpdateCodec.decodeOffer(payload)
        } catch (e: UpdateProtocolException) {
            return WatchUpdateCodec.encodeOfferReply(ApkOfferReply(id = "", accepted = false, error = UpdateError(e.code, e.message.orEmpty())))
        }
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
        if (error != null) {
            val retryAfter = when (error.code) {
                UpdateErrorCode.THROTTLED -> lastInstall?.let { WatchUpdateRules.retryAfter(it, now) }
                UpdateErrorCode.BUSY -> 60L
                else -> null
            }
            return WatchUpdateCodec.encodeOfferReply(ApkOfferReply(id = offer.id, accepted = false, error = error, retryAfterSeconds = retryAfter))
        }
        accepted = offer to now
        return WatchUpdateCodec.encodeOfferReply(ApkOfferReply(id = offer.id, accepted = true))
    }

    /** [WearLink.PATH_APK_TRANSFER]: receive, check, answer, then install. */
    suspend fun handleTransfer(input: InputStream, output: OutputStream) {
        val header = try {
            ApkTransferCodec.readHeader(input)
        } catch (e: UpdateProtocolException) {
            ApkTransferCodec.writeResult(output, ApkTransferResult(id = "", received = false, error = UpdateError(e.code, e.message.orEmpty())))
            return
        }
        val offer = accepted?.takeIf { (offer, at) ->
            offer.id == header.id && offer.size == header.size && offer.sha256 == header.sha256 && offer.versionCode == header.versionCode &&
                Duration.between(at, Instant.now()) < OFFER_LIFETIME
        }?.first
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
            val apk = receive(input, output, header) ?: return
            install(apk, offer.versionCode, offer.versionName)
        } finally {
            mutex.unlock()
        }
    }

    /** Receives into private storage and checks the archive; null (and an answer saying why) when refused. */
    private suspend fun receive(input: InputStream, output: OutputStream, header: ApkTransferHeader): File? = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, "incoming.part")
        try {
            part.outputStream().buffered().use { sink -> ApkTransferCodec.readBody(input, header, sink) }
        } catch (e: UpdateProtocolException) {
            part.delete()
            refuse(output, header, UpdateError(e.code, e.message.orEmpty()))
            return@withContext null
        } catch (e: Exception) {
            part.delete()
            // The channel broke: the phone sees that itself.
            if (e is CancellationException) throw e
            return@withContext null
        }
        val apk = File(dir, apkName(header.versionCode))
        if (!part.renameTo(apk)) {
            part.delete()
            refuse(output, header, UpdateError(UpdateErrorCode.STORAGE, "could not store the APK"))
            return@withContext null
        }
        val refusal = ApkRules.check(ApkInspector.archive(context, apk), header.versionCode, ApkInspector.installed(context), BuildConfig.VERSION_CODE.toLong())
        if (refusal != null) {
            apk.delete()
            refuse(output, header, UpdateError(refusal.code, refusal.message))
            store.save(WatchUpdateResult(header.versionCode, "", WatchUpdateStatus.REFUSED, refusal.message, Instant.now().toString()))
            report()
            return@withContext null
        }
        ApkTransferCodec.writeResult(output, ApkTransferResult(id = header.id, received = true))
        apk
    }

    private fun refuse(output: OutputStream, header: ApkTransferHeader, error: UpdateError) {
        runCatching { ApkTransferCodec.writeResult(output, ApkTransferResult(id = header.id, received = false, error = error)) }
    }

    // ---- Installing

    private suspend fun install(apk: File, versionCode: Long, versionName: String) = withContext(Dispatchers.IO) {
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
        } catch (e: Exception) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            if (e is CancellationException) throw e
            store.save(WatchUpdateResult(versionCode, versionName, WatchUpdateStatus.FAILED, "Android did not take the update: ${e.message}", Instant.now().toString()))
            report()
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
                cancelConfirmNotification()
            }
            else -> {
                val why = if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "cancelled on the watch" else "${statusName(status)}: ${message ?: "no reason given"}"
                store.save(WatchUpdateResult(versionCode, versionName, WatchUpdateStatus.FAILED, "Android refused the update ($why)", now))
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
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
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
