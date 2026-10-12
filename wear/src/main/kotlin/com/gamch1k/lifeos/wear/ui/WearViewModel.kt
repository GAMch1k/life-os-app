package com.gamch1k.lifeos.wear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamch1k.lifeos.core.wearlink.HttpOutcome
import com.gamch1k.lifeos.wear.LifeOsWear
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.capture.CaptureMachine
import com.gamch1k.lifeos.wear.capture.CaptureWorker
import com.gamch1k.lifeos.wear.link.CredentialsFetch
import com.gamch1k.lifeos.wear.net.ApiRequest
import com.gamch1k.lifeos.wear.net.ApiResult
import com.gamch1k.lifeos.wear.net.LifeOsApi
import com.gamch1k.lifeos.wear.net.Route
import com.gamch1k.lifeos.wear.today.RefreshResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A task the owner tapped, waiting for "Mark done?", then the result. */
data class Completion(val taskId: String, val title: String, val state: State = State.ASKING, val message: String? = null) {
    enum class State { ASKING, RUNNING, DONE, FAILED }
}

class WearViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = LifeOsWear.graph(application)

    val captures = graph.captureStore.captures
    val today = graph.today.snapshot
    val todayError = graph.today.lastError
    val credentials = graph.credentials.credentials

    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = mutableRefreshing.asStateFlow()

    private val mutableCompletion = MutableStateFlow<Completion?>(null)
    val completion: StateFlow<Completion?> = mutableCompletion.asStateFlow()

    /** The phone's display name when one with the Life OS app is reachable, "" when none, null before the first look. */
    private val mutablePhone = MutableStateFlow<String?>(null)
    val phone: StateFlow<String?> = mutablePhone.asStateFlow()

    private val mutableConnectionMessage = MutableStateFlow<String?>(null)
    val connectionMessage: StateFlow<String?> = mutableConnectionMessage.asStateFlow()

    /** After recording from a tile, the complication or the Home key, the app closes once the capture is on its way. */
    var finishAfterRecording = false

    init {
        // Opening the app is a good moment to send what waits and to look at jobs in transcription.
        CaptureWorker.runNow(application)
        if (graph.today.isStale(java.time.Duration.ofMinutes(5))) refreshToday()
    }

    fun refreshToday() {
        if (mutableRefreshing.value) return
        mutableRefreshing.value = true
        viewModelScope.launch {
            try {
                graph.today.refresh()
            } finally {
                mutableRefreshing.value = false
            }
        }
    }

    fun askToComplete(taskId: String, title: String) {
        mutableCompletion.value = Completion(taskId, title)
    }

    fun confirmCompletion() {
        val pending = mutableCompletion.value?.takeIf { it.state == Completion.State.ASKING } ?: return
        mutableCompletion.value = pending.copy(state = Completion.State.RUNNING)
        viewModelScope.launch {
            mutableCompletion.value = when (val result = graph.today.completeTask(pending.taskId)) {
                RefreshResult.Updated -> pending.copy(state = Completion.State.DONE)
                is RefreshResult.Failed -> pending.copy(state = Completion.State.FAILED, message = result.message)
            }
        }
    }

    fun dismissCompletion() {
        mutableCompletion.value = null
    }

    fun retry(captureId: String) {
        viewModelScope.launch {
            graph.captureStore.update(captureId) { CaptureMachine.retry(it) ?: it }
            CaptureWorker.runNow(getApplication())
        }
    }

    fun delete(captureId: String) {
        viewModelScope.launch { graph.captureStore.remove(captureId) }
    }

    fun lookForPhone() {
        viewModelScope.launch {
            graph.phoneLink.forgetPhone()
            mutablePhone.value = try {
                graph.phoneLink.phoneName() ?: ""
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun fetchToken() {
        viewModelScope.launch {
            mutableConnectionMessage.value = null
            val message = when (graph.refreshCredentials()) {
                CredentialsFetch.RECEIVED -> R.string.token_fetch_received
                CredentialsFetch.PHONE_HAS_NONE -> R.string.token_fetch_phone_has_none
                CredentialsFetch.NO_PHONE -> R.string.token_fetch_no_phone
                CredentialsFetch.FAILED -> R.string.token_fetch_failed
            }
            mutableConnectionMessage.value = getApplication<Application>().getString(message)
            lookForPhone()
        }
    }

    /** One cheap authenticated read, to show which way Life OS answers. */
    fun testConnection() {
        viewModelScope.launch {
            mutableConnectionMessage.value = "…"
            val result = graph.api.execute(ApiRequest("GET", "/api/tasks/v1/tasks" + LifeOsApi.query("limit" to "1")))
            mutableConnectionMessage.value = when (result) {
                is ApiResult.Unavailable -> LifeOsApi.describe(result)
                is ApiResult.Response -> {
                    val via = if (result.route == Route.PHONE) "through the phone" else "directly"
                    if (result.outcome == HttpOutcome.SUCCESS) "OK $via" else "${LifeOsApi.describe(result)} ($via)"
                }
            }
        }
    }
}
