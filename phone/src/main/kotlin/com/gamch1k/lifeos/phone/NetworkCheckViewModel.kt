package com.gamch1k.lifeos.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gamch1k.lifeos.core.netcheck.NetworkCheck
import com.gamch1k.lifeos.core.netcheck.NetworkCheckReport
import com.gamch1k.lifeos.core.netcheck.NetworkCheckResult
import com.gamch1k.lifeos.core.secrets.ServiceToken
import com.gamch1k.lifeos.core.secrets.ServiceTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The stored token as the screen may show it: masked, never the values. */
data class MaskedToken(val clientId: String, val clientSecret: String)

data class NetworkCheckUiState(
    val running: Boolean = false,
    val quicAllowed: Boolean = false,
    val result: NetworkCheckResult? = null,
    val token: MaskedToken? = null,
    val tokenMessage: String? = null,
    val error: String? = null,
)

class NetworkCheckViewModel(application: Application) : AndroidViewModel(application) {
    private val check = NetworkCheck(application)
    private val tokens = ServiceTokenStore(application)
    private val state = MutableStateFlow(NetworkCheckUiState())
    val uiState: StateFlow<NetworkCheckUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val token = withContext(Dispatchers.IO) { tokens.load() }
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
                val token = withContext(Dispatchers.IO) { tokens.load() }
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

    fun saveToken(clientId: String, clientSecret: String) {
        val id = clientId.trim()
        val secret = clientSecret.trim()
        if (id.isEmpty() || secret.isEmpty()) {
            state.update { it.copy(tokenMessage = "Enter both the Client ID and the Client Secret.") }
            return
        }
        viewModelScope.launch {
            val token = ServiceToken(id, secret)
            withContext(Dispatchers.IO) { tokens.save(token) }
            state.update { it.copy(token = token.masked(), tokenMessage = "Saved, encrypted with the Android Keystore.") }
        }
    }

    fun forgetToken() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { tokens.clear() }
            state.update { it.copy(token = null, tokenMessage = "Forgotten.") }
        }
    }

    fun report(): String? = state.value.result?.let(NetworkCheckReport::build)

    private fun ServiceToken.masked() = MaskedToken(maskedClientId, maskedClientSecret)
}
