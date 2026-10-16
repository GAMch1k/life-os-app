package com.gamch1k.lifeos.phone.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.gamch1k.lifeos.core.push.SnoozeAlarm
import com.gamch1k.lifeos.phone.AppGraph
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Snooze's wake-up on the phone (P18-05): one inexact
 * `setAndAllowWhileIdle` alarm at the earliest snoozed notification. It needs
 * no permission (exact alarms would), fires in Doze, and may come a few
 * minutes late but never early. Alarms survive the app's process; a reboot
 * clears them, so [SnoozeRestoreReceiver] is enabled while something is
 * snoozed, and the app re-arms whenever it starts (after a force stop too).
 */
class AndroidSnoozeAlarm(private val context: Context) : SnoozeAlarm {
    override fun set(at: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), operation())
        restoreAfterReboot(true)
    }

    override fun cancel() {
        context.getSystemService(AlarmManager::class.java)?.cancel(operation())
        restoreAfterReboot(false)
    }

    /** One PendingIntent for the one alarm: setting it again replaces the previous time. */
    private fun operation(): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, SnoozeAlarmReceiver::class.java).setAction(SnoozeAlarmReceiver.ACTION_DUE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The phone's start wakes Life OS only while something is snoozed. */
    private fun restoreAfterReboot(enabled: Boolean) {
        val packages = context.packageManager
        val component = ComponentName(context, SnoozeRestoreReceiver::class.java)
        val enabledNow = packages.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if (enabledNow == enabled) return
        packages.setComponentEnabledSetting(
            component,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

/** The snooze alarm went off: what is due comes back, and the alarm moves to the next one. */
class SnoozeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DUE) return
        inBackground(context) { it.snoozes.wake() }
    }

    companion object {
        const val ACTION_DUE = "com.gamch1k.lifeos.SNOOZE_DUE"
    }
}

/**
 * After a reboot (which clears alarms) or an app update: snoozed
 * notifications that are due come back, and the alarm is set again.
 * Disabled in the manifest; [AndroidSnoozeAlarm] enables it while something
 * is snoozed.
 */
class SnoozeRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> inBackground(context) { it.snoozes.wake() }
        }
    }
}

/**
 * Runs [work] in the app's scope while Android keeps this receiver's process
 * alive (goAsync: up to about 10 seconds, plenty for the DataStore).
 */
internal fun BroadcastReceiver.inBackground(context: Context, work: suspend (AppGraph) -> Unit) {
    val graph = context.graph
    val pending = goAsync()
    graph.scope.launch {
        try {
            work(graph)
        } finally {
            pending.finish()
        }
    }
}
