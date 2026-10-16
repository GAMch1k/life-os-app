package com.gamch1k.lifeos.wear.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.gamch1k.lifeos.core.wearlink.ApkStatusState
import com.gamch1k.lifeos.wear.LifeOsWear
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps the watch app's process alive and awake while an update arrives
 * (docs/wear.md "Install and updates"): every part of the update that must
 * not be cut off (waiting for the phone's stream, the Wi-Fi download, the
 * stream, the checks and the install) holds it, and while anything does, a
 * partial wake lock is held and [WatchUpdateService] runs in the foreground.
 *
 * [acquire] is called inside the Data Layer's callbacks (the offer, the
 * channel), while Play services still has the listener bound. Where Android
 * refuses the foreground service even there (a background start), the wake
 * lock still holds and a cut transfer resumes (transfer v2); the setup takes
 * the app off the battery optimisations, which is one of Android's documented
 * exemptions for background starts.
 */
class UpdateKeepAlive(private val context: Context) {
    private val holds = MutableStateFlow(0)

    /** How many parts of an update hold the process now. */
    val held: StateFlow<Int> = holds.asStateFlow()

    /** Whether the last [acquire] got the foreground service. */
    @Volatile
    var foreground: Boolean = false
        private set

    private val wakeLock: PowerManager.WakeLock? by lazy {
        context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifeos:wear-update")
            ?.apply { setReferenceCounted(false) }
    }

    fun acquire() {
        var first = false
        holds.update { count ->
            first = count == 0
            count + 1
        }
        if (first) runCatching { wakeLock?.acquire(WAKE_LOCK_CAP_MILLIS) }
        // Once it runs, the service stays until nothing holds this any more.
        if (first || !WatchUpdateService.running) foreground = WatchUpdateService.start(context)
    }

    fun release() {
        var last = false
        holds.update { count ->
            last = count == 1
            (count - 1).coerceAtLeast(0)
        }
        if (last) runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
    }

    suspend fun <T> hold(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private companion object {
        /** Longer than any update should take (6.6 MB at 4 KB/s is 28 minutes); released as soon as nothing holds it. */
        const val WAKE_LOCK_CAP_MILLIS = 60 * 60_000L
    }
}

/**
 * The foreground service (type `dataSync`) that keeps the watch app alive
 * while an update arrives, with an ongoing notification of its progress
 * ("Over Wi-Fi: 43% · 2.3 of 5.4 MB · about 20 s left"). It stops when
 * nothing holds [UpdateKeepAlive] any more.
 */
class WatchUpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var lastStartId = 0
    private var lastNotified = 0L
    private var lastState: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val graph = LifeOsWear.graph(this)
        // Every startForegroundService() needs its startForeground(), even while already running.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(graph.selfUpdater.progress.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: RuntimeException) {
            // Not allowed after all (ForegroundServiceStartNotAllowedException, a missing permission):
            // the wake lock alone keeps the update going.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                val updates = launch { graph.selfUpdater.progress.collect { notifyProgress(it) } }
                // On the main thread, like onStartCommand: a new hold between the check and the stop
                // comes with a new start, and stopSelfResult then keeps the service.
                while (true) {
                    graph.updateKeepAlive.held.first { it == 0 }
                    if (graph.updateKeepAlive.held.value == 0) break
                }
                updates.cancel()
                ServiceCompat.stopForeground(this@WatchUpdateService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelfResult(lastStartId)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15's daily limit for dataSync services (6 hours); an update never gets near it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        super.onDestroy()
    }

    private fun notifyProgress(progress: UpdateProgress?) {
        val now = SystemClock.elapsedRealtime()
        if (progress?.state == lastState && now - lastNotified < 1_000) return
        lastState = progress?.state
        lastNotified = now
        runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(progress)) }
    }

    private fun notification(progress: UpdateProgress?): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.update_progress_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val numbers = progress?.progress
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(
                progress?.versionName?.takeIf { it.isNotEmpty() }?.let { getString(R.string.update_progress_title, it) }
                    ?: getString(R.string.update_progress_title_plain),
            )
            .setContentText(UpdateProgressText.line(this, progress))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val moving = progress?.moving == true && numbers != null
        builder.setProgress(100, numbers?.percent ?: 0, !moving)
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "update_progress"
        private const val NOTIFICATION_ID = 1907

        /** Whether the service runs in the foreground now. */
        @Volatile
        var running: Boolean = false
            private set

        /** Starts (or refreshes) the service; false when Android doesn't allow a foreground service now. */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, WatchUpdateService::class.java)) != null
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

/** The watch's words for an update in progress, for Connection and the notification. */
object UpdateProgressText {
    fun line(context: Context, progress: UpdateProgress?): String {
        if (progress == null) return context.getString(R.string.update_progress_starting)
        val numbers = progress.progress?.detail()
        val why = progress.error?.message?.ifEmpty { null } ?: progress.error?.code
        return when (progress.state) {
            ApkStatusState.WAITING_FOR_WIFI -> context.getString(R.string.update_progress_waiting_wifi)
            ApkStatusState.DOWNLOADING -> context.getString(R.string.update_progress_wifi, numbers ?: "…")
            ApkStatusState.NEEDS_STREAM -> if (why != null) {
                context.getString(R.string.update_progress_waiting_phone_because, why)
            } else {
                context.getString(R.string.update_progress_waiting_phone)
            }
            ApkStatusState.RECEIVING -> context.getString(R.string.update_progress_phone, numbers ?: "…")
            ApkStatusState.VERIFYING -> context.getString(R.string.update_progress_verifying)
            ApkStatusState.INSTALLING -> context.getString(R.string.update_progress_installing)
            ApkStatusState.FAILED -> context.getString(R.string.update_progress_failed, why ?: "?")
            else -> progress.state
        }
    }
}
