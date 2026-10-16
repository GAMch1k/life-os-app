package com.gamch1k.lifeos.phone.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.gamch1k.lifeos.phone.LifeOsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Life OS Capture" in the app list (P18-12): straight into a voice
 * recording, made for the side key's double press (README). It shows over
 * the lock screen and turns the screen on. Only the recording is shown there:
 * Send, Cancel and Back close it, back to where the phone was (the lock
 * screen included); it opens nothing else, so the rest of the app stays
 * behind the unlock. Its own task, out of Recents, so closing it never
 * uncovers Home.
 */
class VoiceCaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Home's snackbar is not there to say it, and the note is queued after this screen closed.
        val app = applicationContext
        val toast: suspend (String) -> Unit = { message ->
            withContext(Dispatchers.Main) { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
        }
        setContent {
            LifeOsTheme {
                VoiceCaptureScreen(onClose = ::finish, announce = toast)
            }
        }
    }
}
