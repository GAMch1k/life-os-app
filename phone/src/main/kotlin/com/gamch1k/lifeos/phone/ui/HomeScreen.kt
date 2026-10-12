package com.gamch1k.lifeos.phone.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.core.capture.CaptureStatus
import com.gamch1k.lifeos.core.capture.CaptureStatusText
import com.gamch1k.lifeos.core.today.EventRow
import com.gamch1k.lifeos.core.today.TaskRow
import com.gamch1k.lifeos.phone.Dashboard
import com.gamch1k.lifeos.phone.R
import com.gamch1k.lifeos.phone.graph
import com.gamch1k.lifeos.phone.update.UpdateState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navigator: Navigator, viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var photoChoice by remember { mutableStateOf(false) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.followLastCapture() } }
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            launch {
                val result = snackbar.showSnackbar(
                    message.text,
                    actionLabel = if (message.undoTaskId != null) "Undo" else null,
                    duration = if (message.undoTaskId != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) message.undoTaskId?.let(viewModel::undo)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Today")
                        state.today?.let { Text(Times.day(it.today), style = MaterialTheme.typography.bodySmall) }
                    }
                },
                actions = {
                    IconButton(onClick = { navigator.go(Screen.Queue) }) {
                        BadgedBox(badge = {
                            if (state.waiting + state.failed > 0) Badge { Text("${state.waiting + state.failed}") }
                        }) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Capture queue") }
                    }
                    IconButton(onClick = { navigator.go(Screen.Settings) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item { UpdateBanner(state.update, onAct = { context.graph.updates.act() }) }
                item { FreshnessLine(state, onSetUp = { navigator.go(Screen.Settings) }) }
                item {
                    CaptureButtons(
                        onVoice = { navigator.go(Screen.VoiceCapture) },
                        onPhoto = { photoChoice = true },
                        onText = { navigator.go(Screen.TextCapture) },
                    )
                }
                item { CaptureSummary(state, onOpen = { navigator.go(Screen.Queue) }) }

                val today = state.today
                if (today == null) {
                    if (!state.refreshing) item { Hint("Nothing loaded yet. Pull down to refresh.") }
                } else {
                    tasks(
                        today.overdue,
                        today.dueToday,
                        onComplete = { viewModel.complete(it.id, it.title) },
                        onOpen = { Dashboard.open(context, scope, "/tasks") },
                    )
                    events("Today", today.eventsToday) { Dashboard.open(context, scope, "/calendar?date=${it.date}") }
                    events("Tomorrow", today.eventsTomorrow) { Dashboard.open(context, scope, "/calendar?date=${it.date}") }
                }
                item {
                    TextButton(onClick = { Dashboard.open(context, scope, "/") }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Open the Dashboard")
                    }
                }
            }
        }
    }

    if (photoChoice) {
        AlertDialog(
            onDismissRequest = { photoChoice = false },
            title = { Text("Photo") },
            text = { Text("Take a photo, or choose up to five from the gallery.") },
            confirmButton = {
                TextButton(onClick = {
                    photoChoice = false
                    navigator.go(Screen.PhotoCapture(camera = true))
                }) { Text("Camera") }
            },
            dismissButton = {
                TextButton(onClick = {
                    photoChoice = false
                    navigator.go(Screen.PhotoCapture(camera = false))
                }) { Text("Gallery") }
            },
        )
    }
}

