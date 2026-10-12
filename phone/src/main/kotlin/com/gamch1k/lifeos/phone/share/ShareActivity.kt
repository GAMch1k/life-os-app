package com.gamch1k.lifeos.phone.share

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.gamch1k.lifeos.core.capture.CaptureKind
import com.gamch1k.lifeos.core.share.ShareInput
import com.gamch1k.lifeos.core.share.ShareMapper
import com.gamch1k.lifeos.core.share.SharePlan
import com.gamch1k.lifeos.core.share.SharedStream
import com.gamch1k.lifeos.phone.LifeOsTheme
import com.gamch1k.lifeos.phone.MainActivity
import com.gamch1k.lifeos.phone.capture.ImagePreparer
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Share → Life OS: text, links (Instagram, TikTok, YouTube included) and
 * images from any app. A small sheet shows what came and takes an optional
 * note; Send puts it in the capture queue, which delivers it to the Inbox
 * (images as JPEGs with `mediaReferences`).
 */
class ShareActivity : ComponentActivity() {
    private var sending by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val plan = ShareMapper.map(readShare(intent))
        setContent {
            LifeOsTheme {
                val signedIn by produceState<Boolean?>(null) { value = graph.tokens.current() != null }
                ShareSheet(
                    plan = plan,
                    signedIn = signedIn,
                    sending = sending,
                    error = error,
                    onSend = { note -> if (plan is SharePlan.Capture) send(plan, note) },
                    onOpenApp = {
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                    onDismiss = { if (!sending) finish() },
                )
            }
        }
    }

    private fun readShare(intent: Intent): ShareInput {
        val streams = buildList {
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
            } else {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
            }
            // Some apps put the images only in the ClipData. A text share's ClipData may hold a
            // preview thumbnail, which must not turn a link into a photo: only for image shares.
            val imageShare = intent.type?.startsWith("image/") == true
            if (isEmpty() && imageShare) intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.forEach(::add) }
        }.distinct().map { uri -> SharedStream(uri.toString(), runCatching { contentResolver.getType(uri) }.getOrNull()) }
        return ShareInput(
            action = intent.action,
            type = intent.type,
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
            subject = intent.getStringExtra(Intent.EXTRA_SUBJECT),
            streams = streams,
        )
    }

    /** Images are converted while this activity still holds the right to read them, then everything is queued. */
    private fun send(plan: SharePlan.Capture, note: String) {
        if (sending) return
        sending = true
        error = null
        lifecycleScope.launch {
            val queue = graph.queue
            val id = queue.newId()
            var queued = false
            try {
                val files = if (plan.kind == CaptureKind.PHOTO) {
                    val dir = queue.dirFor(id)
                    withContext(Dispatchers.Default) {
                        plan.imageUris.mapIndexed { index, uri ->
                            File(dir, "photo-${index + 1}.jpg").also { ImagePreparer.prepare(this@ShareActivity, Uri.parse(uri), it) }
                        }
                    }
                } else {
                    emptyList()
                }
                queue.add(id, plan.kind, text = plan.content(note), files = files)
                queued = true
                val what = if (plan.kind == CaptureKind.PHOTO) (if (files.size == 1) "Photo" else "${files.size} photos") else "Shared text"
                Toast.makeText(this@ShareActivity, "$what queued for the Life OS Inbox", Toast.LENGTH_SHORT).show()
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                sending = false
                error = "Could not read what was shared: ${failure.message ?: failure.javaClass.simpleName}"
            } finally {
                // Not queued (a failure, or the activity went away): no half-written photos stay behind.
                if (!queued && plan.kind == CaptureKind.PHOTO) queue.dirFor(id).deleteRecursively()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareSheet(
    plan: SharePlan,
    signedIn: Boolean?,
    sending: Boolean,
    error: String?,
    onSend: (String) -> Unit,
    onOpenApp: () -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    // While the photos are prepared the sheet stays: swipe, Back and a tap outside do nothing.
    val busy by rememberUpdatedState(sending)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !busy }),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !sending, shouldDismissOnClickOutside = !sending),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Send to the Life OS Inbox", style = MaterialTheme.typography.titleLarge)
            when {
                signedIn == false -> {
                    Text("This phone is not connected to Life OS yet.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onOpenApp) { Text("Open Life OS") }
                }
                plan is SharePlan.Unsupported -> {
                    Text(plan.reason, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                plan is SharePlan.Capture -> {
                    plan.link?.let { Text(it.platform.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    if (plan.text.isNotBlank()) {
                        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                            Text(plan.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (plan.imageUris.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(plan.imageUris) { uri -> Thumbnail(uri) }
                        }
                    }
                    if (plan.droppedImages > 0) {
                        Text("Only the first ${ShareMapper.MAX_IMAGES} images are sent (${plan.droppedImages} left out).", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Note (optional)") },
                        placeholder = { Text("Cook this on Sunday") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !sending,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onDismiss, enabled = !sending) { Text("Cancel") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { onSend(note) }, enabled = !sending && signedIn == true) {
                            if (sending) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("  Preparing…")
                            } else {
                                Text("Send")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(uri: String) {
    val context = LocalContext.current
    val parsed = remember(uri) { Uri.parse(uri) }
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(parsed, Size(320, 320), null) }.getOrNull() }
    }
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.size(96.dp)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Shared image", contentScale = ContentScale.Crop) }
    }
}
