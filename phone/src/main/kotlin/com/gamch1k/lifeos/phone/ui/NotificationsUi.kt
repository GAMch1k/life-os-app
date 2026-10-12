package com.gamch1k.lifeos.phone.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.gamch1k.lifeos.phone.graph
import com.gamch1k.lifeos.phone.push.LifeOsNotifications
import com.gamch1k.lifeos.phone.push.PushState
import kotlinx.coroutines.launch

/** Whether Life OS may post notifications, re-checked whenever the screen comes back. */
@Composable
private fun rememberCanPost(): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var canPost by remember { mutableStateOf(LifeOsNotifications.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canPost = LifeOsNotifications.canPost(context) }
    return canPost to { canPost = LifeOsNotifications.canPost(context) }
}

/** Home's one-time card: notifications need the owner's permission (Android 13+). */
@Composable
fun NotificationPrompt() {
    val context = LocalContext.current
    val graph = context.graph
    val (canPost, recheck) = rememberCanPost()
    val dismissed by graph.push.promptDismissed.collectAsState(initial = true)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }
    if (canPost || dismissed) return
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Allow notifications so reminders reach this phone and your watch.", style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                TextButton(onClick = { graph.scope.launch { graph.push.dismissPrompt() } }) { Text("Not now") }
            }
        }
    }
}

/** Settings › Notifications: permission, the push subscription, the channels. */
@Composable
fun NotificationsSection() {
    val context = LocalContext.current
    val graph = context.graph
    val (canPost, recheck) = rememberCanPost()
    val push by graph.push.state.collectAsState()
    // After two refusals Android stops asking; "Notification settings" still works then.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck() }
    SectionCard("Notifications") {
        Text(
            if (canPost) "Allowed on this phone." else "Not allowed on this phone: reminders cannot be shown.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (canPost) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        Text(pushText(push), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!canPost) OutlinedButton(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
            OutlinedButton(onClick = { graph.scope.launch { graph.push.sync() } }) { Text("Register again") }
            TextButton(onClick = { context.startActivity(appNotificationSettings(context.packageName)) }) { Text("Notification settings") }
        }
        Text(
            "Pushes come through Google Play services (Firebase Cloud Messaging), so they also arrive where ntfy is blocked. " +
                "Urgent, Reminders and Information are separate channels; the watch mirrors them.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun pushText(state: PushState): String = when (state) {
    PushState.Unknown -> "Push: not registered yet."
    PushState.NotSignedIn -> "Push: connect Life OS first."
    PushState.ServerNotConfigured -> "Push: the server has no web push keys yet (VAPID), so the phone cannot subscribe."
    PushState.Registering -> "Push: registering with Play services…"
    is PushState.Subscribed ->
        if (state.delivering) "Push: subscribed; Life OS delivers notifications here." else "Push: subscribed, but the server does not deliver by web push yet (test pushes only)."
    is PushState.Failed -> "Push: ${state.message}"
}

private fun appNotificationSettings(packageName: String) = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
