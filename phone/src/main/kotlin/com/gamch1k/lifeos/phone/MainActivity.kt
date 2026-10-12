package com.gamch1k.lifeos.phone

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
import com.gamch1k.lifeos.phone.ui.AppRoot
import com.gamch1k.lifeos.phone.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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
                        else -> AppRoot(startSignedIn = known)
                    }
                }
            }
        }
        graph.scope.launch { graph.updates.check(UpdateManager.Trigger.START) }
    }

    override fun onResume() {
        super.onResume()
        graph.updates.onResume()
    }
}

@Composable
fun LifeOsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}
