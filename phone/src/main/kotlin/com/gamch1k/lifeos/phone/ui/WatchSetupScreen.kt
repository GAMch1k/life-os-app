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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamch1k.lifeos.core.update.WatchAbi
import com.gamch1k.lifeos.core.watchsetup.SetupIssue
import com.gamch1k.lifeos.core.watchsetup.SetupState
import com.gamch1k.lifeos.core.watchsetup.SetupStep
import com.gamch1k.lifeos.core.watchsetup.WatchSetup
import com.gamch1k.lifeos.phone.graph
import com.gamch1k.lifeos.phone.watchsetup.AndroidPhoneWifi
import com.gamch1k.lifeos.phone.watchsetup.LibAdbWatchAdb
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WatchSetupViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph

    private val setup = WatchSetup(
        adb = LibAdbWatchAdb(application, graph.adbIdentity),
        apkSource = { onProgress -> graph.watchUpdates.prepareForSetup(onProgress) },
        ping = { minVersion, timeout -> graph.watchLink.awaitInfo(minVersion, timeout)?.also { graph.watchUpdates.rememberWatch(it) } },
        wifi = AndroidPhoneWifi(application),
        memory = graph.watchSetupMemory,
    )

    val state = setup.state

    private var job: Job? = null

    /** One step at a time: a second tap while one runs does nothing. */
    private fun launchStep(block: suspend WatchSetup.() -> Unit) {
        if (job?.isActive == true) return
        job = viewModelScope.launch { setup.block() }
    }

    fun start() = launchStep { prepare() }
    fun pair(address: String, code: String) = launchStep { pairAndInstall(address, code) }
    fun connect(address: String) = launchStep { connectAndInstall(address) }
    fun usePort(port: String) = launchStep { this.usePort(port) }
    fun retry() = launchStep { this.retry() }
    fun backToPairing() = launchStep { this.backToPairing() }
}

/** Settings › Watch › Set up the watch app: the one-time install over Wireless debugging (docs/wear.md). */
@Composable
fun WatchSetupScreen(navigator: Navigator, viewModel: WatchSetupViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The owner reads the watch while this screen waits; the phone's screen stays on.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(topBar = { BackTopBar("Set up the watch app", navigator::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val current = state) {
                SetupState.Intro -> Intro(onStart = viewModel::start)
                is SetupState.Preparing -> Busy("Downloading the watch app from Life OS…", current.fraction)
                is SetupState.Pair -> PairForm(current, onPair = viewModel::pair, onConnect = viewModel::connect)
                is SetupState.NeedPort -> PortForm(current, onPort = viewModel::usePort, onBack = viewModel::backToPairing)
                is SetupState.Working -> Busy(stepText(current.step), null)
                is SetupState.Failed -> Failed(current, onRetry = viewModel::retry, onBack = viewModel::backToPairing)
                is SetupState.Done -> Done(current, onClose = navigator::back)
            }
        }
    }
}

