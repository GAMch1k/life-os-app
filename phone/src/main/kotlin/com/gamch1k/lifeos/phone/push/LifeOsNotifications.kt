package com.gamch1k.lifeos.phone.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.api.LifeOsJson
import com.gamch1k.lifeos.core.push.PushPayload
import com.gamch1k.lifeos.phone.MainActivity
import com.gamch1k.lifeos.phone.R
import com.gamch1k.lifeos.phone.graph
import java.util.concurrent.TimeUnit

/**
 * Life OS notifications on the phone, bridged to the watch (docs/wear.md):
 * one channel per importance, the payload's tag as the notification tag (a
 * repeat replaces the first), a dismissal id and a bridge tag for the watch,
 * never setLocalOnly, one group with a summary, Done on a Task, and Snooze
 * on all but the summaries ([PushPayload.snoozable]).
 */
object LifeOsNotifications {
    // Android fixes a channel's sound and vibration when it is created, so the
    // urgent channel with the long vibration has a new id; the first one
    // ("lifeos.high", default vibration) is deleted.
    const val CHANNEL_HIGH = "lifeos.urgent"
    private val RETIRED_CHANNELS = listOf("lifeos.high")

    /** Long and strong, like ntfy's max priority: about 5 seconds of buzzing. */
    val URGENT_VIBRATION = longArrayOf(0, 1000, 400, 1000, 400, 1000, 400, 1000)
    const val CHANNEL_MEDIUM = "lifeos.medium"
    const val CHANNEL_LOW = "lifeos.low"
    private const val GROUP = "lifeos"
    private const val SUMMARY_TAG = "lifeos-summary"
    private const val NOTIFICATION_ID = 1
    const val ACTION_OPEN_LINK = "com.gamch1k.lifeos.OPEN_LINK"
    const val EXTRA_LINK = "link"

    /** The sub-text of a notification that comes back after Snooze. */
    const val SNOOZED = "Snoozed"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        RETIRED_CHANNELS.forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_HIGH, "Urgent", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Something is broken or needs action now. Long vibration."
                    enableVibration(true)
                    vibrationPattern = URGENT_VIBRATION
                    enableLights(true)
                },
                NotificationChannel(CHANNEL_MEDIUM, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Task and event reminders, captures that need you."
                },
                NotificationChannel(CHANNEL_LOW, "Information", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Summaries and other quiet news."
                },
            ),
        )
    }

    fun canPost(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Shows [payload]'s notification. [problem] (Done failed) goes under the
     * title; a [snoozed] one coming back says "Snoozed" there instead, with
     * the time it came back, and alerts again like a new one.
     */
    fun show(context: Context, payload: PushPayload, problem: String? = null, snoozed: Boolean = false) {
        if (!canPost(context)) return
        val channel = when (payload.level) {
            PushPayload.Importance.HIGH -> CHANNEL_HIGH
            PushPayload.Importance.MEDIUM -> CHANNEL_MEDIUM
            PushPayload.Importance.LOW -> CHANNEL_LOW
        }
        val requestCode = payload.notificationTag.hashCode()
        val open = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_LINK)
                .putExtra(EXTRA_LINK, payload.link ?: "/notifications")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(payload.title)
            .setContentIntent(open)
            .setAutoCancel(true)
            // A repeated delivery replaces the first without alerting again.
            .setOnlyAlertOnce(true)
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setCategory(category(payload.category))
            .extend(
                NotificationCompat.WearableExtender()
                    .setDismissalId(payload.dismissalId)
                    .setBridgeTag(payload.bridgeTag),
            )
        if (snoozed) {
            builder.setWhen(System.currentTimeMillis()).setShowWhen(true)
        } else {
            payload.timestamp?.let { builder.setWhen(it).setShowWhen(true) }
        }
        val body = payload.body?.takeIf { it.isNotBlank() }
        body?.let { builder.setContentText(it.lineSequence().first()).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        (problem ?: SNOOZED.takeIf { snoozed })?.let { builder.setSubText(it) }
        val json = LifeOsJson.encodeToString(PushPayload.serializer(), payload)
        // Broadcasts to the phone, so they work from the watch too: the watch shows
        // these actions on its bridged copy and runs them here. They open nothing.
        payload.taskId?.let { taskId ->
            val done = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(NotificationActionReceiver.ACTION_DONE)
                    .putExtra(NotificationActionReceiver.EXTRA_TASK_ID, taskId)
                    .putExtra(NotificationActionReceiver.EXTRA_PAYLOAD, json),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(action(R.drawable.ic_check, "Done", done))
        }
        if (payload.snoozable) {
            val snooze = PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(NotificationActionReceiver.ACTION_SNOOZE)
                    .putExtra(NotificationActionReceiver.EXTRA_PAYLOAD, json),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(action(R.drawable.ic_snooze, "Snooze", snooze))
        }
        val manager = NotificationManagerCompat.from(context)
        try {
            manager.notify(payload.notificationTag, NOTIFICATION_ID, builder.build())
            manager.notify(SUMMARY_TAG, NOTIFICATION_ID, summary(context))
        } catch (_: SecurityException) {
            // Notifications were turned off in between.
        }
    }

    /** An action that runs in the background: the watch confirms it instead of saying "Opened on phone". */
    private fun action(icon: Int, title: String, intent: PendingIntent) =
        NotificationCompat.Action.Builder(icon, title, intent).setShowsUserInterface(false).build()

    /** Removes one notification, and the group's summary once it summarises nothing. */
    fun cancel(context: Context, tag: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(tag, NOTIFICATION_ID)
        val others = runCatching { manager.activeNotifications }.getOrDefault(emptyList())
            .count { it.tag != null && it.tag != tag && it.tag != SUMMARY_TAG && it.tag.startsWith("lifeos-") }
        if (others == 0) manager.cancel(SUMMARY_TAG, NOTIFICATION_ID)
    }

    /** The group's summary, so the shade and the watch show one stack; on the quiet channel, it never alerts. */
    private fun summary(context: Context) = NotificationCompat.Builder(context, CHANNEL_LOW)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Life OS")
        .setGroup(GROUP)
        .setGroupSummary(true)
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .setAutoCancel(true)
        .extend(NotificationCompat.WearableExtender().setBridgeTag("lifeos.summary"))
        .build()

    private fun category(category: String) = when {
        category.startsWith("tasks.") || category.startsWith("calendar.") -> NotificationCompat.CATEGORY_REMINDER
        category.startsWith("status.") -> NotificationCompat.CATEGORY_ERROR
        else -> NotificationCompat.CATEGORY_MESSAGE
    }
}

