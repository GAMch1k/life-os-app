package com.gamch1k.lifeos.wear.link

import com.gamch1k.lifeos.core.wearlink.WearLink
import com.gamch1k.lifeos.wear.LifeOsWear
import com.gamch1k.lifeos.wear.capture.CaptureWorker
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch

/**
 * The watch's end of the phone's pings (docs/wear.md): a new token, and the
 * phone app coming into reach (then waiting recordings go out at once).
 */
class WatchWearLinkService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val graph = LifeOsWear.graph(this)
        when (event.path) {
            WearLink.PATH_CREDENTIALS_CHANGED -> graph.scope.launch { graph.refreshCredentials() }
        }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name != WearLink.CAPABILITY_PHONE) return
        val graph = LifeOsWear.graph(this)
        graph.phoneLink.forgetPhone()
        if (info.nodes.isEmpty()) return
        CaptureWorker.runNow(this)
        if (graph.credentials.current == null) graph.scope.launch { graph.refreshCredentials() }
    }
}
