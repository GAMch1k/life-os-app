package com.gamch1k.lifeos.phone.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.capture.CaptureStatus
import com.gamch1k.lifeos.phone.BuildConfig
import com.gamch1k.lifeos.phone.Dashboard
import com.gamch1k.lifeos.phone.graph
import com.gamch1k.lifeos.phone.update.UpdateManager
import com.gamch1k.lifeos.phone.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val server: String? = null,
    val clientId: String? = null,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean = false,
    val unsent: Int = 0,
    val update: UpdateState = UpdateState.Idle,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val test = MutableStateFlow(Triple(false, null as String?, false))

    val uiState = combine(graph.tokens.connection, graph.captures.captures, graph.updates.state, test) { connection, captures, update, (testing, result, ok) ->
        SettingsUiState(
            server = connection?.baseUrl,
            clientId = connection?.token?.maskedClientId,
            testing = testing,
            testResult = result,
            testOk = ok,
            unsent = captures.count { it.status != CaptureStatus.SENT },
            update = update,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun testConnection() {
        test.value = Triple(true, null, false)
        viewModelScope.launch {
            test.value = try {
                val connection = graph.tokens.current() ?: throw LifeOsException.NotSignedIn()
                graph.client.testConnection(connection)
                Triple(false, "Connected: Life OS answered through Cloudflare Access.", true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LifeOsException) {
                Triple(false, error.message, false)
            }
        }
    }

    fun checkForUpdates() {
        graph.scope.launch { graph.updates.check(UpdateManager.Trigger.MANUAL) }
    }

    fun updateAction() = graph.updates.act()

    val signingOut = MutableStateFlow(false)

    /** Runs in the app's scope: leaving Settings meanwhile must not leave the token behind. */
    fun signOut(done: () -> Unit) {
        if (signingOut.value) return
        signingOut.value = true
        val work = graph.scope.async { graph.signOut() }
        viewModelScope.launch {
            work.await()
            done()
        }
    }
}

@Composable
fun SettingsScreen(navigator: Navigator, viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar("Settings", navigator::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("Connection") {
                Text("Server: ${state.server ?: "not set up"}", style = MaterialTheme.typography.bodyMedium)
                Text("Client ID: ${state.clientId ?: "none"}", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = viewModel::testConnection, enabled = !state.testing) { Text("Test connection") }
                    TextButton(onClick = { navigator.go(Screen.Onboarding(firstRun = false)) }) { Text("Change token") }
                    if (state.testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                state.testResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (state.testOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }

            SectionCard("Updates") {
                Text("Life OS ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
                UpdateStatus(state.update)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val busy = state.update is UpdateState.Checking || state.update is UpdateState.Downloading || state.update is UpdateState.Installing
                    OutlinedButton(onClick = viewModel::checkForUpdates, enabled = !busy) { Text("Check for updates") }
                    updateActionLabel(state.update)?.let { label -> Button(onClick = viewModel::updateAction) { Text(label) } }
                }
                Text(
                    "Life OS checks when it starts and once a day on Wi-Fi; installing needs \"Install unknown apps\" for Life OS. " +
                        "The first update after installing from your browser asks you to confirm once; " +
                        "after that, updates install without asking.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            NotificationsSection()

            SectionCard("Diagnostics") {
                Text("Can this phone reach Life OS with the hostname hidden (ECH)?", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { navigator.go(Screen.NetworkCheck) }) { Text("Network check") }
            }

            SectionCard("Dashboard") {
                Text("Everything else (Finance, Food, the Inbox, Calendar) is in the Dashboard, in your browser.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { Dashboard.open(context, scope, "/") }) { Text("Open the Dashboard") }
            }

            SectionCard("Sign out") {
                Text("Removes the service token, the push subscription, the cached Today and the capture queue from this phone.", style = MaterialTheme.typography.bodySmall)
                val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { confirmSignOut = true }, enabled = !signingOut) { Text("Sign out") }
                    if (signingOut) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    if (state.unsent > 0) "${state.unsent} capture(s) not sent yet will be deleted. The token is removed from this phone; delete it in Cloudflare to revoke it."
                    else "The token is removed from this phone; delete it in Cloudflare to revoke it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    viewModel.signOut { navigator.reset(Screen.Onboarding(firstRun = true)) }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UpdateStatus(update: UpdateState) {
    val text = when (update) {
        UpdateState.Idle -> null
        UpdateState.Checking -> "Checking…"
        UpdateState.UpToDate -> "Up to date."
        is UpdateState.Available -> "Version ${update.release.versionName} (${update.release.versionCode}) is available." +
            (update.release.notes?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
        is UpdateState.Downloading -> "Downloading ${update.release.versionName}: ${(update.fraction * 100).toInt()} %"
        is UpdateState.ReadyToInstall -> "Version ${update.release.versionName} is downloaded and verified."
        is UpdateState.NeedsPermission -> "Allow \"Install unknown apps\" for Life OS so it can install ${update.release.versionName}."
        is UpdateState.Installing -> "Installing ${update.release.versionName}…"
        is UpdateState.WaitingForConfirmation -> "Android asks you to confirm ${update.release.versionName}."
        is UpdateState.Failed -> update.message
    }
    text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (update is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
    if (update is UpdateState.Downloading) LinearProgressIndicator(progress = { update.fraction }, modifier = Modifier.fillMaxWidth())
}

private fun updateActionLabel(update: UpdateState): String? = when (update) {
    is UpdateState.Available, is UpdateState.ReadyToInstall -> "Install"
    is UpdateState.NeedsPermission -> "Allow"
    is UpdateState.WaitingForConfirmation -> "Confirm"
    is UpdateState.Failed -> if (update.release != null) "Try again" else null
    else -> null
}
