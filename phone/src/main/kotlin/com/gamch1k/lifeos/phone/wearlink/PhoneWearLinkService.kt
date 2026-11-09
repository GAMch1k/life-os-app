package com.gamch1k.lifeos.phone.wearlink

import com.gamch1k.lifeos.core.secrets.ServiceTokenStore
import com.gamch1k.lifeos.core.wearlink.PhoneWearLinkHandler
import com.gamch1k.lifeos.core.wearlink.WearCredentials
import com.gamch1k.lifeos.core.wearlink.WearLink
import com.gamch1k.lifeos.core.wearlink.WearLinkHost
import com.gamch1k.lifeos.core.wearlink.WearRelay
import com.gamch1k.lifeos.core.wearlink.UpdateProtocolException
import com.gamch1k.lifeos.core.wearlink.WatchUpdateCodec
import com.gamch1k.lifeos.core.wearlink.WearTokenSource
import com.gamch1k.lifeos.phone.graph
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The phone's end of the link to the watch app (docs/wear.md). Play services
 * starts it when the watch sends a request, a message or opens a channel on
 * a `/lifeos/` path; the relay's work is [PhoneWearLinkHandler]'s, the watch's
 * update reports go to the app's WatchUpdateManager.
 *
 * The token source and the relay come from the app's `Application`, which
 * implements [WearLinkHost] since 0.3.0. The fallback without it: the token
 * store's server and token through [ServiceTokenStore], and no relay (the
 * watch then calls Life OS directly).
 */
class PhoneWearLinkService : WearableListenerService() {
    private val handler by lazy {
        val host = application as? WearLinkHost
        PhoneWearLinkHandler(
            tokenSource = host?.wearTokenSource ?: fallbackTokenSource(),
            relay = host?.wearRelay ?: WearRelay.UNAVAILABLE,
        )
    }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != WearLink.PATH_CREDENTIALS && path != WearLink.PATH_RELAY) return null
        val reply = TaskCompletionSource<ByteArray>()
        scope.launch {
            try {
                reply.setResult(
                    if (path == WearLink.PATH_CREDENTIALS) handler.handleCredentials(request) else handler.handleRelayMessage(request),
                )
            } catch (e: Exception) {
                reply.setException(e)
            }
        }
        return reply.task
    }

    /** The watch app reports its version and its last install (P19-06). */
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearLink.PATH_WATCH_REPORT) return
        val info = try {
            WatchUpdateCodec.decodeInfo(event.data)
        } catch (_: UpdateProtocolException) {
            return
        }
        val graph = applicationContext.graph
        graph.scope.launch { graph.watchUpdates.onWatchReport(info) }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WearLink.PATH_RELAY_CHANNEL) return
        val channels = Wearable.getChannelClient(applicationContext)
        scope.launch {
            try {
                val input = channels.getInputStream(channel).await()
                val output = channels.getOutputStream(channel).await()
                handler.handleRelayChannel(input, output)
                // Ends our side after the reply; the watch closes the channel once it has read it.
                output.close()
            } catch (_: Exception) {
                // The watch sees the channel close and treats the phone as unreachable.
                channels.close(channel)
            }
        }
    }

    // Only without a WearLinkHost: the server and token entered at onboarding or under Settings.
    private fun fallbackTokenSource() = WearTokenSource {
        ServiceTokenStore(applicationContext).loadConnection()?.let { connection ->
            WearCredentials(baseUrl = connection.baseUrl, clientId = connection.token.clientId, clientSecret = connection.token.clientSecret)
        }
    }

    private companion object {
        // Not tied to the service's lifetime: Play services may unbind it before a reply is ready.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
