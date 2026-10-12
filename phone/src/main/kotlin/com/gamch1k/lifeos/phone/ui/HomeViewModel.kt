package com.gamch1k.lifeos.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.capture.Capture
import com.gamch1k.lifeos.core.capture.CaptureStatus
import com.gamch1k.lifeos.core.today.TodayBuilder
import com.gamch1k.lifeos.core.today.TodayView
import com.gamch1k.lifeos.phone.graph
import com.gamch1k.lifeos.phone.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class HomeUiState(
    val today: TodayView? = null,
    val fetchedAt: Instant? = null,
    val refreshing: Boolean = false,
    val error: LifeOsException? = null,
    val lastCapture: Capture? = null,
    val waiting: Int = 0,
    val failed: Int = 0,
    val update: UpdateState = UpdateState.Idle,
)

/** A snackbar: [undoTaskId] adds Undo (reopen the Task just completed). */
data class HomeMessage(val text: String, val undoTaskId: String? = null)

/** P18-07: Home's Today (overdue and due-today Tasks, today's and tomorrow's events) with the capture buttons. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val snapshot = graph.today.snapshot
    private val hidden = MutableStateFlow<Set<String>>(emptySet())
    private val refreshing = MutableStateFlow(false)
    private val error = MutableStateFlow<LifeOsException?>(null)
    private val messageChannel = Channel<HomeMessage>(Channel.BUFFERED)
    val messages: Flow<HomeMessage> = messageChannel.receiveAsFlow()

    /** Ticks every minute, so "overdue" and the day roll over while Home stays open. */
    private val minute = flow {
        while (true) {
            emit(Instant.now())
            delay(60_000)
        }
    }

    private val loading = combine(refreshing, error) { busy, failure -> busy to failure }

    val uiState: StateFlow<HomeUiState> = combine(snapshot, hidden, loading, graph.captures.captures, combine(graph.updates.state, minute) { u, m -> u to m }) {
            current, hiddenIds, (busy, failure), captures, (update, now) ->
        val zone = ZoneId.systemDefault()
        val recent = captures.maxByOrNull { it.createdAt }?.takeIf { Duration.between(Instant.parse(it.createdAt), now) < RECENT }
        HomeUiState(
            today = current?.let { TodayBuilder.build(it, now, zone, hiddenIds) },
            fetchedAt = current?.fetchedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            refreshing = busy,
            error = failure,
            lastCapture = recent,
            waiting = captures.count { it.status == CaptureStatus.PENDING || it.status == CaptureStatus.SENDING },
            failed = captures.count { it.status == CaptureStatus.FAILED },
            update = update,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            graph.today.load()
            refresh()
        }
        viewModelScope.launch { graph.updates.noteLaunch()?.let { messageChannel.send(HomeMessage(it)) } }
        viewModelScope.launch { for (notice in graph.notices) messageChannel.send(HomeMessage(notice)) }
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                graph.today.refresh()
                error.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LifeOsException) {
                error.value = failure
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Tap on a Task: done at once (optimistic), with Undo. */
    fun complete(taskId: String, title: String) {
        hidden.update { it + taskId }
        graph.scope.launch {
            try {
                graph.client.completeTask(taskId)
                messageChannel.send(HomeMessage("Done: $title", undoTaskId = taskId))
                graph.today.taskCompleted(taskId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LifeOsException) {
                hidden.update { it - taskId }
                messageChannel.send(HomeMessage("Not marked done: ${failure.message}"))
            }
        }
    }

    fun undo(taskId: String) {
        graph.scope.launch {
            try {
                graph.client.reopenTask(taskId)
                hidden.update { it - taskId }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LifeOsException) {
                messageChannel.send(HomeMessage("Could not reopen: ${failure.message}"))
            }
        }
    }

    /**
     * Polls the newest capture until Life OS has settled it (in Inbox,
     * processed), for at most [RECENT]. Home runs it only while it is visible.
     */
    suspend fun followLastCapture() {
        while (true) {
            delay(5_000)
            val latest = graph.captures.all().maxByOrNull { it.createdAt } ?: continue
            if (latest.status == CaptureStatus.SENT && !latest.settled &&
                Duration.between(Instant.parse(latest.createdAt), Instant.now()) < RECENT
            ) {
                graph.statusChecker.refresh(latest.id)
            }
        }
    }

    private companion object {
        val RECENT: Duration = Duration.ofMinutes(30)
    }
}
