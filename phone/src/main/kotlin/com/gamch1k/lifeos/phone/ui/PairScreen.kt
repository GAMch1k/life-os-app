package com.gamch1k.lifeos.phone.ui

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.pairing.DeviceNames
import com.gamch1k.lifeos.core.pairing.PairingChallenge
import com.gamch1k.lifeos.core.pairing.PairingCode
import com.gamch1k.lifeos.core.pairing.PairingLink
import com.gamch1k.lifeos.core.pairing.QrCodes
import com.gamch1k.lifeos.core.secrets.PendingPairing
import com.gamch1k.lifeos.core.secrets.ServerUrl
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

data class PairUiState(
    /** The server the code is redeemed at: the app's, or the one the person typed at the first run. */
    val server: String = "",
    /** The server the app is set up for now, to say when a link leads elsewhere. */
    val savedServer: String = "",
    val code: String = "",
    /** A link from a QR code or another app, waiting for "Pair with <host>?". */
    val confirm: PairingLink.Code? = null,
    val working: Boolean = false,
    val waitingForBrowser: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** Set once paired: what to tell the person. */
    val done: String? = null,
)

/**
 * P21-06: pairs this phone with an account (life-os docs/api/devices.md).
 * The code comes from a QR code in the Dashboard's Settings → Devices (read
 * from a photo the camera app takes, or opened by the camera app as a
 * `lifeos://pair` link), typed by hand, or from signing in in a Custom Tab
 * (with a PKCE-style verifier and a state). A link from outside is shown
 * ("Pair with lifeos.example.com?") and paired only after a tap.
 */
class PairViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val state = MutableStateFlow(PairUiState())
    val uiState = state.asStateFlow()
    private var started = false
    private var redeeming = false
    private val qrDir = File(application.cacheDir, "camera")

    fun start(link: String?, server: String?) {
        if (started) return
        started = true
        viewModelScope.launch {
            val saved = graph.tokens.serverUrl()
            state.update { it.copy(server = server?.let(ServerUrl::normalize) ?: saved, savedServer = saved) }
            if (link != null) handleLink(link)
        }
    }

    /** A pairing link: from [PairLinkActivity][com.gamch1k.lifeos.phone.PairLinkActivity] or read from a QR code. */
    fun handleLink(text: String) {
        when (val link = PairingLink.parse(text.trim())) {
            null -> fail("That is not a Life OS pairing code. Show a new one in Life OS: Settings → Devices → Pair a phone.")
            is PairingLink.Refused -> viewModelScope.launch {
                val pending = graph.tokens.pending()
                if (pending?.state != link.state) return@launch fail("A pairing link that was not started on this phone was ignored.")
                graph.tokens.clearPending()
                state.update { it.copy(waitingForBrowser = false, message = "Pairing was cancelled in the browser.", messageIsError = false) }
            }
            is PairingLink.Code -> if (link.state != null) {
                viewModelScope.launch { fromBrowser(link) }
            } else {
                state.update { it.copy(confirm = link, message = null) }
            }
        }
    }

    /** The Custom Tab came back with a code: only for the state this phone started, redeemed with its verifier. */
    private suspend fun fromBrowser(link: PairingLink.Code) {
        val pending = graph.tokens.pending()
        if (pending == null || pending.state != link.state) {
            fail("This pairing link was not started on this phone, or it took too long. Tap Sign in on this phone again.")
            return
        }
        graph.tokens.clearPending()
        state.update { it.copy(waitingForBrowser = false, server = pending.server) }
        // Redeemed at the server the app opened, which is normally the link's host too.
        redeem(pending.server, link.code, pending.verifier)
    }

    fun confirmLink() {
        val link = state.value.confirm ?: return
        state.update { it.copy(confirm = null, server = link.server) }
        viewModelScope.launch { redeem(link.server, link.code, verifier = null) }
    }

    fun cancelLink() = state.update { it.copy(confirm = null) }

    fun editCode(text: String) = state.update { it.copy(code = text, message = null) }

    fun pairWithCode() {
        val code = PairingCode.normalize(state.value.code)
            ?: return fail("A pairing code has 16 letters and digits, such as K7QM-2XRP-9VDA-H4TC.")
        viewModelScope.launch { redeem(state.value.server, code, verifier = null) }
    }

    /** "Sign in on this phone": remembers the verifier and state, then [open]s the sign-in page in a Custom Tab. */
    fun signInInBrowser(open: (Uri) -> Unit) {
        val server = state.value.server
        val challenge = PairingChallenge.create()
        viewModelScope.launch {
            graph.tokens.savePending(PendingPairing(server, challenge.state, challenge.verifier, System.currentTimeMillis()))
            state.update { it.copy(waitingForBrowser = true, message = null) }
            open(Uri.parse(challenge.signInUrl(server)))
        }
    }

    /** Where the camera app writes the photo of the QR code (shared through our FileProvider, like Photo capture's). */
    fun qrPhotoTarget(): Uri {
        qrDir.mkdirs()
        val file = File(qrDir, "pair-${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.files", file)
    }

    fun readQrPhoto(uri: Uri) {
        state.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            val text = withContext(Dispatchers.Default) {
                try {
                    // Large first; a smaller copy sometimes reads better (a screen's moiré).
                    readQr(uri, 2048) ?: readQr(uri, 1024)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } finally {
                    qrDir.listFiles()?.filter { it.name.startsWith("pair-") }?.forEach { it.delete() }
                }
            }
            state.update { it.copy(working = false) }
            if (text == null) {
                fail("No QR code could be read from the photo. Hold the phone steady and fill the photo with the code, or type the code below.")
            } else {
                handleLink(text)
            }
        }
    }

    private fun readQr(uri: Uri, maxEdge: Int): String? {
        val source = ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > maxEdge) {
                val scale = maxEdge.toDouble() / longest
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            QrCodes.decode(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    fun fail(message: String) = state.update { it.copy(message = message, messageIsError = true, working = false) }

    /**
     * Redeems [code] at [server] and keeps the key. Runs in the app's scope:
     * a code works once, so leaving the screen meanwhile must not lose the key.
     */
    private suspend fun redeem(server: String, code: String, verifier: String?) {
        // One at a time: a double tap must not spend the code twice.
        if (redeeming) return
        redeeming = true
        try {
            redeemOnce(server, code, verifier)
        } finally {
            redeeming = false
        }
    }

    private suspend fun redeemOnce(server: String, code: String, verifier: String?) {
        state.update { it.copy(working = true, message = null) }
        val name = DeviceNames.clean(phoneName(), DeviceNames.PHONE_FALLBACK)
        val work = graph.scope.async {
            val paired = graph.client.pair(server, code, name, verifier)
            graph.paired(server, paired, name)
        }
        try {
            work.await()
            val pairing = graph.tokens.currentPairing()
            state.update {
                it.copy(
                    working = false,
                    code = "",
                    done = "Paired as ${pairing?.account ?: "your account"} on ${hostOf(server)}. " +
                        "Life OS lists this phone as \"${pairing?.deviceName ?: name}\" in Settings → Devices.",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LifeOsException) {
            state.update { it.copy(working = false, message = describe(error), messageIsError = true) }
        } catch (error: IllegalArgumentException) {
            state.update { it.copy(working = false, message = "Life OS answered with a key this app cannot use.", messageIsError = true) }
        }
    }

    /** The phone's name as the person set it ("Galaxy S24"), else its model. */
    private fun phoneName(): String? {
        val resolver = getApplication<Application>().contentResolver
        return runCatching { Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    }

    companion object {
        fun hostOf(server: String): String = runCatching { URI(server).host }.getOrNull() ?: server

        fun describe(error: LifeOsException): String = when (error) {
            is LifeOsException.AccessRedirect, is LifeOsException.Unauthorized ->
                "Cloudflare Access did not let the request through. If your Life OS is behind Access, set up this phone's " +
                    "service token first (Settings › Connection, or the first screen), then pair."
            is LifeOsException.Api -> when {
                error.status == 429 -> "Too many tries from this network. Wait ${error.retryAfterSeconds ?: 60} seconds, then try again."
                // pairing_code_invalid and the rest: Life OS's own words.
                else -> error.detail
            }
            else -> error.message ?: "Pairing failed."
        }
    }
}

@Composable
fun PairScreen(navigator: Navigator, link: String?, server: String?, viewModel: PairViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingPhoto by rememberSaveable { mutableStateOf<Uri?>(null) }
    LaunchedEffect(Unit) { viewModel.start(link, server) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingPhoto
        pendingPhoto = null
        if (saved && uri != null) viewModel.readQrPhoto(uri)
    }

    Scaffold(topBar = { BackTopBar("Pair this phone", navigator::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val done = state.done
            if (done != null) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        Text(done, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    "The watch gets a key of its own from the phone the next time it connects. To unpair, use Settings › Account here, " +
                        "or sign the phone out in Life OS: Settings → Devices.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { navigator.reset(Screen.Home) }) { Text("Continue") }
            } else {
                PairChoices(state, viewModel, onScan = {
                    val target = viewModel.qrPhotoTarget()
                    pendingPhoto = target
                    try {
                        takePhoto.launch(target)
                    } catch (_: ActivityNotFoundException) {
                        pendingPhoto = null
                        viewModel.fail("This phone has no camera app: type the code instead.")
                    }
                }, onSignIn = {
                    viewModel.signInInBrowser { uri ->
                        try {
                            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
                        } catch (_: ActivityNotFoundException) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                    }
                })
            }
        }
    }

    state.confirm?.let { asked ->
        val elsewhere = asked.server != state.savedServer
        AlertDialog(
            onDismissRequest = viewModel::cancelLink,
            title = { Text("Pair with ${asked.host}?") },
            text = {
                Text(
                    "This phone gets a key for your account on ${asked.server}. Pair only with a code you just made yourself." +
                        if (elsewhere) " The app is set up for ${PairViewModel.hostOf(state.savedServer)} now; pairing moves it to ${asked.host}." else "",
                )
            },
            confirmButton = { TextButton(onClick = viewModel::confirmLink) { Text("Pair") } },
            dismissButton = { TextButton(onClick = viewModel::cancelLink) { Text("Cancel") } },
        )
    }
}

/** The three ways to a code: a photo of the QR code, typing it, or signing in in the browser. */
@Composable
private fun PairChoices(state: PairUiState, viewModel: PairViewModel, onScan: () -> Unit, onSignIn: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Pairing gives this phone a key of its own for your Life OS account: no password is stored on it, " +
                "and Life OS (Settings → Devices) can sign it out at any time. Its watch gets a key of its own too.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("Server: ${state.server}", style = MaterialTheme.typography.bodyMedium)

        SectionCard("With a second screen") {
            Text(
                "In Life OS on a computer: Settings → Devices → Pair a phone → Show a pairing code. Then take a photo of the QR code; " +
                    "Life OS reads the code from it. (Your camera app may also open the code by itself.)",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onScan, enabled = !state.working) { Text("Scan the QR code") }
            OutlinedTextField(
                value = state.code,
                onValueChange = viewModel::editCode,
                label = { Text("Or type the code") },
                placeholder = { Text("K7QM-2XRP-9VDA-H4TC") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = viewModel::pairWithCode, enabled = !state.working && state.code.isNotBlank()) { Text("Pair with the code") }
        }

        SectionCard("On this phone only") {
            Text(
                "Sign in to Life OS in the browser and confirm Pair; the browser then brings you back here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onSignIn, enabled = !state.working) { Text("Sign in on this phone") }
            if (state.waitingForBrowser) {
                Text("Waiting for the browser… If it doesn't bring you back, tap Sign in on this phone again.", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.working) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text("Pairing…", style = MaterialTheme.typography.bodyMedium)
            }
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
    }
}
