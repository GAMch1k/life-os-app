package com.gamch1k.lifeos.phone.watch

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
import com.gamch1k.lifeos.phone.MainActivity
import com.gamch1k.lifeos.phone.R
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The push of a wear APK to the watch, from the download to the watch's
 * verdict, as a foreground service (type `connectedDevice`, docs/wear.md
 * "Install and updates"): a progress notification ("Updating the watch:
 * 43% · 2.3 of 5.4 MB · about 2 min left") with Cancel, and a partial wake
 * lock, capped at [WAKE_LOCK_CAP_MILLIS]. Leaving the app doesn't end it,
 * and no WorkManager time limit applies.
 *
 * [WatchUpdateManager.check] starts it when a push is due, and it runs the
 * check again inside ([WatchUpdateManager.check] with `inService`).
 */
class WatchUpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotified = 0L
    private var lastHeadline = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // Sent with startService from the notification or Settings, never to start the service.
            job?.cancel() ?: stopSelf()
            return START_NOT_STICKY
        }
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)
            ?.let { name -> WatchUpdateManager.Trigger.entries.firstOrNull { it.name == name } }
            ?: WatchUpdateManager.Trigger.MANUAL
        // Every startForegroundService() needs its startForeground(), even while a push runs.
        val foreground = try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(WatchActivity.Checking), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (_: RuntimeException) {
            // Not allowed after all (ForegroundServiceStartNotAllowedException, a missing permission).
            false
        }
        if (job?.isActive == true) return START_NOT_STICKY
        if (!foreground) {
            // The push still runs, as before 0.4.3: in the app's scope, without the service.
            val graph = applicationContext.graph
            graph.scope.launch { graph.watchUpdates.check(trigger, inService = true) }
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        val graph = applicationContext.graph
        job = scope.launch {
            val updates = launch {
                graph.watchUpdates.status.collect { status -> notifyProgress(status.activity) }
            }
            try {
                graph.watchUpdates.check(trigger, inService = true)
            } finally {
                updates.cancel()
                finish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun finish() {
        job = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lifeos:watch-update")
            ?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_CAP_MILLIS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { runCatching { it.release() } }
        wakeLock = null
    }

    /** At most one update a second (Android drops faster ones), but every change of step at once. */
    private fun notifyProgress(activity: WatchActivity) {
        if (!activity.busy) return
        val headline = WatchActivityText.headline(activity)
        val now = SystemClock.elapsedRealtime()
        if (headline == lastHeadline && now - lastNotified < 1_000) return
        lastHeadline = headline
        lastNotified = now
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(activity))
    }

    private fun notification(activity: WatchActivity): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watch updates", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while the phone updates the watch app."
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, WatchUpdateService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val progress = when (activity) {
            is WatchActivity.Downloading -> activity.progress
            is WatchActivity.Sending -> activity.progress
            else -> null
        }
        val way = when (activity) {
            is WatchActivity.Downloading -> "Downloading from Life OS"
            is WatchActivity.Sending -> activity.route.label.replaceFirstChar { it.uppercase() }
            else -> null
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (progress != null) "Updating the watch: ${progress.summary()}" else "Updating the watch")
            .setContentText(if (progress != null) way else WatchActivityText.headline(activity))
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(WatchActivityText.headline(activity), WatchActivityText.numbers(activity)).joinToString("\n")))
            .setProgress(100, progress?.percent ?: 0, progress == null)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // The phone's notifications are bridged to the watch; this one is about the watch.
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val ACTION_RUN = "com.gamch1k.lifeos.watch-update.RUN"
        private const val ACTION_CANCEL = "com.gamch1k.lifeos.watch-update.CANCEL"
        private const val EXTRA_TRIGGER = "trigger"
        private const val CHANNEL_ID = "lifeos.watch-update"
        private const val NOTIFICATION_ID = 1906

        /** Longer than any push should take (a 6 MB stream at 4 KB/s is 25 minutes); released as soon as it ends. */
        private const val WAKE_LOCK_CAP_MILLIS = 60 * 60_000L

        /**
         * Starts a push for [trigger] in the service. False when Android
         * doesn't allow a foreground service now (the app is in the
         * background: the daily job, a report from the watch); the caller
         * then pushes itself.
         */
        fun start(context: Context, trigger: WatchUpdateManager.Trigger): Boolean = try {
            context.startForegroundService(
                Intent(context, WatchUpdateService::class.java).setAction(ACTION_RUN).putExtra(EXTRA_TRIGGER, trigger.name),
            ) != null
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
            false
        } catch (_: SecurityException) {
            false
        }

        /** Settings' Cancel: ends a running push (the watch keeps what it has, for a resume). */
        fun cancel(context: Context) {
            try {
                context.startService(Intent(context, WatchUpdateService::class.java).setAction(ACTION_CANCEL))
            } catch (_: IllegalStateException) {
                // Not running, and the app is in the background: nothing to cancel.
            }
        }
    }
}