@Composable
private fun Intro(onStart: () -> Unit) {
    SectionCard("Once, without a computer") {
        Text(
            "This phone installs Life OS on the watch over Wireless debugging, and allows the watch app to update itself. " +
                "After that, the phone sends every new version to the watch and it installs it on its own; " +
                "Wireless debugging stays off.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("You need the watch on the same Wi-Fi as this phone, and about five minutes.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "First the phone downloads the watch app (about 15 MB), so the pairing code doesn't expire while it waits.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = onStart) { Text("Start") }
    }
}

@Composable
private fun PairForm(state: SetupState.Pair, onPair: (String, String) -> Unit, onConnect: (String) -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var rescueAddress by rememberSaveable { mutableStateOf(state.pairedHost.orEmpty()) }

    Text("Life OS ${state.apk.versionName} for the watch is downloaded and checked.", style = MaterialTheme.typography.bodyMedium)
    state.issue?.let { IssueText(it) }

    SectionCard("1. On the watch: the same Wi-Fi") {
        Step("Settings › Connections › Wi-Fi: join the network this phone is on.")
        Step("Keep the watch's screen on while you set it up: Wireless debugging stops when the watch's Wi-Fi sleeps.")
    }
    SectionCard("2. On the watch: Developer options") {
        Step("Settings › About watch › Software information: tap Software version about 5 times, until the watch says Developer mode is on.")
        Step("Settings › Developer options: turn on ADB debugging.")
        Step("Turn on Wireless debugging, and allow it on this network (\"Always allow on this network\").")
    }
    SectionCard("3. Pair") {
        Step("On the watch, open Wireless debugging › Pair new device. It shows a Wi-Fi pairing code and an IP address & port.")
        Step("Type both here, then tap Pair and install. The code works only while that screen is open.")
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("IP address & port, e.g. 192.168.1.23:37421") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Wi-Fi pairing code (6 digits)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onPair(address, code) }) { Text("Pair and install") }
    }
    SectionCard("Paired before?") {
        Text(
            "If this phone is still listed on the watch under Wireless debugging › Paired devices (\"Life OS phone\"), " +
                "turn Wireless debugging on and connect without a new code: type the IP address from the Wireless debugging screen " +
                "(the port is optional).",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = rescueAddress,
            onValueChange = { rescueAddress = it },
            label = { Text("IP address (and port)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { onConnect(rescueAddress) }) { Text("Connect and install") }
    }
}

@Composable
private fun PortForm(state: SetupState.NeedPort, onPort: (String) -> Unit, onBack: () -> Unit) {
    var port by rememberSaveable { mutableStateOf("") }
    SectionCard("The connection port") {
        state.issue?.let { IssueText(it) } ?: Text(
            "Paired. The watch didn't announce its connection port on the network, so type it:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Step("On the watch, go back to the main Wireless debugging screen. Under IP address & port it shows ${state.host}:<port>.")
        Step("Type the port, the number after the colon. It is not the pairing port.")
        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onPort(port) }) { Text("Connect and install") }
            TextButton(onClick = onBack) { Text("Pair again") }
        }
    }
}

@Composable
private fun Busy(text: String, fraction: Float?) {
    SectionCard("Working") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text("Keep the watch's screen on and Wireless debugging open.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Failed(state: SetupState.Failed, onRetry: () -> Unit, onBack: () -> Unit) {
    SectionCard("Not done yet") {
        IssueText(state.issue)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text("Try again") }
            if (state.step != SetupStep.CHECKING && state.step != SetupStep.PREPARING) TextButton(onClick = onBack) { Text("Pair again") }
        }
    }
}

@Composable
private fun Done(state: SetupState.Done, onClose: () -> Unit) {
    SectionCard("The watch app is set up") {
        Text("Life OS ${state.watch.versionName} runs on the watch and answers over Bluetooth.", style = MaterialTheme.typography.bodyMedium)
        Text("New versions now come through this phone and install on the watch by themselves.", style = MaterialTheme.typography.bodyMedium)
        Text("Now turn Wireless debugging off", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Step("On the watch: Settings › Developer options › Wireless debugging: off. You can turn ADB debugging off too.")
        if (state.abis.isNotEmpty()) {
            val runs = WatchAbi.runs(state.abis)
            Text(
                "The watch runs ${state.abis.joinToString()}. " +
                    if (runs) "The watch app carries ${WatchAbi.APK_ABIS.joinToString()} code, which it runs."
                    else "The watch app carries ${WatchAbi.APK_ABIS.joinToString()} code only, which it can't run, so the phone won't send it updates.",
                style = MaterialTheme.typography.bodySmall,
                color = if (runs) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
        state.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = onClose) { Text("Done") }
    }
}

@Composable
private fun Step(text: String) {
    Text("• $text", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun IssueText(issue: SetupIssue) {
    Text(issue.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

private fun stepText(step: SetupStep): String = when (step) {
    SetupStep.PREPARING -> "Downloading the watch app…"
    SetupStep.PAIRING -> "Pairing with the watch…"
    SetupStep.CONNECTING -> "Connecting to the watch…"
    SetupStep.READING -> "Reading the watch's details…"
    SetupStep.INSTALLING -> "Installing Life OS on the watch (about a minute)…"
    SetupStep.GRANTING -> "Allowing the watch app to install its updates…"
    SetupStep.STARTING -> "Starting Life OS on the watch…"
    SetupStep.CHECKING -> "Waiting for the watch app to answer over Bluetooth…"
}
