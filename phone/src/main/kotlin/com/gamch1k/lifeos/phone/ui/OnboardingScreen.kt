package com.gamch1k.lifeos.phone.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamch1k.lifeos.core.api.Connection
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.secrets.ServerUrl
import com.gamch1k.lifeos.core.secrets.ServiceToken
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingState(
    /** Empty at the first run: there is no default server (P22-13). */
    val server: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val testing: Boolean = false,
    /** The values the last successful test used; Save needs them unchanged. */
    val tested: Triple<String, String, String>? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** The last test failed for a reason that may pass (offline): saving anyway is allowed. */
    val canSaveUntested: Boolean = false,
    val saving: Boolean = false,
)

/**
 * P18-03: the server address and the Cloudflare Access service token, at the
 * first run and from Settings › Edit connection. Another server needs its own
 * token; the watch follows the saved server. Since P21-06 it also leads to
 * pairing with an account ([Screen.Pair]); a server without Access needs no
 * token at all.
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val state = MutableStateFlow(OnboardingState())
    val uiState = state.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = graph.tokens.serverUrl() ?: return@launch
            // Only into an untouched field: what the person typed meanwhile stays.
            state.update { if (it.server.isEmpty()) it.copy(server = saved) else it }
        }
    }

    fun edit(server: String = state.value.server, clientId: String = state.value.clientId, clientSecret: String = state.value.clientSecret) =
        state.update { it.copy(server = server, clientId = clientId, clientSecret = clientSecret, message = null, canSaveUntested = false) }

    private fun parsed(): Pair<String, ServiceToken>? {
        val current = state.value
        val url = ServerUrl.normalize(current.server)
        if (url == null) {
            state.update { it.copy(message = SERVER_NEEDED, messageIsError = true) }
            return null
        }
        val token = ServiceToken.parse(current.clientId, current.clientSecret)
        if (token == null) {
            state.update { it.copy(message = "Paste both the Client ID and the Client Secret (no spaces inside).", messageIsError = true) }
            return null
        }
        return url to token
    }

    /** The server to pair with: the typed one, or null (with a message) when it is not an https address. */
    fun pairingServer(): String? {
        val url = ServerUrl.normalize(state.value.server)
        if (url == null) state.update { it.copy(message = SERVER_NEEDED, messageIsError = true) }
        return url
    }

    /**
     * Tests the connection; [onPassed] runs after a passed test (the screen
     * saves then). [onNeedsPairing] runs when Access let the token through
     * but Life OS wants a sign-in (legacy access is off): the token is right,
     * and pairing comes next, which needs the token saved first.
     */
    fun test(onPassed: (() -> Unit)? = null, onNeedsPairing: (() -> Unit)? = null) {
        val (url, token) = parsed() ?: return
        state.update { it.copy(testing = true, message = null) }
        viewModelScope.launch {
            try {
                // A paired phone tests with its key as well: once legacy access is off, the token alone gets 401.
                val current = graph.tokens.current()
                val key = current?.deviceKey?.takeIf { current.baseUrl == url }
                graph.client.testConnection(Connection(url, token, key))
                state.update {
                    it.copy(
                        testing = false,
                        tested = Triple(url, token.clientId, token.clientSecret),
                        message = "Connected: Life OS answered through Cloudflare Access.",
                        messageIsError = false,
                    )
                }
                onPassed?.invoke()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LifeOsException.SignInRequired) {
                // Life OS's own 401: Cloudflare Access let the token through.
                state.update {
                    it.copy(
                        testing = false,
                        tested = Triple(url, token.clientId, token.clientSecret),
                        message = "Cloudflare Access let the phone through. Life OS asks for a sign-in: pair this phone next.",
                        messageIsError = false,
                    )
                }
                onNeedsPairing?.invoke()
            } catch (error: LifeOsException) {
                val hint = if (!token.clientId.endsWith(".access") && error.authProblem) " The Client ID normally ends in .access." else ""
                state.update {
                    it.copy(testing = false, tested = null, message = (error.message ?: "The test failed.") + hint, messageIsError = true, canSaveUntested = error.retryable)
                }
            }
        }
    }

    fun save(onSaved: () -> Unit) {
        val (url, token) = parsed() ?: return
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val previous = graph.tokens.serverUrl()
            // A device key belongs to its server: another server unpairs the phone (Life OS signs the key out).
            if (previous != url) graph.leavePairing()
            graph.tokens.save(url, token)
            if (previous != url) graph.today.clear()
            // The watch, the push subscription and captures that failed on a bad token.
            graph.tokenSaved()
            state.update { it.copy(saving = false, clientSecret = "") }
            onSaved()
        }
    }

    val testedCurrent: Boolean
        get() = state.value.let { current ->
            val url = ServerUrl.normalize(current.server)
            // Compare what the test actually used: ServiceToken.parse cleans
            // more than trim() (zero-width characters a pasted value can
            // carry), so trim() alone made a passed test look stale.
            val token = ServiceToken.parse(current.clientId, current.clientSecret)
            token != null && current.tested == Triple(url, token.clientId, token.clientSecret)
        }

    private companion object {
        const val SERVER_NEEDED = "Enter your Life OS's https:// address, such as ${ServerUrl.EXAMPLE}."
    }
}

