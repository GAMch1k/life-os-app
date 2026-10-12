package com.gamch1k.lifeos.phone.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamch1k.lifeos.core.capture.Capture
import com.gamch1k.lifeos.core.capture.CaptureKind
import com.gamch1k.lifeos.core.capture.CaptureStatus
import com.gamch1k.lifeos.core.capture.CaptureStatusText
import com.gamch1k.lifeos.phone.Dashboard
import com.gamch1k.lifeos.phone.R
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class QueueUiState(val captures: List<Capture> = emptyList(), val refreshing: Boolean = false)

/** P18-04: what the queue holds, newest first, with Retry and Delete. */
class QueueViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val refreshing = MutableStateFlow(false)

    val uiState = combine(graph.captures.captures.map { list -> list.sortedByDescending { it.createdAt } }, refreshing) { captures, busy ->
        QueueUiState(captures, busy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueUiState())

    /** While the queue is on screen, follows recent sends until they settle. */
    suspend fun follow() {
        while (true) {
            followRecent(Duration.ofMinutes(30))
            delay(5_000)
        }
    }

    private suspend fun followRecent(window: Duration) {
        val now = Instant.now()
        graph.captures.all()
            .filter { it.status == CaptureStatus.SENT && !it.settled }
            .filter { Duration.between(Instant.parse(it.sentAt ?: it.createdAt), now) < window }
            .forEach { graph.statusChecker.refresh(it.id) }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                followRecent(Duration.ofDays(7))
            } finally {
                refreshing.value = false
            }
        }
    }

    fun retry(id: String) = viewModelScope.launch { graph.queue.retry(id) }

    fun delete(id: String) = viewModelScope.launch { graph.queue.delete(id) }

    fun clearSent() = viewModelScope.launch { graph.captures.pruneSent(Instant.now(), Duration.ZERO) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(navigator: Navigator, viewModel: QueueViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.follow() } }

    Scaffold(
        topBar = {
            BackTopBar("Captures", navigator::back) {
                if (state.captures.any { it.status == CaptureStatus.SENT }) TextButton(onClick = { viewModel.clearSent() }) { Text("Clear sent") }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.captures.isEmpty()) {
                    item { Text("Nothing captured yet. Sent captures stay here for a week.", style = MaterialTheme.typography.bodyMedium) }
                }
                items(state.captures, key = { it.id }) { capture ->
                    CaptureCard(
                        capture,
                        onRetry = { viewModel.retry(capture.id) },
                        onDelete = { viewModel.delete(capture.id) },
                        onOpenInbox = { Dashboard.open(context, scope, "/inbox") },
                    )
                }
            }
        }
    }
}

@Composable
private fun CaptureCard(capture: Capture, onRetry: () -> Unit, onDelete: () -> Unit, onOpenInbox: () -> Unit) {
    val failed = capture.status == CaptureStatus.FAILED
    Card(
        Modifier.fillMaxWidth(),
        colors = if (failed) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (capture.kind) {
                    CaptureKind.VOICE -> Icon(painterResource(R.drawable.ic_mic), contentDescription = "Voice", modifier = Modifier.size(20.dp))
                    CaptureKind.PHOTO -> Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = "Photo", modifier = Modifier.size(20.dp))
                    CaptureKind.TEXT -> Icon(Icons.Filled.Edit, contentDescription = "Text", modifier = Modifier.size(20.dp))
                }
                Text(CaptureStatusText.title(capture), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(Times.parseOrNull(capture.createdAt)?.let { Times.short(it) } ?: "", style = MaterialTheme.typography.bodySmall)
            }
            Text(CaptureStatusText.describe(capture), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (capture.status == CaptureStatus.FAILED || capture.status == CaptureStatus.PENDING) {
                    TextButton(onClick = onRetry) { Text(if (failed) "Retry" else "Send now") }
                }
                if (capture.inboxItemId != null) TextButton(onClick = onOpenInbox) { Text("Open Inbox") }
                TextButton(onClick = onDelete) { Text(if (capture.status == CaptureStatus.SENT) "Remove" else "Delete") }
            }
        }
    }
}
