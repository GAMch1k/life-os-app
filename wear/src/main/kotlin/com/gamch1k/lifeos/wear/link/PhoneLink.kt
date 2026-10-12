package com.gamch1k.lifeos.wear.link

import android.content.Context
import com.gamch1k.lifeos.core.wearlink.CredentialsReply
import com.gamch1k.lifeos.core.wearlink.CredentialsRequest
import com.gamch1k.lifeos.core.wearlink.RelayCodec
import com.gamch1k.lifeos.core.wearlink.RelayErrorCode
import com.gamch1k.lifeos.core.wearlink.WearLink
import com.gamch1k.lifeos.wear.net.PhoneConnection
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** The result of asking the phone for the token. */
enum class CredentialsFetch { RECEIVED, PHONE_HAS_NONE, NO_PHONE, FAILED }

/** The watch's end of the Data Layer link (docs/wear.md). */
class PhoneLink(context: Context, private val store: CredentialStore) : PhoneConnection {
    private val appContext = context.applicationContext
    private val capabilities by lazy { Wearable.getCapabilityClient(appContext) }
    private val messages by lazy { Wearable.getMessageClient(appContext) }
    private val channels by lazy { Wearable.getChannelClient(appContext) }

    @Volatile
    private var cachedNode: Pair<String, Long>? = null

    /**
     * The phone with the Life OS app (capability [WearLink.CAPABILITY_PHONE]),
     * preferring a nearby (Bluetooth) one over one reached through the cloud.
     * Cached for a few seconds, so a burst of requests asks Play services once.
     */
    override suspend fun phoneNodeId(): String? {
        cachedNode?.let { (id, at) -> if (System.currentTimeMillis() - at < NODE_CACHE_MILLIS) return id }
        val nodes = capabilities.getCapability(WearLink.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        cachedNode = node?.let { it.id to System.currentTimeMillis() }
        return node?.id
    }

    /** The display name of the phone [phoneNodeId] would use, for the Connection screen. */
    suspend fun phoneName(): String? {
        val nodes = capabilities.getCapability(WearLink.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
        return (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.displayName
    }

    /** Forget the cached phone, after it failed or the capability changed. */
    fun forgetPhone() {
        cachedNode = null
    }

    override suspend fun request(nodeId: String, path: String, payload: ByteArray, timeoutMillis: Long): ByteArray = try {
        // A timeout must not look like the caller's cancellation, hence OrNull and an IOException.
        withTimeoutOrNull(timeoutMillis) { messages.sendRequest(nodeId, path, payload).await() }
            ?: throw IOException("the phone did not answer within ${timeoutMillis / 1000} s")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        forgetPhone()
        throw e
    }

    override suspend fun <T> channel(
        nodeId: String,
        path: String,
        timeoutMillis: Long,
        send: (OutputStream) -> Unit,
        receive: (InputStream) -> T,
    ): T {
        val channel = withTimeoutOrNull(CHANNEL_OPEN_TIMEOUT_MILLIS) { channels.openChannel(nodeId, path).await() }
            ?: run {
                forgetPhone()
                throw IOException("could not open a channel to the phone")
            }
        return try {
            coroutineScope {
                // Blocking stream calls don't see coroutine cancellation; closing the channel unblocks them.
                val watchdog = launch {
                    delay(timeoutMillis)
                    channels.close(channel)
                }
                try {
                    withContext(Dispatchers.IO) {
                        val output = channels.getOutputStream(channel).await()
                        send(output)
                        val input = channels.getInputStream(channel).await()
                        receive(input)
                    }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            forgetPhone()
            throw e
        } finally {
            try {
                channels.close(channel)
            } catch (_: Exception) {
            }
        }
    }

    /** Asks the phone for the server URL and service token, and stores them. */
    suspend fun fetchCredentials(): CredentialsFetch {
        val node = try {
            phoneNodeId()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return CredentialsFetch.NO_PHONE
        val request = CredentialsRequest(id = UUID.randomUUID().toString())
        return try {
            val payload = RelayCodec.json.encodeToString(CredentialsRequest.serializer(), request).toByteArray(Charsets.UTF_8)
            val reply = RelayCodec.json.decodeFromString(
                CredentialsReply.serializer(),
                request(node, WearLink.PATH_CREDENTIALS, payload, CREDENTIALS_TIMEOUT_MILLIS).toString(Charsets.UTF_8),
            )
            val credentials = reply.credentials
            when {
                reply.id != request.id -> CredentialsFetch.FAILED
                credentials != null && credentials.isComplete -> {
                    store.save(credentials)
                    CredentialsFetch.RECEIVED
                }
                reply.error?.code == RelayErrorCode.NO_CREDENTIALS -> CredentialsFetch.PHONE_HAS_NONE
                else -> CredentialsFetch.FAILED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            CredentialsFetch.NO_PHONE
        }
    }

    private companion object {
        const val NODE_CACHE_MILLIS = 15_000L
        const val CHANNEL_OPEN_TIMEOUT_MILLIS = 15_000L
        const val CREDENTIALS_TIMEOUT_MILLIS = 15_000L
    }
}
