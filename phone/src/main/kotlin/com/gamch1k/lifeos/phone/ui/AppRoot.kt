package com.gamch1k.lifeos.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gamch1k.lifeos.phone.NetworkCheckScreen
import kotlinx.coroutines.flow.MutableStateFlow

/** The app's screens. */
sealed interface Screen {
    /** Server address and service token; [firstRun] has no way back. */
    data class Onboarding(val firstRun: Boolean) : Screen
    data object Home : Screen
    data object TextCapture : Screen
    data object VoiceCapture : Screen
    data class PhotoCapture(val camera: Boolean) : Screen
    data object Queue : Screen
    data object Settings : Screen
    data object NetworkCheck : Screen
}

/** Moves between screens; the back stack is a plain list (Navigation 3). */
class Navigator(private val stack: SnapshotStateList<Screen>) {
    /** A double tap opens the screen once. */
    fun go(screen: Screen) {
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Replaces the whole stack, e.g. Home after sign-in, Onboarding after sign-out. */
    fun reset(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }
}

/**
 * Keeps the back stack across process death. A voice recording cannot
 * survive its process, so the Voice screen is not brought back.
 */
private val StackSaver = listSaver<SnapshotStateList<Screen>, String>(
    save = { stack -> stack.mapNotNull(::encode) },
    restore = { saved -> saved.mapNotNull(::decode).ifEmpty { null }?.toMutableStateList() },
)

private fun encode(screen: Screen): String? = when (screen) {
    is Screen.Onboarding -> "onboarding:${screen.firstRun}"
    Screen.Home -> "home"
    Screen.TextCapture -> "text"
    Screen.VoiceCapture -> null
    is Screen.PhotoCapture -> "photo:${screen.camera}"
    Screen.Queue -> "queue"
    Screen.Settings -> "settings"
    Screen.NetworkCheck -> "network-check"
}

private fun decode(saved: String): Screen? = when {
    saved.startsWith("onboarding:") -> Screen.Onboarding(saved.endsWith("true"))
    saved == "home" -> Screen.Home
    saved == "text" -> Screen.TextCapture
    saved.startsWith("photo:") -> Screen.PhotoCapture(saved.endsWith("true"))
    saved == "queue" -> Screen.Queue
    saved == "settings" -> Screen.Settings
    saved == "network-check" -> Screen.NetworkCheck
    else -> null
}

@Composable
fun AppRoot(startSignedIn: Boolean, launches: MutableStateFlow<Screen?> = MutableStateFlow(null)) {
    val stack = rememberSaveable(saver = StackSaver) {
        mutableStateListOf<Screen>(if (startSignedIn) Screen.Home else Screen.Onboarding(firstRun = true))
    }
    val navigator = remember { Navigator(stack) }
    // A widget's capture button: open its screen on top of Home (once signed in).
    val launch by launches.collectAsState()
    LaunchedEffect(launch) {
        val screen = launch ?: return@LaunchedEffect
        launches.value = null
        if (stack.firstOrNull() !is Screen.Onboarding) {
            navigator.reset(Screen.Home)
            navigator.go(screen)
        }
    }
    NavDisplay(
        backStack = stack,
        onBack = navigator::back,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
        entryProvider = entryProvider {
            entry<Screen.Onboarding> { OnboardingScreen(navigator, firstRun = it.firstRun) }
            entry<Screen.Home> { HomeScreen(navigator) }
            entry<Screen.TextCapture> { TextCaptureScreen(navigator) }
            entry<Screen.VoiceCapture> { VoiceCaptureScreen(navigator) }
            entry<Screen.PhotoCapture> { PhotoCaptureScreen(navigator, camera = it.camera) }
            entry<Screen.Queue> { QueueScreen(navigator) }
            entry<Screen.Settings> { SettingsScreen(navigator) }
            entry<Screen.NetworkCheck> { NetworkCheckScreen(onBack = navigator::back) }
        },
    )
}
