package com.gamch1k.lifeos.core.wearlink

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * What the phone app calls to tell the watch that something changed. Each
 * call sends a payload-free message to every reachable watch with the Life OS
 * app and returns how many got it (0 when there is no watch, which is fine).
 */
object WearLinkPhone {
    /** Call after the service token or the server URL changed: the watch fetches them again. */
    suspend fun notifyCredentialsChanged(context: Context): Int = broadcast(context, WearLink.PATH_CREDENTIALS_CHANGED)

    /** Call when tasks or events changed (a push arrived, the owner completed a task): the watch refreshes Today. */
    suspend fun notifyTodayChanged(context: Context): Int = broadcast(context, WearLink.PATH_TODAY_CHANGED)

    private suspend fun broadcast(context: Context, path: String): Int = try {
        val nodes = Wearable.getCapabilityClient(context)
            .getCapability(WearLink.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
            .await()
            .nodes
        val messages = Wearable.getMessageClient(context)
        nodes.count { node ->
            try {
                messages.sendMessage(node.id, path, ByteArray(0)).await()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // No Wear OS companion on this phone, or Play services can't reach the watch.
        0
    }
}
