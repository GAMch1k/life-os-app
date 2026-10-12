package com.gamch1k.lifeos.wear.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The watch app: Home (record, recent captures, Today, Connection), the
 * recording screen, Today, and Connection. Tiles, the complication, the
 * Capture launcher entry and the recording notice open it with a [Launch].
 */
class MainActivity : ComponentActivity() {
    private val viewModel: WearViewModel by viewModels()
    private val launches = MutableStateFlow<Pair<Launch, Long>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) offer(intent)
        setContent {
            val launch by launches.collectAsStateWithLifecycle()
            WearApp(
                viewModel = viewModel,
                launch = launch,
                onLaunchHandled = { launches.value = null },
                onFinish = ::finish,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offer(intent)
    }

    private fun offer(intent: Intent?) {
        Launch.from(intent)?.let { launches.value = it to System.nanoTime() }
    }

    companion object {
        /** Opens the app straight into recording (tiles, complication, notice). */
        fun recordIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .putExtra(Launch.EXTRA_ACTION, Launch.RECORD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

private object Routes {
    const val HOME = "home"
    const val RECORD = "record"
    const val TODAY = "today"
    const val CONNECTION = "connection"
}

@Composable
private fun WearApp(viewModel: WearViewModel, launch: Pair<Launch, Long>?, onLaunchHandled: () -> Unit, onFinish: () -> Unit) {
    MaterialTheme {
        AppScaffold {
            val navController = rememberSwipeDismissableNavController()

            LaunchedEffect(launch) {
                val request = launch?.first ?: return@LaunchedEffect
                when (request) {
                    Launch.Record -> {
                        viewModel.finishAfterRecording = true
                        navController.navigate(Routes.RECORD) { launchSingleTop = true }
                    }
                    is Launch.Today -> {
                        viewModel.highlight(request.highlight)
                        navController.navigate(Routes.TODAY) { launchSingleTop = true }
                    }
                    is Launch.CompleteTask -> {
                        viewModel.askToComplete(request.taskId, request.title)
                        navController.navigate(Routes.TODAY) { launchSingleTop = true }
                    }
                }
                onLaunchHandled()
            }

            SwipeDismissableNavHost(navController = navController, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        viewModel = viewModel,
                        onRecord = {
                            viewModel.finishAfterRecording = false
                            navController.navigate(Routes.RECORD) { launchSingleTop = true }
                        },
                        onToday = { navController.navigate(Routes.TODAY) },
                        onConnection = { navController.navigate(Routes.CONNECTION) },
                    )
                }
                composable(Routes.RECORD) {
                    RecordScreen(
                        viewModel = viewModel,
                        onDone = {
                            if (viewModel.finishAfterRecording) {
                                viewModel.finishAfterRecording = false
                                onFinish()
                            } else {
                                navController.popBackStack()
                            }
                        },
                    )
                }
                composable(Routes.TODAY) { TodayScreen(viewModel) }
                composable(Routes.CONNECTION) { ConnectionScreen(viewModel) }
            }
        }
    }
}
