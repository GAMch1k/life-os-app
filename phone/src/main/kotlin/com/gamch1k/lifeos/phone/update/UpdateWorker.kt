package com.gamch1k.lifeos.phone.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gamch1k.lifeos.phone.graph
import com.gamch1k.lifeos.phone.watch.WatchUpdateManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * The daily update check, on an unmetered network: the watch app first (its
 * push needs this process), then the phone's own update, which may install
 * and so end the process.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        graph.watchUpdates.check(WatchUpdateManager.Trigger.DAILY)
        graph.updates.check(UpdateManager.Trigger.DAILY)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.UNMETERED))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("update-check", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** Receives PackageInstaller's verdict on a commit. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        // The in-flight release may have to be read from disk (a fresh process).
        val pending = goAsync()
        graph.scope.launch {
            try {
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> graph.updates.onPendingUserAction(confirm)
                    PackageInstaller.STATUS_SUCCESS -> graph.updates.onInstalled()
                    else -> graph.updates.onInstallFailed(status, message)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