@Composable
fun OnboardingScreen(navigator: Navigator, firstRun: Boolean, viewModel: OnboardingViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(firstRun) }
    val testedCurrent = viewModel.testedCurrent

    Scaffold(topBar = { if (!firstRun) BackTopBar("Connection", navigator::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (firstRun) {
                Spacer(Modifier.size(24.dp))
                Text("Connect to Life OS", style = MaterialTheme.typography.headlineMedium)
            }
            // After signing out: a key Life OS has not confirmed signing out yet.
            RevokeNotice()
            Text(
                "The app signs in to Life OS with a key of its own, which it gets when you pair it with your account. " +
                    "A Life OS behind Cloudflare Access also needs a service token made for this phone: " +
                    "connect with it below first, then pair. Both are stored encrypted with the Android Keystore and sent only to your server.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = state.server,
                onValueChange = { viewModel.edit(server = it) },
                label = { Text("Server") },
                placeholder = { Text(ServerUrl.EXAMPLE) },
                supportingText = { Text("The address of your own Life OS, as you open it in the browser.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            SectionCard("Pair with your account") {
                Text(
                    "With a QR code from Life OS (Settings → Devices), or by signing in on this phone. " +
                        "Without Cloudflare Access in front, this is all the app needs.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { viewModel.pairingServer()?.let { navigator.go(Screen.Pair(server = it)) } }, enabled = !state.testing && !state.saving) {
                    Text("Pair this phone")
                }
            }
            Text("Cloudflare Access service token", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.clientId,
                onValueChange = { viewModel.edit(clientId = it) },
                label = { Text("Client ID (CF-Access-Client-Id)") },
                placeholder = { Text("…….access") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.clientSecret,
                onValueChange = { viewModel.edit(clientSecret = it) },
                label = { Text("Client Secret (CF-Access-Client-Secret)") },
                singleLine = true,
                visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // A passed test saves and continues by itself; the second button
                // stays for saving after a test that failed only for being offline.
                Button(
                    onClick = {
                        viewModel.test(
                            onPassed = { viewModel.save { navigator.reset(Screen.Home) } },
                            onNeedsPairing = {
                                viewModel.save {
                                    navigator.reset(Screen.Home)
                                    navigator.go(Screen.Pair())
                                }
                            },
                        )
                    },
                    enabled = !state.testing && !state.saving,
                ) { Text("Connect") }
                if (testedCurrent || state.canSaveUntested) {
                    OutlinedButton(
                        onClick = { viewModel.save { navigator.reset(Screen.Home) } },
                        enabled = !state.testing && !state.saving,
                    ) { Text(if (testedCurrent) "Save and continue" else "Save anyway") }
                }
                if (state.testing || state.saving) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            }

            state.message?.let { message ->
                val colors = if (state.messageIsError) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors()
                Card(Modifier.fillMaxWidth(), colors = colors) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(if (state.messageIsError) Icons.Filled.Warning else Icons.Filled.CheckCircle, contentDescription = null)
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "Hide: how to get a token" else "How to get a token") }
            if (showHelp) TokenHelp()
        }
    }
}

@Composable
private fun TokenHelp() {
    SectionCard("How to get a token") {
        val steps = listOf(
            "On a computer, open the Cloudflare dashboard → Zero Trust.",
            "Access controls → Service credentials → Service Tokens → Create Service Token.",
            "Name it life-os-phone, choose a duration (Non-expiring, or a year and renew it), then Generate token.",
            "Copy the Client ID (it ends in .access) and the Client Secret now: Cloudflare shows the secret only once.",
            "Access controls → Policies → Add a policy: name it \"Life OS phone\", Action Service Auth, " +
                "Include → Service Token → life-os-phone. Save.",
            "Access controls → Applications → Life OS → Configure → Policies → Select existing policies → " +
                "tick \"Life OS phone\" → Confirm → Save.",
            "Paste both values here and tap Test connection.",
        )
        steps.forEachIndexed { index, step ->
            Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "If the test says Access asked for a login, no Service Auth policy includes the token. " +
                "A 401 means the ID or secret is wrong or incomplete. To revoke the phone, delete the token in Cloudflare.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
