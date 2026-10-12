package com.gamch1k.lifeos.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamch1k.lifeos.core.netcheck.NetworkCheck
import com.gamch1k.lifeos.core.netcheck.NetworkCheckReport
import com.gamch1k.lifeos.core.netcheck.NetworkCheckResult
import com.gamch1k.lifeos.core.secrets.ServiceToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The stored token as the screen may show it: masked, never the values. */
data class MaskedToken(val clientId: String, val clientSecret: String)

data class NetworkCheckUiState(
    val running: Boolean = false,
    val quicAllowed: Boolean = false,
    val result: NetworkCheckResult? = null,
    val token: MaskedToken? = null,
    val error: String? = null,
)

class NetworkCheckViewModel(application: Application) : AndroidViewModel(application) {
    private val check = NetworkCheck(application)
    private val tokens = application.graph.tokens
    private val state = MutableStateFlow(NetworkCheckUiState())
    val uiState: StateFlow<NetworkCheckUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val token = tokens.current()?.token
            state.update { it.copy(token = token?.masked()) }
        }
    }

    fun setQuicAllowed(allowed: Boolean) = state.update { it.copy(quicAllowed = allowed) }

    fun runCheck() {
        if (state.value.running) return
        state.update { it.copy(running = true) }
        viewModelScope.launch {
            val quic = state.value.quicAllowed
            try {
                val token = tokens.current()?.token
                val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                val result = check.run(appVersion = appVersion, quicAllowed = quic, token = token)
                state.update { it.copy(running = false, result = result, error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(running = false, error = "The check stopped: ${error.javaClass.simpleName}: ${error.message}") }
            }
        }
    }

    fun report(): String? = state.value.result?.let(NetworkCheckReport::build)

    private fun ServiceToken.masked() = MaskedToken(maskedClientId, maskedClientSecret)
}
