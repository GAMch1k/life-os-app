package com.gamch1k.lifeos.phone.ui

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamch1k.lifeos.core.capture.CaptureKind
import com.gamch1k.lifeos.core.capture.CaptureStatusText
import com.gamch1k.lifeos.phone.R
import com.gamch1k.lifeos.phone.capture.VoiceRecorder
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface VoiceState {
    data object Starting : VoiceState
    data object NeedsPermission : VoiceState
    data object Recording : VoiceState

    /** Stopped without sending: the screen went to the background, or the 20-minute limit. */
    data class Stopped(val durationMillis: Long, val why: String) : VoiceState
    data class Error(val message: String) : VoiceState
}

/** P18-04: record a voice note (Ogg/Opus) and queue it for Voice, which transcribes it into the Inbox. */
class VoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val recorder = VoiceRecorder(application)
    private val id = graph.queue.newId()
    private val file: File by lazy { File(graph.queue.dirFor(id), "voice.ogg") }
    private val state = MutableStateFlow<VoiceState>(VoiceState.Starting)
    val uiState = state.asStateFlow()
    private var sent = false

    val elapsedMillis: Long get() = recorder.elapsedMillis

    fun level() = recorder.level()

    fun permissionDenied() {
        state.value = VoiceState.NeedsPermission
    }

    /** Starts once: on entering the screen, or when the microphone permission arrives. */
    fun start() {
        val current = state.value
        if (recorder.recording || (current != VoiceState.Starting && current != VoiceState.NeedsPermission)) return
        try {
            recorder.start(file) { viewModelScope.launch { stop("Reached the 20-minute limit.") } }
            state.value = VoiceState.Recording
        } catch (error: Exception) {
            state.value = VoiceState.Error("The microphone could not start: ${error.message}")
        }
    }

    /** Keeps the recording without sending (screen left, or limit reached). */
    fun stop(why: String) {
        if (!recorder.recording) return
        val duration = recorder.stop(file)
        state.value = if (duration == null) VoiceState.Error("Nothing was recorded.") else VoiceState.Stopped(duration, why)
    }

    /**
     * Stop (if needed) and queue; [announce] gets "Voice note (0:12) queued
     * for the Inbox" once it is. Returns false when there is nothing to send.
     */
    fun send(announce: suspend (String) -> Unit): Boolean {
        val duration = when (val current = state.value) {
            is VoiceState.Stopped -> current.durationMillis
            VoiceState.Recording -> recorder.stop(file)
            else -> null
        }
        if (duration == null) {
            state.value = VoiceState.Error("Nothing was recorded.")
            return false
        }
        sent = true
        graph.scope.launch {
            graph.queue.add(id, CaptureKind.VOICE, files = listOf(file), durationMillis = duration)
            announce("Voice note (${CaptureStatusText.duration(duration)}) queued for the Inbox")
        }
        return true
    }

    fun discard() {
        recorder.cancel()
        file.parentFile?.deleteRecursively()
    }

    override fun onCleared() {
        if (!sent) discard()
    }
}

/**
 * Records at once. [onClose] leaves the screen (after Send, Cancel or a
 * discard); [announce] tells that the note was queued. Hosted by the app
 * (Home's Voice) and by [VoiceCaptureActivity] (the side key, P18-12).
 */
@Composable
fun VoiceCaptureScreen(onClose: () -> Unit, announce: suspend (String) -> Unit, viewModel: VoiceViewModel = viewModel()) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.start() else viewModel.permissionDenied()
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.start()
            return@LaunchedEffect
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (activity != null && keyguard?.isKeyguardLocked == true) {
            // Over the lock screen (the side key): the permission dialog comes after the unlock.
            keyguard.requestDismissKeyguard(
                activity,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = permission.launch(Manifest.permission.RECORD_AUDIO)
                    override fun onDismissCancelled() = viewModel.permissionDenied()
                    override fun onDismissError() = viewModel.permissionDenied()
                },
            )
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // The screen stays on while recording: when it goes off, the activity stops, and so does the recording.
    val view = LocalView.current
    DisposableEffect(view, state == VoiceState.Recording) {
        view.keepScreenOn = state == VoiceState.Recording
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(state) {
        while (state == VoiceState.Recording) {
            elapsed = viewModel.elapsedMillis
            level = viewModel.level()
            delay(100)
        }
    }
    // Android stops giving a background app the microphone: keep what was said.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stop("Stopped when the screen was left.") }
    // Back from the app settings with the microphone allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (state == VoiceState.NeedsPermission &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.start()
        }
    }
    BackHandler(enabled = state == VoiceState.Recording || state is VoiceState.Stopped) { confirmDiscard = true }

    Scaffold(topBar = { BackTopBar("Voice", onBack = { confirmDiscard = state == VoiceState.Recording || state is VoiceState.Stopped; if (!confirmDiscard) onClose() }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.weight(1f))
            when (val current = state) {
                VoiceState.Starting -> Text("Starting the microphone…")
                VoiceState.Recording -> {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                    Text(CaptureStatusText.duration(elapsed), style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace)
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                    Text("Recording. Speak, then tap Stop and send.", style = MaterialTheme.typography.bodyMedium)
                }
                is VoiceState.Stopped -> {
                    Text(CaptureStatusText.duration(current.durationMillis), style = MaterialTheme.typography.displayLarge, fontFamily = FontFamily.Monospace)
                    Text("${current.why} Send it, or discard it.", style = MaterialTheme.typography.bodyMedium)
                }
                VoiceState.NeedsPermission -> {
                    Text("Life OS needs the microphone to record voice notes.", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }) { Text("Open app settings") }
                }
                is VoiceState.Error -> Text(current.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.weight(1f))
            val canSend = state == VoiceState.Recording || state is VoiceState.Stopped
            Button(
                onClick = { if (viewModel.send(announce)) onClose() },
                enabled = canSend,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) { Text(if (state == VoiceState.Recording) "Stop and send" else "Send", style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(
                onClick = {
                    viewModel.discard()
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (canSend) "Cancel" else "Close") }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard the recording?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.discard()
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}
