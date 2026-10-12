package com.gamch1k.lifeos.wear.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gamch1k.lifeos.wear.LifeOsWear
import java.util.concurrent.TimeUnit

/**
 * Runs the upload queue in the background ([CaptureUploader]). Started right
 * after a recording, when the phone reconnects or hands over a token, and
 * again whenever a capture's next attempt is due. No network constraint: the
 * phone relay needs only Bluetooth.
 */
class CaptureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = LifeOsWear.graph(applicationContext)
        val wakeUp = graph.captureUploader.run(followFreshJobs = true)
        if (wakeUp != null) runLater(applicationContext, wakeUp - System.currentTimeMillis())
        return Result.success()
    }

    companion object {
        private const val NOW = "capture-upload"
        private const val LATER = "capture-upload-later"

        /** Runs the queue as soon as possible; a run already going is followed by another one. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<CaptureWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Runs the queue after [delayMillis] (the earliest next attempt); replaces an earlier plan. */
        fun runLater(context: Context, delayMillis: Long) {
            val request = OneTimeWorkRequestBuilder<CaptureWorker>()
                .setInitialDelay(delayMillis.coerceAtLeast(5_000), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(LATER, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
