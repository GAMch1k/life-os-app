package com.gamch1k.lifeos.wear.today

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gamch1k.lifeos.wear.LifeOsWear
import java.util.concurrent.TimeUnit

/**
 * Keeps Today fresh on a battery budget: every 30 minutes (WorkManager
 * batches it with other wake-ups), when the tile becomes visible with data
 * older than 10 minutes, and when the phone says something changed. It also
 * looks once at voice jobs still being transcribed.
 */
class TodayRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = LifeOsWear.graph(applicationContext)
        graph.today.refresh()
        graph.captureUploader.pollSentOnce()
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "today-refresh"
        private const val ONCE = "today-refresh-now"

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<TodayRefreshWorker>(30, TimeUnit.MINUTES, 10, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Refreshes soon; a refresh already queued or running is enough. */
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<TodayRefreshWorker>().build())
        }
    }
}
