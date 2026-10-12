package com.gamch1k.lifeos.wear

import android.app.Application
import android.content.Context
import com.gamch1k.lifeos.wear.capture.CaptureStore
import com.gamch1k.lifeos.wear.capture.CaptureUploader
import com.gamch1k.lifeos.wear.capture.CaptureWorker
import com.gamch1k.lifeos.wear.link.CredentialStore
import com.gamch1k.lifeos.wear.link.CredentialsFetch
import com.gamch1k.lifeos.wear.link.PhoneLink
import com.gamch1k.lifeos.wear.net.DirectTransport
import com.gamch1k.lifeos.wear.net.LifeOsApi
import com.gamch1k.lifeos.wear.net.RelayTransport
import com.gamch1k.lifeos.wear.net.RoutingTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** The watch app's process: builds the [WearGraph]. */
class LifeOsWear : Application() {
    val graph: WearGraph by lazy { WearGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // First start, or the Keystore lost its key: ask the phone for the token right away.
        if (graph.credentials.current == null) graph.scope.launch { graph.refreshCredentials() }
    }

    companion object {
        fun graph(context: Context): WearGraph = (context.applicationContext as LifeOsWear).graph
    }
}

/** The app's single instances, wired by hand (it is small enough). */
class WearGraph(private val app: Context) {
    /** For work that outlives a screen or a listener call. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val credentials = CredentialStore(app)
    val phoneLink = PhoneLink(app, credentials)

    /** Relay first, direct second (docs/wear.md). */
    val api = LifeOsApi(RoutingTransport(RelayTransport(phoneLink), DirectTransport(app) { credentials.current }))

    val captureStore = CaptureStore(File(app.filesDir, "captures"))
    val captureUploader = CaptureUploader(captureStore, api, onAuthRefused = { refreshCredentials() })

    /** Asks the phone for the token; with a new one, waiting recordings go out. */
    suspend fun refreshCredentials(): CredentialsFetch {
        val result = phoneLink.fetchCredentials()
        if (result == CredentialsFetch.RECEIVED) CaptureWorker.runNow(app)
        return result
    }
}
