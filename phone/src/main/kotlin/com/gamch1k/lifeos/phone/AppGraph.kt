package com.gamch1k.lifeos.phone

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.gamch1k.lifeos.core.api.CronetTransport
import com.gamch1k.lifeos.core.api.HttpRequest
import com.gamch1k.lifeos.core.api.HttpResponse
import com.gamch1k.lifeos.core.api.HttpTransport
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsJson
import com.gamch1k.lifeos.core.api.ResponseSink
import com.gamch1k.lifeos.core.capture.CaptureSender
import com.gamch1k.lifeos.core.capture.CaptureStatusChecker
import com.gamch1k.lifeos.core.capture.CaptureStore
import com.gamch1k.lifeos.core.capture.asCaptureApi
import com.gamch1k.lifeos.core.secrets.KeystoreCipher
import com.gamch1k.lifeos.core.secrets.TokenStore
import com.gamch1k.lifeos.core.today.TodaySnapshot
import com.gamch1k.lifeos.phone.capture.CaptureQueue
import com.gamch1k.lifeos.phone.update.UpdateManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The app's singletons, built once in [LifeOsApp]. */
class AppGraph(private val app: Application) {
    /**
     * Work that must outlive a screen (completing a Task, starting an update).
     * A failure there is logged, never a crash; messages never hold the token.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error ->
            Log.w("LifeOS", "background task failed: ${error.javaClass.simpleName}: ${error.message}")
        },
    )

    private val settings: DataStore<Preferences> = store("settings")
    private val queueData: DataStore<Preferences> = store("captures")
    private val cacheData: DataStore<Preferences> = store("cache")

    val tokens = TokenStore(settings, KeystoreCipher()).apply { install() }

    val client = LifeOsClient(
        transport = LazyTransport { CronetTransport.create(app) },
        connection = { tokens.current() },
        userAgent = "LifeOS-Android/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    )

    val captures = CaptureStore(queueData)
    val sender = CaptureSender(captures, client.asCaptureApi())
    val statusChecker = CaptureStatusChecker(captures, client.asCaptureApi())
    val queue = CaptureQueue(app, captures, sender)
    val homeCache = HomeCache(cacheData)

    private val foregroundState = MutableStateFlow(false)

    /** True while one of our activities is started (visible). */
    val foreground: StateFlow<Boolean> = foregroundState.asStateFlow()

    val updates = UpdateManager(app, client, settings, scope, foreground)

    /** Short messages for Home's snackbar from screens that just closed ("Voice note queued"). */
    val notices = Channel<String>(Channel.BUFFERED)

    init {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var started = 0
                override fun onActivityStarted(activity: Activity) {
                    started++
                    foregroundState.value = true
                }
                override fun onActivityStopped(activity: Activity) {
                    started = (started - 1).coerceAtLeast(0)
                    foregroundState.value = started > 0
                }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /** Sign out: the token, the cached Today, the queue with its files, downloaded updates. */
    suspend fun signOut() {
        tokens.clear()
        homeCache.clear()
        queue.clear()
        updates.forget()
    }

    private fun store(name: String) = PreferenceDataStoreFactory.create(produceFile = { app.preferencesDataStoreFile(name) })
}

/** Builds the Cronet engine on first use, off the main thread. */
private class LazyTransport(create: () -> HttpTransport) : HttpTransport {
    private val delegate by lazy(create)

    override suspend fun execute(request: HttpRequest, sink: ResponseSink?, onProgress: ((Long) -> Unit)?): HttpResponse =
        withContext(Dispatchers.IO) { delegate }.execute(request, sink, onProgress)
}

/** Home's last Today, so it opens instantly and offline. */
class HomeCache(private val store: DataStore<Preferences>) {
    suspend fun load(): TodaySnapshot? = store.data.first()[KEY]?.let {
        runCatching { LifeOsJson.decodeFromString(TodaySnapshot.serializer(), it) }.getOrNull()
    }

    suspend fun save(snapshot: TodaySnapshot) {
        store.edit { it[KEY] = LifeOsJson.encodeToString(TodaySnapshot.serializer(), snapshot) }
    }

    suspend fun clear() {
        store.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("today")
    }
}