@Composable
private fun FreshnessLine(state: HomeUiState, onSetUp: () -> Unit) {
    val error = state.error
    val time = state.fetchedAt?.let { Times.short(it) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        when {
            error == null -> time?.let { Text("Updated $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error is LifeOsException.Offline || error is LifeOsException.Timeout ->
                Text(
                    "Offline" + (time?.let { " · showing what was there at $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            else -> {
                Text(error.message ?: "Could not refresh.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (error.authProblem) TextButton(onClick = onSetUp) { Text("Open Settings") }
            }
        }
    }
}

@Composable
private fun CaptureButtons(onVoice: () -> Unit, onPhoto: () -> Unit, onText: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CaptureButton("Voice", painterResource(R.drawable.ic_mic), onVoice, Modifier.weight(1f), primary = true)
        CaptureButton("Photo", painterResource(R.drawable.ic_photo_camera), onPhoto, Modifier.weight(1f))
        CaptureButton("Text", null, onText, Modifier.weight(1f), vector = Icons.Filled.Edit)
    }
}

@Composable
private fun CaptureButton(
    label: String,
    painter: androidx.compose.ui.graphics.painter.Painter?,
    onClick: () -> Unit,
    modifier: Modifier,
    primary: Boolean = false,
    vector: ImageVector? = null,
) {
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (painter != null) Icon(painter, contentDescription = null, modifier = Modifier.size(32.dp))
            if (vector != null) Icon(vector, contentDescription = null, modifier = Modifier.size(32.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
    if (primary) {
        Button(onClick = onClick, modifier = modifier.height(96.dp), shape = MaterialTheme.shapes.large) { content() }
    } else {
        FilledTonalButton(onClick = onClick, modifier = modifier.height(96.dp), shape = MaterialTheme.shapes.large) { content() }
    }
}

@Composable
private fun CaptureSummary(state: HomeUiState, onOpen: () -> Unit) {
    val last = state.lastCapture
    if (last == null && state.waiting == 0 && state.failed == 0) return
    Card(
        onClick = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = if (state.failed > 0) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (last != null) {
                Text("Last capture: " + CaptureStatusText.title(last), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(CaptureStatusText.describe(last), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val queued = buildList {
                if (state.waiting > 0 && !(state.waiting == 1 && last?.status in setOf(CaptureStatus.PENDING, CaptureStatus.SENDING))) add("${state.waiting} waiting to send")
                if (state.failed > 0) add("${state.failed} not sent")
            }
            if (queued.isNotEmpty()) Text(queued.joinToString(" · "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun UpdateBanner(update: UpdateState, onAct: () -> Unit) {
    val (text, action) = when (update) {
        is UpdateState.Available -> "Version ${update.release.versionName} is available." to "Install"
        is UpdateState.ReadyToInstall -> "Version ${update.release.versionName} is downloaded." to "Install"
        is UpdateState.NeedsPermission -> "Allow Life OS to install its updates (Install unknown apps)." to "Allow"
        is UpdateState.WaitingForConfirmation -> "Version ${update.release.versionName} is waiting for your confirmation." to "Confirm"
        is UpdateState.Downloading -> "Downloading version ${update.release.versionName}…" to null
        is UpdateState.Installing -> "Installing version ${update.release.versionName}…" to null
        else -> return
    }
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                action?.let { TextButton(onClick = onAct) { Text(it) } }
            }
            if (update is UpdateState.Downloading) LinearProgressIndicator(progress = { update.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun LazyListScope.tasks(overdue: List<TaskRow>, dueToday: List<TaskRow>, onComplete: (TaskRow) -> Unit, onOpen: () -> Unit) {
    item { SectionTitle("Tasks", "Tap to mark done · long-press to open") }
    if (overdue.isEmpty() && dueToday.isEmpty()) {
        item { Hint("Nothing due today.") }
        return
    }
    items(overdue, key = { "t-" + it.id }) { TaskItem(it, overdue = true, onComplete, onOpen) }
    items(dueToday, key = { "t-" + it.id }) { TaskItem(it, overdue = false, onComplete, onOpen) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskItem(row: TaskRow, overdue: Boolean, onComplete: (TaskRow) -> Unit, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = { onComplete(row) }, onLongClick = onOpen, onLongClickLabel = "Open in the Dashboard"),
        leadingContent = { Icon(painterResource(R.drawable.ic_radio_unchecked), contentDescription = "Mark done") },
        headlineContent = { Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (overdue) Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                Text(row.detail, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.recurring) Icon(painterResource(R.drawable.ic_repeat), contentDescription = "Repeats", modifier = Modifier.size(14.dp))
            }
        },
    )
}

private fun LazyListScope.events(title: String, rows: List<EventRow>, onOpen: (EventRow) -> Unit) {
    item { SectionTitle(title) }
    if (rows.isEmpty()) {
        item { Hint("No events.") }
        return
    }
    items(rows, key = { "e-$title-" + it.key }) { row ->
        ListItem(
            modifier = Modifier.clickable { onOpen(row) },
            overlineContent = { Text(row.time) },
            headlineContent = { Text(row.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = listOfNotNull(row.location, row.sourceName).takeIf { it.isNotEmpty() }?.let { parts ->
                { Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String, hint: String? = null) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
