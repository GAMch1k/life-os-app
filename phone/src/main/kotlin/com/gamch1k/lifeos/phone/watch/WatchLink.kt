package com.gamch1k.lifeos.phone.watch

import android.content.Context
import android.os.SystemClock
import com.gamch1k.lifeos.core.update.WatchUpdateRules
import com.gamch1k.lifeos.core.wearlink.ApkOffer
import com.gamch1k.lifeos.core.wearlink.ApkOfferReply
import com.gamch1k.lifeos.core.wearlink.ApkStatus
import com.gamch1k.lifeos.core.wearlink.ApkStatusRequest
import com.gamch1k.lifeos.core.wearlink.ApkTransferCodec
import com.gamch1k.lifeos.core.wearlink.ApkTransferHeader
import com.gamch1k.lifeos.core.wearlink.ApkTransferResult
import com.gamch1k.lifeos.core.wearlink.WatchInfo
import com.gamch1k.lifeos.core.wearlink.WatchInfoRequest
import com.gamch1k.lifeos.core.wearlink.WatchUpdateCodec
import com.gamch1k.lifeos.core.wearlink.WearLink
import com.gamch1k.lifeos.core.wearlink.WearNodes
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
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The phone's side of the watch-update protocol over the Data Layer
 * (docs/wear.md, "Install and updates"): ask the watch app its version,
 * offer it an APK, follow the watch's own download (v2), stream the APK
 * through a channel (from where the watch stands, in v2).
 */
class WatchLink(context: Context) {
    private val appContext = context.applicationContext
    private val capabilities by lazy { Wearable.getCapabilityClient(appContext) }
    private val messages by lazy { Wearable.getMessageClient(appContext) }
    private val channels by lazy { Wearable.getChannelClient(appContext) }

    /** The watch with the Life OS app (capability [WearLink.CAPABILITY_WATCH]), a nearby one first; null when none is reachable. */
    suspend fun watchNode(): String? = try {
        WearNodes.find(appContext, WearLink.CAPABILITY_WATCH).firstOrNull()?.id
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

    /** v2: how the watch's own download of the accepted offer [id] goes. */
    suspend fun status(node: String, id: String): ApkStatus {
        val reply = WatchUpdateCodec.decodeStatus(request(node, WearLink.PATH_APK_STATUS, WatchUpdateCodec.encodeStatusRequest(ApkStatusRequest(id = id)), STATUS_TIMEOUT_MILLIS))
        if (reply.id != id) throw IOException("the watch answered about another offer")
        return reply
    }

    /**
     * Streams [file] from [header.offset] on, after [header], through a
     * channel, and returns the watch's verdict. [onProgress] gets the
     * position in the APK.
     *
     * The stream is cut (and this throws, saying why) when the channel takes
     * no byte for [WatchUpdateRules.STREAM_IDLE], when the watch's verdict
     * doesn't come within [WatchUpdateRules.STREAM_VERDICT_WAIT] of the last
     * byte, or after [WatchUpdateRules.streamCap] in all. A slow stream that
     * still moves is not cut early: 0.4.2's fixed 15 minutes cut the 16.6 MB
     * APK near its end over Bluetooth.
     */
    suspend fun transfer(node: String, header: ApkTransferHeader, file: File, onProgress: (Long) -> Unit): ApkTransferResult {
        val channel = withTimeoutOrNull(CHANNEL_OPEN_TIMEOUT_MILLIS) { channels.openChannel(node, WearLink.PATH_APK_TRANSFER).await() }
            ?: throw IOException("could not open a channel to the watch")
        val started = SystemClock.elapsedRealtime()
        val moved = AtomicLong(started)
        val sentAll = AtomicLong(0)
        val cut = AtomicReference<String?>(null)
        return try {
            coroutineScope {
                // Blocking stream calls don't see coroutine cancellation; closing the channel unblocks them.
                val watchdog = launch {
                    val cap = WatchUpdateRules.streamCap(header.size - header.offset).toMillis()
                    while (true) {
                        delay(WATCHDOG_TICK_MILLIS)
                        val now = SystemClock.elapsedRealtime()
                        val reason = when {
                            now - started > cap -> "the stream took longer than ${cap / 60_000} minutes"
                            sentAll.get() == 0L && now - moved.get() > WatchUpdateRules.STREAM_IDLE.toMillis() ->
                                "no byte moved for ${WatchUpdateRules.STREAM_IDLE.toMinutes()} minutes"
                            sentAll.get() > 0L && now - sentAll.get() > WatchUpdateRules.STREAM_VERDICT_WAIT.toMillis() ->
                                "the watch did not answer within ${WatchUpdateRules.STREAM_VERDICT_WAIT.toMinutes()} minutes of the last byte"
                            else -> null
                        }
                        if (reason != null) {
                            cut.set(reason)
                            channels.close(channel)
                            break
                        }
                    }
                }
                try {
                    val output = channels.getOutputStream(channel).await()
                    val input = channels.getInputStream(channel).await()
                    runInterruptible(Dispatchers.IO) {
                        FileInputStream(file).use { apk ->
                            apk.channel.position(header.offset)
                            ApkTransferCodec.send(output, header, apk, onProgress = { position ->
                                moved.set(SystemClock.elapsedRealtime())
                                onProgress(position)
                            })
                        }
                        sentAll.set(SystemClock.elapsedRealtime())
                        ApkTransferCodec.readResult(input)
                    }
                } catch (e: IOException) {
                    throw cut.get()?.let { IOException(it, e) } ?: e
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
        const val STATUS_TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 3_000L
        const val CHANNEL_OPEN_TIMEOUT_MILLIS = 20_000L
        const val WATCHDOG_TICK_MILLIS = 5_000L
    }
}
