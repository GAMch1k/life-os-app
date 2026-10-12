package com.gamch1k.lifeos.wear.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.capture.Capture
import com.gamch1k.lifeos.wear.capture.CaptureState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Home: the big Record button, the recent captures with their state, and Connection. */
@Composable
fun HomeScreen(viewModel: WearViewModel, onRecord: () -> Unit, onConnection: () -> Unit) {
    val captures by viewModel.captures.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val use24Hour = DateFormat.is24HourFormat(context)
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onRecord, buttonSize = EdgeButtonSize.Large) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = stringResource(R.string.record_voice_note), modifier = Modifier.size(32.dp))
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.app_name)) } }
            if (credentials == null) {
                item {
                    Text(
                        stringResource(R.string.token_missing) + " · " + stringResource(R.string.token_fetch_no_phone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (captures.isNotEmpty()) {
                item { ListHeader { Text(stringResource(R.string.recent)) } }
                items(captures.take(8), key = { it.id }) { capture ->
                    CaptureButton(
                        capture = capture,
                        use24Hour = use24Hour,
                        onRetry = { viewModel.retry(capture.id) },
                        onDelete = { viewModel.delete(capture.id) },
                    )
                }
            }
            item {
                FilledTonalButton(
                    onClick = onConnection,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(painterResource(R.drawable.ic_phone), contentDescription = null) },
                    label = { Text(stringResource(R.string.connection)) },
                )
            }
        }
    }
}

/** One capture: its time and length, its state; tap retries a failed or waiting one, long-press deletes. */
@Composable
private fun CaptureButton(capture: Capture, use24Hour: Boolean, onRetry: () -> Unit, onDelete: () -> Unit) {
    val time = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a")
        .format(Instant.ofEpochMilli(capture.createdAt).atZone(ZoneId.systemDefault()))
    val seconds = capture.durationMillis / 1000
    val retryable = capture.state == CaptureState.FAILED || capture.state == CaptureState.WAITING
    Button(
        onClick = { if (retryable) onRetry() },
        onLongClick = onDelete,
        onLongClickLabel = stringResource(R.string.delete),
        modifier = Modifier.fillMaxWidth(),
        colors = if (capture.state == CaptureState.FAILED) ButtonDefaults.filledVariantButtonColors() else ButtonDefaults.filledTonalButtonColors(),
        label = { Text("$time · ${seconds / 60}:${"%02d".format(seconds % 60)}") },
        secondaryLabel = {
            Text(
                listOfNotNull(stringResource(capture.state.label()), capture.message?.takeIf { retryable }).joinToString(" · "),
                maxLines = 3,
            )
        },
    )
}

fun CaptureState.label(): Int = when (this) {
    CaptureState.QUEUED -> R.string.state_queued
    CaptureState.SENDING -> R.string.state_sending
    CaptureState.WAITING -> R.string.state_waiting
    CaptureState.SENT -> R.string.state_sent
    CaptureState.IN_INBOX -> R.string.state_in_inbox
    CaptureState.FAILED -> R.string.state_failed
}
