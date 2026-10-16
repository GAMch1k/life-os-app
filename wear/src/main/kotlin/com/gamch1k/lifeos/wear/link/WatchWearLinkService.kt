package com.gamch1k.lifeos.wear.link

import com.gamch1k.lifeos.core.wearlink.WearLink
import com.gamch1k.lifeos.wear.LifeOsWear
import com.gamch1k.lifeos.wear.capture.CaptureWorker
import com.gamch1k.lifeos.wear.today.TodayRefreshWorker
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityInfo
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
 * The watch's end of the phone's pings (docs/wear.md): a new token, changed
 * tasks or events, and the phone app coming into reach (then waiting
 * recordings go out at once). Since 0.4.0 also the phone's update requests:
 * the version question, an APK offer, and the APK's channel (P19-06); since
 * 0.4.3 the status of the watch's own download.
 */
class WatchWearLinkService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val graph = LifeOsWear.graph(this)
        when (event.path) {
            WearLink.PATH_CREDENTIALS_CHANGED -> graph.scope.launch { graph.refreshCredentials() }
            WearLink.PATH_TODAY_CHANGED -> TodayRefreshWorker.refreshNow(this)
        }
    }

    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        val updater = LifeOsWear.graph(this).selfUpdater
        return when (path) {
            WearLink.PATH_WATCH_INFO -> Tasks.forResult(updater.handleInfoRequest(request))
            WearLink.PATH_APK_STATUS -> Tasks.forResult(updater.handleStatusRequest(request))
            WearLink.PATH_APK_OFFER -> {
                val reply = TaskCompletionSource<ByteArray>()
                scope.launch {
                    try {
                        reply.setResult(updater.handleOffer(request))
                    } catch (e: Exception) {
                        reply.setException(e)
                    }
                }
                reply.task
            }
            else -> null
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WearLink.PATH_APK_TRANSFER) return
        val channels = Wearable.getChannelClient(applicationContext)
        val graph = LifeOsWear.graph(this)
        // At once, while Play services has this listener bound (0.4.3): the transfer must outlive
        // the binding and the screen going off (a wake lock, and the foreground service where allowed).
        graph.updateKeepAlive.acquire()
        scope.launch {
            try {
                val input = channels.getInputStream(channel).await()
                val output = channels.getOutputStream(channel).await()
                graph.selfUpdater.handleTransfer(input, output)
                output.close()
            } catch (_: Exception) {
                // The phone sees the channel close and counts the transfer as failed (or resumes it).
                channels.close(channel)
            } finally {
                graph.updateKeepAlive.release()
            }
        }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name != WearLink.CAPABILITY_PHONE) return
        val graph = LifeOsWear.graph(this)
        graph.phoneLink.forgetPhone()
        if (info.nodes.isEmpty()) return
        CaptureWorker.runNow(this)
        if (graph.credentials.current == null) graph.scope.launch { graph.refreshCredentials() }
        // An update result the phone hasn't heard yet.
        graph.scope.launch { graph.selfUpdater.report() }
    }

    private companion object {
        // Not tied to the service's lifetime: Play services may unbind it before a transfer is done.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
