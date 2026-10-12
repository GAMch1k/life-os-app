package com.gamch1k.lifeos.phone.push

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gamch1k.lifeos.core.api.LifeOsClient
import com.gamch1k.lifeos.core.api.LifeOsException
import com.gamch1k.lifeos.phone.graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.unifiedpush.android.connector.UnifiedPush
import java.util.concurrent.TimeUnit

sealed interface PushState {
    data object Unknown : PushState
    data object NotSignedIn : PushState

    /** The server has no VAPID keys: nothing can subscribe yet. */
    data object ServerNotConfigured : PushState

    /** Registered with Play services; waiting for the endpoint or for Life OS to store it. */
    data object Registering : PushState

    /** Life OS knows this phone. [delivering]: notifications go out by web push (else only test pushes). */
    data class Subscribed(val delivering: Boolean) : PushState

    data class Failed(val message: String) : PushState
}

/**
 * Push notifications (P18-05) through UnifiedPush with the embedded FCM
 * distributor: this app is its own distributor, so it never picks another
 * UnifiedPush app (such as ntfy) and needs no Firebase project of ours. The
 * distributor registers with Play services using the server's VAPID key
 * (`GET /v1/push/config`); the endpoint it gets is FCM's Web Push endpoint,
 * which Life OS stores with the connector's keys (`POST /v1/push/subscriptions`)
 * and posts aes128gcm-encrypted payloads to. The connector decrypts them
 * ([LifeOsPushService]).
 */
