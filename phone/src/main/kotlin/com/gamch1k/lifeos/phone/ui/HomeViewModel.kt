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
    /** P21-06: "no longer connected", or the offer to pair; null when there is nothing to say. */
    val pairNotice: PairNotice? = null,
    /** Modules the account may not use here ("Tasks", "Calendar"): their part of Today is left out. */
    val notAllowed: Set<String> = emptySet(),
)

enum class PairNotice {
    /** Life OS refused the phone's key (401): pair again. */
    NO_LONGER_CONNECTED,

    /** Life OS asks for a sign-in and the phone has no key (legacy access is off): pair. */
    PAIRING_REQUIRED,

    /** Connected with the service token alone (legacy access): pairing is offered, not forced. */
    OFFER,
}

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

    /** What Home says about pairing (P21-06), and the modules the account may not use. */
    private val account = combine(graph.tokens.connection, graph.tokens.pairing, graph.tokens.pairPromptDismissed, graph.today.notAllowed, error) {
            connection, pairing, dismissed, notAllowed, failure ->
        val notice = when {
            connection == null -> null
            pairing?.refused == true -> PairNotice.NO_LONGER_CONNECTED
            failure is LifeOsException.SignInRequired && !failure.keySent -> PairNotice.PAIRING_REQUIRED
            pairing == null && !dismissed -> PairNotice.OFFER
            else -> null
        }
        notice to notAllowed
    }

    val uiState: StateFlow<HomeUiState> = combine(snapshot, hidden, loading, graph.captures.captures, combine(graph.updates.state, minute, account) { u, m, a -> Triple(u, m, a) }) {
            current, hiddenIds, (busy, failure), captures, (update, now, accountState) ->
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
            pairNotice = accountState.first,
            notAllowed = accountState.second,
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
                // A snoozed reminder about it would only come back for nothing.
                graph.snoozes.taskDone(taskId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: LifeOsException) {
                hidden.update { it - taskId }
                messageChannel.send(HomeMessage("Not marked done: ${failure.message}"))
            }
        }
    }

    /** Home's "Not now" on the offer to pair: Settings › Account keeps offering it. */
    fun dismissPairOffer() {
        viewModelScope.launch { graph.tokens.dismissPairPrompt() }
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
