package com.gamch1k.lifeos.phone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.gamch1k.lifeos.phone.push.LifeOsNotifications
import com.gamch1k.lifeos.phone.today.CaptureLinks
import com.gamch1k.lifeos.phone.today.QuickCaptureWidget
import com.gamch1k.lifeos.phone.ui.AppRoot
import com.gamch1k.lifeos.phone.ui.Screen
import com.gamch1k.lifeos.phone.update.UpdateManager
import com.gamch1k.lifeos.phone.watch.WatchUpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** A capture screen asked for by a widget button, for AppRoot to open. */
    private val launches = MutableStateFlow<Screen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            LifeOsTheme {
                Surface(Modifier.fillMaxSize()) {
                    var signedIn by rememberSaveable { mutableStateOf<Boolean?>(null) }
                    LaunchedEffect(Unit) {
                        if (signedIn != null) return@LaunchedEffect
                        signedIn = withContext(Dispatchers.IO) {
                            graph.tokens.migrateFrom(this@MainActivity)
                            graph.tokens.current() != null
                        }
                    }
                    when (val known = signedIn) {
                        null -> Box(Modifier.fillMaxSize())
                        else -> AppRoot(startSignedIn = known, launches = launches)
                    }
                }
            }
        }
        graph.scope.launch { graph.updates.check(UpdateManager.Trigger.START) }
        graph.scope.launch { graph.watchUpdates.check(WatchUpdateManager.Trigger.START) }
        // Push registration is refreshed when the app is opened (not on every background wake).
        graph.scope.launch { if (graph.tokens.current() != null) graph.push.syncIfDue() }
        // A Capture widget that 0.4.2 drew Today into (BUG-41) heals when the app opens.
        graph.scope.launch { QuickCaptureWidget.refreshAll(applicationContext) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        graph.updates.onResume()
    }

    /** A notification's link opens in the Dashboard; a widget button opens its capture screen. */
    private fun handle(intent: Intent?) {
        intent ?: return
        // Reopened from Recents: the old intent comes again; it was handled already.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action == LifeOsNotifications.ACTION_OPEN_LINK) {
            intent.getStringExtra(LifeOsNotifications.EXTRA_LINK)?.let { Dashboard.open(this, lifecycleScope, it) }
            return
        }
        launches.value = when (intent.getStringExtra(CaptureLinks.EXTRA_CAPTURE)) {
            "voice" -> Screen.VoiceCapture
            "photo" -> Screen.PhotoCapture(camera = true)
            "text" -> Screen.TextCapture
            else -> return
        }
    }
}

@Composable
fun LifeOsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}
