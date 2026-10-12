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
import java.util.concurrent.TimeUnit

/** The daily update check, on an unmetered network. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.graph.updates.check(UpdateManager.Trigger.DAILY)
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
        val updates = context.graph.updates
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                updates.onPendingUserAction(intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            PackageInstaller.STATUS_SUCCESS -> updates.onInstalled()
            else -> updates.onInstallFailed(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    companion object {
        const val EXTRA_VERSION_CODE = "version-code"
    }
}
