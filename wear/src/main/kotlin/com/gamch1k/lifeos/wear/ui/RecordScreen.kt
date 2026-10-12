package com.gamch1k.lifeos.wear.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.capture.CaptureState
import com.gamch1k.lifeos.wear.capture.Recorder
import com.gamch1k.lifeos.wear.capture.RecorderState
import kotlinx.coroutines.delay

/**
 * Recording: starts at once (asking for the microphone the first time), shows
 * a timer, and offers Send and Cancel, with haptics for start, send and
 * cancel. After Send it shows the capture's state until it is sent or waits
 * for the phone, then returns (or closes the app when opened from a tile, the
 * complication or the Home key).
 */
@Composable
fun RecordScreen(viewModel: WearViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by Recorder.state.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(hasMicrophone(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        asked = true
    }

    LaunchedEffect(granted) {
        if (!granted && !asked) permission.launch(Manifest.permission.RECORD_AUDIO)
        if (granted && Recorder.state.value is RecorderState.Idle) Recorder.start(context)
    }

    ScreenScaffold { contentPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                !granted -> MicrophoneRequest(asked = asked, onAsk = { permission.launch(Manifest.permission.RECORD_AUDIO) })
                state is RecorderState.Recording -> Recording(
                    state as RecorderState.Recording,
                    onSend = {
                        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        Recorder.send(context)
                    },
                    onCancel = {
                        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                        Recorder.cancel(context)
                    },
                )
                state is RecorderState.Finished -> Finished(viewModel, state as RecorderState.Finished, onDone)
                else -> Text(stringResource(R.string.recording), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Recording(state: RecorderState.Recording, onSend: () -> Unit, onCancel: () -> Unit) {
    val view = LocalView.current
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.captureId) {
        view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
        while (true) {
            elapsed = SystemClock.elapsedRealtime() - state.startedAtElapsed
            delay(250)
        }
    }
    // The screen stays on while recording, so the timer stays in sight.
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val seconds = elapsed / 1000
    Text(stringResource(R.string.recording), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
    Text("${seconds / 60}:${"%02d".format(seconds % 60)}", style = MaterialTheme.typography.displayMedium)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = onCancel, modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize)) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel))
        }
        Spacer(Modifier.width(12.dp))
        FilledIconButton(onClick = onSend, modifier = Modifier.size(IconButtonDefaults.LargeButtonSize)) {
            Icon(
                painterResource(R.drawable.ic_send),
                contentDescription = stringResource(R.string.send),
                modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
            )
        }
    }
}

/** After the recording: what happened to it, live, until it is on its way. */
@Composable
private fun Finished(viewModel: WearViewModel, state: RecorderState.Finished, onDone: () -> Unit) {
    val view = LocalView.current
    val captures by viewModel.captures.collectAsStateWithLifecycle()
    val capture = state.captureId?.let { id -> captures.find { it.id == id } }
    val settled = when (state.outcome) {
        RecorderState.Outcome.QUEUED -> capture == null || capture.state !in setOf(CaptureState.QUEUED, CaptureState.SENDING)
        else -> true
    }
    LaunchedEffect(state, settled) {
        if (!settled) {
            delay(10_000) // Don't hold the owner longer than this; the queue goes on in the background.
        } else {
            if (capture?.state == CaptureState.SENT || capture?.state == CaptureState.IN_INBOX) {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            }
            delay(1_500)
        }
        Recorder.acknowledge()
        onDone()
    }
    val text = when (state.outcome) {
        RecorderState.Outcome.QUEUED -> stringResource(capture?.state?.label() ?: R.string.state_queued)
        RecorderState.Outcome.TOO_SHORT -> stringResource(R.string.too_short)
        RecorderState.Outcome.CANCELLED -> stringResource(R.string.cancelled)
        RecorderState.Outcome.FAILED -> state.message ?: stringResource(R.string.state_failed)
    }
    Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    capture?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
}

@Composable
private fun MicrophoneRequest(asked: Boolean, onAsk: () -> Unit) {
    val context = LocalContext.current
    val permanentlyDenied = asked && context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false
    Text(stringResource(R.string.mic_permission_title), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(if (permanentlyDenied) R.string.mic_permission_denied else R.string.mic_permission_text),
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
    Spacer(Modifier.height(8.dp))
    if (permanentlyDenied) {
        FilledTonalButton(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            label = { Text(stringResource(R.string.mic_permission_settings)) },
        )
    } else {
        FilledTonalButton(onClick = onAsk, label = { Text(stringResource(R.string.mic_permission_allow)) })
    }
}

private fun hasMicrophone(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