/**
 * A notification's Done and Snooze, from the phone or the watch (bridged
 * actions run here). The notification goes at once, on both devices; the
 * Task is completed by [TaskDoneWorker], a snooze is stored and its alarm set
 * ([com.gamch1k.lifeos.core.push.Snoozer]). Done also drops a pending
 * snoozed copy of the same notification, or of the same Task.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra(EXTRA_PAYLOAD) ?: return
        val payload = runCatching { LifeOsJson.decodeFromString(PushPayload.serializer(), json) }.getOrNull()
        when (intent.action) {
            ACTION_DONE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
                payload?.let { LifeOsNotifications.cancel(context, it.notificationTag) }
                TaskDoneWorker.enqueue(context, taskId, json)
                val tag = payload?.notificationTag
                inBackground(context) { graph -> if (tag != null) graph.snoozes.done(tag, taskId) else graph.snoozes.taskDone(taskId) }
            }
            ACTION_SNOOZE -> {
                payload ?: return
                LifeOsNotifications.cancel(context, payload.notificationTag)
                inBackground(context) { graph -> graph.snoozes.snooze(payload) }
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.gamch1k.lifeos.TASK_DONE"
        const val ACTION_SNOOZE = "com.gamch1k.lifeos.SNOOZE"
        const val EXTRA_TASK_ID = "task-id"
        const val EXTRA_PAYLOAD = "payload"
    }
}

/**
 * Completes a Task from a notification (`POST /v1/tasks/{id}/complete`,
 * idempotent), offline too: it waits for a network. Then Today and the
 * watch's tile drop it. When it cannot, the notification comes back saying why.
 */
class TaskDoneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK) ?: return Result.failure()
        val graph = applicationContext.graph
        return try {
            graph.client.completeTask(taskId)
            graph.today.taskCompleted(taskId)
            graph.todayChanged()
            Result.success()
        } catch (error: LifeOsException) {
            if (error.retryable && runAttemptCount < 5) return Result.retry()
            inputData.getString(KEY_PAYLOAD)
                ?.let { runCatching { LifeOsJson.decodeFromString(PushPayload.serializer(), it) }.getOrNull() }
                ?.let { LifeOsNotifications.show(applicationContext, it, problem = "Not marked done: ${error.message}") }
            Result.failure()
        }
    }

    companion object {
        private const val KEY_TASK = "task"
        private const val KEY_PAYLOAD = "payload"

        fun enqueue(context: Context, taskId: String, payload: String) {
            val request = OneTimeWorkRequestBuilder<TaskDoneWorker>()
                .setInputData(workDataOf(KEY_TASK to taskId, KEY_PAYLOAD to payload))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("task-done-$taskId", ExistingWorkPolicy.KEEP, request)
        }
    }
}
