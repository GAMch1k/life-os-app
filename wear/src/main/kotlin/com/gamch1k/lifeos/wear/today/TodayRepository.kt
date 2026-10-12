package com.gamch1k.lifeos.wear.today

import com.gamch1k.lifeos.core.wearlink.HttpOutcome
import com.gamch1k.lifeos.wear.net.ApiResult
import com.gamch1k.lifeos.wear.net.EventListDto
import com.gamch1k.lifeos.wear.net.LifeOsApi
import com.gamch1k.lifeos.wear.net.TaskListDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** How a refresh went. */
sealed interface RefreshResult {
    data object Updated : RefreshResult

    data class Failed(val message: String) : RefreshResult
}

/**
 * Today's events and tasks for the tile, the complication and the Today
 * screen. Fetched through the phone or directly ([LifeOsApi]), cached in
 * `today.json` so the tile always has something to show, and pushed to the
 * tile and complication after every change ([onChanged]).
 */
class TodayRepository(
    private val cacheFile: File,
    private val api: LifeOsApi,
    private val onChanged: () -> Unit = {},
    private val onAuthRefused: suspend () -> Unit = {},
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(load())
    private val failure = MutableStateFlow<String?>(null)

    val snapshot: StateFlow<TodaySnapshot?> = state.asStateFlow()

    /** The last refresh's problem, null after a good one. */
    val lastError: StateFlow<String?> = failure.asStateFlow()

    fun isStale(maxAge: Duration): Boolean {
        val fetched = state.value?.fetchedAt ?: return true
        val now = clock()
        val sameDay = Instant.ofEpochMilli(fetched).atZone(zone()).toLocalDate() == now.atZone(zone()).toLocalDate()
        return !sameDay || Duration.between(Instant.ofEpochMilli(fetched), now) > maxAge
    }

    suspend fun refresh(): RefreshResult {
        mutex.withLock { return refreshLocked() }
    }

    private suspend fun refreshLocked(): RefreshResult {
        val now = clock()
        val zone = zone()
        val today = now.atZone(zone).toLocalDate()
        val startOfToday = today.atStartOfDay(zone).toInstant()
        val startOfTomorrow = today.plusDays(1).atStartOfDay(zone).toInstant()
        val endOfTomorrow = today.plusDays(2).atStartOfDay(zone).toInstant()
        val (events, tasks) = coroutineScope {
            val events = async { api.events(startOfToday, endOfTomorrow, zone) }
            val tasks = async { api.tasksDueBefore(startOfTomorrow) }
            events.await() to tasks.await()
        }
        val problem = problemOf(events) ?: problemOf(tasks)
        if (problem != null) {
            if (listOf(events, tasks).any { it is ApiResult.Response && it.outcome == HttpOutcome.AUTH }) onAuthRefused()
            failure.value = problem
            return RefreshResult.Failed(problem)
        }
        val snapshot = try {
            TodayParser.snapshot(
                events = json.decodeFromString(EventListDto.serializer(), (events as ApiResult.Response).text).items,
                tasks = json.decodeFromString(TaskListDto.serializer(), (tasks as ApiResult.Response).text).items,
                fetchedAt = now,
                zone = zone,
            )
        } catch (_: Exception) {
            failure.value = "Life OS answered something unreadable."
            return RefreshResult.Failed(failure.value!!)
        }
        save(snapshot)
        failure.value = null
        onChanged()
        return RefreshResult.Updated
    }

    /** Marks a task done (one [actionId] per tap), then drops it from the cache and refreshes. */
    suspend fun completeTask(taskId: String, actionId: String = UUID.randomUUID().toString()): RefreshResult {
        val result = api.completeTask(taskId, actionId)
        problemOf(result)?.let { problem ->
            // 404: the task is gone already; treat it as done.
            if (!(result is ApiResult.Response && result.status == 404)) return RefreshResult.Failed(problem)
        }
        mutex.withLock {
            state.value?.let { current -> save(current.copy(tasks = current.tasks.filter { it.id != taskId })) }
        }
        onChanged()
        refresh()
        return RefreshResult.Updated
    }

    private fun problemOf(result: ApiResult): String? = when (result) {
        is ApiResult.Unavailable -> LifeOsApi.describe(result)
        is ApiResult.Response -> if (result.outcome == HttpOutcome.SUCCESS) null else LifeOsApi.describe(result)
    }

    private suspend fun save(snapshot: TodaySnapshot) {
        withContext(Dispatchers.IO) {
            cacheFile.parentFile?.mkdirs()
            val temporary = File(cacheFile.path + ".tmp")
            temporary.writeText(cacheJson.encodeToString(TodaySnapshot.serializer(), snapshot))
            if (!temporary.renameTo(cacheFile)) {
                cacheFile.delete()
                temporary.renameTo(cacheFile)
            }
        }
        state.value = snapshot
    }

    private fun load(): TodaySnapshot? = try {
        if (cacheFile.isFile) cacheJson.decodeFromString(TodaySnapshot.serializer(), cacheFile.readText()) else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        val json = LifeOsApi.json
        val cacheJson = Json { ignoreUnknownKeys = true }
    }
}
