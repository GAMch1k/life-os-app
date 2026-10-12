package com.gamch1k.lifeos.wear.ui

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FailureConfirmationDialog
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.confirmationDialogCurvedText
import com.gamch1k.lifeos.wear.R
import com.gamch1k.lifeos.wear.today.TodayLayout
import com.gamch1k.lifeos.wear.today.TodayRow
import java.time.Instant
import java.time.ZoneId

/**
 * Today: the events still ahead (today and tomorrow) and the tasks due; tap a
 * task to mark it done. The item the complication or the tile was showing is
 * outlined and scrolled to.
 */
@Composable
fun TodayScreen(viewModel: WearViewModel) {
    val snapshot by viewModel.today.collectAsStateWithLifecycle()
    val error by viewModel.todayError.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val completion by viewModel.completion.collectAsStateWithLifecycle()
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val now = Instant.now()
    val zone = ZoneId.systemDefault()
    val events = snapshot?.let { TodayLayout.upcomingEvents(it, now, zone, use24Hour) }.orEmpty()
    val tasks = snapshot?.let { TodayLayout.tasks(it, now, zone, use24Hour) }.orEmpty()
    val listState = rememberTransformingLazyColumnState()
    val highlighted by viewModel.highlighted.collectAsStateWithLifecycle()
    // The header is item 0, then the tasks, then the events.
    val highlightIndex = highlighted?.let { key ->
        val task = tasks.indexOfFirst { TodayLayout.taskHighlight(it.id) == key }
        val event = events.indexOfFirst { TodayLayout.eventHighlight(it.key) == key }
        when {
            task >= 0 -> 1 + task
            event >= 0 -> 1 + tasks.size + event
            else -> null
        }
    }
    LaunchedEffect(highlighted, highlightIndex) {
        if (highlightIndex != null) listState.animateScrollToItem(highlightIndex)
    }
    DisposableEffect(Unit) { onDispose { viewModel.highlight(null) } }
    val outline = BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = viewModel::refreshToday, enabled = !refreshing) {
                Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.refresh))
            }
        },
    ) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.today)) } }
            if (tasks.isNotEmpty()) {
                items(tasks, key = { "task:" + it.id }) { task ->
                    val border = outline.takeIf { highlighted == TodayLayout.taskHighlight(task.id) }
                    TaskButton(task, border) { viewModel.askToComplete(task.id, task.title) }
                }
            }
            items(events, key = { "event:" + it.key }) { event ->
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    border = outline.takeIf { highlighted == TodayLayout.eventHighlight(event.key) },
                    label = { Text(event.title, maxLines = 2) },
                    secondaryLabel = { Text(event.time) },
                )
            }
            if (snapshot != null && events.isEmpty() && tasks.isEmpty()) {
                item { Text(stringResource(R.string.nothing_today), textAlign = TextAlign.Center) }
            }
            item {
                val fetched = snapshot?.fetchedAt?.let { TodayLayout.clock(Instant.ofEpochMilli(it), zone, use24Hour) }
                val lines = listOfNotNull(error, fetched?.let { stringResource(R.string.updated_at, it) })
                if (lines.isNotEmpty()) {
                    Text(
                        lines.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    CompletionDialogs(completion, viewModel)
}

@Composable
private fun TaskButton(task: TodayRow.Task, border: BorderStroke?, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (task.overdue) ButtonDefaults.filledVariantButtonColors() else ButtonDefaults.buttonColors(),
        border = border,
        icon = { Icon(painterResource(R.drawable.ic_check), contentDescription = null) },
        label = { Text(task.title, maxLines = 2) },
        secondaryLabel = { Text(task.due) },
    )
}

/** "Mark done?" for a tapped task, then a short success or failure confirmation. */
@Composable
private fun CompletionDialogs(completion: Completion?, viewModel: WearViewModel) {
    val curvedStyle = ConfirmationDialogDefaults.curvedTextStyle
    val doneText = stringResource(R.string.marked_done)
    val failedText = stringResource(R.string.could_not_complete)
    AlertDialog(
        visible = completion?.state == Completion.State.ASKING || completion?.state == Completion.State.RUNNING,
        onDismissRequest = viewModel::dismissCompletion,
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = viewModel::confirmCompletion)
        },
        title = { Text(stringResource(R.string.mark_done)) },
        text = { Text(completion?.title.orEmpty(), textAlign = TextAlign.Center, maxLines = 3) },
    )
    SuccessConfirmationDialog(
        visible = completion?.state == Completion.State.DONE,
        onDismissRequest = viewModel::dismissCompletion,
        curvedText = { confirmationDialogCurvedText(doneText, curvedStyle) },
    )
    FailureConfirmationDialog(
        visible = completion?.state == Completion.State.FAILED,
        onDismissRequest = viewModel::dismissCompletion,
        curvedText = { confirmationDialogCurvedText(failedText, curvedStyle) },
    )
}
