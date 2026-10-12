package com.gamch1k.lifeos.core.wearlink

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * The other side of the phone–watch link: the nodes advertising [capability]
 * (res/values/wear.xml, kept through resource shrinking by res/raw/keep.xml),
 * else every connected node. The fallback matters: 0.4.0 shipped with the
 * capability shrunk out of both APKs, so a 0.4.0 app advertises nothing, and
 * a newer app on the other side still reaches it by its connected node (our
 * listener answers only our paths, so another app's node just doesn't reply).
 * A nearby (Bluetooth) node comes first.
 */
object WearNodes {
    suspend fun find(context: Context, capability: String): List<Node> {
        val advertised = Wearable.getCapabilityClient(context)
            .getCapability(capability, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
        val nodes = advertised.ifEmpty { Wearable.getNodeClient(context).connectedNodes.await().toSet() }
        return nodes.sortedByDescending { it.isNearby }
    }
}
