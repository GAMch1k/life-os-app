package com.gamch1k.lifeos.phone.watch

import android.content.Context
import com.gamch1k.lifeos.core.wearlink.ApkOffer
import com.gamch1k.lifeos.core.wearlink.ApkOfferReply
import com.gamch1k.lifeos.core.wearlink.ApkTransferCodec
import com.gamch1k.lifeos.core.wearlink.ApkTransferHeader
import com.gamch1k.lifeos.core.wearlink.ApkTransferResult
import com.gamch1k.lifeos.core.wearlink.WatchInfo
import com.gamch1k.lifeos.core.wearlink.WatchInfoRequest
import com.gamch1k.lifeos.core.wearlink.WatchUpdateCodec
import com.gamch1k.lifeos.core.wearlink.WearLink
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The phone's side of the watch-update protocol over the Data Layer
 * (docs/wear.md, "Install and updates"): ask the watch app its version,
 * offer it an APK, stream the APK through a channel.
 */
class WatchLink(context: Context) {
    private val appContext = context.applicationContext
    private val capabilities by lazy { Wearable.getCapabilityClient(appContext) }
    private val messages by lazy { Wearable.getMessageClient(appContext) }
    private val channels by lazy { Wearable.getChannelClient(appContext) }

    /** The watch with the Life OS app (capability [WearLink.CAPABILITY_WATCH]), a nearby one first; null when none is reachable. */
    suspend fun watchNode(): String? = try {
        val nodes = capabilities.getCapability(WearLink.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes
        (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // No Wear OS companion on this phone, or Play services can't reach the watch.
        null
    }

    /**
     * The watch app's [WatchInfo]. Throws when it doesn't answer, or answers
     * nothing readable: a watch app older than 0.4.0 has no handler for the
     * path, so Play services fails the request.
     */
    suspend fun info(node: String, timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS): WatchInfo {
        val request = WatchInfoRequest(id = UUID.randomUUID().toString())
        val reply = request(node, WearLink.PATH_WATCH_INFO, WatchUpdateCodec.encodeInfoRequest(request), timeoutMillis)
        val info = WatchUpdateCodec.decodeInfo(reply)
        if (info.id != request.id) throw IOException("the watch answered another request")
        return info
    }

    /** Asks the watch app until it answers with at least [minVersionCode], for up to [timeoutMillis]. */
    suspend fun awaitInfo(minVersionCode: Long, timeoutMillis: Long): WatchInfo? = withTimeoutOrNull(timeoutMillis) {
        while (isActive) {
            val node = watchNode()
            if (node != null) {
                val info = try {
                    info(node)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (info != null && info.versionCode >= minVersionCode) return@withTimeoutOrNull info
            }
            delay(POLL_MILLIS)
        }
        null
    }

    suspend fun offer(node: String, offer: ApkOffer): ApkOfferReply {
        val reply = WatchUpdateCodec.decodeOfferReply(request(node, WearLink.PATH_APK_OFFER, WatchUpdateCodec.encodeOffer(offer), REQUEST_TIMEOUT_MILLIS))
        if (reply.id != offer.id) throw IOException("the watch answered another offer")
        return reply
    }

    /**
     * Streams [file] after [header] through a channel and returns the watch's
     * verdict. [onProgress] gets the fraction sent.
     */
    suspend fun transfer(node: String, header: ApkTransferHeader, file: File, onProgress: (Float) -> Unit): ApkTransferResult {
        val channel = withTimeoutOrNull(CHANNEL_OPEN_TIMEOUT_MILLIS) { channels.openChannel(node, WearLink.PATH_APK_TRANSFER).await() }
            ?: throw IOException("could not open a channel to the watch")
        return try {
            coroutineScope {
                // Blocking stream calls don't see coroutine cancellation; closing the channel unblocks them.
                val watchdog = launch {
                    delay(TRANSFER_TIMEOUT_MILLIS)
                    channels.close(channel)
                }
                try {
                    val output = channels.getOutputStream(channel).await()
                    val input = channels.getInputStream(channel).await()
                    runInterruptible(Dispatchers.IO) {
                        file.inputStream().buffered().use { apk ->
                            ApkTransferCodec.send(output, header, apk, onProgress = { sent -> onProgress(sent.toFloat() / header.size) })
                        }
                        ApkTransferCodec.readResult(input)
                    }
                } finally {
                    watchdog.cancel()
                }
            }
        } finally {
            try {
                channels.close(channel).await()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun request(node: String, path: String, payload: ByteArray, timeoutMillis: Long): ByteArray =
        withTimeoutOrNull(timeoutMillis) { messages.sendRequest(node, path, payload).await() }
            ?: throw IOException("the watch did not answer within ${timeoutMillis / 1000} s")

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
        const val POLL_MILLIS = 3_000L
        const val CHANNEL_OPEN_TIMEOUT_MILLIS = 20_000L

        /** 15 MB over Bluetooth takes a few minutes; Wi-Fi is much faster. */
        const val TRANSFER_TIMEOUT_MILLIS = 15 * 60_000L
    }
}