class PushManager(
    private val app: Application,
    private val client: LifeOsClient,
    private val settings: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow<PushState>(PushState.Unknown)
    val state: StateFlow<PushState> = stateFlow.asStateFlow()

    /** Whether the owner dismissed Home's "Allow notifications" card. */
    val promptDismissed: Flow<Boolean> = settings.data.map { it[PROMPT_DISMISSED] == true }

    init {
        scope.launch {
            val stored = settings.data.first()
            if (stored[SUBSCRIPTION_ID] != null) stateFlow.value = PushState.Subscribed(stored[DELIVERING] == true)
        }
    }

    /** [sync] when the app opens, at most every 12 hours unless something is missing. */
    suspend fun syncIfDue() {
        val last = settings.data.first()[LAST_SYNC]?.toLongOrNull() ?: 0L
        val subscribed = stateFlow.value is PushState.Subscribed
        if (subscribed && System.currentTimeMillis() - last < 12 * 3_600_000L) return
        sync()
    }

    /**
     * Makes sure this phone is subscribed: when the app opens, after a token is saved,
     * and from Settings. Registering again is cheap and keeps the endpoint
     * current; the endpoint comes back through [onNewEndpoint].
     */
    suspend fun sync() {
        val config = try {
            client.pushConfig()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LifeOsException) {
            when {
                error is LifeOsException.NotSignedIn -> stateFlow.value = PushState.NotSignedIn
                stateFlow.value !is PushState.Subscribed -> stateFlow.value = PushState.Failed(error.message ?: "Life OS did not answer.")
            }
            return
        }
        val vapid = config.publicKey
        if (!config.configured || vapid.isNullOrBlank()) {
            stateFlow.value = PushState.ServerNotConfigured
            return
        }
        settings.edit { it[DELIVERING] = config.delivering }
        val stored = settings.data.first()
        try {
            // Our own package: the embedded FCM distributor, never another UnifiedPush app.
            UnifiedPush.saveDistributor(app, app.packageName)
            UnifiedPush.register(app, INSTANCE, null, vapid)
        } catch (error: Exception) {
            stateFlow.value = PushState.Failed("Could not register for push: ${error.message ?: error.javaClass.simpleName}")
            return
        }
        settings.edit {
            it[VAPID] = vapid
            it[LAST_SYNC] = System.currentTimeMillis().toString()
        }
        stateFlow.value = if (stored[SUBSCRIPTION_ID] != null && stored[VAPID] == vapid) PushState.Subscribed(config.delivering) else PushState.Registering
        // Life OS forgets an endpoint the push service refuses; telling it again is idempotent.
        if (stored[ENDPOINT] != null && stored[VAPID] == vapid) PushSubscribeWorker.enqueue(app)
    }

    /** From [LifeOsPushService]: a (new) endpoint and the Web Push keys the connector made for it. */
    suspend fun onNewEndpoint(url: String, p256dh: String?, auth: String?) {
        if (p256dh.isNullOrBlank() || auth.isNullOrBlank()) {
            stateFlow.value = PushState.Failed("The push registration came without Web Push keys.")
            return
        }
        settings.edit {
            it[ENDPOINT] = url
            it[P256DH] = p256dh
            it[AUTH] = auth
        }
        PushSubscribeWorker.enqueue(app)
    }

    /** [PushSubscribeWorker]: tells Life OS the stored endpoint; replaces an older one. */
    suspend fun subscribeStored() {
        val stored = settings.data.first()
        val endpoint = stored[ENDPOINT] ?: return
        val subscription = client.subscribePush(endpoint, stored[P256DH] ?: return, stored[AUTH] ?: return)
        // Signed out (or a newer endpoint arrived) while this was on its way: keep nothing.
        if (settings.data.first()[ENDPOINT] != endpoint) return
        val oldId = stored[SUBSCRIPTION_ID]
        settings.edit {
            it[SUBSCRIPTION_ID] = subscription.id
            it[SUBSCRIBED_ENDPOINT] = endpoint
        }
        if (oldId != null && oldId != subscription.id) runCatching { client.unsubscribePush(oldId) }
        stateFlow.value = PushState.Subscribed(stored[DELIVERING] == true)
    }

    fun onRegistrationFailed(reason: String) {
        stateFlow.value = PushState.Failed(
            when (reason) {
                "ACTION_REQUIRED" -> "Push needs Google Play services, which this phone lacks or must update."
                "NETWORK" -> "Push registration needs a network; Life OS tries again on the next start."
                "VAPID_REQUIRED" -> "The server sent no VAPID key."
                else -> "Push registration failed ($reason)."
            },
        )
    }

    suspend fun onUnregistered() {
        clearStored()
        stateFlow.value = PushState.Unknown
    }

    /** Sign out: Life OS forgets this phone (while the token still works), then the registration goes. */
    suspend fun unregister() {
        settings.data.first()[SUBSCRIPTION_ID]?.let { id ->
            withTimeoutOrNull(10_000) { runCatching { client.unsubscribePush(id) } }
        }
        runCatching { UnifiedPush.unregister(app, INSTANCE) }
        WorkManager.getInstance(app).cancelUniqueWork(PushSubscribeWorker.NAME)
        clearStored()
        stateFlow.value = PushState.NotSignedIn
    }

    suspend fun dismissPrompt() {
        settings.edit { it[PROMPT_DISMISSED] = true }
    }

    private suspend fun clearStored() {
        settings.edit {
            for (key in listOf(ENDPOINT, P256DH, AUTH, SUBSCRIPTION_ID, SUBSCRIBED_ENDPOINT, VAPID)) it.remove(key)
        }
    }

    companion object {
        const val INSTANCE = "lifeos"
        private val ENDPOINT = stringPreferencesKey("push-endpoint")
        private val P256DH = stringPreferencesKey("push-p256dh")
        private val AUTH = stringPreferencesKey("push-auth")
        private val SUBSCRIPTION_ID = stringPreferencesKey("push-subscription-id")
        private val SUBSCRIBED_ENDPOINT = stringPreferencesKey("push-subscribed-endpoint")
        private val VAPID = stringPreferencesKey("push-vapid")
        private val DELIVERING = booleanPreferencesKey("push-delivering")
        private val PROMPT_DISMISSED = booleanPreferencesKey("notifications-prompt-dismissed")
        private val LAST_SYNC = stringPreferencesKey("push-last-sync")
    }
}

/** Tells Life OS the push endpoint, when there is a network; retried with backoff. */
class PushSubscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.graph.push.subscribeStored()
        Result.success()
    } catch (error: LifeOsException) {
        if (error.retryable && runAttemptCount < 10) Result.retry() else Result.failure()
    }

    companion object {
        const val NAME = "push-subscribe"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushSubscribeWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
