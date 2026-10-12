package com.gamch1k.lifeos.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.gamch1k.lifeos.phone.NetworkCheckScreen

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
    fun go(screen: Screen) {
        stack.add(screen)
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

@Composable
fun AppRoot(startSignedIn: Boolean) {
    val stack = remember { mutableStateListOf<Screen>(if (startSignedIn) Screen.Home else Screen.Onboarding(firstRun = true)) }
    val navigator = remember { Navigator(stack) }
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
