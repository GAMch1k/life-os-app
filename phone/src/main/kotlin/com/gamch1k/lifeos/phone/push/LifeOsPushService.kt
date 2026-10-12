package com.gamch1k.lifeos.phone.push

import com.gamch1k.lifeos.core.push.PushPayloads
import com.gamch1k.lifeos.phone.graph
import com.gamch1k.lifeos.phone.today.TodayRefreshWorker
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** UnifiedPush's callbacks: endpoints for Life OS, decrypted pushes for the notification shade. */
class LifeOsPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val graph = applicationContext.graph
        graph.scope.launch { graph.push.onNewEndpoint(endpoint.url, endpoint.pubKeySet?.pubKey, endpoint.pubKeySet?.auth) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // Life OS always encrypts (RFC 8291); anything else is not ours.
        if (!message.decrypted) return
        val payload = PushPayloads.parse(message.content) ?: return
        LifeOsNotifications.show(applicationContext, payload)
        if (payload.touchesToday) {
            // The watch hears at once; the phone's Today (Home, widget) is read again when there is a network.
            applicationContext.graph.todayChanged()
            TodayRefreshWorker.refreshSoon(applicationContext)
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        applicationContext.graph.push.onRegistrationFailed(reason.name)
    }

    override fun onUnregistered(instance: String) {
        val graph = applicationContext.graph
        graph.scope.launch { graph.push.onUnregistered() }
    }
}
