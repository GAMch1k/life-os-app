package com.gamch1k.lifeos.phone.today

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.today.TodayBuilder
import com.gamch1k.lifeos.core.today.TodaySnapshot
import com.gamch1k.lifeos.phone.HomeCache
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Today's Tasks and events, as Home, the Today widget and the watch see
 * them: read from Life OS, cached, and announced ([onChanged]: the watch's
 * Today tile and the widgets) only when the data really changed.
 */
class TodayRepository(
    private val client: LifeOsClient,
    private val cache: HomeCache,
    private val onChanged: () -> Unit,
) {
    private val state = MutableStateFlow<TodaySnapshot?>(null)
    val snapshot: StateFlow<TodaySnapshot?> = state.asStateFlow()
    private val mutex = Mutex()

    /** The cached Today, read once. */
    suspend fun load(): TodaySnapshot? {
        if (state.value == null) state.value = cache.load()
        return state.value
    }

    /** Reads Today from Life OS; throws [LifeOsException] when it cannot. */
    suspend fun refresh(): TodaySnapshot = mutex.withLock {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val (from, to) = TodayBuilder.eventRange(now, zone)
        val fresh = coroutineScope {
            val tasks = async { client.openTasksDueBefore(TodayBuilder.endOfToday(now, zone)) }
            val events = async { client.events(from, to, zone) }
            TodaySnapshot(fetchedAt = now.toString(), timeZone = zone.id, tasks = tasks.await().items, events = events.await())
        }
        val previous = load()
        state.value = fresh
        cache.save(fresh)
        if (!fresh.sameDataAs(previous)) onChanged()
        fresh
    }

    /** A Task was completed here (Home, a notification's Done): drop it at once. */
    suspend fun taskCompleted(taskId: String) = mutex.withLock {
        val current = load() ?: return@withLock
        if (current.tasks.none { it.id == taskId }) return@withLock
        val updated = current.copy(tasks = current.tasks.filterNot { it.id == taskId })
        state.value = updated
        cache.save(updated)
        onChanged()
    }

    suspend fun clear() = mutex.withLock {
        state.value = null
        cache.clear()
    }
}

/** Keeps Today (and so the widget and the watch) fresh while the app is closed: hourly, with a network. */
class TodayRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        if (graph.tokens.current() == null) return Result.success()
        return try {
            graph.today.refresh()
            Result.success()
        } catch (_: LifeOsException) {
            Result.success()
        } finally {
            // Relative labels ("Overdue", the next event) move on with the clock.
            TodayWidget.refreshAll(applicationContext)
        }
    }

    companion object {
        /** Once, as soon as there is a network (a push said Tasks or events changed). */
        fun refreshSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<TodayRefreshWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("today-refresh-now", ExistingWorkPolicy.REPLACE, request)
        }

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TodayRefreshWorker>(1, TimeUnit.HOURS, 20, TimeUnit.MINUTES)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("today-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
