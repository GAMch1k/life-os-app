package com.gamch1k.lifeos.wear

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.gamch1k.lifeos.wear.capture.CaptureStore
import com.gamch1k.lifeos.wear.capture.CaptureUploader
import com.gamch1k.lifeos.wear.capture.CaptureWorker
import com.gamch1k.lifeos.wear.complication.NextEventComplicationService
import com.gamch1k.lifeos.wear.link.CredentialStore
import com.gamch1k.lifeos.wear.link.CredentialsFetch
import com.gamch1k.lifeos.wear.link.PhoneLink
import com.gamch1k.lifeos.wear.net.DirectTransport
import com.gamch1k.lifeos.wear.net.LifeOsApi
import com.gamch1k.lifeos.wear.net.RelayTransport
import com.gamch1k.lifeos.wear.net.RoutingTransport
import com.gamch1k.lifeos.wear.tile.TodayTileService
import com.gamch1k.lifeos.wear.today.TodayRefreshWorker
import com.gamch1k.lifeos.wear.today.TodayRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** The watch app's process: builds the [WearGraph] and plans the Today refresh. */
class LifeOsWear : Application() {
    val graph: WearGraph by lazy { WearGraph(this) }

    override fun onCreate() {
        super.onCreate()
        TodayRefreshWorker.schedulePeriodic(this)
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

    val today = TodayRepository(
        cacheFile = File(app.filesDir, "today.json"),
        api = api,
        onChanged = { updateSurfaces() },
        onAuthRefused = { refreshCredentials() },
    )

    /** Asks the phone for the token; with a new one, waiting recordings go out. */
    suspend fun refreshCredentials(): CredentialsFetch {
        val result = phoneLink.fetchCredentials()
        if (result == CredentialsFetch.RECEIVED) {
            CaptureWorker.runNow(app)
            if (today.snapshot.value == null) TodayRefreshWorker.refreshNow(app)
        }
        return result
    }

    /** Redraws the Today tile and the next-event complication from the cache. */
    fun updateSurfaces() {
        try {
            TileService.getUpdater(app).requestUpdate(TodayTileService::class.java)
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, NextEventComplicationService::class.java))
                .requestUpdateAll()
        } catch (_: Exception) {
            // No tile or complication in use: nothing to redraw.
        }
    }
}
