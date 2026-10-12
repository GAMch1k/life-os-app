package com.gamch1k.lifeos.phone.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gamch1k.lifeos.core.capture.CaptureKind
import com.gamch1k.lifeos.phone.R
import com.gamch1k.lifeos.phone.capture.ImagePreparer
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class PhotoState(
    val photos: List<Uri> = emptyList(),
    val caption: String = "",
    val preparing: Boolean = false,
    val error: String? = null,
    /** The first picker was opened (so an empty list means it was cancelled). */
    val asked: Boolean = false,
)

/** P18-04: photos (camera or picker, up to five) into the Inbox as JPEGs. */
class PhotoViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = application.graph
    private val cameraDir = File(application.cacheDir, "camera")
    private val state = MutableStateFlow(PhotoState())
    val uiState = state.asStateFlow()

    /** A file the camera app writes into, shared through our FileProvider. */
    fun cameraTarget(): Uri {
        cameraDir.mkdirs()
        val file = File(cameraDir, "${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.files", file)
    }

    fun asked() = state.update { it.copy(asked = true) }

    fun add(uris: List<Uri>) = state.update { current ->
        current.copy(photos = (current.photos + uris).distinct().take(MAX_PHOTOS), error = null)
    }

    fun remove(uri: Uri) = state.update { it.copy(photos = it.photos - uri) }

    fun caption(text: String) = state.update { it.copy(caption = text) }

    fun send(onQueued: () -> Unit) {
        val current = state.value
        if (current.photos.isEmpty() || current.preparing) return
        state.update { it.copy(preparing = true, error = null) }
        viewModelScope.launch {
            val id = graph.queue.newId()
            val dir = graph.queue.dirFor(id)
            try {
                val files = withContext(Dispatchers.Default) {
                    current.photos.mapIndexed { index, uri ->
                        File(dir, "photo-${index + 1}.jpg").also { ImagePreparer.prepare(getApplication(), uri, it) }
                    }
                }
                graph.queue.add(id, CaptureKind.PHOTO, text = current.caption.trim(), files = files)
                cameraDir.deleteRecursively()
                val what = if (files.size == 1) "Photo" else "${files.size} photos"
                graph.notices.send("$what queued for the Inbox")
                onQueued()
            } catch (cancelled: CancellationException) {
                dir.deleteRecursively()
                throw cancelled
            } catch (error: Exception) {
                dir.deleteRecursively()
                state.update { it.copy(preparing = false, error = "A photo could not be read: ${error.message ?: error.javaClass.simpleName}") }
            }
        }
    }

    companion object {
        const val MAX_PHOTOS = 5
    }
}

@Composable
fun PhotoCaptureScreen(navigator: Navigator, camera: Boolean, viewModel: PhotoViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingCamera by rememberSaveable { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingCamera
        pendingCamera = null
        if (saved && uri != null) viewModel.add(listOf(uri)) else if (state.photos.isEmpty()) navigator.back()
    }
    val remaining = PhotoViewModel.MAX_PHOTOS - state.photos.size
    // A first picker closed without a photo means "never mind".
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PhotoViewModel.MAX_PHOTOS)) { uris ->
        if (uris.isEmpty() && state.photos.isEmpty()) navigator.back() else viewModel.add(uris)
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null && state.photos.isEmpty()) navigator.back() else uri?.let { viewModel.add(listOf(it)) }
    }
    val images = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    fun openCamera() {
        val target = viewModel.cameraTarget()
        pendingCamera = target
        takePicture.launch(target)
    }

    fun openGallery() = if (remaining > 1) pickMany.launch(images) else pickOne.launch(images)

    LaunchedEffect(Unit) {
        if (!state.asked) {
            viewModel.asked()
            if (camera) openCamera() else openGallery()
        }
    }

    Scaffold(topBar = { BackTopBar("Photo", navigator::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.photos.isEmpty()) {
                Text("No photo yet. Take one or choose from the gallery.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.photos, key = { it.toString() }) { uri -> Thumbnail(uri, onRemove = { viewModel.remove(uri) }) }
                }
                Text("${state.photos.size} of ${PhotoViewModel.MAX_PHOTOS}. They are sent as JPEGs, at most $MAX_EDGE_TEXT.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = ::openCamera, enabled = remaining > 0 && !state.preparing) {
                    Icon(painterResource(R.drawable.ic_photo_camera), contentDescription = null)
                    Text("  Camera")
                }
                OutlinedButton(onClick = ::openGallery, enabled = remaining > 0 && !state.preparing) {
                    Icon(painterResource(R.drawable.ic_image), contentDescription = null)
                    Text("  Gallery")
                }
            }
            OutlinedTextField(
                value = state.caption,
                onValueChange = viewModel::caption,
                label = { Text("Note (optional)") },
                placeholder = { Text("Receipt for lunch with Ira") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { viewModel.send(onQueued = navigator::back) },
                enabled = state.photos.isNotEmpty() && !state.preparing,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                if (state.preparing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Preparing…")
                } else {
                    Text("Send to Inbox")
                }
            }
        }
    }
}

private const val MAX_EDGE_TEXT = "${ImagePreparer.MAX_EDGE} px on the long side"

@Composable
private fun Thumbnail(uri: Uri, onRemove: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, Size(480, 480), null) }.getOrNull() }
    }
    Box(Modifier.size(120.dp)) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxSize()) {
            bitmap?.let { Image(it.asImageBitmap(), contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        FilledTonalIconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp))
        }
    }
}
