package com.gamch1k.lifeos.phone.pairing

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gamch1k.lifeos.core.pairing.KeyRevoker
import com.gamch1k.lifeos.phone.graph
import java.util.concurrent.TimeUnit

/**
 * Signs out, when the phone is online, the device keys it let go while
 * Life OS could not confirm it (KeyRevoker): with backoff from a minute to
 * WorkManager's five hours, for [MAX_ATTEMPTS] tries or three days; then
 * the app asks the person to sign the phone out on the web.
 */
class RevokeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcome = applicationContext.graph.revoker.retryPending(giveUp = runAttemptCount >= MAX_ATTEMPTS)
        return if (outcome == KeyRevoker.Result.RETRY) Result.retry() else Result.success()
    }

    companion object {
        const val NAME = "revoke-device-keys"
        const val MAX_ATTEMPTS = 12

        /** [now]: the notice's Try again, which starts over at once. */
        fun enqueue(context: Context, now: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<RevokeWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, if (now) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}
