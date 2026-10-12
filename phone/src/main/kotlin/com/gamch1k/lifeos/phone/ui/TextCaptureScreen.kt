package com.gamch1k.lifeos.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.gamch1k.lifeos.core.capture.CaptureKind
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.launch

/** P18-04: type something into the Inbox. */
@Composable
fun TextCaptureScreen(navigator: Navigator) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun send() {
        val content = text.trim()
        if (content.isEmpty()) return
        val graph = context.graph
        graph.scope.launch {
            graph.queue.add(graph.queue.newId(), CaptureKind.TEXT, text = content)
            graph.notices.send("Text queued for the Inbox")
        }
        navigator.back()
    }

    Scaffold(topBar = { BackTopBar("Text", navigator::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Anything: a task, an appointment, a purchase. Life OS sorts it out.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Dentist Thursday 9:30") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(focus),
            )
            Button(onClick = ::send, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Text("  Send to Inbox")
            }
        }
    }
}
