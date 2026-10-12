package com.gamch1k.lifeos.wear.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.gamch1k.lifeos.wear.BuildConfig
import com.gamch1k.lifeos.wear.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Connection: is the phone there, does the watch hold a token, does Life OS answer (and which way). */
@Composable
fun ConnectionScreen(viewModel: WearViewModel) {
    val phone by viewModel.phone.collectAsStateWithLifecycle()
    val credentials by viewModel.credentials.collectAsStateWithLifecycle()
    val message by viewModel.connectionMessage.collectAsStateWithLifecycle()
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val listState = rememberTransformingLazyColumnState()
    LaunchedEffect(Unit) { viewModel.lookForPhone() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.connection)) } }
            item {
                Line(
                    stringResource(R.string.phone),
                    when (phone) {
                        null -> "…"
                        "" -> stringResource(R.string.phone_not_connected)
                        else -> stringResource(R.string.phone_connected, phone.orEmpty())
                    },
                )
            }
            item {
                val stored = credentials
                Line(
                    stringResource(R.string.token),
                    if (stored == null) {
                        stringResource(R.string.token_missing)
                    } else {
                        val at = Instant.ofEpochMilli(stored.receivedAtMillis).atZone(ZoneId.systemDefault())
                        stringResource(R.string.token_received, at.format(DateTimeFormatter.ofPattern(if (use24Hour) "yyyy-MM-dd HH:mm" else "yyyy-MM-dd h:mm a")))
                    },
                )
            }
            item {
                FilledTonalButton(
                    onClick = viewModel::fetchToken,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.get_token)) },
                )
            }
            item {
                FilledTonalButton(
                    onClick = viewModel::testConnection,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.test_connection)) },
                )
            }
            message?.let { text ->
                item { Text(text, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            }
            item {
                Text(
                    stringResource(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Line(title: String, value: String) {
    Text(
        "$title: $value",
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}
